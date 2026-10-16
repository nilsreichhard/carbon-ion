/**
 * Event layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>
#include "arc.h"

typedef struct EventLayer EventLayer;

EventLayer *event_layer_create(GRect frame);
void event_layer_destroy(EventLayer *layer);
Layer *event_layer_get_layer(EventLayer *layer);

// hourly_code: 24-entry WMO code array, shifted so index 0 = current hour.
// hours_remaining: how many of those 24 entries contain valid data.
//   Entries at index >= hours_remaining are drawn as a missing-data span
//   marked with ICON_CONNECTION_SIGNAL__OFF.
// fog: per-hour fog flags (may be NULL); shown as a low-priority fog event
//   when the Fog indicator setting is on.
void event_layer_set_data(EventLayer *layer, const uint8_t *hourly_code,
                          const uint8_t *fog, uint8_t hours_remaining);
#if defined(PBL_ROUND)
// Round arc mode: draw the span glyphs upright at map(screen-src x, y).
void event_layer_draw_icons_mapped(EventLayer *layer, GContext *ctx,
                                   ArcMapFn map);
#endif
