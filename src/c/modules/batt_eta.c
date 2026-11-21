/**
 * Battery milestone ETA (see batt_eta.h).
 *
 * Pipeline (2.6.6; markers since 2.7.0; cold-start SEED 2.7.5; 3.3.1 rework):
 *  1) Input: BatteryChargeState.charge_percent + is_charging + is_plugged.
 *     On emery and gabbro PebbleOS reports ceil(fuel-gauge SoC) in 1 % steps,
 *     sampled once a minute, so a new percent N appears exactly when the charge
 *     reaches N %. 3.3.1: no mid-step boost any more (it was for 10 % steps and
 *     fired falsely after a change was missed while another app was open).
 *  2) History (persist key 1): SEED on unplug / cold start (not a rate endpoint),
 *     CHANGE on real percent drops. Prune > 7 d, up to BATT_HIST_MAX samples.
 *  3) Measured rate: first -> last CHANGE; >= 2 h span, >= 3 % drop, secs/% in
 *     [8640, 30240] (~10-35 days full).
 *  4) Rate: 2/3 measured + 1/3 learned (persist key 6), else learned, else
 *     the default 17280 (~20 days full). On cable: fold measured into learned,
 *     clear the history.
 *  5) Anchor (3.3.1): the newest history sample. A 1-2 % rise off cable (gauge
 *     bounce) keeps the earlier crossing; after a restart that missed changes the
 *     SEED sits at the estimated crossing time, not at "now".
 *  6) Hour-of-day profile (3.3.1, persist key 12): relative drain per local
 *     hour, learned from each 1 % step between two CHANGE samples; used once
 *     ~3 days are learned. The ETA integrates the needed average-rate seconds
 *     over the profile, so a full day still matches the average rate.
 */

#include "batt_eta.h"
#include "storage_keys.h"

#define BATT_HIST_WINDOW_SEC (7L * 24L * 3600L)
#define BATT_HIST_LEGACY_MAX 12
#define BATT_HIST_LEGACY_OLD_BYTES (1 + BATT_HIST_LEGACY_MAX * 5) /* 61 */
#define BATT_DEFAULT_SECS_PER_PCT 17280L                        /* ~20 days full */
#define BATT_SECS_PER_PCT_MIN 8640L                             /* 10 days */
#define BATT_SECS_PER_PCT_MAX 30240L                            /* 35 days */
#define BATT_RATE_MIN_SPAN_SEC (2L * 3600L)
#define BATT_RATE_MIN_DROP_PCT 3
#define BATT_HIST_FLAG_SEED 0x01
#define BATT_LEARNED_EWMA_NUM 1
#define BATT_LEARNED_EWMA_DEN 4
#define BATT_RISE_RESET_PCT 3
/* Profile: weights 1000 = average; each step moves the hours it spanned by
 * (observed/predicted - 1)/3 per full hour; clamp 0.25x-4x. */
#define BATT_PROFILE_VERSION 1
#define BATT_PROFILE_W_MIN 250
#define BATT_PROFILE_W_MAX 4000
#define BATT_PROFILE_GAIN_DEN 3
#define BATT_PROFILE_MAX_STEP_SEC (2L * 24L * 3600L)
/* ETA walk over the profile: at most this many hour segments. */
#define BATT_WALK_MAX_SEGMENTS 240

/* ---- persist ---- */

static void prv_learned_save(const BattEta *b) {
	if (b->learned_spp == 0) {
		if (persist_exists(STORAGE_KEY_BATT_LEARNED))
			persist_delete(STORAGE_KEY_BATT_LEARNED);
		return;
	}
	persist_write_int(STORAGE_KEY_BATT_LEARNED, (int32_t)b->learned_spp);
}

static void prv_learned_load(BattEta *b) {
	b->learned_spp = 0;
	if (!persist_exists(STORAGE_KEY_BATT_LEARNED))
		return;
	int32_t v = persist_read_int(STORAGE_KEY_BATT_LEARNED);
	if (v >= (int32_t)BATT_SECS_PER_PCT_MIN && v <= (int32_t)BATT_SECS_PER_PCT_MAX)
		b->learned_spp = (uint32_t)v;
}

