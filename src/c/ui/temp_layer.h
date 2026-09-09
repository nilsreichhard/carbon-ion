/**
 * Temperature layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>

typedef struct TempLayer TempLayer;

TempLayer *temp_layer_create(GRect frame);
void temp_layer_destroy(TempLayer *layer);
Layer *temp_layer_get_layer(TempLayer *layer);
void temp_layer_set_data(TempLayer *layer, int16_t current, int16_t high,
                         int16_t low, const int8_t *hourly,
                         const int8_t *apparent_hourly, uint8_t current_hour,
                         uint8_t hours_remaining);
// No-op kept for call-site compatibility; unit is baked into values by pkjs.
void temp_layer_set_unit(TempLayer *layer, bool celsius);
// Update only the current hour (tick positions) and hours_remaining without
// touching graph data. Pass hours_remaining=0 when no data is available.
void temp_layer_set_current_hour(TempLayer *layer, uint8_t current_hour,
                                 uint8_t hours_remaining);
