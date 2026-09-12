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

#define MAX_TIMELINE_EVENTS 6

struct DaylightLayer {
	Layer *layer;
	uint8_t sunrise_hour;
	uint8_t sunrise_minute;
	uint8_t sunset_hour;
	uint8_t sunset_minute;
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

// Event bar height matches the daylight track (3px).
#define EVENT_BAR_H 3

// Top y relative to track centerline. place: 0=on, 1=above, 2=below
static int prv_event_top_y(int line_y, uint8_t place) {
	if (place == 1)
		return line_y - 1 - EVENT_BAR_H - 1; // above track (1px gap)
	if (place == 2)
		return line_y + 2; // below track (track ends at line_y+1)
	return line_y - 1; // on track — same band as the 3px timeline fill
}

// Start-only events: short vertical bar (~3px wide).
static void prv_draw_event_bar(GContext *ctx, int x, int line_y, GColor col,
                               uint8_t place) {
	graphics_context_set_fill_color(ctx, col);
	graphics_fill_rect(ctx,
	                   GRect(x - 1, prv_event_top_y(line_y, place), 3, EVENT_BAR_H),
	                   0, GCornerNone);
}

// Duration events: solid block spanning [x0,x1].
static void prv_draw_event_span(GContext *ctx, int x0, int x1, int line_y,
                                GColor col, uint8_t place) {
	int left = x0 < x1 ? x0 : x1;
	int right = x0 < x1 ? x1 : x0;
	int width = right - left;
	if (width < 3)
		width = 3;
	graphics_context_set_fill_color(ctx, col);
	graphics_fill_rect(
	    ctx, GRect(left, prv_event_top_y(line_y, place), width, EVENT_BAR_H), 0,
	    GCornerNone);
}

#if defined(PBL_COLOR)
static GColor prv_event_color(uint8_t id, bool is_light) {
	switch (id) {
	case 1: return GColorBlue;
	case 2: return GColorGreen;
	case 3: return GColorRed;
	case 4: return GColorOrange;
	case 5: return GColorPurple;
	case 6: return GColorYellow;
	case 7: return GColorMagenta;
	case 0:
	default:
		return is_light ? GColorVividCerulean : GColorCyan;
	}
}
#else
static GColor prv_event_color(uint8_t id, bool is_light) {
	(void)id;
	return is_light ? GColorBlack : GColorWhite;
}
#endif


// Pixel x for a time offset from "now" (needle at 1/5). Round to nearest px.
static int prv_x_from_now_diff(long diff_sec, int x_now, int graph_w, int total_hours) {
	long denom = 3600L * (long)total_hours;
	long num = diff_sec * (long)graph_w;
	if (num >= 0)
		return x_now + (int)((num + denom / 2) / denom);
	return x_now + (int)((num - denom / 2) / denom);
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

	// Theme colors
	GColor night_col = is_light ? GColorBlack : GColorDarkGray;
	GColor day_col = is_light ? GColorLightGray : GColorWhite;
	GColor bracket_col = is_light ? GColorBlack : GColorWhite;

	// One live clock for markers + events (avoids stale current_minute drift)
	time_t now_sec = time(NULL);
	struct tm *tm_now = localtime(&now_sec);
	int cur_h = tm_now ? tm_now->tm_hour : (int)dl->current_hour;
	int cur_m = tm_now ? tm_now->tm_min : (int)dl->current_minute;
	int cur_s = tm_now ? tm_now->tm_sec : 0;
	int x_now_mark = graph_x + graph_w / 5;
	int rise_h = (int)dl->sunrise_hour;
	int rise_m = (int)dl->sunrise_minute;
	int set_h = (int)dl->sunset_hour;
	int set_m = (int)dl->sunset_minute;

	// Seconds from now to today's sunrise/sunset/noon/midnight
	long rise_from_now =
	    ((rise_h - cur_h) * 60 + (rise_m - cur_m)) * 60L - cur_s;
	long set_from_now =
	    ((set_h - cur_h) * 60 + (set_m - cur_m)) * 60L - cur_s;
	if (set_from_now <= rise_from_now)
		set_from_now += 86400L;
	long noon_from_now = ((12 - cur_h) * 60 - cur_m) * 60L - cur_s;
	long midn_from_now = ((24 - cur_h) * 60 - cur_m) * 60L - cur_s;

	// 1. Night track full width — filled so it is a solid bar, not a hairline
	graphics_context_set_fill_color(ctx, night_col);
	graphics_fill_rect(ctx, GRect(graph_x, line_y - 1, graph_w, 3), 0, GCornerNone);

	// 2. Daylight spans as opaque fill (covers night; grey "infill" look)
	for (int day = -3; day <= 3; day++) {
		long rd = rise_from_now + (long)day * 86400L;
		long sd = set_from_now + (long)day * 86400L;
		int xr = prv_x_from_now_diff(rd, x_now_mark, graph_w, total_hours);
		int xs = prv_x_from_now_diff(sd, x_now_mark, graph_w, total_hours);
		int x1 = xr < graph_x ? graph_x : xr;
		int x2 = xs > graph_x + graph_w ? graph_x + graph_w : xs;
		if (x2 > x1) {
			graphics_context_set_fill_color(ctx, day_col);
			graphics_fill_rect(ctx, GRect(x1, line_y - 1, x2 - x1, 3), 0,
			                   GCornerNone);
		}
	}

	// Sunrise/sunset brackets on top of the track (not crossed by a stroke)
	graphics_context_set_stroke_color(ctx, bracket_col);
	graphics_context_set_stroke_width(ctx, 1);
	for (int day = -3; day <= 3; day++) {
		long rd = rise_from_now + (long)day * 86400L;
		long sd = set_from_now + (long)day * 86400L;
		int xr = prv_x_from_now_diff(rd, x_now_mark, graph_w, total_hours);
		int xs = prv_x_from_now_diff(sd, x_now_mark, graph_w, total_hours);
		if (xr >= graph_x && xr <= graph_x + graph_w) {
			graphics_draw_line(ctx, GPoint(xr, line_y - 4), GPoint(xr, line_y + 4));
		}
		if (xs >= graph_x && xs <= graph_x + graph_w) {
			graphics_draw_line(ctx, GPoint(xs, line_y - 4), GPoint(xs, line_y + 4));
		}
	}

	// 3–4. Solar noon + midnight moon (same now as events)
	for (int day = -3; day <= 3; day++) {
		long noon_diff = noon_from_now + (long)day * 86400L;
		int x_noon = prv_x_from_now_diff(noon_diff, x_now_mark, graph_w, total_hours);
		if (x_noon >= graph_x && x_noon <= graph_x + graph_w) {
			prv_draw_col_marker(ctx, x_noon, 4, line_y);
		}

		long midn_diff = midn_from_now + (long)day * 86400L;
		int x_midn = prv_x_from_now_diff(midn_diff, x_now_mark, graph_w, total_hours);
		if (x_midn >= graph_x && x_midn <= graph_x + graph_w) {
			time_t target_sec = now_sec + midn_diff;
			int moon_phase = prv_moon_phase_at(target_sec);
			prv_draw_col_marker(ctx, x_midn, moon_phase, line_y);
		}
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

	// Calendar events (after icons; needle drawn after this)
	TimelineEventMode event_mode = settings_get()->timeline_event;
	if (event_mode != TIMELINE_EVENT_NONE && dl->event_count > 0) {
		time_t now = now_sec; /* same clock as noon/rise/set */
		int x_now = x_now_mark;
		long window_start = (long)now - (long)past_hours * 3600L;
		long window_end = (long)now + (long)forecast_hours * 3600L;

		for (uint8_t i = 0; i < dl->event_count; i++) {
			long start_sec = (long)dl->events[i].start_time;
			long end_sec = (long)dl->events[i].end_time;
			if (start_sec > window_end || end_sec < window_start)
				continue;

			GColor event_col = prv_event_color(dl->events[i].color, is_light);
			uint8_t place = dl->events[i].place;
			if (place > 2)
				place = 0;
			long diff_sec = start_sec - (long)now;
			int x = prv_x_from_now_diff(diff_sec, x_now, graph_w, total_hours);

			if (event_mode == TIMELINE_EVENT_SPAN && end_sec > start_sec) {
				long end_diff_sec = end_sec - (long)now;
				int x_end =
				    prv_x_from_now_diff(end_diff_sec, x_now, graph_w, total_hours);
				prv_draw_event_span(ctx, x, x_end, line_y, event_col, place);
			} else {
				prv_draw_event_bar(ctx, x, line_y, event_col, place);
			}
		}
	}

	// Red needle last so it stays above events (covers below-track bars)
	NeedleMode needle_mode = settings_get()->needle_mode;
	if (needle_mode == NEEDLE_BOTH || needle_mode == NEEDLE_ABOVE) {
		int x_now = graph_x + (graph_w / 5);
#if defined(PBL_COLOR)
		graphics_context_set_fill_color(ctx, GColorRed);
#else
		graphics_context_set_fill_color(ctx, is_light ? GColorBlack : GColorWhite);
#endif
		// Extend a few px past below-timeline events (track + gap + EVENT_BAR_H)
		graphics_fill_rect(ctx, GRect(x_now - 1, 0, 2, line_y + 10), 0,
		                   GCornerNone);
	}
}

DaylightLayer *daylight_layer_create(GRect frame) {
	DaylightLayer *dl = malloc(sizeof(DaylightLayer));
	if (!dl)
		return NULL;
	dl->sunrise_hour = 6;
	dl->sunrise_minute = 0;
	dl->sunset_hour = 18;
	dl->sunset_minute = 0;
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
                             uint8_t sunrise_minute, uint8_t sunset_hour,
                             uint8_t sunset_minute, uint8_t current_hour,
                             bool sunrise_approx, bool sunset_approx) {
	if (!layer)
		return;
	layer->sunrise_hour = sunrise_hour;
	layer->sunrise_minute = sunrise_minute > 59 ? 59 : sunrise_minute;
	layer->sunset_hour = sunset_hour;
	layer->sunset_minute = sunset_minute > 59 ? 59 : sunset_minute;
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
	if (count > 0 && !events)
		count = 0;
	layer->event_count = count;
	for (uint8_t i = 0; i < count; i++) {
		layer->events[i] = events[i];
	}
	layer_mark_dirty(layer->layer);
}