static void prv_hist_save(const BattEta *b) {
	/* Packed blob: count + samples[BATT_HIST_MAX] (sec LE, percent, flags). */
	uint8_t blob[1 + BATT_HIST_MAX * 6];
	blob[0] = b->count;
	for (uint8_t i = 0; i < BATT_HIST_MAX; i++) {
		uint8_t *p = &blob[1 + i * 6];
		uint32_t sec = (i < b->count) ? b->hist[i].sec : 0;
		p[0] = (uint8_t)(sec & 0xFF);
		p[1] = (uint8_t)((sec >> 8) & 0xFF);
		p[2] = (uint8_t)((sec >> 16) & 0xFF);
		p[3] = (uint8_t)((sec >> 24) & 0xFF);
		p[4] = (i < b->count) ? b->hist[i].percent : 0;
		p[5] = (i < b->count) ? b->hist[i].flags : 0;
	}
	persist_write_data(STORAGE_KEY_BATT_HIST, blob, sizeof(blob));
}

static void prv_hist_load(BattEta *b) {
	b->count = 0;
	memset(b->hist, 0, sizeof(b->hist));
	if (!persist_exists(STORAGE_KEY_BATT_HIST))
		return;
	/* Legacy 61-byte (5-byte samples) and 73-byte (12x6) blobs still load. */
	uint8_t blob[1 + BATT_HIST_MAX * 6];
	int n = persist_read_data(STORAGE_KEY_BATT_HIST, blob, sizeof(blob));
	if (n < 1)
		return;
	uint8_t count = blob[0];
	if (count > BATT_HIST_MAX)
		count = BATT_HIST_MAX;
	bool new_fmt = (n > BATT_HIST_LEGACY_OLD_BYTES);
	int stride = new_fmt ? 6 : 5;
	if (1 + count * stride > n)
		count = (uint8_t)((n - 1) / stride);
	for (uint8_t i = 0; i < count; i++) {
		const uint8_t *p = &blob[1 + i * stride];
		b->hist[i].sec = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
		                 ((uint32_t)p[3] << 24);
		b->hist[i].percent = p[4];
		b->hist[i].flags = new_fmt ? p[5] : 0;
	}
	b->count = count;
}

static void prv_profile_defaults(BattProfile *p) {
	for (int h = 0; h < BATT_PROFILE_HOURS; h++)
		p->w[h] = 1000;
	p->hours = 0;
}

static void prv_profile_save(const BattEta *b) {
	/* version, pad, hours (LE), w[24] (LE) = 52 bytes */
	uint8_t blob[4 + BATT_PROFILE_HOURS * 2];
	blob[0] = BATT_PROFILE_VERSION;
	blob[1] = 0;
	blob[2] = (uint8_t)(b->prof.hours & 0xFF);
	blob[3] = (uint8_t)(b->prof.hours >> 8);
	for (int h = 0; h < BATT_PROFILE_HOURS; h++) {
		blob[4 + h * 2] = (uint8_t)(b->prof.w[h] & 0xFF);
		blob[5 + h * 2] = (uint8_t)(b->prof.w[h] >> 8);
	}
	persist_write_data(STORAGE_KEY_BATT_PROFILE, blob, sizeof(blob));
}

static void prv_profile_load(BattEta *b) {
	prv_profile_defaults(&b->prof);
	if (!persist_exists(STORAGE_KEY_BATT_PROFILE))
		return;
	uint8_t blob[4 + BATT_PROFILE_HOURS * 2];
	int n = persist_read_data(STORAGE_KEY_BATT_PROFILE, blob, sizeof(blob));
	if (n != (int)sizeof(blob) || blob[0] != BATT_PROFILE_VERSION)
		return; /* unknown / other version: start over */
	BattProfile p;
	p.hours = (uint16_t)(blob[2] | (blob[3] << 8));
	for (int h = 0; h < BATT_PROFILE_HOURS; h++) {
		uint16_t w = (uint16_t)(blob[4 + h * 2] | (blob[5 + h * 2] << 8));
		if (w < BATT_PROFILE_W_MIN || w > BATT_PROFILE_W_MAX)
			return; /* corrupt: keep defaults */
		p.w[h] = w;
	}
	b->prof = p;
}

/* ---- history ---- */

static void prv_hist_clear(BattEta *b) {
	b->count = 0;
	memset(b->hist, 0, sizeof(b->hist));
}

