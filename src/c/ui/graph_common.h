/**
 * Graph common utilities
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>
#include <stdlib.h>

#ifndef PBL_DISPLAY_HEIGHT
#if defined(PBL_PLATFORM_EMERY)
#define PBL_DISPLAY_WIDTH 200
#define PBL_DISPLAY_HEIGHT 228
#elif defined(PBL_PLATFORM_CHALK)
#define PBL_DISPLAY_WIDTH 180
#define PBL_DISPLAY_HEIGHT 180
#else
#define PBL_DISPLAY_WIDTH 144
#define PBL_DISPLAY_HEIGHT 168
#endif
#endif

// Horizontal pixel offset shared by all graph layers (daylight, cloud,
// precip, temp). Bleeds flush to screen edges with 0 offset.
#define GRAPH_OFFSET_X 0

// Number of hourly slots displayed across all graph layers:
// Forecast 12/18/24/36/48h (Clay value, or tapped override); past = forecast/4
// (integer, so 18h → 4h past, 22h total). Needle sits at past/total of the
// width — exactly 1/5 except at 18h — use graph_now_x() everywhere.
#include "../modules/settings.h"

static inline int graph_get_forecast_hours(void) {
	int fh = settings_effective_forecast_hours();
	if (fh < MIN_FORECAST_HOURS || fh > MAX_FORECAST_HOURS)
		return DEFAULT_FORECAST_HOURS;
	return fh;
}

static inline int graph_get_past_hours(void) {
	return graph_get_forecast_hours() / 4;
}

static inline int graph_get_total_hours(void) {
	int fh = graph_get_forecast_hours();
	return fh + fh / 4;
}

/* X of the "now" needle: same column grid as hourly data (col = past). */
static inline int graph_now_x(int graph_x, int graph_w) {
	return graph_x + graph_w * graph_get_past_hours() / graph_get_total_hours();
}

#define GRAPH_HOURS (graph_get_total_hours())

// Draw a dashed line by sampling pixels along the segment.
static inline void graph_draw_dashed_line(GContext *ctx, GPoint start,
                                          GPoint end, int dash_len,
                                          int gap_len) {
	int dx = end.x - start.x;
	int dy = end.y - start.y;
	int steps = abs(dx) > abs(dy) ? abs(dx) : abs(dy);
	if (steps == 0) {
		graphics_draw_pixel(ctx, start);
		return;
	}
	int period = dash_len + gap_len;
	if (period == 0)
		period = 1;
	for (int step = 0; step <= steps; step++) {
		if ((step % period) < dash_len) {
			int x = start.x + dx * step / steps;
			int y = start.y + dy * step / steps;
			graphics_draw_pixel(ctx, GPoint(x, y));
		}
	}
}

// Draw the vertical separator line between label area and graph area.
static inline void graph_draw_separator(GContext *ctx, int graph_x,
                                        int layer_h) {
	graphics_context_set_stroke_width(ctx, 1);
#if defined(PBL_COLOR)
	graphics_context_set_stroke_color(
	    ctx, !g_high_contrast ? GColorDarkGray
	                          : (settings_get()->light_theme ? GColorBlack : GColorLightGray));
	graphics_draw_line(ctx, GPoint(graph_x - 1, 0),
	                   GPoint(graph_x - 1, layer_h - 1));
#else
	graphics_context_set_stroke_color(ctx, GColorWhite);
	graphics_draw_line(ctx, GPoint(graph_x - 1, 0),
	                   GPoint(graph_x - 1, layer_h - 1));
#endif
}
