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


/* Discharge history for usage-based battery ETA (persisted).
 *
 * Pipeline (2.6.6; milestones 20/10/4/0 since 2.7.0; cold-start SEED 2.7.5):
 *  1) Input: BatteryChargeState.charge_percent + is_charging + is_plugged.
 *     Emery reports 1% steps; older platforms were often 10%. Mid-step boost
 *     only applies when estimated step > 1 (coarse buckets).
 *  2) History (persist key 1): SEED on unplug/cold-start (not a rate endpoint);
 *     CHANGE on real percent drops. Prune >7d; keep up to BATT_HIST_MAX samples
 *     (~7d of 1% steps at ~4.7h/%). Legacy 61-byte (5-byte samples) and 73-byte
 *     (12×6) blobs still load; saves are 1+MAX*6.
 *  3) Measured rate: first→last CHANGE only; need ≥2h span, ≥3% drop, spp in
 *     [8640,30240] (~10–35d full). SEED skipped so unplug mid-bucket is not an
 *     endpoint.
 *  4) Rate select: valid measured (blend 2/3 measured + 1/3 learned if set),
 *     else learned EWMA (key 6), else default 17280 (~20d full). Charging/plugged
 *     folds measured into learned then clears hist; learned survives.
 *  5) ETA to milestone M: secs = f(raw,eff,M)*spp from percent-change anchor.
 *     Already at/below M → ≤0 from anchor (never mid-step into the future).
 *  6) Place with prv_x_from_now_diff inside [−past,+forecast]; beyond horizon
 *     → omit (no right-edge park). Default ~20d full means empty is usually
 *     weeks out — only appears when battery is low enough that ETA fits the
 *     chart. Past markers keep their milestone colour (2.6.7).
 * Persist keys: see modules/storage_keys.h.
 */
#include "../modules/storage_keys.h"
/* ~7d of 1% steps at ~4.7h/% ≈ 35 samples; 36 keeps the full prune window. */
#define BATT_HIST_MAX 36
#define BATT_HIST_WINDOW_SEC (7L * 24L * 3600L) /* several days / since unplug */
/* Legacy persist sizes (MAX was 12): 5-byte samples=61, 6-byte=73. */
#define BATT_HIST_LEGACY_MAX 12
#define BATT_HIST_LEGACY_OLD_BYTES (1 + BATT_HIST_LEGACY_MAX * 5) /* 61 */
#define BATT_HIST_LEGACY_NEW_BYTES (1 + BATT_HIST_LEGACY_MAX * 6) /* 73 */
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
#define BATT_RISE_RESET_PCT 3 /* off-cable rise >= this = charge/reset */

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
	bool battery_plugged;
	/* When percent/charge-cable last changed — ETA anchors here so flat
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
	 * Size = 1 + 36*6 = 217 bytes (under PERSIST_DATA_MAX_LENGTH 256). */
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
	/* Prefer 6-byte samples (flags); fall back to pre-2.6.4 5-byte layout.
	 * Detect by blob size, NOT current BATT_HIST_MAX (was 12 → 61/73 bytes). */
	uint8_t blob[1 + BATT_HIST_MAX * 6];
	int n = persist_read_data(STORAGE_KEY_BATT_HIST, blob, sizeof(blob));
	if (n < 1)
		return;
	uint8_t count = blob[0];
	if (count > BATT_HIST_MAX)
		count = BATT_HIST_MAX;
	bool new_fmt = (n > BATT_HIST_LEGACY_OLD_BYTES);
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

/* Seconds from anchor until milestone M. At/below the reported bucket the
 * milestone is at/before the anchor (mid-step never pushes it forward). */
