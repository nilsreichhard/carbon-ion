/**
 * Daylight layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "daylight_layer.h"
#include "../generated/icons.h"
#include "../modules/settings.h"
#include "graph_common.h"
#include <stddef.h>

#define MAX_TIMELINE_EVENTS 4

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
	TimelineEvent events[MAX_TIMELINE_EVENTS];
	uint8_t event_count;
};

// Calculate the moon phase at a specific timestamp (0=new, 1=wax crescent, 2=first quarter,
// 3=wax gibbous, 4=full, 5=wan gibbous, 6=last quarter, 7=wan crescent).
// Integer-only adaptation of the classic algorithm, scaled x10000 and
// epoch-offset to year 2000 so all values fit in 32-bit long.
static int prv_moon_phase_at(time_t target_time) {
	struct tm *t = localtime(&target_time);
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
static void prv_draw_col_marker(GContext *ctx, int cx, int phase, int line_y) {
	bool is_light = settings_get()->light_theme;
	// Lit-side threshold indexed by phase (0 and 4 are special-cased below)
	static const int s_thr[8] = {0, 2, 1, -1, 0, 1, -1, -2};
	const int r = 3;
	GPoint pt = GPoint(cx, line_y);

	// Base: fill + outline
	graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
	graphics_fill_circle(ctx, pt, r);
	graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
	graphics_context_set_stroke_width(ctx, 1);
	graphics_draw_circle(ctx, pt, r);

	if (phase == 0)
		return; // new moon: all dark, done

	if (phase == 4) {
		// Full / noon: solid on top of outline
		graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
		graphics_fill_circle(ctx, pt, r);
		return;
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

static void prv_draw_event_bar(GContext *ctx, int x, int line_y, GColor col) {
	graphics_context_set_stroke_color(ctx, col);
	graphics_context_set_stroke_width(ctx, 3);
	graphics_draw_line(ctx, GPoint(x, line_y - 5), GPoint(x, line_y + 5));
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
	DaylightLayer *dl = *(DaylightLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS)
		total_hours = MAX_GRAPH_HOURS;
	int past_hours = graph_get_past_hours();
	int forecast_hours = graph_get_forecast_hours();
	int lh = bounds.size.h;
	int line_y = lh / 2;
	bool is_light = settings_get()->light_theme;

	int base_hour = ((int)dl->current_hour - past_hours + 240) % 24;

	// Theme colors
	GColor night_col = is_light ? GColorBlack : GColorDarkGray;
	GColor day_col = is_light ? GColorLightGray : GColorWhite;
	GColor bracket_col = is_light ? GColorBlack : GColorWhite;

	// Calculate daylight spans across the rolling multi-day window
	int raw_rise = ((int)dl->sunrise_hour - base_hour + 240) % 24;
	int raw_set = ((int)dl->sunset_hour - base_hour + 240) % 24;
	if (raw_set < raw_rise)
		raw_set += 24;

	typedef struct {
		int start;
		int end;
		int orig_rise;
		int orig_set;
	} DaySpan;
	DaySpan day_spans[8];
	int day_span_count = 0;

	for (int k = -72; k <= total_hours + 48; k += 24) {
		int span_rise = raw_rise + k;
		int span_set = raw_set + k;
		int clip_start = span_rise < 0 ? 0 : (span_rise > total_hours ? total_hours : span_rise);
		int clip_end = span_set < 0 ? 0 : (span_set > total_hours ? total_hours : span_set);

		if (clip_end > clip_start && day_span_count < 8) {
			day_spans[day_span_count].start = clip_start;
			day_spans[day_span_count].end = clip_end;
			day_spans[day_span_count].orig_rise = span_rise;
			day_spans[day_span_count].orig_set = span_set;
			day_span_count++;
		}
	}

	// Sort day spans chronologically
	for (int i = 0; i < day_span_count - 1; i++) {
		for (int j = i + 1; j < day_span_count; j++) {
			if (day_spans[j].start < day_spans[i].start) {
				DaySpan tmp = day_spans[i];
				day_spans[i] = day_spans[j];
				day_spans[j] = tmp;
			}
		}
	}

	// 1. Draw solid Night Track across 100% full screen width (3px bold)
	graphics_context_set_stroke_width(ctx, 3);
	graphics_context_set_stroke_color(ctx, night_col);
	graphics_draw_line(ctx, GPoint(graph_x, line_y), GPoint(graph_x + graph_w, line_y));

	// 2. Draw Daylight Spans & Brackets across all multi-day cycles (3px bold)
	graphics_context_set_stroke_color(ctx, day_col);
	for (int i = 0; i < day_span_count; i++) {
		int x1 = graph_x + day_spans[i].start * graph_w / total_hours;
		int x2 = graph_x + day_spans[i].end * graph_w / total_hours;
		if (x2 > x1) {
			graphics_draw_line(ctx, GPoint(x1, line_y), GPoint(x2, line_y));
		}

		// Endcap brackets at sunrise and sunset (in both dark and light theme)
		graphics_context_set_stroke_color(ctx, bracket_col);
		graphics_context_set_stroke_width(ctx, 1);
		if (day_spans[i].orig_rise >= 0 && day_spans[i].orig_rise <= total_hours) {
			int xr = graph_x + day_spans[i].orig_rise * graph_w / total_hours;
			graphics_draw_line(ctx, GPoint(xr, line_y - 4), GPoint(xr, line_y + 4));
		}
		if (day_spans[i].orig_set >= 0 && day_spans[i].orig_set <= total_hours) {
			int xs = graph_x + day_spans[i].orig_set * graph_w / total_hours;
			graphics_draw_line(ctx, GPoint(xs, line_y - 4), GPoint(xs, line_y + 4));
		}
		graphics_context_set_stroke_width(ctx, 3);
		graphics_context_set_stroke_color(ctx, day_col);
	}

	// 3. Solar Noon Markers across all cycles
	for (int k = -72; k <= total_hours + 48; k += 24) {
		int noon_t = ((12 - base_hour + 240) % 24) + k;
		if (noon_t >= 0 && noon_t <= total_hours) {
			int x_noon = graph_x + noon_t * graph_w / total_hours;
			prv_draw_col_marker(ctx, x_noon, 4, line_y);
		}
	}

	// 4. Midnight Moon Phase Markers across all cycles
	time_t now_sec = time(NULL);
	for (int k = -72; k <= total_hours + 48; k += 24) {
		int midn_t = ((24 - base_hour + 240) % 24) + k;
		if (midn_t >= 0 && midn_t <= total_hours) {
			int x_midn = graph_x + midn_t * graph_w / total_hours;
			time_t target_sec = now_sec + (midn_t - past_hours) * 3600;
			int moon_phase = prv_moon_phase_at(target_sec);
			prv_draw_col_marker(ctx, x_midn, moon_phase, line_y);
		}
	}

	// 5. Red line indicator at 1/5 of the timeline (crisp 1px with T-top)
	NeedleMode needle_mode = settings_get()->needle_mode;
	if (needle_mode == NEEDLE_BOTH || needle_mode == NEEDLE_ABOVE) {
		int x_now = graph_x + (graph_w / 5);
#if defined(PBL_COLOR)
		graphics_context_set_stroke_color(ctx, GColorRed);
#else
		graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
		graphics_context_set_stroke_width(ctx, 1);
		graphics_draw_line(ctx, GPoint(x_now, line_y - 6),
		                   GPoint(x_now, line_y + 6));
		graphics_draw_line(ctx, GPoint(x_now - 2, line_y - 6),
		                   GPoint(x_now + 2, line_y - 6));
	}

	// 6. Battery life depletion markers directly on the timeline bar
	TimelineBatteryMode tb_mode = settings_get()->timeline_battery;
	if (tb_mode != TIMELINE_BATT_NONE && !dl->battery_charging && dl->battery_percent < 100) {
		// 20% Yellow Marker (Option 1: 20% yellow, 10% orange, 0% red)
		if (tb_mode == TIMELINE_BATT_20_10_0 && dl->battery_percent > 20) {
			int hrs_to_20 = (int)(dl->battery_percent - 20) * 4 / 5;
			if (hrs_to_20 >= 0 && hrs_to_20 <= forecast_hours) {
				int col20 = past_hours + hrs_to_20;
				int x20 = graph_x + col20 * graph_w / total_hours;
				graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
				graphics_fill_circle(ctx, GPoint(x20, line_y), 6);
#if defined(PBL_COLOR)
				graphics_context_set_stroke_color(ctx, GColorYellow);
				graphics_context_set_fill_color(ctx, GColorYellow);
#else
				graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
				graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
				graphics_context_set_stroke_width(ctx, 1);
				graphics_draw_round_rect(ctx, GRect(x20 - 4, line_y - 3, 8, 6), 1);
				graphics_fill_rect(ctx, GRect(x20 + 4, line_y - 1, 1, 3), 0, GCornerNone);
				graphics_fill_rect(ctx, GRect(x20 - 3, line_y - 2, 4, 4), 0, GCornerNone);
			}
		}

		// 10% Orange Marker (Option 1 and Option 2: 10% orange)
		if ((tb_mode == TIMELINE_BATT_20_10_0 || tb_mode == TIMELINE_BATT_10_0) && dl->battery_percent > 10) {
			int hrs_to_10 = (int)(dl->battery_percent - 10) * 4 / 5;
			if (hrs_to_10 >= 0 && hrs_to_10 <= forecast_hours) {
				int col10 = past_hours + hrs_to_10;
				int x10 = graph_x + col10 * graph_w / total_hours;
				graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
				graphics_fill_circle(ctx, GPoint(x10, line_y), 6);
#if defined(PBL_COLOR)
				graphics_context_set_stroke_color(ctx, GColorOrange);
				graphics_context_set_fill_color(ctx, GColorOrange);
#else
				graphics_context_set_stroke_color(ctx, is_light ? GColorBlack : GColorWhite);
				graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
				graphics_context_set_stroke_width(ctx, 1);
				graphics_draw_round_rect(ctx, GRect(x10 - 4, line_y - 3, 8, 6), 1);
				graphics_fill_rect(ctx, GRect(x10 + 4, line_y - 1, 1, 3), 0, GCornerNone);
				graphics_fill_rect(ctx, GRect(x10 - 3, line_y - 2, 2, 4), 0, GCornerNone);
			}
		}

		// 0% Red Marker (Option 1, Option 2, and Option 3: 0% red)
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
			graphics_draw_round_rect(ctx, GRect(x0 - 4, line_y - 3, 8, 6), 1);
			graphics_fill_rect(ctx, GRect(x0 + 4, line_y - 1, 1, 3), 0, GCornerNone);
		}
	}

	// 7. Upcoming calendar event indicators
	TimelineEventMode event_mode = settings_get()->timeline_event;
	if (event_mode != TIMELINE_EVENT_NONE && dl->event_count > 0) {
		time_t now = time(NULL);
		int x_now = graph_x + graph_w / 5;
#if defined(PBL_COLOR)
		GColor event_col = is_light ? GColorVividCerulean : GColorCyan;
#else
		GColor event_col = is_light ? GColorBlack : GColorWhite;
#endif
		long window_start = (long)now - (long)past_hours * 3600L;
		long window_end = (long)now + (long)forecast_hours * 3600L;

		for (uint8_t i = 0; i < dl->event_count; i++) {
			long start_sec = (long)dl->events[i].start_time;
			long end_sec = (long)dl->events[i].end_time;
			if (start_sec > window_end || end_sec < window_start)
				continue;

			long diff_sec = start_sec - (long)now;
			int x = x_now + (int)((diff_sec * (long)graph_w) /
			                      (3600L * (long)total_hours));
			prv_draw_event_bar(ctx, x, line_y, event_col);

			if (event_mode == TIMELINE_EVENT_SPAN && end_sec > start_sec) {
				long end_diff_sec = end_sec - (long)now;
				int x_end = x_now + (int)((end_diff_sec * (long)graph_w) /
				                          (3600L * (long)total_hours));
				prv_draw_event_bar(ctx, x_end, line_y, event_col);

				int bracket_x1 = x < x_end ? x : x_end;
				int bracket_x2 = x < x_end ? x_end : x;
				graphics_context_set_stroke_color(ctx, event_col);
				graphics_context_set_stroke_width(ctx, 2);
				graphics_draw_line(ctx, GPoint(bracket_x1, line_y - 5),
				                   GPoint(bracket_x2, line_y - 5));
			}
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
	dl->event_count = 0;

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

void daylight_layer_set_events(DaylightLayer *layer, const TimelineEvent *events,
                               uint8_t count) {
	if (!layer)
		return;
	if (count > MAX_TIMELINE_EVENTS)
		count = MAX_TIMELINE_EVENTS;
	layer->event_count = count;
	for (uint8_t i = 0; i < count; i++) {
		layer->events[i] = events[i];
	}
	layer_mark_dirty(layer->layer);
}
