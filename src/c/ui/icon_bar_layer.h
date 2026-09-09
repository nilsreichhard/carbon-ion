/**
 * Icon bar layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include "../modules/settings.h"
#include "../modules/weather.h"
#include <pebble.h>

typedef struct IconBarLayer IconBarLayer;

IconBarLayer *icon_bar_layer_create(GRect frame);
void icon_bar_layer_destroy(IconBarLayer *layer);
Layer *icon_bar_layer_get_layer(IconBarLayer *layer);

void icon_bar_layer_notify_battery(IconBarLayer *layer,
                                   BatteryChargeState state);
void icon_bar_layer_notify_bt(IconBarLayer *layer, bool connected);
void icon_bar_layer_set_condition(IconBarLayer *layer,
                                  WeatherCondition condition);
void icon_bar_layer_set_daytime(IconBarLayer *layer, bool is_day);
void icon_bar_layer_set_disconnected(IconBarLayer *layer, bool disconnected);
void icon_bar_layer_set_pending(IconBarLayer *layer, bool pending);
void icon_bar_layer_set_battery_display(IconBarLayer *layer,
                                        BatteryDisplay display);