static void prv_hist_prune(BattEta *b, time_t now) {
	uint8_t drop = 0;
	while (drop < b->count && (long)now - (long)b->hist[drop].sec > BATT_HIST_WINDOW_SEC)
		drop++;
	if (drop == 0)
		return;
	uint8_t remain = (uint8_t)(b->count - drop);
	if (remain > 0)
		memmove(&b->hist[0], &b->hist[drop], remain * sizeof(BattHistSample));
	b->count = remain;
}

static void prv_hist_push(BattEta *b, time_t now, time_t sec, uint8_t percent, uint8_t flags) {
	prv_hist_prune(b, now);
	if (b->count > 0 && b->hist[b->count - 1].percent == percent)
		return; /* same percent: keep the earlier crossing */
	if (b->count == BATT_HIST_MAX) {
		memmove(&b->hist[0], &b->hist[1], (BATT_HIST_MAX - 1) * sizeof(BattHistSample));
		b->count = BATT_HIST_MAX - 1;
	}
	b->hist[b->count].sec = (uint32_t)sec;
	b->hist[b->count].percent = percent;
	b->hist[b->count].flags = flags;
	b->count++;
}

/* Measured secs/% from CHANGE samples only. 0 = invalid. */
static long prv_measured_spp(BattEta *b, time_t now) {
	prv_hist_prune(b, now);
	int first = -1, last = -1;
	for (uint8_t i = 0; i < b->count; i++) {
		if (b->hist[i].flags & BATT_HIST_FLAG_SEED)
			continue;
		if (first < 0)
			first = (int)i;
		last = (int)i;
	}
	if (first < 0 || last <= first)
		return 0;
	const BattHistSample *o = &b->hist[first];
	const BattHistSample *n = &b->hist[last];
	if (n->percent >= o->percent)
		return 0;
	long dsec = (long)n->sec - (long)o->sec;
	int dpct = (int)o->percent - (int)n->percent;
	if (dsec < BATT_RATE_MIN_SPAN_SEC || dpct < BATT_RATE_MIN_DROP_PCT)
		return 0;
	long spp = dsec / (long)dpct;
	if (spp < BATT_SECS_PER_PCT_MIN || spp > BATT_SECS_PER_PCT_MAX)
		return 0;
	return spp;
}

static void prv_learned_update(BattEta *b, long m) {
	if (m < BATT_SECS_PER_PCT_MIN || m > BATT_SECS_PER_PCT_MAX)
		return;
	if (b->learned_spp == 0)
		b->learned_spp = (uint32_t)m;
	else
		b->learned_spp = (b->learned_spp * (BATT_LEARNED_EWMA_DEN - BATT_LEARNED_EWMA_NUM) +
		                  (uint32_t)m * BATT_LEARNED_EWMA_NUM) / BATT_LEARNED_EWMA_DEN;
	prv_learned_save(b);
}

static void prv_fold_and_clear(BattEta *b, time_t now) {
	long m = prv_measured_spp(b, now);
	if (m > 0)
		prv_learned_update(b, m);
	prv_hist_clear(b);
}

long batt_eta_secs_per_pct(BattEta *b, time_t now) {
	long measured = prv_measured_spp(b, now);
	if (measured > 0)
		return b->learned_spp > 0 ? (measured * 2L + (long)b->learned_spp) / 3L : measured;
	if (b->learned_spp >= (uint32_t)BATT_SECS_PER_PCT_MIN &&
	    b->learned_spp <= (uint32_t)BATT_SECS_PER_PCT_MAX)
		return (long)b->learned_spp;
	return BATT_DEFAULT_SECS_PER_PCT;
}

/* ---- hour-of-day profile ---- */

bool batt_eta_profile_ready(const BattEta *b) {
	return b->prof.hours >= BATT_PROFILE_MIN_HOURS;
}

/* Local hour of t and the start of the next local hour. */
static int prv_hour_seg(time_t t, time_t *next) {
	struct tm *tm = localtime(&t);
	if (!tm) {
		*next = t + 3600 - (t % 3600);
		return (int)((t / 3600) % 24);
	}
	*next = t + 3600 - (tm->tm_min * 60 + tm->tm_sec);
	return tm->tm_hour;
}

/* One 1 % (or larger) step between two CHANGE crossings a -> c: compare the
 * observed drain with the profile's prediction and nudge the hours it spanned. */
