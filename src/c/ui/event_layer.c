/**
 * Event layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "glyph.h"
#include "event_layer.h"
#include "../modules/settings.h"
#include "../generated/icons.h"
#include "graph_common.h"
#if defined(PBL_ROUND)
#include "arc.h"
#endif
#include <stddef.h>

struct EventLayer {
	Layer *layer;
	GFont icon_font;
	uint8_t hourly_code[MAX_GRAPH_HOURS];
	uint8_t fog[MAX_GRAPH_HOURS]; // 1 = fog hour (2.8.1: fog is an event)
	uint8_t wind[MAX_GRAPH_HOURS]; // 1 = gusts over the threshold (2.9.3)
	uint8_t storm[MAX_GRAPH_HOURS]; // 1 = strong thunderstorm hour (2.9.4)
	uint8_t hours_remaining;
};

typedef enum {
	EVENT_KIND_NONE = 0,
	EVENT_KIND_SNOW,
	EVENT_KIND_STORM,
	EVENT_KIND_TORNADO,
	EVENT_KIND_MISSING,
	EVENT_KIND_FOG,
	EVENT_KIND_WIND, /* 2.9.3: strong gusts */
} EventKind;

// Represents a consecutive span of the same event type
typedef struct {
	int start_hour;
	int end_hour;
	EventKind kind;
	GColor color;
	const char *icon;
} EventSpan;

// Returns the icon string and its display color for notable WMO codes.
// Returns NULL for codes that don't warrant a special icon.
/* 2.9.4: snow codes per level (0 any, 1 moderate and heavy, 2 heavy only). */
static bool prv_snow_at_level(uint8_t code, int level) {
	if (level >= 2)
		return code == 75 || code == 86;
	if (level == 1)
		return code == 73 || code == 75 || code == 86;
	return code == 71 || code == 73 || code == 75 || code == 77 || code == 85 || code == 86;
}

static const char *prv_event_icon(uint8_t code, bool strong, GColor *out_color,
                                  EventKind *out_kind) {
	/* Per-event Clay settings (2.9.3 toggles, 2.9.4 levels); a hidden kind
	 * leaves the hour to the next priority (wind, fog) like an unmarked hour. */
	uint8_t ev = settings_get()->event_hide;
	bool tornado_on = !(ev & EVENT_HIDE_TORNADO);
	if ((code == 19 || code == 99) && tornado_on) {
		*out_color = GColorOrange;
		*out_kind = EVENT_KIND_TORNADO;
		return ICON_TORNADO;
	}
	/* Thunder 95-98; 99 (storm with heavy hail) too when tornado is off.
	 * "Strong only": hail codes 96-99 or the phone's strong-storm bit. */
	if (((code >= 95 && code <= 98) || (code == 99 && !tornado_on)) &&
	    !(ev & EVENT_HIDE_THUNDER) &&
	    (!(ev & EVENT_THUNDER_STRONG) || code >= 96 || strong)) {
		*out_color = GColorYellow;
		*out_kind = EVENT_KIND_STORM;
		return ICON_LIGHTNING;
	}
	if (!(ev & EVENT_HIDE_SNOW) &&
	    prv_snow_at_level(code, (ev & EVENT_SNOW_LVL_MASK) >> EVENT_SNOW_LVL_SHIFT)) {
		*out_color = GColorCyan;
		*out_kind = EVENT_KIND_SNOW;
		return ICON_SNOWFLAKE;
	}
	*out_kind = EVENT_KIND_NONE;
	return NULL;
}

// Scan hourly_code to identify consecutive runs of the same event type.
// Also appends a single MISSING span for hours >= hours_remaining.
// Returns the number of spans found and populates the spans array.
/* Priority per hour: tornado > thunder > snow (WMO codes) > wind (gust
 * bits, 2.9.3) > fog. Collisions resolve to one span per run. */
static bool prv_fog_on(void) { return settings_get()->fog_indicator; }

static const char *prv_hour_event(const uint8_t *codes, const uint8_t *fog,
                                  const uint8_t *wind, const uint8_t *storm, int i,
                                  GColor *out_color, EventKind *out_kind) {
	const char *icon = prv_event_icon(codes[i], storm && storm[i], out_color, out_kind);
	if (icon)
		return icon;
	if (wind && wind[i]) {
		*out_color = GColorPurple;
		*out_kind = EVENT_KIND_WIND;
		return ""; /* drawn as a bit mask, not a glyph */
	}
	if (fog && fog[i] && prv_fog_on()) {
		*out_color = GColorLightGray;
		*out_kind = EVENT_KIND_FOG;
		return ""; /* drawn as wavy lines, not a glyph */
	}
	return NULL;
}

