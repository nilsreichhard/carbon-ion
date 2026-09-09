/**
 * Precipitation layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include "../modules/weather.h"
#include <pebble.h>

typedef struct PrecipLayer PrecipLayer;

PrecipLayer *precip_layer_create(GRect frame);
void precip_layer_destroy(PrecipLayer *layer);
Layer *precip_layer_get_layer(PrecipLayer *layer);
void precip_layer_set_data(PrecipLayer *layer, const uint8_t *prob,
                           const uint8_t *hourly_code, uint8_t current_hour);
