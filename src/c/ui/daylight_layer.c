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
#include <string.h>

#define MAX_TIMELINE_EVENTS 6

/* Discharge history for usage-based battery ETA (persisted).
 *
 * Rate model (2.6.4+):
 *  - Default full-pack life ≈ 20 days → 17280 s/%.
 *  - Accept measured rates in roughly 10–35 days full (8640–30240 s/%).
 *  - History window up to 7 days (12 samples max). At ~1%/4.7h a day holds
 *    only ~5 changes; 7d keeps enough samples without growing persist size.
 *  - Unplug pushes a SEED sample (mid-bucket); rate uses only CHANGE samples.
 *  - Learned long-term EWMA rate persists across charges (key 6); charging
 *    clears history but not the learned rate.
 *  - Selection: valid recent measured (blended with learned if present),
 *    else learned, else 20-day default.
 * Persist keys used elsewhere: 0=settings, 1=batt hist, 2–3/5=weather, 4=events.
 */
#define STORAGE_KEY_BATT_HIST 1
#define STORAGE_KEY_BATT_LEARNED 6 /* uint32 secs_per_pct; survives charges */
#define BATT_HIST_MAX 12
#define BATT_HIST_WINDOW_SEC (7L * 24L * 3600L) /* several days / since unplug */
/* ~20-day full pack: 20*24*3600/100 = 17280 s/%. */
#define BATT_DEFAULT_SECS_PER_PCT 17280L
/* Accept ~10–35 days full; outside → fall back to learned/default. */
#define BATT_SECS_PER_PCT_MIN 8640L  /* 10 days */
#define BATT_SECS_PER_PCT_MAX 30240L /* 35 days */
/* Need a useful window before trusting measured drain. */
#define BATT_RATE_MIN_SPAN_SEC (2L * 3600L) /* 2h — fine with 1% steps */
#define BATT_RATE_MIN_DROP_PCT 3
#define BATT_HIST_FLAG_SEED 0x01 /* unplug/mid-bucket seed; not a rate endpoint */
#define BATT_LEARNED_EWMA_NUM 1
#define BATT_LEARNED_EWMA_DEN 4 /* learned = (3*old + new)/4 */

typedef struct {
	uint32_t sec;
	uint8_t percent;
	uint8_t flags;
} BattHistSample;

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
	/* When percent/charging last changed — ETA anchors here so flat
	 * percent does not slide markers forward every minute (2.6.1). */
	time_t battery_anchor_sec;
	uint8_t battery_anchor_percent;
	/* Compact chronological discharge samples (oldest → newest). */
	BattHistSample batt_hist[BATT_HIST_MAX];
	uint8_t batt_hist_count;
	/* Long-term secs/% learned across charges; 0 = unset. */
	uint32_t batt_learned_spp;
	bool sunrise_approx;
	bool sunset_approx;
	TimelineEvent events[MAX_TIMELINE_EVENTS];
	uint8_t event_count;
};


static void prv_batt_learned_save(const DaylightLayer *dl) {
	if (dl->batt_learned_spp == 0) {
		if (persist_exists(STORAGE_KEY_BATT_LEARNED))
			persist_delete(STORAGE_KEY_BATT_LEARNED);
		return;
	}
	persist_write_int(STORAGE_KEY_BATT_LEARNED, (int)dl->batt_learned_spp);
}

static void prv_batt_learned_load(DaylightLayer *dl) {
	dl->batt_learned_spp = 0;
	if (!persist_exists(STORAGE_KEY_BATT_LEARNED))
		return;
	int v = persist_read_int(STORAGE_KEY_BATT_LEARNED);
	if (v >= (int)BATT_SECS_PER_PCT_MIN && v <= (int)BATT_SECS_PER_PCT_MAX)
		dl->batt_learned_spp = (uint32_t)v;
}

