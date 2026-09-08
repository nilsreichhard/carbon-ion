/**
 * Daylight layer
 *
 * @author    Cory Hughart <cory@coryhughart.com>
 * @copyright 2026 Cory Hughart
 * @license   https://www.gnu.org/licenses/gpl-3.0.html GPL-3.0-or-later
 * @link      https://cr0ybot.com/project/pebble-watchface-carbon
 */

#include "daylight_layer.h"
#include "../generated/icons.h"
#include "../modules/settings.h"
#include "graph_common.h"
#include <stddef.h>

struct DaylightLayer {
	Layer *layer;
	uint8_t sunrise_hour;
	uint8_t sunset_hour;
	uint8_t current_hour;
	uint8_t current_minute;
	uint8_t battery_percent;
	bool battery_charging;
	bool sunrise_approx;
	bool sunset_approx;
};

// Distance from the approximate-hour center at which the solid line stops.
// Dots are drawn at center-2, center, center+2 (centered on the approx hour).
#define DITHER_REACH 4

// Calculate the current moon phase (0=new, 1=wax crescent, 2=first quarter,
// 3=wax gibbous, 4=full, 5=wan gibbous, 6=last quarter, 7=wan crescent).
// Integer-only adaptation of the classic algorithm, scaled x10000 and
// epoch-offset to year 2000 so all values fit in 32-bit long.
static int prv_moon_phase(void) {
	time_t now = time(NULL);
	struct tm *t = localtime(&now);
	if (!t)
		return 0;
	int year = t->tm_year + 1900;
	int month = t->tm_mon + 1;
	int day = t->tm_mday;
	if (month < 3) {
		year--;
		month += 12;
	}
	month++;
	int yp = year - 2000;
	long jd = 3652500L * yp + 306000L * month + 10000L * day + 364609100L;
	long denom = 295306L; // 29.5305882 * 10000 (lunar cycle)
	long rem = jd % denom;
	int b = (int)((rem * 8L + denom / 2L) / denom);
	return (b >= 8) ? 0 : b;
}

// Draw a circle marker styled by phase:
//   phase 4 (noon): solid white.
//   phase 0-7 (moon): 0=new (dark outline), 4=full (solid white),
//     partial phases fill interior pixel columns from the lit side.
//     r=3 gives interior dx range [-2..+2]; thresholds per phase:
//       waxing  1→dx>=+2  2→dx>=+1  3→dx>=-1
//       waning  5→dx<=+1  6→dx<=-1  7→dx<=-2
// When col==0 also draws the marker peeking from the right edge (wrap).
// Draw a dithered fade centered at x to indicate an approximate endpoint.
// Three white pixels at offsets -2, 0, +2 create the sparse `——∙∙∙` look.
static void prv_draw_dither_end(GContext *ctx, int x, int line_y) {
	bool is_light = settings_get()->light_theme;
	graphics_context_set_stroke_color(ctx, is_light ? GColorDarkGray : GColorWhite);
	graphics_context_set_stroke_width(ctx, 1);
	graphics_draw_pixel(ctx, GPoint(x - 2, line_y));
	graphics_draw_pixel(ctx, GPoint(x, line_y));
	graphics_draw_pixel(ctx, GPoint(x + 2, line_y));
}

