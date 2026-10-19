/**
 * Temperature layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "temp_layer.h"
#include "../modules/settings.h"
#include "../modules/weather.h"
#include "graph_common.h"
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct TempLayer {
	Layer *layer;
	int16_t current;
	int16_t high;
	int16_t low;
	int8_t hourly[MAX_GRAPH_HOURS + 1];
	int8_t apparent_hourly[MAX_GRAPH_HOURS + 1];
	uint8_t current_hour;
	uint8_t hours_remaining;
	bool celsius;
	bool expired; /* 2.8.7: forecast ran out / older than 24 h -> "--" */

	// Pre-allocated heap buffers to eliminate stack pressure during drawing
	int16_t pts[MAX_GRAPH_HOURS + 1];
	int16_t apt[MAX_GRAPH_HOURS + 1];
	int spx[MAX_GRAPH_HOURS + 1];
	int spy[MAX_GRAPH_HOURS + 1];
	int apx[MAX_GRAPH_HOURS + 1];
	int apy[MAX_GRAPH_HOURS + 1];
};

static char curr_buf[10], high_buf[8], low_buf[8];
static char s_range_buf[18];

/* Hour ticks along the meteogram baseline (3 px, 5 px at 00/12). */
static void prv_draw_ticks(GContext *ctx, const TempLayer *tl, int total_hours, int now_col,
                           int graph_x, int graph_w, int lh, bool is_light) {
	int base_hour = ((int)tl->current_hour - now_col + 240) % 24;
	int tick_step = (total_hours > 36) ? 3 : 1;
	graphics_context_set_stroke_width(ctx, 1);
	for (int i = 0; i <= total_hours; i += tick_step) {
		int tx = graph_x + i * graph_w / total_hours;
		int hour_val = (base_hour + i) % 24;
		int tick_len = (hour_val == 0 || hour_val == 12) ? 5 : 3;
		GColor tick_col;
		if (is_light) {
			tick_col = (hour_val == 0 || hour_val == 12) ? GColorBlack : (g_high_contrast ? GColorBlack : GColorDarkGray);
		} else {
			tick_col = (hour_val == 0 || hour_val == 12) ? GColorWhite : (g_high_contrast ? GColorLightGray : GColorDarkGray);
		}
		graphics_context_set_stroke_color(ctx, tick_col);
		graphics_draw_line(ctx, GPoint(tx, lh - tick_len), GPoint(tx, lh - 1));
	}
}

/* Red "now" stem through the meteogram. */
static void prv_draw_needle(GContext *ctx, int graph_x, int graph_w, int lh, bool is_light) {
	NeedleMode needle_mode = settings_get()->needle_mode;
	if (needle_mode == NEEDLE_BOTH || needle_mode == NEEDLE_BELOW) {
		int x_now = graph_now_x(graph_x, graph_w);
#if defined(PBL_COLOR)
		graphics_context_set_fill_color(ctx, GColorRed);
#else
		graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
		// Stem 2px wide
		graphics_fill_rect(ctx, GRect(x_now - 1, 0, 2, lh), 0, GCornerNone);
	}

}