static long prv_batt_secs_to_mark(int ap_raw, int ap_eff, int m, long spp) {
	if (ap_raw < m)
		return (long)(ap_raw - m) * spp;
	if (ap_raw == m)
		return 0L;
	return (long)(ap_eff - m) * spp;
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

/* Battery depletion markers: 20 yellow, 10 orange, 4 red (PebbleOS battery-
 * save threshold), 0 deep red. Hidden while on cable or at 100%. */
static void prv_draw_battery(const DrawCtx *d, DaylightLayer *dl) {
	TimelineBatteryMode tb_mode = settings_get()->timeline_battery;
	if (tb_mode == TIMELINE_BATT_NONE || dl->battery_charging || dl->battery_plugged ||
	    dl->battery_percent >= 100 || dl->battery_anchor_sec == 0)
		return;
	int ap_raw = (int)dl->battery_anchor_percent;
	int ap_eff = prv_batt_effective_anchor_pct(dl);
	long spp = prv_batt_secs_per_pct(dl, d->now_sec);
	long anchor_off = (long)(dl->battery_anchor_sec - d->now_sec);
	bool is_light = d->is_light;

#if defined(PBL_COLOR)
	/* 3.1.2: darker 20%/10% markers on the light theme (Yellow/Orange were
	 * barely visible on white). */
	GColor batt_y = is_light ? GColorChromeYellow : GColorYellow;
	GColor batt_o = is_light ? GColorWindsorTan : GColorOrange;
	GColor batt_r = GColorRed;
	GColor batt_e = is_light ? GColorDarkCandyAppleRed : GColorFolly;
#else
	GColor fg = is_light ? GColorBlack : GColorWhite;
	GColor batt_y = fg, batt_o = fg, batt_r = fg, batt_e = fg;
#endif

	bool show20 = (tb_mode == TIMELINE_BATT_20_10_4_0);
	bool show10 = (tb_mode == TIMELINE_BATT_20_10_4_0 || tb_mode == TIMELINE_BATT_10_4_0 ||
	               tb_mode == TIMELINE_BATT_10_4);
	bool show4 = (tb_mode != TIMELINE_BATT_NONE);
	bool show0 = (tb_mode != TIMELINE_BATT_10_4 && tb_mode != TIMELINE_BATT_NONE);

	if (show20)
		prv_draw_batt_mark(d, anchor_off + prv_batt_secs_to_mark(ap_raw, ap_eff, 20, spp), batt_y, 4);
	if (show10)
		prv_draw_batt_mark(d, anchor_off + prv_batt_secs_to_mark(ap_raw, ap_eff, 10, spp), batt_o, 2);
	if (show4)
		prv_draw_batt_mark(d, anchor_off + prv_batt_secs_to_mark(ap_raw, ap_eff, 4, spp), batt_r, 1);
	if (show0)
		prv_draw_batt_mark(d, anchor_off + prv_batt_secs_to_mark(ap_raw, ap_eff, 0, spp), batt_e, 0);
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
	dl->battery_percent = 100;
	dl->battery_charging = false;
	dl->battery_plugged = false;
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

void daylight_layer_set_battery(DaylightLayer *layer, uint8_t percent, bool charging,
                                bool plugged) {
	if (!layer)
		return;
	bool was_charging = layer->battery_charging;
	bool was_plugged = layer->battery_plugged;
	uint8_t was_percent = layer->battery_percent;
	bool was_on_power = was_charging || was_plugged;
	bool on_power = charging || plugged;
	bool changed = (percent != was_percent) || (charging != was_charging) ||
	               (plugged != was_plugged);
	time_t now = time(NULL);
	bool hist_dirty = false;

	if (on_power) {
		/* On cable (charging OR plugged): fold measured into learned, clear hist.
		 * Learned is NOT reset. is_plugged matters: at 100% Pebble often reports
		 * is_charging==false while still on cable — must not record drain. */
		long m = prv_batt_measured_spp(layer, now);
		if (m > 0)
			prv_batt_learned_update(layer, m);
		if (layer->batt_hist_count > 0) {
			prv_batt_hist_clear(layer);
			hist_dirty = true;
		}
	} else if (was_on_power) {
		/* Fresh unplug — SEED sample only (mid-bucket); not a rate endpoint. */
		prv_batt_hist_clear(layer);
		prv_batt_hist_push(layer, now, percent, BATT_HIST_FLAG_SEED);
		hist_dirty = true;
	} else if (layer->battery_anchor_sec == 0 && layer->batt_hist_count > 0) {
		/* Cold start with saved history. The face was not running, so the
		 * real time of any change is unknown: a big rise means it charged
		 * meanwhile (fold + reset); any other change is recorded as a SEED so
		 * the restart time is never used as a rate endpoint. */
		BattHistSample *last = &layer->batt_hist[layer->batt_hist_count - 1];
		if (percent >= last->percent + BATT_RISE_RESET_PCT) {
			long m = prv_batt_measured_spp(layer, now);
			if (m > 0)
				prv_batt_learned_update(layer, m);
			prv_batt_hist_clear(layer);
			prv_batt_hist_push(layer, now, percent, BATT_HIST_FLAG_SEED);
			hist_dirty = true;
		} else if (percent != last->percent) {
			prv_batt_hist_push(layer, now, percent, BATT_HIST_FLAG_SEED);
			hist_dirty = true;
		}
	} else if (layer->battery_anchor_sec != 0 && percent > was_percent) {
		/* Percent rose while off-cable. A 1–2% bounce is gauge noise (load /
		 * temperature recovery) and must not wipe the discharge history; only
		 * a real jump is treated as a charge/reset. */
		if (percent < was_percent + BATT_RISE_RESET_PCT)
			goto hist_done;
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
		/* Learned rate is folded once per discharge (on cable / reset), not on
		 * every 1% step — per-step folding re-counted the same window ~30×
		 * and turned the "long-term" rate into a copy of the current one. */
		if (layer->batt_hist_count != before)
			hist_dirty = true;
	}
hist_done:

	if (hist_dirty)
		prv_batt_hist_save(layer);

	layer->battery_percent = percent;
	layer->battery_charging = charging;
	layer->battery_plugged = plugged;

	if (layer->battery_anchor_sec == 0) {
		/* First sample after create: reuse persisted bucket entry time so a
		 * restart mid-bucket does not shove milestones forward. */
		if (!on_power && layer->batt_hist_count > 0) {
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
		/* Re-anchor drain ETA when the OS reports a new percent/cable state.
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
