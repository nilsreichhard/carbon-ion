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
	uint8_t color;       // calendar color id (0-7)
} TimelineEvent;

DaylightLayer *daylight_layer_create(GRect frame);
void daylight_layer_destroy(DaylightLayer *layer);
Layer *daylight_layer_get_layer(DaylightLayer *layer);

// sunrise_hour and sunset_hour are wall-clock hours (0-23).
// current_hour is the first hour shown in the graph (leftmost column).
// When sunrise_approx or sunset_approx is true the corresponding endpoint is
// drawn as a dithered fade centered on that hour instead of a solid endcap.
void daylight_layer_set_data(DaylightLayer *layer, uint8_t sunrise_hour,
                             uint8_t sunset_hour, uint8_t current_hour,
                             bool sunrise_approx, bool sunset_approx);

// Set current time for the small red current-time indicator line on the daylight track
void daylight_layer_set_current_time(DaylightLayer *layer, uint8_t hour, uint8_t minute);

// Set battery state for timeline depletion indicators (yellow at 10%, red when expected to die)
void daylight_layer_set_battery(DaylightLayer *layer, uint8_t percent, bool charging);

// Upcoming calendar events (up to 6) shown on the daylight timeline
void daylight_layer_set_events(DaylightLayer *layer, const TimelineEvent *events,
                               uint8_t count);
