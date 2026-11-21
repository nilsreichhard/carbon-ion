/**
 * Battery milestone ETA (3.3.1): discharge history, drain rate, hour-of-day
 * profile and the projected time of each timeline battery marker.
 *
 * Pure logic (persist + localtime only), shared by the daylight layer and the
 * host test tools/test_batt_eta.c.
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>

#define BATT_HIST_MAX 36
#define BATT_PROFILE_HOURS 24
/* Hours of discharge the hour-of-day profile needs before it is used (~3 days). */
#define BATT_PROFILE_MIN_HOURS 72

typedef struct {
	uint32_t sec;
	uint8_t percent;
	uint8_t flags;
} BattHistSample;

typedef struct {
	uint16_t w[BATT_PROFILE_HOURS]; /* relative drain per local hour; mean 1000 */
	uint16_t hours;                 /* hours of discharge learned (saturating) */
} BattProfile;

typedef struct {
	uint8_t percent;
	bool charging;
	bool plugged;
	/* Time and percent the ETA counts from: the last recorded percent crossing. */
	time_t anchor_sec;
	uint8_t anchor_percent;
	BattHistSample hist[BATT_HIST_MAX]; /* chronological discharge samples */
	uint8_t count;
	uint32_t learned_spp; /* long-term secs/% across charges; 0 = unset */
	BattProfile prof;
} BattEta;

/* Defaults, then the persisted history, learned rate and profile. */
void batt_eta_init(BattEta *b);
/* New BatteryChargeState at wall time now. */
void batt_eta_set(BattEta *b, time_t now, uint8_t percent, bool charging, bool plugged);
/* Average seconds of wall time per 1 % (measured / learned / default). */
long batt_eta_secs_per_pct(BattEta *b, time_t now);
/* True once the hour-of-day profile has about 3 days of data. */
bool batt_eta_profile_ready(const BattEta *b);
/* Wall time at which the reported percent reaches m. False when it is later than
 * limit (not worth computing / off the chart). */
bool batt_eta_time(BattEta *b, time_t now, int m, time_t limit, time_t *out);