static void prv_profile_learn(BattEta *b, const BattHistSample *a, const BattHistSample *c,
                              long spp) {
	long dur = (long)c->sec - (long)a->sec;
	int dp = (int)a->percent - (int)c->percent;
	if (dur <= 0 || dp <= 0 || dp > 50 || dur > BATT_PROFILE_MAX_STEP_SEC || spp <= 0)
		return;
	/* Predicted average-rate seconds over the step (32-bit: <= 2 d * 4). */
	long wsec = 0;
	time_t t = (time_t)a->sec, end = (time_t)c->sec, nxt;
	while (t < end) {
		int h = prv_hour_seg(t, &nxt);
		if (nxt > end)
			nxt = end;
		wsec += (long)(nxt - t) * b->prof.w[h] / 1000L;
		t = nxt;
	}
	if (wsec <= 0)
		return;
	/* ratio (x1000) = observed / predicted drain; dp*spp*1000 <= 1.5e9 */
	long ratio = (long)dp * spp * 1000L / wsec;
	if (ratio < BATT_PROFILE_W_MIN)
		ratio = BATT_PROFILE_W_MIN;
	if (ratio > BATT_PROFILE_W_MAX)
		ratio = BATT_PROFILE_W_MAX;
	t = (time_t)a->sec;
	while (t < end) {
		int h = prv_hour_seg(t, &nxt);
		if (nxt > end)
			nxt = end;
		long w = b->prof.w[h];
		w += w * (ratio - 1000) / 1000L * (long)(nxt - t) / (3600L * BATT_PROFILE_GAIN_DEN);
		b->prof.w[h] = (uint16_t)(w < BATT_PROFILE_W_MIN ? BATT_PROFILE_W_MIN
		                          : w > BATT_PROFILE_W_MAX ? BATT_PROFILE_W_MAX : w);
		t = nxt;
	}
	/* Renormalise to a mean of 1000 so a whole day keeps the average rate. */
	int32_t sum = 0;
	for (int h = 0; h < BATT_PROFILE_HOURS; h++)
		sum += b->prof.w[h];
	for (int h = 0; h < BATT_PROFILE_HOURS; h++) {
		int32_t w = b->prof.w[h] * 1000 * BATT_PROFILE_HOURS / sum;
		b->prof.w[h] = (uint16_t)(w < BATT_PROFILE_W_MIN ? BATT_PROFILE_W_MIN
		                          : w > BATT_PROFILE_W_MAX ? BATT_PROFILE_W_MAX : w);
	}
	uint32_t hours = (uint32_t)b->prof.hours + (uint32_t)((dur + 1800) / 3600);
	b->prof.hours = (uint16_t)(hours > 0xFFFF ? 0xFFFF : hours);
	prv_profile_save(b);
}

/* Wall time when `need` average-rate seconds of drain have passed after t,
 * following the profile once it is ready; limit + 1 when beyond limit. */
static time_t prv_walk(const BattEta *b, time_t t, long need, time_t limit) {
	if (!batt_eta_profile_ready(b))
		return t + need;
	time_t nxt;
	for (int i = 0; i < BATT_WALK_MAX_SEGMENTS && need > 0; i++) {
		if (t > limit)
			return limit + 1;
		int h = prv_hour_seg(t, &nxt);
		long w = b->prof.w[h];
		long cap = (long)(nxt - t) * w / 1000L;
		if (cap >= need)
			return t + (time_t)(need * 1000L / w);
		need -= cap;
		t = nxt;
	}
	return need > 0 ? t + need : t; /* very far: finish linearly */
}

bool batt_eta_time(BattEta *b, time_t now, int m, time_t limit, time_t *out) {
	int ap = (int)b->anchor_percent;
	long spp = batt_eta_secs_per_pct(b, now);
	time_t t;
	if (ap <= m)
		t = b->anchor_sec - (time_t)((long)(m - ap) * spp); /* already reached */
	else
		t = prv_walk(b, b->anchor_sec, (long)(ap - m) * spp, limit);
	*out = t;
	return t <= limit;
}

/* ---- state updates ---- */