static void prv_batt_learned_update(DaylightLayer *dl, long measured_spp) {
	if (measured_spp < BATT_SECS_PER_PCT_MIN || measured_spp > BATT_SECS_PER_PCT_MAX)
		return;
	if (dl->batt_learned_spp == 0) {
		dl->batt_learned_spp = (uint32_t)measured_spp;
	} else {
		/* EWMA: (DEN-NUM)/DEN * old + NUM/DEN * new */
		uint32_t old = dl->batt_learned_spp;
		dl->batt_learned_spp = (old * (BATT_LEARNED_EWMA_DEN - BATT_LEARNED_EWMA_NUM) +
		                        (uint32_t)measured_spp * BATT_LEARNED_EWMA_NUM) /
		                       BATT_LEARNED_EWMA_DEN;
	}
	prv_batt_learned_save(dl);
}

static void prv_batt_hist_save(const DaylightLayer *dl) {
	/* Packed blob: count + samples[BATT_HIST_MAX] (sec, percent, flags).
	 * Size = 1 + 12*6 = 73 bytes (well under persist 256). */
	uint8_t blob[1 + BATT_HIST_MAX * 6];
	blob[0] = dl->batt_hist_count;
	for (uint8_t i = 0; i < BATT_HIST_MAX; i++) {
		uint8_t *p = &blob[1 + i * 6];
		uint32_t sec = (i < dl->batt_hist_count) ? dl->batt_hist[i].sec : 0;
		p[0] = (uint8_t)(sec & 0xFFu);
		p[1] = (uint8_t)((sec >> 8) & 0xFFu);
		p[2] = (uint8_t)((sec >> 16) & 0xFFu);
		p[3] = (uint8_t)((sec >> 24) & 0xFFu);
		p[4] = (i < dl->batt_hist_count) ? dl->batt_hist[i].percent : 0;
		p[5] = (i < dl->batt_hist_count) ? dl->batt_hist[i].flags : 0;
	}
	persist_write_data(STORAGE_KEY_BATT_HIST, blob, sizeof(blob));
}