static void prv_draw_col_marker(GContext *ctx, int col, int phase, int graph_x,
                                int bar_w, int line_y, int layer_w) {
	bool is_light = settings_get()->light_theme;
	// Lit-side threshold indexed by phase (0 and 4 are special-cased below)
	static const int s_thr[8] = {0, 2, 1, -1, 0, 1, -1, -2};
	const int r = 3;
	int xs[2], n = 1;
	xs[0] = graph_x + col * bar_w;
	if (col == 0) {
		xs[1] = layer_w;
		n = 2;
	}
	for (int i = 0; i < n; i++) {
		GPoint pt = GPoint(xs[i], line_y);

		// Base: fill + outline
		graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
		graphics_fill_circle(ctx, pt, r);
		graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
		graphics_context_set_stroke_width(ctx, 1);
		graphics_draw_circle(ctx, pt, r);

		if (phase == 0)
			continue; // new moon: all dark, done

		if (phase == 4) {
			// Full / noon: solid on top of outline
			graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
			graphics_fill_circle(ctx, pt, r);
			continue;
		}

		// Partial phases: paint lit interior columns with horizontal lines
		bool waxing = (phase >= 1 && phase <= 3);
		int thr = s_thr[phase];

		graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
		graphics_context_set_stroke_width(ctx, 1);
		for (int dy = -(r - 1); dy <= r - 1; dy++) {
			// Find max dx strictly inside the circle at this row
			int dx_max = 0;
			while ((dx_max + 1) * (dx_max + 1) + dy * dy < r * r)
				dx_max++;
			// Lit x range: waxing fills [thr..+dx_max], waning fills
			// [-dx_max..thr]
			int x_lo = waxing ? thr : -dx_max;
			int x_hi = waxing ? dx_max : thr;
			if (x_lo < -dx_max)
				x_lo = -dx_max;
			if (x_hi > dx_max)
				x_hi = dx_max;
			if (x_lo > x_hi)
				continue;
			graphics_draw_line(ctx, GPoint(pt.x + x_lo, pt.y + dy),
			                   GPoint(pt.x + x_hi, pt.y + dy));
		}
	}
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
	DaylightLayer *dl = *(DaylightLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	int past_hours = graph_get_past_hours();
	int forecast_hours = graph_get_forecast_hours();
	int bar_w = graph_w / total_hours;
	int lh = bounds.size.h;
	int line_y = lh / 2;
	bool is_light = settings_get()->light_theme;

	// Timeline window: past_hours (1/5) in past, forecast_hours (4/5) in future.
	// The red indicator stays permanently anchored at 1/5 (column past_hours = Now).
	int base_hour = ((int)dl->current_hour - past_hours + 240) % 24;

	// Markers drawn first; label column filled black after to clip any bleed
	int noon_off = (12 - base_hour + 24) % 24;
	int midn_off = (24 - base_hour) % 24;
	int moon_phase = prv_moon_phase();
	prv_draw_col_marker(ctx, noon_off, 4, graph_x, bar_w, line_y,
	                    bounds.size.w);
	prv_draw_col_marker(ctx, midn_off, moon_phase, graph_x, bar_w, line_y,
	                    bounds.size.w);

	// Daylight line (Soft grey on light theme, white on dark theme)
#if defined(PBL_COLOR)
	graphics_context_set_stroke_color(ctx, is_light ? GColorLightGray : GColorWhite);
#else
	graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
	graphics_context_set_stroke_width(ctx, 2);

	int rise_off = ((int)dl->sunrise_hour - base_hour + 24) % 24;
	int set_off = ((int)dl->sunset_hour - base_hour + 24) % 24;

	int x_rise = graph_x + rise_off * bar_w;
	int x_set = graph_x + set_off * bar_w;
	int x_end = bounds.size.w;

	// Solid-line endpoints, pulled inward when the end is approximate.
	int x_rise_solid = dl->sunrise_approx ? x_rise + DITHER_REACH : x_rise;
	int x_set_solid = dl->sunset_approx ? x_set - DITHER_REACH : x_set;

	if (rise_off < set_off) {
		if (x_rise_solid < x_set_solid) {
			graphics_draw_line(ctx, GPoint(x_rise_solid, line_y),
			                   GPoint(x_set_solid, line_y));
		}
		if (dl->sunrise_approx) {
			prv_draw_dither_end(ctx, x_rise, line_y);
		} else {
			graphics_draw_line(ctx, GPoint(x_rise, line_y - 2),
			                   GPoint(x_rise, line_y + 2));
		}
		if (dl->sunset_approx) {
			prv_draw_dither_end(ctx, x_set, line_y);
		} else {
			graphics_draw_line(ctx, GPoint(x_set, line_y - 2),
			                   GPoint(x_set, line_y + 2));
		}
	} else if (rise_off > set_off) {
		if (set_off > 0) {
			graphics_draw_line(ctx, GPoint(graph_x, line_y),
			                   GPoint(x_set_solid, line_y));
			if (dl->sunset_approx) {
				prv_draw_dither_end(ctx, x_set, line_y);
			} else {
				graphics_draw_line(ctx, GPoint(x_set, line_y - 2),
				                   GPoint(x_set, line_y + 2));
			}
		}
		graphics_draw_line(ctx, GPoint(x_rise_solid, line_y),
		                   GPoint(x_end, line_y));
		if (dl->sunrise_approx) {
			prv_draw_dither_end(ctx, x_rise, line_y);
		} else {
			graphics_draw_line(ctx, GPoint(x_rise, line_y - 2),
			                   GPoint(x_rise, line_y + 2));
		}
	}

	// Red line indicator on the top stays FIXED at exactly 1/5 of the timeline (6h past, 24h future)
	NeedleMode needle_mode = settings_get()->needle_mode;
	if (needle_mode == NEEDLE_BOTH || needle_mode == NEEDLE_ABOVE) {
		int x_now = graph_x + (graph_w / 5);
#if defined(PBL_COLOR)
		graphics_context_set_stroke_color(ctx, GColorRed);
#else
		graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
		graphics_context_set_stroke_width(ctx, 2);
		graphics_draw_line(ctx, GPoint(x_now, line_y - 5),
		                   GPoint(x_now, line_y + 5));
		graphics_draw_line(ctx, GPoint(x_now - 2, line_y + 5),
		                   GPoint(x_now + 2, line_y + 5));
		graphics_draw_line(ctx, GPoint(x_now - 1, line_y + 6),
		                   GPoint(x_now + 1, line_y + 6));
		graphics_draw_pixel(ctx, GPoint(x_now, line_y + 7));
	}

	// Battery life depletion projection on the bar (Yellow at 10%, Red when expected to die)
	// Drawn directly and vertically center-aligned on the timeline bar
	if (!dl->battery_charging && dl->battery_percent < 100) {
		// Drain rate: ~1.25% per hour (100% in 80 hours)
		if (dl->battery_percent > 10) {
			int hrs_to_10 = (int)(dl->battery_percent - 10) * 4 / 5;
			if (hrs_to_10 >= 0 && hrs_to_10 <= forecast_hours) {
				int col10 = past_hours + hrs_to_10;
				int x10 = graph_x + col10 * graph_w / total_hours;
				graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
				graphics_fill_circle(ctx, GPoint(x10, line_y), 6);
#if defined(PBL_COLOR)
				graphics_context_set_stroke_color(ctx, GColorChromeYellow);
				graphics_context_set_fill_color(ctx, GColorChromeYellow);
#else
				graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
				graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
				graphics_context_set_stroke_width(ctx, 1);
				// Battery body and terminal vertically centered on line_y
				graphics_draw_round_rect(ctx, GRect(x10 - 4, line_y - 3, 8, 6), 1);
				graphics_fill_rect(ctx, GRect(x10 + 4, line_y - 1, 1, 3), 0, GCornerNone);
				graphics_fill_rect(ctx, GRect(x10 - 3, line_y - 2, 2, 4), 0, GCornerNone);
			}
		}

		int hrs_to_0 = (int)dl->battery_percent * 4 / 5;
		if (hrs_to_0 >= 0 && hrs_to_0 <= forecast_hours) {
			int col0 = past_hours + hrs_to_0;
			int x0 = graph_x + col0 * graph_w / total_hours;
			graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
			graphics_fill_circle(ctx, GPoint(x0, line_y), 6);
#if defined(PBL_COLOR)
			graphics_context_set_stroke_color(ctx, GColorRed);
			graphics_context_set_fill_color(ctx, GColorRed);
#else
			graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
			graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
			graphics_context_set_stroke_width(ctx, 1);
			// Empty battery body and terminal vertically centered on line_y
			graphics_draw_round_rect(ctx, GRect(x0 - 4, line_y - 3, 8, 6), 1);
			graphics_fill_rect(ctx, GRect(x0 + 4, line_y - 1, 1, 3), 0, GCornerNone);
		}
	}
}

DaylightLayer *daylight_layer_create(GRect frame) {
	DaylightLayer *dl = malloc(sizeof(DaylightLayer));
	if (!dl)
		return NULL;
	dl->sunrise_hour = 6;
	dl->sunset_hour = 18;
	dl->current_hour = 0;
	dl->current_minute = 0;
	dl->battery_percent = 100;
	dl->battery_charging = false;
	dl->sunrise_approx = true;
	dl->sunset_approx = true;

	dl->layer = layer_create_with_data(frame, sizeof(DaylightLayer *));
	*(DaylightLayer **)layer_get_data(dl->layer) = dl;
	layer_set_update_proc(dl->layer, prv_update_proc);
	return dl;
}

void daylight_layer_set_battery(DaylightLayer *layer, uint8_t percent, bool charging) {
	if (!layer)
		return;
	layer->battery_percent = percent;
	layer->battery_charging = charging;
	layer_mark_dirty(layer->layer);
}

void daylight_layer_set_current_time(DaylightLayer *layer, uint8_t hour, uint8_t minute) {
	if (!layer)
		return;
	layer->current_hour = hour;
	layer->current_minute = minute;
	layer_mark_dirty(layer->layer);
}

void daylight_layer_destroy(DaylightLayer *layer) {
	if (!layer)
		return;
	layer_destroy(layer->layer);
	free(layer);
}

Layer *daylight_layer_get_layer(DaylightLayer *layer) {
	return layer ? layer->layer : NULL;
}

void daylight_layer_set_data(DaylightLayer *layer, uint8_t sunrise_hour,
                             uint8_t sunset_hour, uint8_t current_hour,
                             bool sunrise_approx, bool sunset_approx) {
	if (!layer)
		return;
	layer->sunrise_hour = sunrise_hour;
	layer->sunset_hour = sunset_hour;
	layer->current_hour = current_hour;
	layer->sunrise_approx = sunrise_approx;
	layer->sunset_approx = sunset_approx;
	layer_mark_dirty(layer->layer);
}