static int prv_find_spans(const uint8_t hourly_code[MAX_GRAPH_HOURS],
                          const uint8_t *fog, const uint8_t *wind, const uint8_t *storm,
                          uint8_t hours_remaining, EventSpan spans[MAX_GRAPH_HOURS]) {
	int span_count = 0;
	int i = 0;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

	while (i < (int)hours_remaining && i < total_hours) {
		GColor color;
		EventKind kind;
		const char *icon = prv_hour_event(hourly_code, fog, wind, storm, i, &color, &kind);
		if (!icon) {
			i++;
			continue;
		}

		// Group consecutive hours that map to the same visual event type.
		int start = i;
		while (i < (int)hours_remaining && i < total_hours) {
			GColor next_color;
			EventKind next_kind;
			if (!prv_hour_event(hourly_code, fog, wind, storm, i, &next_color, &next_kind) ||
			    next_kind != kind) {
				break;
			}
			i++;
		}

		spans[span_count].start_hour = start;
		spans[span_count].end_hour = i - 1;
		spans[span_count].kind = kind;
		spans[span_count].color = color;
		spans[span_count].icon = icon;
		span_count++;
	}

	// Only append a missing span when some valid data exists — total absence
	// is indicated by the icon_bar disconnect icon instead.
	if ((int)hours_remaining > 0 && (int)hours_remaining < total_hours) {
		spans[span_count].start_hour = (int)hours_remaining;
		spans[span_count].end_hour = total_hours - 1;
		spans[span_count].kind = EVENT_KIND_MISSING;
		spans[span_count].color = GColorRed;
		spans[span_count].icon = ICON_CONNECTION_SIGNAL__OFF;
		span_count++;
	}

	return span_count;
}

/* Fog glyph: two stacked sine waves, 11 px wide (2.9.2: was three rows).
 * The rows sit at -2/+2 around the old middle row, so the icon keeps the
 * 2.8.3 centre and stays inside its old 11 x 9 footprint (7 px tall). */
#define FOG_W 11
#define FOG_CY_OFS 1
static const int8_t s_fog_row_dy[2] = {-2, 2};
static GColor prv_fog_second(void);
static void prv_draw_pixel2(GContext *ctx, GPoint p, GColor c, GColor c2);
static void prv_draw_fog_icon(GContext *ctx, int cx, int cy, GColor col, int32_t angle) {
	GColor col2 = prv_fog_second();
	static const int8_t wave[FOG_W] = {0, -1, -1, 0, 1, 1, 0, -1, -1, 0, 1};
	cy += FOG_CY_OFS;
	graphics_context_set_stroke_color(ctx, col);
	if (angle != 0) {
		/* Tilted (gabbro arc, 2.8.4): inverse-sample the upright pixel set so
		 * the rotated glyph keeps its size with crisp 1 px waves. */
		GlyphXf t = {(int16_t)cx, (int16_t)cy, cos_lookup(angle), sin_lookup(angle)};
		uint16_t mask[9] = {0}; /* rows -4..+4 (index +4) */
		for (int r = 0; r < 2; r++) {
			for (int k = 0; k < FOG_W; k++) {
				mask[s_fog_row_dy[r] + wave[k] + 4] |= (uint16_t)(1u << k);
				if (k < FOG_W - 1 && wave[k + 1] != wave[k])
					mask[s_fog_row_dy[r] + wave[k + 1] + 4] |= (uint16_t)(1u << k);
			}
		}
		gx_mask2(ctx, &t, mask, FOG_W, 9, -(FOG_W / 2), -4, col, col2);
		return;
	}
	for (int r = 0; r < 2; r++) {
		int y = cy + s_fog_row_dy[r];
		for (int k = 0; k < FOG_W; k++) {
			prv_draw_pixel2(ctx, GPoint(cx - FOG_W / 2 + k, y + wave[k]), col, col2);
			if (k < FOG_W - 1 && wave[k + 1] != wave[k])
				prv_draw_pixel2(ctx, GPoint(cx - FOG_W / 2 + k, y + wave[k + 1]), col, col2);
		}
	}
}

/* Solid lightning bolt, 9 x 13 px (2.8.5): emery's rendered bolt with every
 * row filled between its outer edges, so no interior gaps on any platform.
 * Row y, bit x. Same footprint/anchor as the 2.8.2 path bolt. */
static const uint16_t s_mask_bolt[13] = {0x0e0, 0x070, 0x070, 0x038, 0x03c, 0x1fe, 0x0fe, 0x07f, 0x030, 0x038, 0x018, 0x00c, 0x004};