static void prv_batt_hist_load(DaylightLayer *dl) {
	dl->batt_hist_count = 0;
	memset(dl->batt_hist, 0, sizeof(dl->batt_hist));
	if (!persist_exists(STORAGE_KEY_BATT_HIST))
		return;
	/* Prefer new 6-byte samples; fall back to pre-2.6.4 5-byte layout.
	 * Old saves were always exactly 1+12*5=61 bytes; new are 1+12*6=73. */
	uint8_t blob[1 + BATT_HIST_MAX * 6];
	int n = persist_read_data(STORAGE_KEY_BATT_HIST, blob, sizeof(blob));
	if (n < 1)
		return;
	uint8_t count = blob[0];
	if (count > BATT_HIST_MAX)
		count = BATT_HIST_MAX;
	bool new_fmt = (n > (1 + BATT_HIST_MAX * 5));
	int stride = new_fmt ? 6 : 5;
	int need = 1 + (int)count * stride;
	if (n < need)
		count = (uint8_t)((n > 1) ? (n - 1) / stride : 0);
	for (uint8_t i = 0; i < count; i++) {
		const uint8_t *p = &blob[1 + i * stride];
		dl->batt_hist[i].sec = (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
		                       ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
		dl->batt_hist[i].percent = p[4];
		/* Old blobs have no flags: treat as CHANGE (usable for rate). */
		dl->batt_hist[i].flags = new_fmt ? p[5] : 0;
	}
	dl->batt_hist_count = count;
}

static void prv_batt_hist_clear(DaylightLayer *dl) {
	dl->batt_hist_count = 0;
	memset(dl->batt_hist, 0, sizeof(dl->batt_hist));
}

static void prv_batt_hist_prune(DaylightLayer *dl, time_t now) {
	uint8_t drop = 0;
	while (drop < dl->batt_hist_count) {
		long age = (long)now - (long)dl->batt_hist[drop].sec;
		if (age <= BATT_HIST_WINDOW_SEC)
			break;
		drop++;
	}
	if (drop == 0)
		return;
	uint8_t remain = (uint8_t)(dl->batt_hist_count - drop);
	if (remain > 0) {
		memmove(&dl->batt_hist[0], &dl->batt_hist[drop],
		        remain * sizeof(BattHistSample));
	}
	dl->batt_hist_count = remain;
}

static void prv_batt_hist_push(DaylightLayer *dl, time_t now, uint8_t percent,
                               uint8_t flags) {
	prv_batt_hist_prune(dl, now);
	if (dl->batt_hist_count > 0) {
		BattHistSample *last = &dl->batt_hist[dl->batt_hist_count - 1];
		if (last->percent == percent) {
			/* Same bucket — keep the earlier timestamp as anchor. */
			return;
		}
	}
	if (dl->batt_hist_count == BATT_HIST_MAX) {
		memmove(&dl->batt_hist[0], &dl->batt_hist[1],
		        (BATT_HIST_MAX - 1) * sizeof(BattHistSample));
		dl->batt_hist_count = BATT_HIST_MAX - 1;
	}
	dl->batt_hist[dl->batt_hist_count].sec = (uint32_t)now;
	dl->batt_hist[dl->batt_hist_count].percent = percent;
	dl->batt_hist[dl->batt_hist_count].flags = flags;
	dl->batt_hist_count++;
}

/* Measured secs/% from CHANGE samples only (skips unplug SEED). 0 = invalid. */
static long prv_batt_measured_spp(DaylightLayer *dl, time_t now) {
	prv_batt_hist_prune(dl, now);
	int first = -1;
	int last = -1;
	for (uint8_t i = 0; i < dl->batt_hist_count; i++) {
		if (dl->batt_hist[i].flags & BATT_HIST_FLAG_SEED)
			continue;
		if (first < 0)
			first = (int)i;
		last = (int)i;
	}
	if (first < 0 || last <= first)
		return 0;
	const BattHistSample *oldest = &dl->batt_hist[first];
	const BattHistSample *newest = &dl->batt_hist[last];
	if (newest->percent >= oldest->percent)
		return 0;
	long dsec = (long)newest->sec - (long)oldest->sec;
	int dpct = (int)oldest->percent - (int)newest->percent;
	if (dsec < BATT_RATE_MIN_SPAN_SEC || dpct < BATT_RATE_MIN_DROP_PCT)
		return 0;
	long spp = dsec / (long)dpct;
	if (spp < BATT_SECS_PER_PCT_MIN || spp > BATT_SECS_PER_PCT_MAX)
		return 0;
	return spp;
}

/* Estimate reporting step from consecutive CHANGE diffs; 1 if unknown. */
static int prv_batt_est_step(const DaylightLayer *dl) {
	int best = 1;
	uint8_t prev_pct = 0;
	bool have = false;
	for (uint8_t i = 0; i < dl->batt_hist_count; i++) {
		if (dl->batt_hist[i].flags & BATT_HIST_FLAG_SEED)
			continue;
		if (have) {
			int d = (int)prev_pct - (int)dl->batt_hist[i].percent;
			if (d < 0)
				d = -d;
			if (d > best)
				best = d;
		}
		prev_pct = dl->batt_hist[i].percent;
		have = true;
	}
	if (best > 20)
		best = 20; /* sanity */
	return best;
}

/* Effective anchor percent for ETA: mid-step when step > 1. */
static int prv_batt_effective_anchor_pct(const DaylightLayer *dl) {
	int ap = (int)dl->battery_anchor_percent;
	int step = prv_batt_est_step(dl);
	if (step > 1) {
		/* Reported ap is the bottom of a coarse bucket → mid ≈ ap + step/2. */
		ap += step / 2;
		if (ap > 100)
			ap = 100;
	}
	return ap;
}

/*
 * Seconds of wall time per 1% drain.
 * Prefer valid recent measured (blended with learned), else learned, else default.
 */
static long prv_batt_secs_per_pct(DaylightLayer *dl, time_t now) {
	/* Read-only during draw — learned updates happen in set_battery. */
	long measured = prv_batt_measured_spp(dl, now);
	if (measured > 0) {
		if (dl->batt_learned_spp > 0) {
			/* Blend: 2/3 recent + 1/3 learned. */
			return (measured * 2L + (long)dl->batt_learned_spp) / 3L;
		}
		return measured;
	}
	if (dl->batt_learned_spp >= (uint32_t)BATT_SECS_PER_PCT_MIN &&
	    dl->batt_learned_spp <= (uint32_t)BATT_SECS_PER_PCT_MAX)
		return (long)dl->batt_learned_spp;
	return BATT_DEFAULT_SECS_PER_PCT;
}

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

	// 6. Battery life depletion markers directly on the timeline bar.
	// Drain rate: measured CHANGE samples (blended with learned EWMA), else
	// learned, else ~20-day default (17280 s/%). ETA anchors on percent change
	// so flat percent does not slide markers (2.6.1). Markers past the timeline
	// window simply do not draw.
	TimelineBatteryMode tb_mode = settings_get()->timeline_battery;
	if (tb_mode != TIMELINE_BATT_NONE && !dl->battery_charging &&
	    dl->battery_percent < 100 && dl->battery_anchor_sec != 0) {
		int ap = prv_batt_effective_anchor_pct(dl);
		time_t anchor = dl->battery_anchor_sec;
		long window_end_sec = (long)forecast_hours * 3600L;
		long secs_per_pct = prv_batt_secs_per_pct(dl, now_sec);

		/* Draw one milestone icon at an absolute ETA (seconds from now). */
		#define DRAW_BATT_MARK(secs_from_anchor, stroke_col, fill_w)                    \
			do {                                                                         \
				long eta_diff = (long)(anchor - now_sec) + (long)(secs_from_anchor);       \
				if (eta_diff < 0 || eta_diff > window_end_sec)                               \
					break;                                                                   \
				int bx = prv_x_from_now_diff(eta_diff, x_now_mark, graph_w, total_hours);   \
				if (bx < graph_x || bx > graph_x + graph_w)                                 \
					break;                                                                   \
				graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack); \
				graphics_fill_circle(ctx, GPoint(bx, line_y), 6);                            \
				graphics_context_set_stroke_color(ctx, stroke_col);                          \
				graphics_context_set_fill_color(ctx, stroke_col);                            \
				graphics_context_set_stroke_width(ctx, 1);                                   \
				graphics_draw_round_rect(ctx, GRect(bx - 4, line_y - 3, 8, 6), 1);           \
				graphics_fill_rect(ctx, GRect(bx + 4, line_y - 1, 1, 3), 0, GCornerNone);    \
				if ((fill_w) > 0)                                                           \
					graphics_fill_rect(ctx, GRect(bx - 3, line_y - 2, (fill_w), 4), 0,         \
					                   GCornerNone);                                          \
			} while (0)

#if defined(PBL_COLOR)
		GColor batt_y = GColorYellow;
		GColor batt_o = GColorOrange;
		GColor batt_r = GColorRed;
#else
		GColor batt_y = is_light ? GColorBlack : GColorWhite;
		GColor batt_o = is_light ? GColorBlack : GColorWhite;
		GColor batt_r = is_light ? GColorBlack : GColorWhite;
#endif

		if (tb_mode == TIMELINE_BATT_20_10_0 && ap > 20) {
			long secs_to_20 = (long)(ap - 20) * secs_per_pct;
			DRAW_BATT_MARK(secs_to_20, batt_y, 4);
		}

		if ((tb_mode == TIMELINE_BATT_20_10_0 || tb_mode == TIMELINE_BATT_10_0) &&
		    ap > 10) {
			long secs_to_10 = (long)(ap - 10) * secs_per_pct;
			DRAW_BATT_MARK(secs_to_10, batt_o, 2);
		}

		{
			long secs_to_0 = (long)ap * secs_per_pct;
			DRAW_BATT_MARK(secs_to_0, batt_r, 0);
		}

		#undef DRAW_BATT_MARK
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
		graphics_fill_rect(ctx, GRect(x_now - 1, 0, 2, line_y + 7), 0,
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
	dl->battery_anchor_sec = 0;
	dl->battery_anchor_percent = 100;
	dl->batt_hist_count = 0;
	memset(dl->batt_hist, 0, sizeof(dl->batt_hist));
	dl->batt_learned_spp = 0;
	prv_batt_learned_load(dl);
	prv_batt_hist_load(dl);
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
	bool was_charging = layer->battery_charging;
	uint8_t was_percent = layer->battery_percent;
	bool changed = (percent != was_percent) || (charging != was_charging);
	time_t now = time(NULL);
	bool hist_dirty = false;

	if (charging) {
		/* Fold any solid measured rate into learned memory, then clear hist.
		 * Learned rate is NOT reset by charging. */
		long m = prv_batt_measured_spp(layer, now);
		if (m > 0)
			prv_batt_learned_update(layer, m);
		if (layer->batt_hist_count > 0) {
			prv_batt_hist_clear(layer);
			hist_dirty = true;
		}
	} else if (was_charging) {
		/* Fresh unplug — SEED sample only (mid-bucket); not a rate endpoint. */
		prv_batt_hist_clear(layer);
		prv_batt_hist_push(layer, now, percent, BATT_HIST_FLAG_SEED);
		hist_dirty = true;
	} else if (layer->battery_anchor_sec != 0 && percent > was_percent) {
		/* Percent rose while "not charging" — treat as charge/reset. */
		long m = prv_batt_measured_spp(layer, now);
		if (m > 0)
			prv_batt_learned_update(layer, m);
		prv_batt_hist_clear(layer);
		prv_batt_hist_push(layer, now, percent, BATT_HIST_FLAG_SEED);
		hist_dirty = true;
	} else if (percent != was_percent || layer->battery_anchor_sec == 0) {
		/* Actual percent-change timestamps (and first sample after create). */
		uint8_t before = layer->batt_hist_count;
		uint8_t flags = 0; /* CHANGE — usable as rate start/end */
		if (layer->batt_hist_count == 0 && layer->battery_anchor_sec == 0) {
			/* Cold start with no hist: seed until a real drop. */
			flags = BATT_HIST_FLAG_SEED;
		}
		prv_batt_hist_push(layer, now, percent, flags);
		if (layer->batt_hist_count != before) {
			hist_dirty = true;
			long m = prv_batt_measured_spp(layer, now);
			if (m > 0)
				prv_batt_learned_update(layer, m);
		}
	}

	if (hist_dirty)
		prv_batt_hist_save(layer);

	layer->battery_percent = percent;
	layer->battery_charging = charging;

	if (layer->battery_anchor_sec == 0) {
		/* First sample after create: reuse persisted bucket entry time so a
		 * restart mid-bucket does not shove milestones forward. */
		if (!charging && layer->batt_hist_count > 0) {
			BattHistSample *last = &layer->batt_hist[layer->batt_hist_count - 1];
			if (last->percent == percent) {
				layer->battery_anchor_sec = (time_t)last->sec;
				layer->battery_anchor_percent = percent;
			}
		}
		if (layer->battery_anchor_sec == 0) {
			layer->battery_anchor_sec = now;
			layer->battery_anchor_percent = percent;
		}
	} else if (changed) {
		/* Re-anchor drain ETA when the OS reports a new percent/charge state.
		 * Flat percent must not slide markers (2.6.1 behaviour). */
		layer->battery_anchor_sec = now;
		layer->battery_anchor_percent = percent;
	}
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
