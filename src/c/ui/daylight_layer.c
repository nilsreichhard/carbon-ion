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
#include "glyph.h"
#include "arc.h"
#include <stddef.h>
#include <string.h>


/* Battery milestone ETA: modules/batt_eta.c (history, rate, profile, anchor). */
#include "../modules/batt_eta.h"

struct DaylightLayer {
	Layer *layer;
	uint8_t sunrise_hour;
	uint8_t sunrise_minute;
	uint8_t sunset_hour;
	uint8_t sunset_minute;
	uint8_t current_hour;
	uint8_t current_minute;
	BattEta batt; /* battery state, history and ETA model */
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
/* Per-frame geometry shared by the draw helpers below. */
typedef struct {
	GContext *ctx;
	int graph_x;
	int graph_w;
	int total_hours;
	int past_hours;
	int forecast_hours;
	int line_y;
	int x_now;
	bool is_light;
	time_t now_sec;
	GPoint org;  /* layer frame origin (round: source band coords) */
	bool mapped; /* round overlay pass: glyphs go through the arc transform */
} DrawCtx;

/* Anchor transform for a glyph at layer-local (x, y). */
static GlyphXf prv_xf(const DrawCtx *d, int x, int y) {
#if defined(PBL_ROUND)
	if (d->mapped)
		return arc_glyph_xf_top(d->org.x + x, d->org.y + y);
#endif
	return gx_identity(x, y);
}


static void prv_draw_col_marker(const DrawCtx *d, int cx, int phase) {
	GContext *ctx = d->ctx;
	bool is_light = d->is_light;
	// Lit-side threshold indexed by phase (0 and 4 are special-cased below)
	static const int s_thr[8] = {0, 2, 1, -1, 0, 1, -1, -2};
	const int r = 3; /* 2.8.4: emery size on every platform (gabbro was 4) */
	GlyphXf t = prv_xf(d, cx, d->line_y);
	GColor bg = is_light ? GColorWhite : GColorBlack;
	GColor fg = is_light ? GColorBlack : GColorWhite;

	// Base: fill + outline
	gx_fill_circle(ctx, &t, 0, 0, r, bg);
	gx_draw_circle(ctx, &t, 0, 0, r, fg);

	if (phase == 0)
		return; // new moon: all dark, done

	if (phase == 4) {
		// Full / noon: solid on top of outline
		gx_fill_circle(ctx, &t, 0, 0, r, fg);
		return;
	}

	// Partial phases: paint lit interior columns with horizontal lines
	bool waxing = (phase >= 1 && phase <= 3);
	int thr = s_thr[phase];
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
		gx_line(ctx, &t, x_lo, dy, x_hi, dy, fg);
	}
}

// Event bar height matches the daylight track (3px).
#define EVENT_BAR_H 3

// Top y relative to track centerline. place: 0=on, 1=above, 2=below
static int prv_event_top_y(int line_y, uint8_t place) {
	if (place == 1)
		return line_y - 1 - EVENT_BAR_H - 1; // above track (1px gap)
	if (place == 2)
		return line_y + 3; // below track: 1px gap, same as the row above (2.8.3)
	return line_y - 1; // on track — same band as the 3px timeline fill
}