static void prv_draw_bolt_icon(GContext *ctx, int cx, int cy, GColor col, int32_t angle) {
	GlyphXf t = {(int16_t)cx, (int16_t)cy, cos_lookup(angle), sin_lookup(angle)};
	gx_mask(ctx, &t, s_mask_bolt, 9, 13, -4, -6, col);
}

/* Wind (2.9.3): two gust lines with curled ends and a short middle line,
 * 10 x 9 px (the fog/snow footprint). Row y, bit x. */
static const uint16_t s_mask_wind[9] = {0x1c0, 0x200, 0x1ff, 0x000, 0x07f, 0x000, 0x1ff, 0x200, 0x1c0};

static void prv_draw_wind_icon(GContext *ctx, int cx, int cy, GColor col, int32_t angle) {
	GlyphXf t = {(int16_t)cx, (int16_t)cy, cos_lookup(angle), sin_lookup(angle)};
	gx_mask(ctx, &t, s_mask_wind, 10, 9, -5, -4, col);
}

#if defined(PBL_PLATFORM_EMERY)
#define EVENT_ICON_LIFT 1
#else
#define EVENT_ICON_LIFT 0
#endif

/* 3.0.1: the palette has no grey between DarkGray (#555555) and Black, so
 * fog mixes DarkGray with a second colour on every other pixel: Black on
 * light screens (reads ~#2A2A2A, darker yet still grey next to black
 * text), LightGray on dark screens (~#808080, a little brighter so it
 * keeps the same contrast against black). */
static GColor prv_fog_second(void) {
#if defined(PBL_COLOR)
	return settings_get()->light_theme ? GColorBlack : GColorLightGray;
#else
	return GColorWhite;
#endif
}

static void prv_draw_pixel2(GContext *ctx, GPoint p, GColor c, GColor c2) {
	graphics_context_set_stroke_color(ctx, ((p.x + p.y) & 1) ? c2 : c);
	graphics_draw_pixel(ctx, p);
}

/* Straight (horizontal or vertical) 1 px line, checkered c / c2. */
static void prv_line2(GContext *ctx, GPoint a, GPoint b, GColor c, GColor c2) {
	if (gcolor_equal(c, c2)) {
		graphics_context_set_stroke_color(ctx, c);
		graphics_draw_line(ctx, a, b);
		return;
	}
	int dx = b.x > a.x ? 1 : (b.x < a.x ? -1 : 0), dy = b.y > a.y ? 1 : (b.y < a.y ? -1 : 0);
	for (GPoint p = a;; p.x += dx, p.y += dy) {
		prv_draw_pixel2(ctx, p, c, c2);
		if (p.x == b.x && p.y == b.y)
			break;
	}
}

static GColor prv_span_color(const EventSpan *span) {
#if defined(PBL_COLOR)
	bool light = settings_get()->light_theme;
	/* Light theme needs darker inks: yellow/cyan/light grey vanish on white. */
	/* 2.9.2/2.9.3: fog is grey; 3.0.1: DarkGray checkered with
	 * prv_fog_second() (see there). */
	if (span->kind == EVENT_KIND_FOG)
		return GColorDarkGray;
	/* 2.9.3: wind is violet, apart from thunder tan, snow blue, tornado orange. */
	if (span->kind == EVENT_KIND_WIND)
		return light ? GColorPurple : GColorLavenderIndigo;
	if (span->kind == EVENT_KIND_STORM)
		return light ? GColorWindsorTan : GColorYellow; /* dark amber */
	if (span->kind == EVENT_KIND_SNOW)
		return light ? GColorCobaltBlue : GColorCyan;
	return span->color;
#else
	(void)span;
	return GColorWhite;
#endif
}

/* Returns true when the icon was drawn already rotated by `angle` (vector
 * icons); glyph icons are drawn upright and rotated by the caller. */
