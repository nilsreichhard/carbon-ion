/**
 * Icon bar layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "icon_bar_layer.h"
#include "../generated/icons.h"
#include "graph_common.h"
#include <stddef.h>
#include <stdlib.h>

struct IconBarLayer {
	Layer *layer;
	GFont icon_font;
	int battery_percent;
	bool battery_charging;
	bool bt_connected;
	WeatherCondition condition;
	bool is_day;
	bool weather_disconnected;
	bool app_pending;
	BatteryDisplay battery_display;
};

#if GRAPH_OFFSET_X > 0
static const char *prv_battery_icon(int pct, bool charging) {
	if (charging)
		return ICON_BATTERY__CHARGING;
	if (pct >= 70)
		return ICON_BATTERY__FULL;
	if (pct >= 35)
		return ICON_BATTERY__HALF;
	if (pct >= 10)
		return ICON_BATTERY__LOW;
	return ICON_BATTERY__EMPTY;
}

// Condition icon is resolved via weather_condition_to_icon() declared in weather.h

static void prv_update_proc(Layer *layer, GContext *ctx) {
	IconBarLayer *sl = *(IconBarLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int graph_x = GRAPH_OFFSET_X;
	int lh = bounds.size.h;
	int zone_h = lh / 3;

#if PBL_DISPLAY_HEIGHT >= 228
	int icon_size = 22;
#elif PBL_DISPLAY_HEIGHT <= 168
	int icon_size = 14;
#else
	int icon_size = 18;
#endif

	bool is_light = settings_get()->light_theme;

	// Column fill covers any graph bleed from underlying layers
	graphics_context_set_fill_color(ctx, is_light ? GColorWhite : GColorBlack);
	graphics_fill_rect(ctx, GRect(0, 0, graph_x - 1, lh), 0, GCornerNone);

	// Single separator spanning the full combined height
	graph_draw_separator(ctx, graph_x, lh);

	// Three equally-spaced icon slots: battery, bluetooth, weather condition
	graphics_context_set_text_color(ctx, is_light ? GColorBlack : GColorWhite);

	// Slot 0: battery (always shown)
	if (sl->battery_display == BATTERY_DISPLAY_PERCENT) {
		char pct_buf[5];
		snprintf(pct_buf, sizeof(pct_buf), "%d", sl->battery_percent);
		GFont small_font = fonts_get_system_font(FONT_KEY_GOTHIC_14);
		int y0 = (zone_h - 14) / 2;
		graphics_draw_text(ctx, pct_buf, small_font, GRect(0, y0, graph_x, 18),
		                   GTextOverflowModeTrailingEllipsis,
		                   GTextAlignmentCenter, NULL);
	} else {
		int y0 = (zone_h - icon_size) / 2;
		graphics_draw_text(
		    ctx, prv_battery_icon(sl->battery_percent, sl->battery_charging),
		    sl->icon_font, GRect(0, y0, graph_x, icon_size),
		    GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
	}

	// Slot 1: connection status — BT disconnect takes priority; signal-off
	// shown for both fully-expired and partially-expired weather data.
	const char *conn_icon = NULL;
	if (!sl->bt_connected)
		conn_icon = ICON_BLUETOOTH__OFF;
	else if (sl->app_pending)
		conn_icon = ICON_PENDING;
	else if (sl->weather_disconnected)
		conn_icon = ICON_CONNECTION_SIGNAL__OFF;
	if (conn_icon) {
		int y1 = zone_h + (zone_h - icon_size) / 2;
		graphics_draw_text(
		    ctx, conn_icon, sl->icon_font, GRect(0, y1, graph_x, icon_size),
		    GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
	}

	// Slot 2: weather condition — only shown when data is available for the
	// current hour (condition != UNKNOWN means set_condition was called).
	if (sl->condition != WEATHER_CONDITION_UNKNOWN) {
		int y2 = 2 * zone_h + (zone_h - icon_size) / 2;
		graphics_draw_text(ctx, weather_condition_to_icon(sl->condition, sl->is_day),
		                   sl->icon_font, GRect(0, y2, graph_x, icon_size),
		                   GTextOverflowModeTrailingEllipsis,
		                   GTextAlignmentCenter, NULL);
	}
}
#endif

IconBarLayer *icon_bar_layer_create(GRect frame) {
#if GRAPH_OFFSET_X <= 0
	return NULL;
#else
	IconBarLayer *sl = malloc(sizeof(IconBarLayer));
	if (!sl)
		return NULL;

	BatteryChargeState batt = battery_state_service_peek();
	sl->battery_percent = batt.charge_percent;
	sl->battery_charging = batt.is_charging;
	sl->bt_connected = true;
	sl->condition = WEATHER_CONDITION_UNKNOWN;
	sl->is_day = true;
	sl->weather_disconnected = true; // shown until first weather fetch
	sl->app_pending = false;
	sl->battery_display = BATTERY_DISPLAY_ICON;

#if PBL_DISPLAY_HEIGHT >= 228
	sl->icon_font = fonts_load_custom_font(
	    resource_get_handle(RESOURCE_ID_CARBON_ICONS_22));
#elif PBL_DISPLAY_HEIGHT <= 168
	sl->icon_font = fonts_load_custom_font(
	    resource_get_handle(RESOURCE_ID_CARBON_ICONS_14));
#else
	sl->icon_font = fonts_load_custom_font(
	    resource_get_handle(RESOURCE_ID_CARBON_ICONS_18));
#endif

	sl->layer = layer_create_with_data(frame, sizeof(IconBarLayer *));
	*(IconBarLayer **)layer_get_data(sl->layer) = sl;
	layer_set_update_proc(sl->layer, prv_update_proc);
	return sl;
#endif
}

void icon_bar_layer_destroy(IconBarLayer *layer) {
	if (!layer)
		return;
	fonts_unload_custom_font(layer->icon_font);
	layer_destroy(layer->layer);
	free(layer);
}

Layer *icon_bar_layer_get_layer(IconBarLayer *layer) {
	return layer ? layer->layer : NULL;
}

void icon_bar_layer_notify_battery(IconBarLayer *layer,
                                   BatteryChargeState state) {
	if (!layer)
		return;
	layer->battery_percent = state.charge_percent;
	layer->battery_charging = state.is_charging;
	layer_mark_dirty(layer->layer);
}

void icon_bar_layer_notify_bt(IconBarLayer *layer, bool connected) {
	if (!layer)
		return;
	layer->bt_connected = connected;
	layer_mark_dirty(layer->layer);
}

void icon_bar_layer_set_condition(IconBarLayer *layer,
                                  WeatherCondition condition) {
	if (!layer)
		return;
	layer->condition = condition;
	layer_mark_dirty(layer->layer);
}

void icon_bar_layer_set_daytime(IconBarLayer *layer, bool is_day) {
	if (!layer)
		return;
	layer->is_day = is_day;
	layer_mark_dirty(layer->layer);
}

void icon_bar_layer_set_disconnected(IconBarLayer *layer, bool disconnected) {
	if (!layer)
		return;
	layer->weather_disconnected = disconnected;
	layer_mark_dirty(layer->layer);
}

void icon_bar_layer_set_pending(IconBarLayer *layer, bool pending) {
	if (!layer)
		return;
	layer->app_pending = pending;
	layer_mark_dirty(layer->layer);
}

void icon_bar_layer_set_battery_display(IconBarLayer *layer,
                                        BatteryDisplay display) {
	if (!layer)
		return;
	layer->battery_display = display;
	layer_mark_dirty(layer->layer);
}
