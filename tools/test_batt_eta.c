/* 3.3.1: host tests for modules/batt_eta.c (battery marker ETA).
 * Built by tools/test_payload.js, run with TZ=UTC, once plain (emery) and once
 * with -DPBL_PLATFORM_GABBRO:
 *   cc -std=c11 -Itools/host tools/test_batt_eta.c src/c/modules/batt_eta.c */
#include <stdio.h>
#include <stdlib.h>
#include "pebble.h"
#include "../src/c/modules/batt_eta.h"
#include "../src/c/modules/storage_keys.h"

HostPersist g_host_persist[32];
static int fails, n;
#define CHECK(name, cond) do { n++; if (cond) printf("PASS %s\n", name); else { fails++; printf("FAIL %s\n", name); } } while (0)

#define S 16848L /* Nils: ~19.5 days per full charge */
#define T0 1763485200L /* 2025-11-18 17:00 UTC */

static void reset(void) { memset(g_host_persist, 0, sizeof(g_host_persist)); }
static long eta(BattEta *b, time_t now, int m) {
	time_t t = 0;
	batt_eta_time(b, now, m, now + 400L * 24 * 3600, &t);
	return (long)t;
}
/* Learned rate S, an unplug at 61 % at T0, then 1 % crossings every S. */
static void start(BattEta *b) {
	reset();
	persist_write_int(STORAGE_KEY_BATT_LEARNED, (int32_t)S);
	batt_eta_init(b);
	batt_eta_set(b, T0 - 10, 61, false, true);
	batt_eta_set(b, T0, 61, false, false);
}

/* Day 07-23 local drains 1.5x, night 0.4x (mean 1.1333 -> normalised). */
static double rate(time_t t) {
	struct tm *tm = gmtime(&t);
	double f = (tm->tm_hour >= 7 && tm->tm_hour < 23) ? 1.5 : 0.4;
	return f / ((16 * 1.5 + 8 * 0.4) / 24.0) / (double)S; /* % per second */
}
/* Runs the watch minute by minute from soc at t until stop; returns the time. */
static time_t drain(BattEta *b, double *soc, time_t t, time_t stop, bool running) {
	int last = (int)(*soc + 0.999999);
	for (; t < stop; t += 60) {
		*soc -= rate(t) * 60;
		int p = (int)(*soc + 0.999999);
		if (p != last && running)
			batt_eta_set(b, t, (uint8_t)p, false, false);
		last = p;
	}
	return t;
}
static time_t truth(double soc, time_t t, int m) {
	for (; soc > m; t += 60)
		soc -= rate(t) * 60;
	return t;
}