static bool prv_draw_span_icon(GContext *ctx, EventLayer *el, const EventSpan *span,
                               int icon_x, int center_y, int32_t angle) {
	GColor c = prv_span_color(span);
	if (span->kind == EVENT_KIND_FOG) {
		prv_draw_fog_icon(ctx, icon_x, center_y, c, angle);
		return true;
	}
	if (span->kind == EVENT_KIND_STORM) {
		prv_draw_bolt_icon(ctx, icon_x, center_y, c, angle);
		return true;
	}
	if (span->kind == EVENT_KIND_WIND) {
		prv_draw_wind_icon(ctx, icon_x, center_y, c, angle);
		return true;
	}
	if (!span->icon)
		return true;
	graphics_context_set_text_color(ctx, c);
	graphics_draw_text(ctx, span->icon, el->icon_font,
	                   GRect(icon_x - 6, center_y - 6, 12, 12),
	                   GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
	return false;
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
	EventLayer *el = *(EventLayer **)layer_get_data(layer);
	if (!el->icon_font)
		return;

	GRect bounds = layer_get_bounds(layer);
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int layer_h = bounds.size.h;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

	// Find all event spans in the hourly data
	EventSpan spans[MAX_GRAPH_HOURS];
	int span_count =
	    prv_find_spans(el->hourly_code, el->fog, el->wind, el->storm, el->hours_remaining, spans);

	// Draw each span in reverse order so earlier spans are on top of later ones
	// in case of overlap.
	for (int s = span_count - 1; s >= 0; s--) {
		EventSpan *span = &spans[s];

		// Calculate x-position for the center of each hour
		int x_start = graph_x + (long)(span->start_hour * 2 + 1) * graph_w /
		                            (total_hours * 2);
		int x_end = graph_x + (long)(span->end_hour * 2 + 1) * graph_w /
		                          (total_hours * 2);

		// Center vertically in the layer
		/* Range lines keep their row; the layer has EVENT_ICON_LIFT extra
		 * rows on top for the lifted icon. */
		int center_y = (layer_h - EVENT_ICON_LIFT) / 2 + EVENT_ICON_LIFT;
		int icon_x = (x_start + x_end) / 2;
		int icon_size = 12;
		int icon_gap =
		    icon_size / 2 + 1; // Add gap between icon and extent lines.
		GColor icon_color = prv_span_color(span);

		graphics_context_set_stroke_width(ctx, 1);

		graphics_context_set_stroke_color(ctx, icon_color);

		WeatherEventDisplay mode = settings_get()->weather_event_display;
		if (mode == WEATHER_EVENT_NONE)
			continue;
		bool draw_range = (mode == WEATHER_EVENT_RANGE_ICON);

		GColor c2 = span->kind == EVENT_KIND_FOG ? prv_fog_second() : icon_color;
		if (draw_range && x_start < icon_x - icon_gap) {
			prv_line2(ctx, GPoint(x_start, center_y), GPoint(icon_x - icon_gap, center_y), icon_color, c2);
			prv_line2(ctx, GPoint(x_start, center_y - 2), GPoint(x_start, center_y + 2), icon_color, c2);
		}
		if (draw_range && x_end > icon_x + icon_gap) {
			prv_line2(ctx, GPoint(icon_x + icon_gap, center_y), GPoint(x_end, center_y), icon_color, c2);
			prv_line2(ctx, GPoint(x_end, center_y - 2), GPoint(x_end, center_y + 2), icon_color, c2);
		}

		// Draw the icon centered in the span (round: drawn upright later
		// by the arc overlay so glyphs aren't bent by the warp).
#if !defined(PBL_ROUND)
		/* 2.8.3: icons sit 1 px higher (towards the timeline); range lines
		 * keep their row. */
		prv_draw_span_icon(ctx, el, span, icon_x, center_y - EVENT_ICON_LIFT, 0);
#endif
	}
}

#if defined(PBL_ROUND)
/* 2.8.4: emery's rendered event glyphs as bit masks (row y, bit x), so the
 * gabbro arc shows them at exactly the emery pixel size/shape, only rotated
 * to the tangent (no warp scaling, no box-rotation smear). */
static const uint16_t s_mask_snow[9] = {0x045, 0x0d6, 0x1d7, 0x038, 0x0ee, 0x038, 0x1d7, 0x0d6, 0x0c5}; /* 9 x 9 */
static const uint16_t s_mask_tornado[9] = {0x07f, 0x000, 0x03e, 0x000, 0x00f, 0x00f, 0x000, 0x00c, 0x018}; /* 7 x 9 */

/* Returns false when the kind has no mask (caller falls back). */
static bool prv_draw_mask_icon(GContext *ctx, const EventSpan *span, GPoint p, int32_t ang) {
	const uint16_t *m;
	int w, h, ox, oy;
	if (span->kind == EVENT_KIND_STORM) {
		m = s_mask_bolt; w = 9; h = 13; ox = -4; oy = -6;
	} else if (span->kind == EVENT_KIND_SNOW) {
		m = s_mask_snow; w = 9; h = 9; ox = -4; oy = -4;
	} else if (span->kind == EVENT_KIND_TORNADO) {
		m = s_mask_tornado; w = 7; h = 9; ox = -3; oy = -4;
	} else if (span->kind == EVENT_KIND_WIND) {
		m = s_mask_wind; w = 10; h = 9; ox = -5; oy = -4;
	} else {
		return false;
	}
	GlyphXf t = {(int16_t)p.x, (int16_t)p.y, cos_lookup(ang), sin_lookup(ang)};
	gx_mask(ctx, &t, m, w, h, ox, oy, prv_span_color(span));
	return true;
}
#endif

#if defined(PBL_ROUND)
void event_layer_draw_icons_mapped(EventLayer *el, GContext *ctx,
                                   ArcMapFn map) {
	if (!el || !el->icon_font || layer_get_hidden(el->layer))
		return;
	if (settings_get()->weather_event_display == WEATHER_EVENT_NONE)
		return;
	GRect f = layer_get_frame(el->layer);
	int graph_w = f.size.w - GRAPH_OFFSET_X;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	EventSpan spans[MAX_GRAPH_HOURS];
	int n = prv_find_spans(el->hourly_code, el->fog, el->wind, el->storm, el->hours_remaining, spans);
	for (int s = n - 1; s >= 0; s--) {
		int xs = GRAPH_OFFSET_X + (long)(spans[s].start_hour * 2 + 1) * graph_w / (total_hours * 2);
		int xe = GRAPH_OFFSET_X + (long)(spans[s].end_hour * 2 + 1) * graph_w / (total_hours * 2);
		GPoint p = map(f.origin.x + (xs + xe) / 2, f.origin.y + f.size.h / 2);
		GColor bg = settings_get()->light_theme ? GColorWhite : GColorBlack;
		/* Knock out the warped range line, draw the glyph upright, then tilt
		 * it onto the arc tangent at its time position (2.8.2). */
		/* 2.8.5: knock out only the range line (icon gap x tick height,
		 * along the tangent), not a 19 px disc that bit into the rain chart. */
		int32_t ang = arc_angle_at(p, true);
		GlyphXf kt = {(int16_t)p.x, (int16_t)p.y, cos_lookup(ang), sin_lookup(ang)};
		gx_fill_rect(ctx, &kt, GRect(-7, -2, 15, 5), bg);
		if (prv_draw_mask_icon(ctx, &spans[s], p, ang))
			continue;
		uint8_t before[ARC_BOX_W * ARC_BOX_W];
		arc_snapshot_box(ctx, p, ARC_BOX_R, bg, before);
		if (!prv_draw_span_icon(ctx, el, &spans[s], p.x, p.y, ang))
			arc_rotate_box(ctx, p, ARC_BOX_R, ang, bg, before); /* font glyphs */
	}
}
#endif

EventLayer *event_layer_create(GRect frame) {
	EventLayer *el = malloc(sizeof(EventLayer));
	if (!el)
		return NULL;
	memset(el->hourly_code, 0, sizeof(el->hourly_code));
	memset(el->fog, 0, sizeof(el->fog));
	memset(el->wind, 0, sizeof(el->wind));
	memset(el->storm, 0, sizeof(el->storm));
	el->hours_remaining = GRAPH_HOURS;
	el->icon_font = fonts_load_custom_font(
	    resource_get_handle(RESOURCE_ID_CARBON_ICONS_12));
	el->layer = layer_create_with_data(frame, sizeof(EventLayer *));
	*(EventLayer **)layer_get_data(el->layer) = el;
	layer_set_update_proc(el->layer, prv_update_proc);
	return el;
}

void event_layer_destroy(EventLayer *layer) {
	if (!layer)
		return;
	if (layer->icon_font)
		fonts_unload_custom_font(layer->icon_font);
	layer_destroy(layer->layer);
	free(layer);
}

Layer *event_layer_get_layer(EventLayer *layer) {
	return layer ? layer->layer : NULL;
}

void event_layer_set_data(EventLayer *layer, const uint8_t *hourly_code,
                          const uint8_t *fog, const uint8_t *wind,
                          const uint8_t *storm, uint8_t hours_remaining) {
	if (!layer)
		return;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	memcpy(layer->hourly_code, hourly_code, total_hours);
	if (fog)
		memcpy(layer->fog, fog, total_hours);
	else
		memset(layer->fog, 0, sizeof(layer->fog));
	if (wind)
		memcpy(layer->wind, wind, total_hours);
	else
		memset(layer->wind, 0, sizeof(layer->wind));
	if (storm)
		memcpy(layer->storm, storm, total_hours);
	else
		memset(layer->storm, 0, sizeof(layer->storm));
	layer->hours_remaining = hours_remaining;
	layer_mark_dirty(layer->layer);
}