// Start-only events: short vertical bar (~3px wide).
static void prv_draw_event_bar(const DrawCtx *d, int x, GColor col, uint8_t place) {
	GlyphXf t = prv_xf(d, x, d->line_y);
	gx_fill_rect(d->ctx, &t, GRect(-1, prv_event_top_y(d->line_y, place) - d->line_y, 3, EVENT_BAR_H),
	             col);
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
	case 8: return GColorTiffanyBlue;
	case 9: return GColorFashionMagenta;
	case 10: return GColorInchworm;
	case 11: return GColorDarkCandyAppleRed;
	case 12: return GColorPictonBlue;
	case 13: return GColorMintGreen;
	case 14: return GColorIndigo;
	case 15: return GColorChromeYellow;
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



static inline int prv_dx(const DrawCtx *d, long diff_sec) {
	return prv_x_from_now_diff(diff_sec, d->x_now, d->graph_w, d->total_hours);
}

static inline bool prv_x_visible(const DrawCtx *d, int x) {
	return x >= d->graph_x && x <= d->graph_x + d->graph_w;
}

/* Night track, daylight spans, sunrise/sunset brackets. */
/* Per-day offsets from now (index day+3, day = -3..+3), DST-aware. */
#define DAY_SPAN 7
typedef struct {
	long rise[DAY_SPAN];
	long set[DAY_SPAN];
	long noon[DAY_SPAN];
	long midn[DAY_SPAN];
} DayMarks;

/* Round (gabbro): thicker 5 px track (2.8.2). Markers keep the emery size
 * everywhere (2.8.4: drawn at native size after the warp). */
#if defined(PBL_ROUND)
#define TRACK_HALF 2
#else
#define TRACK_HALF 1
#endif
#define BM_W 8
#define BM_H 6

static void prv_draw_track(const DrawCtx *d, const DayMarks *dm) {
	GContext *ctx = d->ctx;
	/* Track colours are not altered by backlight-aware mode (2.7.1): the
	 * day/night band keeps full contrast; only secondary marks strengthen. */
	GColor night_col = d->is_light ? GColorBlack : GColorDarkGray;
	GColor day_col = d->is_light ? GColorLightGray : GColorWhite;
	GColor bracket_col = d->is_light ? GColorBlack : GColorWhite;

	graphics_context_set_fill_color(ctx, night_col);
	graphics_fill_rect(ctx, GRect(d->graph_x, d->line_y - TRACK_HALF, d->graph_w, 2 * TRACK_HALF + 1), 0, GCornerNone);

	graphics_context_set_fill_color(ctx, day_col);
	for (int i = 0; i < DAY_SPAN; i++) {
		int xr = prv_dx(d, dm->rise[i]);
		int xs = prv_dx(d, dm->set[i]);
		int x1 = xr < d->graph_x ? d->graph_x : xr;
		int x2 = xs > d->graph_x + d->graph_w ? d->graph_x + d->graph_w : xs;
		if (x2 > x1)
			graphics_fill_rect(ctx, GRect(x1, d->line_y - TRACK_HALF, x2 - x1, 2 * TRACK_HALF + 1), 0, GCornerNone);
	}

	graphics_context_set_stroke_color(ctx, bracket_col);
	graphics_context_set_stroke_width(ctx, 1);
	for (int i = 0; i < DAY_SPAN; i++) {
		int xr = prv_dx(d, dm->rise[i]);
		int xs = prv_dx(d, dm->set[i]);
		if (prv_x_visible(d, xr))
			graphics_draw_line(ctx, GPoint(xr, d->line_y - 4), GPoint(xr, d->line_y + 4));
		if (prv_x_visible(d, xs))
			graphics_draw_line(ctx, GPoint(xs, d->line_y - 4), GPoint(xs, d->line_y + 4));
	}
}

/* Solar noon dots + midnight moon phases. */
static void prv_draw_noon_moon(const DrawCtx *d, const DayMarks *dm) {
	for (int i = 0; i < DAY_SPAN; i++) {
		long noon_diff = dm->noon[i];
		int x_noon = prv_dx(d, noon_diff);
		if (prv_x_visible(d, x_noon))
			prv_draw_col_marker(d, x_noon, 4);

		long midn_diff = dm->midn[i];
		int x_midn = prv_dx(d, midn_diff);
		if (prv_x_visible(d, x_midn))
			prv_draw_col_marker(d, x_midn, prv_moon_phase_at(d->now_sec + midn_diff));
	}
}

/* One battery milestone, only when its ETA is inside [−past,+forecast]
 * (no right-edge park). Past and upcoming keep their colour (2.6.7). */
static void prv_draw_batt_mark(const DrawCtx *d, long eta_diff, GColor col, int fill_w) {
	if (eta_diff < -(long)d->past_hours * 3600L || eta_diff > (long)d->forecast_hours * 3600L)
		return;
	int bx = prv_dx(d, eta_diff);
	if (bx < d->graph_x)
		bx = d->graph_x;
	if (bx > d->graph_x + d->graph_w)
		bx = d->graph_x + d->graph_w;
	GContext *ctx = d->ctx;
	GlyphXf t = prv_xf(d, bx, d->line_y);
	gx_fill_circle(ctx, &t, 0, 0, BM_W / 2 + 2, d->is_light ? GColorWhite : GColorBlack);
	gx_round_rect1(ctx, &t, GRect(-BM_W / 2, -BM_H / 2, BM_W, BM_H), col);
	gx_fill_rect(ctx, &t, GRect(BM_W / 2, -1, 1, 3), col);
	if (fill_w > 0)
		gx_fill_rect(ctx, &t, GRect(-BM_W / 2 + 1, -BM_H / 2 + 1, fill_w * BM_W / 8, BM_H - 2), col);
}

/* Battery depletion markers: 20 yellow, 10 orange, 5 red, 0 deep red (4.0.0:
 * fixed levels on every platform). Hidden while on cable or at 100%. */
static void prv_draw_battery(const DrawCtx *d, DaylightLayer *dl) {
	TimelineBatteryMode tb_mode = settings_get()->timeline_battery;
	BattEta *b = &dl->batt;
	if (tb_mode == TIMELINE_BATT_NONE || b->charging || b->plugged || b->percent >= 100 ||
	    b->anchor_sec == 0)
		return;
	bool is_light = d->is_light;

#if defined(PBL_COLOR)
	/* 3.1.2: darker 20%/10% markers on the light theme (Yellow/Orange were
	 * barely visible on white). */
	GColor batt_y = is_light ? GColorChromeYellow : GColorYellow;
	GColor batt_o = is_light ? GColorWindsorTan : GColorOrange;
	GColor batt_r = GColorRed;
	GColor batt_e = is_light ? GColorDarkCandyAppleRed : GColorFolly; /* 0 %, as in 3.3.0 */
#else
	GColor fg = is_light ? GColorBlack : GColorWhite;
	GColor batt_y = fg, batt_o = fg, batt_r = fg, batt_e = fg;
#endif

	/* 4.0.0: 20 / 10 / 5 / 0 % and their variants (settings_battery_mode has
	 * already mapped old values). ETA = anchor + percent x rate through the
	 * hour-of-day profile walk (batt_eta_time). */
	static const uint8_t levels[] = {20, 10, 5, 0};
	static const uint8_t fills[] = {4, 2, 1, 0};
	uint8_t mask; /* bit i = levels[i] */
	switch (tb_mode) {
	case TIMELINE_BATT_10_5_0: mask = 0x0e; break;
	case TIMELINE_BATT_5_0:    mask = 0x0c; break;
	case TIMELINE_BATT_10_0:   mask = 0x0a; break;
	case TIMELINE_BATT_0:      mask = 0x08; break;
	default:                   mask = 0x0f; break; /* 20 / 10 / 5 / 0 */
	}
	GColor cols[] = {batt_y, batt_o, batt_r, batt_e};
	time_t limit = d->now_sec + (time_t)d->forecast_hours * 3600;
	time_t t;
	for (int i = 0; i < 4; i++) {
		if ((mask & (1 << i)) && batt_eta_time(b, d->now_sec, levels[i], limit, &t))
			prv_draw_batt_mark(d, (long)(t - d->now_sec), cols[i], fills[i]);
	}
}

/* Calendar events (bars or spans on their row). */
static void prv_draw_events(const DrawCtx *d, DaylightLayer *dl, TimelineEventMode event_mode,
                            bool spans, bool bars) {
	if (dl->event_count == 0)
		return;
	long now = (long)d->now_sec;
	long window_start = now - (long)d->past_hours * 3600L;
	long window_end = now + (long)d->forecast_hours * 3600L;
	for (uint8_t i = 0; i < dl->event_count; i++) {
		long start_sec = (long)dl->events[i].start_time;
		long end_sec = (long)dl->events[i].end_time;
		if (start_sec > window_end || end_sec < window_start)
			continue;
		GColor col = prv_event_color(dl->events[i].color, d->is_light);
		uint8_t place = dl->events[i].place;
		if (place > 2)
			place = 0;
		int x = prv_dx(d, start_sec - now);
		if (event_mode == TIMELINE_EVENT_SPAN && end_sec > start_sec) {
			if (spans) /* durations are time: they follow the arc length */
				prv_draw_event_span(d->ctx, x, prv_dx(d, end_sec - now), d->line_y, col, place);
		} else if (bars) {
			/* 3.1.2: an ongoing event that started before the window shows
			 * its bar at the window start instead of off the chart. */
			if (start_sec < window_start)
				x = prv_dx(d, window_start - now);
			prv_draw_event_bar(d, x, col, place);
		}
	}
}

#if defined(CARBON_MODERN_SERVICES)
/* Next system alarm on rows whose source is "Next alarm" (2.7.0). Drawn as a
 * small diamond (distinct from event bars); rows sharing a placement are
 * drawn once (2.7.1). */
static void prv_draw_alarm(const DrawCtx *d) {
	const uint8_t *rows = settings_get()->alarm_rows;
	if (!((rows[0] | rows[1] | rows[2]) & ALARM_ROW_ON))
		return;
	/* Peek the system alarm at most once per minute (2.8.5). */
	static time_t s_alarm_t, s_alarm_checked_min = -1;
	static bool s_alarm_ok;
	time_t this_min = d->now_sec / 60;
	if (this_min != s_alarm_checked_min) {
		s_alarm_ok = alarm_service_peek_next(&s_alarm_t);
		s_alarm_checked_min = this_min;
	}
	if (!s_alarm_ok)
		return;
	time_t alarm_t = s_alarm_t;
	long diff = (long)alarm_t - (long)d->now_sec;
	if (diff < -(long)d->past_hours * 3600L || diff > (long)d->forecast_hours * 3600L)
		return;
	int ax = prv_dx(d, diff);
	uint8_t drawn = 0; /* bitmask of placements already drawn */
	for (int r = 0; r < ALARM_ROW_COUNT; r++) {
		if (!(rows[r] & ALARM_ROW_ON))
			continue;
		uint8_t place = (rows[r] >> 4) & 0x03;
		if (place > 2)
			place = 0;
		if (drawn & (1u << place))
			continue;
		drawn |= (uint8_t)(1u << place);
		GColor col = prv_event_color(rows[r] & 0x0F, d->is_light);
		int cy = prv_event_top_y(d->line_y, place) + EVENT_BAR_H / 2;
		/* Diamond, half-widths 0,1,2,1,0 with a contrasting outline. */
		static const int8_t s_hw[5] = {0, 1, 2, 1, 0};
		GColor bg = d->is_light ? GColorWhite : GColorBlack;
		GlyphXf t = prv_xf(d, ax, cy);
		for (int k = 0; k < 5; k++)
			gx_fill_rect(d->ctx, &t, GRect(-s_hw[k] - 1, -2 + k, 2 * s_hw[k] + 3, 1), bg);
		gx_fill_rect(d->ctx, &t, GRect(0, -3, 1, 7), bg);
		for (int k = 0; k < 5; k++)
			gx_fill_rect(d->ctx, &t, GRect(-s_hw[k], -2 + k, 2 * s_hw[k] + 1, 1), col);
	}
}
#endif

/* Extra seconds a wall-clock time `day` days away is shifted by a DST change
 * between today and then (0 when mktime has no DST info). Computed relative to
 * mktime(today) so any constant timezone handling in mktime cancels out. */
static long prv_dst_shift(const struct tm *now_tm, time_t now, int day) {
	struct tm a = *now_tm;
	a.tm_hour = 12;
	a.tm_min = 0;
	a.tm_sec = 0;
	a.tm_isdst = -1;
	struct tm b = a;
	b.tm_mday += day;
	time_t ta = mktime(&a);
	time_t tb = mktime(&b);
	(void)now;
	if (ta == (time_t)-1 || tb == (time_t)-1)
		return 0;
	long shift = (long)(tb - ta) - (long)day * 86400L;
	if (shift < -7200L || shift > 7200L)
		return 0; /* not a DST shift; ignore */
	return shift;
}

static void prv_setup(DaylightLayer *dl, GContext *ctx, DrawCtx *dp, DayMarks *dmp) {
	GRect bounds = layer_get_bounds(dl->layer);
	DrawCtx d;
	d.org = layer_get_frame(dl->layer).origin;
	d.mapped = false;
	d.ctx = ctx;
	d.graph_x = GRAPH_OFFSET_X;
	d.graph_w = bounds.size.w - d.graph_x;
	d.total_hours = GRAPH_HOURS;
	if (d.total_hours > MAX_GRAPH_HOURS)
		d.total_hours = MAX_GRAPH_HOURS;
	d.past_hours = graph_get_past_hours();
	d.forecast_hours = graph_get_forecast_hours();
#if defined(PBL_ROUND)
	/* 2.8.6: track sits low in the trimmed strip: 2 empty rows above the
	 * upper event bars (outer edge), below-track bars end on the last row. */
	d.line_y = bounds.size.h - 6;
#else
	d.line_y = bounds.size.h / 2;
#endif
	d.is_light = settings_get()->light_theme;
	d.x_now = graph_now_x(d.graph_x, d.graph_w);

	// One live clock for markers + events (avoids stale current_minute drift)
	d.now_sec = time(NULL);
	struct tm *tm_now = localtime(&d.now_sec);
	int cur_h = tm_now ? tm_now->tm_hour : (int)dl->current_hour;
	int cur_m = tm_now ? tm_now->tm_min : (int)dl->current_minute;
	int cur_s = tm_now ? tm_now->tm_sec : 0;

	// Seconds from now to today's sunrise/sunset/noon/midnight
	struct tm now_tm;
	if (tm_now)
		now_tm = *tm_now; /* copy: localtime() buffer is reused below */
	long rise_from_now =
	    (((int)dl->sunrise_hour - cur_h) * 60 + ((int)dl->sunrise_minute - cur_m)) * 60L - cur_s;
	long set_from_now =
	    (((int)dl->sunset_hour - cur_h) * 60 + ((int)dl->sunset_minute - cur_m)) * 60L - cur_s;
	/* rise == set is polar night (2.8.7): zero-length day, not 24 h. */
	if (set_from_now < rise_from_now)
		set_from_now += 86400L;
	long noon_from_now = ((12 - cur_h) * 60 - cur_m) * 60L - cur_s;
	long midn_from_now = ((24 - cur_h) * 60 - cur_m) * 60L - cur_s;

	DayMarks dm;
	long set_extra = set_from_now - rise_from_now; /* sunset after sunrise */
	for (int i = 0; i < DAY_SPAN; i++) {
		int day = i - 3;
		long base = (long)day * 86400L;
		dm.rise[i] = rise_from_now + base;
		dm.set[i] = set_from_now + base;
		dm.noon[i] = noon_from_now + base;
		dm.midn[i] = midn_from_now + base;
		if (tm_now && day != 0) {
			dm.rise[i] += prv_dst_shift(&now_tm, d.now_sec, day);
			dm.set[i] = dm.rise[i] + set_extra;
			dm.noon[i] += prv_dst_shift(&now_tm, d.now_sec, day);
			/* midnight ending day `day` precedes a 02:00/03:00 change on day+1 */
			dm.midn[i] += prv_dst_shift(&now_tm, d.now_sec, day);
		}
	}
	*dp = d;
	*dmp = dm;
}

static void prv_draw_needle(const DrawCtx *d) {
	NeedleMode needle_mode = settings_get()->needle_mode;
	if (!(needle_mode == NEEDLE_BOTH || needle_mode == NEEDLE_ABOVE))
		return;
#if defined(PBL_COLOR)
	GColor c = GColorRed;
#else
	GColor c = d->is_light ? GColorBlack : GColorWhite;
#endif
	// Extend a few px past below-timeline events (track + gap + EVENT_BAR_H)
	if (d->mapped) {
		/* Radial, along the arc: from the outer edge to below the track. */
		GlyphXf t = prv_xf(d, d->x_now, 0);
		gx_fill_rect(d->ctx, &t, GRect(-1, 0, 2, d->line_y + 7), c);
		return;
	}
	graphics_context_set_fill_color(d->ctx, c);
	graphics_fill_rect(d->ctx, GRect(d->x_now - 1, 0, 2, d->line_y + 7), 0, GCornerNone);
}

#if defined(PBL_ROUND)
/* Markers: moon/noon, battery, start-only event bars, alarm. */
static void prv_draw_marks(const DrawCtx *d, const DayMarks *dm, DaylightLayer *dl) {
	prv_draw_noon_moon(d, dm);
	prv_draw_battery(d, dl);
	TimelineEventMode event_mode = settings_get()->timeline_event;
	if (event_mode != TIMELINE_EVENT_NONE) {
		prv_draw_events(d, dl, event_mode, false, true);
#if defined(CARBON_MODERN_SERVICES)
		prv_draw_alarm(d);
#endif
	}
}
#endif

static void prv_update_proc(Layer *layer, GContext *ctx) {
	DaylightLayer *dl = *(DaylightLayer **)layer_get_data(layer);
	DrawCtx d;
	DayMarks dm;
	prv_setup(dl, ctx, &d, &dm);
	prv_draw_track(&d, &dm);
	TimelineEventMode event_mode = settings_get()->timeline_event;
#if defined(PBL_ROUND)
	/* Markers are drawn after the warp at native size
	 * (daylight_layer_draw_marks_mapped); the strip keeps only the
	 * track, spans and needle (the needle is drawn again on top). */
	if (event_mode != TIMELINE_EVENT_NONE)
		prv_draw_events(&d, dl, event_mode, true, false);
	prv_draw_needle(&d);
#else
	prv_draw_noon_moon(&d, &dm);
	prv_draw_battery(&d, dl);
	if (event_mode != TIMELINE_EVENT_NONE) {
		prv_draw_events(&d, dl, event_mode, true, true);
#if defined(CARBON_MODERN_SERVICES)
		prv_draw_alarm(&d);
#endif
	}
	// Red needle last so it stays above events (covers below-track bars)
	prv_draw_needle(&d);
#endif
}

#if defined(PBL_ROUND)
void daylight_layer_draw_marks_mapped(DaylightLayer *dl, GContext *ctx) {
	if (!dl)
		return;
	DrawCtx d;
	DayMarks dm;
	prv_setup(dl, ctx, &d, &dm);
	d.mapped = true;
	graphics_context_set_antialiased(ctx, false);
	prv_draw_marks(&d, &dm, dl);
	prv_draw_needle(&d);
	graphics_context_set_antialiased(ctx, true);
}
#endif

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
	batt_eta_init(&dl->batt);
	dl->sunrise_approx = true;
	dl->sunset_approx = true;
	dl->event_count = 0;

	dl->layer = layer_create_with_data(frame, sizeof(DaylightLayer *));
	*(DaylightLayer **)layer_get_data(dl->layer) = dl;
	layer_set_update_proc(dl->layer, prv_update_proc);
	return dl;
}

void daylight_layer_set_battery(DaylightLayer *layer, uint8_t percent, bool charging,
                                bool plugged) {
	if (!layer)
		return;
	batt_eta_set(&layer->batt, time(NULL), percent, charging, plugged);
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
