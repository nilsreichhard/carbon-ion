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
	uint8_t hours_remaining;
};

typedef enum {
	EVENT_KIND_NONE = 0,
	EVENT_KIND_SNOW,
	EVENT_KIND_STORM,
	EVENT_KIND_TORNADO,
	EVENT_KIND_MISSING,
	EVENT_KIND_FOG,
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
static const char *prv_event_icon(uint8_t code, GColor *out_color,
                                  EventKind *out_kind) {
	if (code == 19 || code == 99) {
		*out_color = GColorOrange;
		*out_kind = EVENT_KIND_TORNADO;
		return ICON_TORNADO;
	}
	if (code >= 95 && code <= 98) {
		*out_color = GColorYellow;
		*out_kind = EVENT_KIND_STORM;
		return ICON_LIGHTNING;
	}
	if (code == 75 || code == 77 || code == 85 || code == 86) {
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
/* Fog is the lowest-priority event: thunder/tornado/snow codes win an hour,
 * so collisions resolve exactly like the existing kinds (one span per run). */
static bool prv_fog_on(void) { return settings_get()->fog_indicator; }

static const char *prv_hour_event(const uint8_t *codes, const uint8_t *fog, int i,
                                  GColor *out_color, EventKind *out_kind) {
	const char *icon = prv_event_icon(codes[i], out_color, out_kind);
	if (icon)
		return icon;
	if (fog && fog[i] && prv_fog_on()) {
		*out_color = GColorLightGray;
		*out_kind = EVENT_KIND_FOG;
		return ""; /* drawn as wavy lines, not a glyph */
	}
	return NULL;
}

static int prv_find_spans(const uint8_t hourly_code[MAX_GRAPH_HOURS],
                          const uint8_t *fog,
                          uint8_t hours_remaining, EventSpan spans[MAX_GRAPH_HOURS]) {
	int span_count = 0;
	int i = 0;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

	while (i < (int)hours_remaining && i < total_hours) {
		GColor color;
		EventKind kind;
		const char *icon = prv_hour_event(hourly_code, fog, i, &color, &kind);
		if (!icon) {
			i++;
			continue;
		}

		// Group consecutive hours that map to the same visual event type.
		int start = i;
		while (i < (int)hours_remaining && i < total_hours) {
			GColor next_color;
			EventKind next_kind;
			if (!prv_hour_event(hourly_code, fog, i, &next_color, &next_kind) ||
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

/* Fog glyph: three stacked sine waves, 11 x 9 px (2.8.3: shrunk to the
 * footprint of the bolt/snow glyphs, bottom row on the bolt's baseline). */
#define FOG_W 11
#define FOG_ROW_DY 3
#define FOG_CY_OFS 1
static void prv_draw_fog_icon(GContext *ctx, int cx, int cy, GColor col, int32_t angle) {
	static const int8_t wave[FOG_W] = {0, -1, -1, 0, 1, 1, 0, -1, -1, 0, 1};
	cy += FOG_CY_OFS;
	graphics_context_set_stroke_color(ctx, col);
	if (angle != 0) {
		/* Tilted (gabbro arc, 2.8.4): inverse-sample the upright pixel set so
		 * the rotated glyph keeps its 11 x 9 size with crisp 1 px waves. */
		GlyphXf t = {(int16_t)cx, (int16_t)cy, cos_lookup(angle), sin_lookup(angle)};
		uint16_t mask[3 * FOG_ROW_DY + 3] = {0}; /* rows -4..+4 (index +4) */
		for (int row = -1; row <= 1; row++) {
			for (int k = 0; k < FOG_W; k++) {
				int y = row * FOG_ROW_DY + wave[k] + 4;
				mask[y] |= (uint16_t)(1u << k);
				if (k < FOG_W - 1 && wave[k + 1] != wave[k])
					mask[row * FOG_ROW_DY + wave[k + 1] + 4] |= (uint16_t)(1u << k);
			}
		}
		gx_mask(ctx, &t, mask, FOG_W, 9, -(FOG_W / 2), -4, col);
		return;
	}
	for (int row = -1; row <= 1; row++) {
		int y = cy + row * FOG_ROW_DY;
		for (int k = 0; k < FOG_W; k++) {
			graphics_draw_pixel(ctx, GPoint(cx - FOG_W / 2 + k, y + wave[k]));
			if (k < FOG_W - 1 && wave[k + 1] != wave[k])
				graphics_draw_pixel(ctx, GPoint(cx - FOG_W / 2 + k, y + wave[k + 1]));
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

#if defined(PBL_PLATFORM_EMERY)
#define EVENT_ICON_LIFT 1
#else
#define EVENT_ICON_LIFT 0
#endif

static GColor prv_span_color(const EventSpan *span) {
#if defined(PBL_COLOR)
	bool light = settings_get()->light_theme;
	/* Light theme needs darker inks: yellow/cyan/light grey vanish on white. */
	if (span->kind == EVENT_KIND_FOG)
		return light ? GColorBlack : GColorLightGray;
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
	    prv_find_spans(el->hourly_code, el->fog, el->hours_remaining, spans);

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

		if (draw_range && x_start < icon_x - icon_gap) {
			graphics_draw_line(ctx, GPoint(x_start, center_y),
			                   GPoint(icon_x - icon_gap, center_y));
			graphics_draw_line(ctx, GPoint(x_start, center_y - 2),
			                   GPoint(x_start, center_y + 2));
		}
		if (draw_range && x_end > icon_x + icon_gap) {
			graphics_draw_line(ctx, GPoint(icon_x + icon_gap, center_y),
			                   GPoint(x_end, center_y));
			graphics_draw_line(ctx, GPoint(x_end, center_y - 2),
			                   GPoint(x_end, center_y + 2));
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
	int n = prv_find_spans(el->hourly_code, el->fog, el->hours_remaining, spans);
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
                          const uint8_t *fog, uint8_t hours_remaining) {
	if (!layer)
		return;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	memcpy(layer->hourly_code, hourly_code, total_hours);
	if (fog)
		memcpy(layer->fog, fog, total_hours);
	else
		memset(layer->fog, 0, sizeof(layer->fog));
	layer->hours_remaining = hours_remaining;
	layer_mark_dirty(layer->layer);
}