int main(void) {
	BattEta b;
	/* 4.0.0: fixed 20 / 10 / 5 / 0 % markers on emery and gabbro (no
	 * platform low-power threshold any more). */
	start(&b);
	batt_eta_set(&b, T0 + S, 60, false, false);
	CHECK("4.0.0: 5 % ETA = anchor + 55 x rate (same on emery and gabbro)", eta(&b, T0 + S, 5) == T0 + 56 * S);
	CHECK("4.0.0: 0 % marker is back: ETA = anchor + 60 x rate", eta(&b, T0 + S, 0) == T0 + 61 * S);
	CHECK("4.0.0: order 20 < 10 < 5 < 0", eta(&b, T0 + S, 20) < eta(&b, T0 + S, 10) && eta(&b, T0 + S, 10) < eta(&b, T0 + S, 5) &&
	      eta(&b, T0 + S, 5) < eta(&b, T0 + S, 0));

	/* A: a change missed while another app was open (60, 59, 58, restart at 56,
	 * then 55). 3.3.0 took the 58->55 gap as 3 % steps and pushed every marker
	 * one step (~4.7 h) later. */
	start(&b);
	for (int p = 60; p >= 58; p--)
		batt_eta_set(&b, T0 + (61 - p) * S, (uint8_t)p, false, false);
	time_t restart = T0 + 5 * S + 1200; /* 57 and 56 crossed while away */
	b.anchor_sec = 0;                    /* face re-created */
	batt_eta_set(&b, restart, 56, false, false);
	CHECK("restart after missed drops: anchor at the estimated crossing, not now",
	      labs((long)b.anchor_sec - (T0 + 5 * S)) < 60 && b.anchor_percent == 56);
	CHECK("restart: estimated crossing never after now", b.anchor_sec <= restart);
	batt_eta_set(&b, T0 + 6 * S, 55, false, false);
	long e20 = eta(&b, T0 + 6 * S, 20), true20 = T0 + 41 * S;
	printf("  20 %% ETA error after a missed change: %+ld s\n", e20 - true20);
	CHECK("missed change: no mid-step boost, 20 % ETA within 2 min", labs(e20 - true20) < 120);
	CHECK("missed change: 5 % and 0 % ETAs within 2 min",
	      labs(eta(&b, T0 + 6 * S, 5) - (T0 + 56 * S)) < 120 && labs(eta(&b, T0 + 6 * S, 0) - (T0 + 61 * S)) < 120);

	/* B: 1-2 % bounce keeps the recorded crossing. */
	start(&b);
	batt_eta_set(&b, T0 + S, 60, false, false);
	batt_eta_set(&b, T0 + 2 * S, 59, false, false);
	time_t cross = T0 + 2 * S;
	uint8_t cnt = b.count;
	batt_eta_set(&b, cross + 900, 61, false, false); /* +2 % bounce */
	CHECK("bounce up 2 %: anchor stays at the 59 % crossing", b.anchor_sec == cross && b.anchor_percent == 59);
	batt_eta_set(&b, cross + 1800, 60, false, false);
	CHECK("bounce: 61 -> 60 is not recorded as a crossing (history unchanged)", b.count == cnt);
	batt_eta_set(&b, cross + 2700, 59, false, false);
	CHECK("bounce back to 59: anchor snaps back to the recorded crossing", b.anchor_sec == cross && b.anchor_percent == 59);
	CHECK("bounce: 20 % ETA unchanged", eta(&b, cross + 2700, 20) == cross + 39 * S);
	batt_eta_set(&b, cross + 2800, 62, false, false); /* +3 %: charged */
	CHECK("rise of 3 % off cable resets the history", b.count == 1 && b.anchor_percent == 62);

	/* Restart with the same percent: anchor is the saved crossing. */
	start(&b);
	batt_eta_set(&b, T0 + S, 60, false, false);
	batt_eta_set(&b, T0 + 2 * S, 59, false, false);
	BattEta r;
	batt_eta_init(&r);
	batt_eta_set(&r, T0 + 2 * S + 3000, 59, false, false);
	CHECK("restart, same percent: anchor = persisted crossing", r.anchor_sec == T0 + 2 * S && r.anchor_percent == 59);

	/* D: hour-of-day profile. */
	reset();
	persist_write_int(STORAGE_KEY_BATT_LEARNED, (int32_t)S);
	batt_eta_init(&b);
	CHECK("no profile yet: not ready, weights 1000", !batt_eta_profile_ready(&b) && b.prof.w[3] == 1000 && b.prof.hours == 0);
	double soc = 100.0;
	time_t t = T0;
	batt_eta_set(&b, t - 60, 100, false, true);
	batt_eta_set(&b, t, 100, false, false);
	t = drain(&b, &soc, t, T0 + 2 * 24 * 3600, true);
	CHECK("after 2 days: profile still in fallback", !batt_eta_profile_ready(&b));
	long lin = (long)b.anchor_sec + (long)(b.anchor_percent - 20) * batt_eta_secs_per_pct(&b, t);
	CHECK("fallback: ETA is the linear average-rate projection", eta(&b, t, 20) == lin);
	t = drain(&b, &soc, t, T0 + 6 * 24 * 3600 + 4 * 3600, true); /* evening, day 6 */
	CHECK("after ~6 days: profile ready", batt_eta_profile_ready(&b));
	int day = 0, night = 0;
	for (int h = 0; h < 24; h++) {
		if (h >= 9 && h < 21) day += b.prof.w[h];
		if (h >= 1 && h < 5) night += b.prof.w[h];
	}
	printf("  profile: day mean %d, night mean %d (truth 1324 / 353)\n", day / 12, night / 4);
	CHECK("profile learned: daytime hours drain faster than night", day / 12 > 1100 && night / 4 < 750);
	int sum = 0;
	for (int h = 0; h < 24; h++) sum += b.prof.w[h];
	CHECK("profile mean stays ~1000 (average rate preserved)", abs(sum / 24 - 1000) <= 15);
	/* ETA of the next marker within 24 h: profile vs linear */
	int m = (int)b.anchor_percent - 4;
	long tp = eta(&b, t, m), tt = (long)truth(soc, t, m);
	long tl = (long)b.anchor_sec + (long)(b.anchor_percent - m) * batt_eta_secs_per_pct(&b, t);
	printf("  %d %% ETA error: profile %+ld min, linear %+ld min\n", m, (tp - tt) / 60, (tl - tt) / 60);
	CHECK("profile ETA closer to the truth than the linear one", labs(tp - tt) < labs(tl - tt));
	/* persistence + versioning */
	BattEta c;
	batt_eta_init(&c);
	CHECK("profile persists (key 12)", memcmp(c.prof.w, b.prof.w, sizeof(b.prof.w)) == 0 && c.prof.hours == b.prof.hours);
	g_host_persist[STORAGE_KEY_BATT_PROFILE].data[0] = 99;
	batt_eta_init(&c);
	CHECK("unknown profile version: back to defaults (fallback)", c.prof.hours == 0 && c.prof.w[12] == 1000);
	g_host_persist[STORAGE_KEY_BATT_PROFILE].data[0] = 1;
	/* charging keeps the profile, clears the history */
	batt_eta_set(&b, t + 60, (uint8_t)b.percent, true, true);
	CHECK("charging: history cleared, profile kept", b.count == 0 && batt_eta_profile_ready(&b));

	printf("%d/%d passed\n", n - fails, n);
	return fails ? 1 : 0;
}
