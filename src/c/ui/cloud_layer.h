/**
 * Cloud layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>

typedef struct CloudLayer CloudLayer;

CloudLayer *cloud_layer_create(GRect frame);
void cloud_layer_destroy(CloudLayer *layer);
Layer *cloud_layer_get_layer(CloudLayer *layer);
void cloud_layer_set_data(CloudLayer *layer, const uint8_t *cover,
                          const uint8_t *hourly_code, uint8_t current_hour);
