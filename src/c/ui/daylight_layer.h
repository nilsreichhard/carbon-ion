/**
 * Daylight layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>

typedef struct DaylightLayer DaylightLayer;

typedef struct {
	uint32_t start_time; // unix timestamp
	uint32_t end_time;   // unix timestamp
	uint8_t color;       // calendar color id (0-15)
	uint8_t place;       // 0=on track, 1=above, 2=below
} TimelineEvent;

/* Max calendar events kept on the watch; JS MAX_TIMELINE_EVENTS must match. */
#define MAX_TIMELINE_EVENTS 12

DaylightLayer *daylight_layer_create(GRect frame);
void daylight_layer_destroy(DaylightLayer *layer);
Layer *daylight_layer_get_layer(DaylightLayer *layer);

// Sunrise/sunset are wall-clock hour+minute (minute-accurate timeline).
// current_hour is the watch's current wall hour (needle at 1/5 with minutes).
// When sunrise_approx or sunset_approx is true the endpoint is approximate.
void daylight_layer_set_data(DaylightLayer *layer, uint8_t sunrise_hour,
                             uint8_t sunrise_minute, uint8_t sunset_hour,
                             uint8_t sunset_minute, uint8_t current_hour,
                             bool sunrise_approx, bool sunset_approx);

// Set current time for the small red current-time indicator line on the daylight track
void daylight_layer_set_current_time(DaylightLayer *layer, uint8_t hour, uint8_t minute);

// Set battery state for timeline depletion markers (Clay 20/10/4/0).
// Pass both is_charging and is_plugged from BatteryChargeState — plugged-but-not-
// charging (typical at 100%) must not be treated as discharging.
void daylight_layer_set_battery(DaylightLayer *layer, uint8_t percent, bool charging,
                                bool plugged);

// Upcoming calendar events (up to MAX_TIMELINE_EVENTS) shown on the daylight timeline
#if defined(PBL_ROUND)
/* Gabbro: draw the timeline markers at native size after the arc warp. */
void daylight_layer_draw_marks_mapped(DaylightLayer *layer, GContext *ctx);
#endif
void daylight_layer_set_events(DaylightLayer *layer, const TimelineEvent *events,
                               uint8_t count);