void batt_eta_init(BattEta *b) {
	b->percent = 100;
	b->charging = false;
	b->plugged = false;
	b->anchor_sec = 0;
	b->anchor_percent = 100;
	prv_learned_load(b);
	prv_hist_load(b);
	prv_profile_load(b);
}

void batt_eta_set(BattEta *b, time_t now, uint8_t percent, bool charging, bool plugged) {
	bool was_on_power = b->charging || b->plugged;
	bool on_power = charging || plugged;
	uint8_t was_percent = b->percent;
	bool changed = percent != was_percent || charging != b->charging || plugged != b->plugged;
	bool dirty = false;

	if (on_power) {
		/* On cable (charging OR plugged, e.g. full at 100 % with is_charging false):
		 * fold measured into learned, clear the history; learned and profile stay. */
		if (b->count > 0) {
			prv_fold_and_clear(b, now);
			dirty = true;
		}
	} else if (was_on_power) {
		/* Fresh unplug: SEED only (mid-percent; not a rate endpoint). */
		prv_hist_clear(b);
		prv_hist_push(b, now, now, percent, BATT_HIST_FLAG_SEED);
		dirty = true;
	} else if (b->anchor_sec == 0 && b->count > 0) {
		/* Cold start with saved history: the face was not running, so any change
		 * happened at an unknown time. */
		BattHistSample last = b->hist[b->count - 1];
		if (percent >= last.percent + BATT_RISE_RESET_PCT) {
			prv_fold_and_clear(b, now); /* charged meanwhile */
			prv_hist_push(b, now, now, percent, BATT_HIST_FLAG_SEED);
			dirty = true;
		} else if (percent < last.percent) {
			/* 3.3.1 (B): missed drops: SEED at the estimated crossing time, clamped
			 * between the last crossing and now (still never a rate endpoint). */
			long spp = batt_eta_secs_per_pct(b, now);
			time_t est = prv_walk(b, (time_t)last.sec, (long)(last.percent - percent) * spp, now);
			if (est > now)
				est = now;
			if (est <= (time_t)last.sec)
				est = (time_t)last.sec + 1;
			prv_hist_push(b, now, est, percent, BATT_HIST_FLAG_SEED);
			dirty = true;
		}
	} else if (b->anchor_sec != 0 && percent > was_percent) {
		/* Rise off cable: 1-2 % is gauge noise (anchor stays, see below); a real
		 * jump means it charged. */
		if (percent >= was_percent + BATT_RISE_RESET_PCT) {
			prv_fold_and_clear(b, now);
			prv_hist_push(b, now, now, percent, BATT_HIST_FLAG_SEED);
			dirty = true;
		}
	} else if (percent != was_percent || b->anchor_sec == 0) {
		bool have_last = b->count > 0;
		BattHistSample last = have_last ? b->hist[b->count - 1] : (BattHistSample){0, 0, 0};
		if (!have_last || percent < last.percent) {
			/* A real crossing (the first sample after create is a SEED). */
			uint8_t flags = (!have_last && b->anchor_sec == 0) ? BATT_HIST_FLAG_SEED : 0;
			uint8_t before = b->count;
			prv_hist_push(b, now, now, percent, flags);
			if (b->count != before)
				dirty = true;
			/* 3.3.1 (D): learn the hour-of-day profile from CHANGE -> CHANGE steps. */
			if (flags == 0 && have_last && !(last.flags & BATT_HIST_FLAG_SEED))
				prv_profile_learn(b, &last, &b->hist[b->count - 1], batt_eta_secs_per_pct(b, now));
		}
		/* else: back down after a bounce, to or above the recorded crossing. */
	}

	if (dirty)
		prv_hist_save(b);

	b->percent = percent;
	b->charging = charging;
	b->plugged = plugged;

	/* Anchor (3.3.1, B): off cable, the newest history sample is the last known
	 * crossing; a 1-2 % bounce above it keeps that crossing. */
	if (!on_power && b->count > 0 &&
	    percent < b->hist[b->count - 1].percent + BATT_RISE_RESET_PCT &&
	    percent >= b->hist[b->count - 1].percent) {
		b->anchor_sec = (time_t)b->hist[b->count - 1].sec;
		b->anchor_percent = b->hist[b->count - 1].percent;
	} else if (b->anchor_sec == 0 || changed) {
		b->anchor_sec = now;
		b->anchor_percent = percent;
	}
}