#if !defined(PBL_ROUND)
/* High / current / low labels (rect platforms). */
static void prv_draw_labels(GContext *ctx, int lh, int graph_x, int graph_w, int zone_h,
                            GFont font_sm, GFont font_md, int sm_h, int md_h, bool is_light,
                            int16_t window_low, int16_t window_high) {
	// Floating temperature labels overlapping on top of the left side of the chart (Right-aligned)
	int label_x = 32;
	GColor text_color = is_light ? GColorBlack : GColorWhite;
	graphics_context_set_text_color(ctx, text_color);

	/* Narrow rows (gabbro's inset meteogram): the needle sits
	 * at ~29 px, through the right-aligned labels. Put them just right of the
	 * needle instead; if three stacked labels don't fit the height, show
	 * current on top and "low-high" below. Emery (200 px) is unchanged. */
	if (graph_w < 180) {
		int nx = graph_now_x(graph_x, graph_w) + 3;
		int lw = graph_w - nx;
#if !defined(PBL_COLOR)
		/* B&W: knock out the curve behind the labels so they stay legible. */
		{
			static char rb[18];
			snprintf(rb, sizeof(rb), "%d-%d", (int)window_low, (int)window_high);
			GSize c1 = graphics_text_layout_get_content_size(curr_buf, font_md, GRect(0, 0, lw, md_h),
			                                                 GTextOverflowModeFill, GTextAlignmentLeft);
			GSize c2 = graphics_text_layout_get_content_size(rb, font_sm, GRect(0, 0, lw, sm_h),
			                                                 GTextOverflowModeFill, GTextAlignmentLeft);
			int bw = (c1.w > c2.w ? c1.w : c2.w) + 3;
			graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
			graphics_fill_rect(ctx, GRect(nx - 1, 0, bw, md_h + sm_h - 2), 0, GCornerNone);
		}
#endif
		if (lh >= 2 * sm_h + md_h) {
			int y0 = (lh - (2 * sm_h + md_h)) / 2 - 2;
			if (y0 < 0) y0 = 0;
			graphics_draw_text(ctx, high_buf, font_sm, GRect(nx, y0, lw, sm_h),
			                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
			graphics_draw_text(ctx, curr_buf, font_md, GRect(nx, y0 + sm_h - 2, lw, md_h),
			                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
			graphics_draw_text(ctx, low_buf, font_sm, GRect(nx, y0 + sm_h + md_h - 2, lw, sm_h),
			                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
		} else {
			static char range_buf[18];
			snprintf(range_buf, sizeof(range_buf), "%d-%d", (int)window_low, (int)window_high);
			graphics_draw_text(ctx, curr_buf, font_md, GRect(nx, -2, lw, md_h),
			                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
			graphics_draw_text(ctx, range_buf, font_sm, GRect(nx, md_h - 3, lw, sm_h),
			                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
		}
		return;
	}
	/* Place current + min first, then lift max so max→current gap matches
	 * current→min gap (was 4px overlap above vs 0px below). */
	const int label_lift = 3;
	const int curr_lift = 2; /* optical: current looked closer to min */
	int y_curr = zone_h + (zone_h - md_h) / 2 - label_lift - curr_lift;
	if (y_curr + md_h > 2 * zone_h)
		y_curr = 2 * zone_h - md_h;
	if (y_curr < 0)
		y_curr = 0;
	int y_low = 2 * zone_h + (zone_h - sm_h) / 2 - label_lift;
	if (y_low < y_curr + md_h - 2)
		y_low = y_curr + md_h - 2;
	if (y_low + sm_h > lh - 2)
		y_low = lh - 2 - sm_h;
	if (y_low < 0)
		y_low = 0;
	int gap_curr_min = y_low - (y_curr + md_h); /* 0 on emery */
	int y_high = y_curr - sm_h - gap_curr_min;
	/* Allow slight clip above layer so the mirrored gap can fit. */
	if (y_high + sm_h > y_curr - gap_curr_min)
		y_high = y_curr - sm_h - gap_curr_min;

	graphics_draw_text(ctx, high_buf, font_sm,
	                   GRect(2, y_high, label_x, sm_h),
	                   GTextOverflowModeWordWrap, GTextAlignmentRight, NULL);
	graphics_draw_text(ctx, curr_buf, font_md,
	                   GRect(2, y_curr, label_x, md_h),
	                   GTextOverflowModeWordWrap, GTextAlignmentRight, NULL);
	graphics_draw_text(ctx, low_buf, font_sm,
	                   GRect(2, y_low, label_x, sm_h),
	                   GTextOverflowModeWordWrap, GTextAlignmentRight, NULL);
}
#endif

static void prv_update_proc(Layer *layer, GContext *ctx) {
	TempLayer *tl = *(TempLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int lh = bounds.size.h;
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

#if PBL_DISPLAY_HEIGHT >= 228
	GFont font_sm = fonts_get_system_font(FONT_KEY_GOTHIC_18);
	GFont font_md = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
#else
	GFont font_sm = fonts_get_system_font(FONT_KEY_GOTHIC_14);
	GFont font_md = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
#endif
	bool is_light = settings_get()->light_theme;
	graphics_context_set_text_color(ctx, is_light ? GColorBlack : GColorWhite);

#if PBL_DISPLAY_HEIGHT >= 228
	int sm_h = 16;
	int md_h = 22;
#else
	int sm_h = 14;
	int md_h = 18;
#endif
	int zone_h =
	    (lh - 2) / 3; // 2px bottom padding keeps low label off the edge

	// Sparkline — total_hours + 1 points with current temp anchored at needle (now_col)
	int now_col = graph_get_past_hours();
	int16_t *pts = tl->pts;
	int16_t *apt = tl->apt;
	for (int i = 0; i <= total_hours; i++) {
		if (i == now_col && tl->current != WEATHER_TEMP_INVALID) {
			pts[i] = tl->current;
		} else if (i > 0 && i >= tl->hours_remaining) {
			pts[i] = pts[i - 1];
		} else {
			pts[i] = tl->hourly[i];
		}

		if (i > 0 && i >= tl->hours_remaining) {
			apt[i] = apt[i - 1];
		} else {
			apt[i] = tl->apparent_hourly[i];
		}
	}

	// Dynamic High and Low across the active visible timeline window
	int16_t window_high = pts[0];
	int16_t window_low = pts[0];
	for (int i = 1; i <= total_hours; i++) {
		if (i >= tl->hours_remaining) break; /* no data: draw nothing */
		if (pts[i] > window_high) window_high = pts[i];
		if (pts[i] < window_low) window_low = pts[i];
	}

	if (tl->expired) {
		strcpy(high_buf, "--");
		strcpy(curr_buf, "--");
		strcpy(low_buf, "--");
		strcpy(s_range_buf, "--");
	} else {
		snprintf(high_buf, sizeof(high_buf), "%d", (int)window_high);
		snprintf(curr_buf, sizeof(curr_buf), "%d", (int)tl->current);
		snprintf(low_buf, sizeof(low_buf), "%d", (int)window_low);
		snprintf(s_range_buf, sizeof(s_range_buf), "%d-%d", (int)window_low, (int)window_high);
	}

	int16_t t_min = pts[0] < apt[0] ? pts[0] : apt[0];
	int16_t t_max = pts[0] > apt[0] ? pts[0] : apt[0];
	for (int i = 1; i <= total_hours; i++) {
		if (i >= tl->hours_remaining) break; /* no data: draw nothing */
		if (pts[i] < t_min)
			t_min = pts[i];
		if (pts[i] > t_max)
			t_max = pts[i];
		if (apt[i] < t_min)
			t_min = apt[i];
		if (apt[i] > t_max)
			t_max = apt[i];
	}
	int t_range = t_max - t_min;
	if (t_range < 1)
		t_range = 1;

#if defined(PBL_ROUND)
	/* Arc (2.8.3): labels live outside the band now, so the curve uses the
	 * band down to a small inner/outer margin (was 30 of 48 rows). */
	int pad = 4;
	int graph_h = lh - pad - 3;
#else
	int pad = 4;
	int graph_h = lh - pad * 2;
#endif

	// Pre-compute pixel positions for both lines
	int *spx = tl->spx;
	int *spy = tl->spy;
	int *apx = tl->apx;
	int *apy = tl->apy;
	for (int i = 0; i <= total_hours; i++) {
		spx[i] = graph_x + i * graph_w / total_hours;
		spy[i] = pad + graph_h - ((pts[i] - t_min) * graph_h / t_range);
		apx[i] = spx[i];
		apy[i] = pad + graph_h - ((apt[i] - t_min) * graph_h / t_range);
	}

#if defined(PBL_COLOR)
	graphics_context_set_compositing_mode(ctx, GCompOpAssign);
	int line_bottom = lh;
#define TEMP_TO_F(t) (tl->celsius ? ((t) * 9 / 5 + 32) : (t))
#define COLOR_DARK_THEME(tf)                                                   \
	((tf) <= 10   ? GColorPurple                                               \
	 : (tf) <= 32 ? GColorElectricUltramarine                                  \
	 : (tf) <= 45 ? GColorCyan                                                 \
	 : (tf) <= 59 ? GColorCadetBlue                                            \
	 : (tf) <= 76 ? GColorKellyGreen                                           \
	 : (tf) <= 84 ? GColorChromeYellow                                         \
	 : (tf) <= 96 ? GColorOrange                                               \
	              : GColorRed)
#define COLOR_LIGHT_THEME(tf)                                                  \
	((tf) <= 10   ? GColorImperialPurple                                       \
	 : (tf) <= 32 ? GColorCobaltBlue                                           \
	 : (tf) <= 45 ? GColorTiffanyBlue                                          \
	 : (tf) <= 59 ? GColorMediumAquamarine                                     \
	 : (tf) <= 76 ? GColorSpringBud                                            \
	 : (tf) <= 84 ? GColorIcterine                                             \
	 : (tf) <= 96 ? GColorOrange                                               \
	              : GColorRed)
#define GET_TEMP_COLOR(tf) (is_light ? COLOR_LIGHT_THEME(tf) : COLOR_DARK_THEME(tf))

	// Pass 1: solid infill based on infill_mode setting
	InfillMode infill = settings_get()->infill_mode;
	if (infill != INFILL_NONE) {
		int now_col = graph_get_past_hours(); // needle column
		int x_now = graph_now_x(graph_x, graph_w);
		int start_col = (infill == INFILL_FUTURE) ? (now_col + 1) : 1;
		int end_col = (infill == INFILL_PAST) ? now_col : total_hours;

		for (int i = start_col; i <= end_col && i <= total_hours; i++) {
			if (i >= tl->hours_remaining) break; /* no data: draw nothing */
			int avg = ((int)pts[i - 1] + (int)pts[i]) / 2;
			GColor fill_col = GET_TEMP_COLOR(TEMP_TO_F(avg));
			fill_col.a = 3; /* force fully opaque (no alpha blend) */
			graphics_context_set_fill_color(ctx, fill_col);
			int x0 = spx[i - 1], y0 = spy[i - 1], x1 = spx[i], y1 = spy[i];
			int dx = x1 - x0, dy = y1 - y0;
			if (dx <= 0)
				continue;
			int x_end = (i == end_col || i == total_hours) ? x1 : (x1 - 1);
			for (int col_x = x0; col_x <= x_end; col_x++) {
				if (infill == INFILL_FUTURE && col_x < x_now)
					continue;
				if (infill == INFILL_PAST && col_x > x_now)
					continue;
				int col_y = y0 + dy * (col_x - x0) / dx + 2; // Offset down so line cleanly covers top of infill
				int col_h = line_bottom - col_y;
				if (col_h > 0) {
					graphics_fill_rect(ctx, GRect(col_x, col_y, 1, col_h), 0,
					                   GCornerNone);
				}
			}
		}
	}

	// Pass 2: bold actual-temp line matching exact color of the infill
	graphics_context_set_stroke_width(ctx, 2);
	for (int i = 1; i <= total_hours; i++) {
		if (i >= tl->hours_remaining) break; /* no data: draw nothing */
		int avg = ((int)pts[i - 1] + (int)pts[i]) / 2;
		GColor stroke_col = GET_TEMP_COLOR(TEMP_TO_F(avg));
		stroke_col.a = 3; /* force fully opaque (no alpha blend) */
		graphics_context_set_stroke_color(ctx, stroke_col);
		graphics_draw_line(ctx, GPoint(spx[i - 1], spy[i - 1]),
		                   GPoint(spx[i], spy[i]));
	}

	// Pass 3: apparent-temp ("feels like") dashed line
	graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
	for (int i = 1; i <= total_hours; i++) {
		if (i >= tl->hours_remaining) break; /* no data: draw nothing */
		graph_draw_dashed_line(ctx, GPoint(apx[i - 1], apy[i - 1]),
		                       GPoint(apx[i], apy[i]), 3, 2);
	}

#undef COLOR_DARK_THEME
#undef COLOR_LIGHT_THEME
#undef GET_TEMP_COLOR
#undef TEMP_TO_F
#else
	// B&W: actual-temp line + dotted apparent-temp line in the theme ink
	// (was always white, so invisible on the light theme).
	graphics_context_set_stroke_width(ctx, 2);
	graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
	for (int i = 1; i <= total_hours; i++) {
		if (i >= tl->hours_remaining) break; /* no data: draw nothing */
		graphics_draw_line(ctx, GPoint(spx[i - 1], spy[i - 1]),
		                   GPoint(spx[i], spy[i]));
	}

	for (int i = 1; i <= total_hours; i++) {
		if (i >= tl->hours_remaining) break; /* no data: draw nothing */
		graph_draw_dashed_line(ctx, GPoint(apx[i - 1], apy[i - 1]),
		                       GPoint(apx[i], apy[i]), 3, 2);
	}
#endif

	prv_draw_ticks(ctx, tl, total_hours, now_col, graph_x, graph_w, lh, is_light);
	prv_draw_needle(ctx, graph_x, graph_w, lh, is_light);
#if defined(PBL_ROUND)
	/* Arc mode: labels are drawn upright by temp_layer_draw_labels_mapped()
	 * after the warp, so they aren't bent along the arc. */
	(void)zone_h;
	(void)font_sm;
	(void)font_md;
	(void)sm_h;
	(void)md_h;
	(void)window_low;
	(void)window_high;
#else
	prv_draw_labels(ctx, lh, graph_x, graph_w, zone_h, font_sm, font_md, sm_h, md_h, is_light,
	                window_low, window_high);
#endif
}

TempLayer *temp_layer_create(GRect frame) {
	TempLayer *tl = malloc(sizeof(TempLayer));
	if (!tl)
		return NULL;
	tl->current = 0;
	tl->high = 0;
	tl->low = 0;
	tl->expired = false;
	tl->current_hour = 0;
	tl->hours_remaining = GRAPH_HOURS;
	tl->celsius = false;
	memset(tl->hourly, 0, sizeof(tl->hourly));
	memset(tl->apparent_hourly, 0, sizeof(tl->apparent_hourly));

	tl->layer = layer_create_with_data(frame, sizeof(TempLayer *));
	*(TempLayer **)layer_get_data(tl->layer) = tl;
	layer_set_update_proc(tl->layer, prv_update_proc);
	return tl;
}

void temp_layer_destroy(TempLayer *layer) {
	if (!layer)
		return;
	layer_destroy(layer->layer);
	free(layer);
}

Layer *temp_layer_get_layer(TempLayer *layer) {
	return layer ? layer->layer : NULL;
}

void temp_layer_set_data(TempLayer *layer, int16_t current, int16_t high,
                         int16_t low, const int8_t *hourly,
                         const int8_t *apparent_hourly, uint8_t current_hour,
                         uint8_t hours_remaining) {
	if (!layer)
		return;
	layer->current = current;
	layer->high = high;
	layer->low = low;
	layer->current_hour = current_hour;
	layer->hours_remaining = hours_remaining;
	memcpy(layer->hourly, hourly, sizeof(layer->hourly));
	memcpy(layer->apparent_hourly, apparent_hourly, sizeof(layer->apparent_hourly));
	layer_mark_dirty(layer->layer);
}

void temp_layer_set_expired(TempLayer *layer, bool expired) {
	if (!layer || layer->expired == expired)
		return;
	layer->expired = expired;
	layer_mark_dirty(layer->layer);
}

void temp_layer_set_unit(TempLayer *layer, bool celsius) {
	if (!layer)
		return;
	layer->celsius = celsius;
	layer_mark_dirty(layer->layer);
}

void temp_layer_set_current_hour(TempLayer *layer, uint8_t current_hour,
                                 uint8_t hours_remaining) {
	if (!layer)
		return;
	layer->current_hour = current_hour;
	layer->hours_remaining = hours_remaining;
	layer_mark_dirty(layer->layer);
}

#if defined(PBL_ROUND)
#define TEMP_LABEL_GAP 4
void temp_layer_draw_labels_mapped(TempLayer *tl, GContext *ctx,
                                   ArcMapFn map) {
	if (!tl)
		return;
	/* 2.8.3: "min  CUR  max" upright, centred in the gap between the date and
	 * the bottom arc. Same faces as emery (Gothic 18 / 24 Bold), current in
	 * the middle; the three share one baseline. */
	GRect f = layer_get_frame(tl->layer);
	GPoint inner = map(f.origin.x + f.size.w / 2, f.origin.y); /* inner edge, centre */
	GFont font_sm = fonts_get_system_font(FONT_KEY_GOTHIC_18);
	GFont font_md = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
	bool light = settings_get()->light_theme;
	GSize cl = graphics_text_layout_get_content_size(low_buf, font_sm, GRect(0, 0, 60, 22),
	                                                 GTextOverflowModeFill, GTextAlignmentLeft);
	GSize cc = graphics_text_layout_get_content_size(curr_buf, font_md, GRect(0, 0, 60, 28),
	                                                 GTextOverflowModeFill, GTextAlignmentLeft);
	GSize ch = graphics_text_layout_get_content_size(high_buf, font_sm, GRect(0, 0, 60, 22),
	                                                 GTextOverflowModeFill, GTextAlignmentLeft);
	int gap = 6, total = cl.w + gap + cc.w + gap + ch.w;
	int x0 = PBL_DISPLAY_WIDTH / 2 - total / 2;
	/* Gothic 24 Bold digits: cap top ~+8, baseline ~+22 in its box; Gothic 18:
	 * top ~+6, baseline ~+16. Baseline sits TEMP_LABEL_GAP px above the arc. */
	int base = inner.y - TEMP_LABEL_GAP;
	graphics_context_set_text_color(ctx, light ? GColorBlack : GColorWhite);
	graphics_draw_text(ctx, low_buf, font_sm, GRect(x0, base - 16, cl.w + 2, 22),
	                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
	graphics_draw_text(ctx, curr_buf, font_md, GRect(x0 + cl.w + gap, base - 22, cc.w + 2, 28),
	                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
	graphics_draw_text(ctx, high_buf, font_sm, GRect(x0 + cl.w + gap + cc.w + gap, base - 16, ch.w + 2, 22),
	                   GTextOverflowModeFill, GTextAlignmentLeft, NULL);
}
#endif
