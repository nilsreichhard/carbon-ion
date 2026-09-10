/**
 * Main watchface entry point
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "modules/demo.h"
#include "modules/settings.h"
#include "modules/weather.h"
#include "ui/cloud_layer.h"
#include "ui/daylight_layer.h"
#include "ui/event_layer.h"
#include "ui/graph_common.h"
#include "ui/precip_layer.h"
#include "ui/temp_layer.h"
#include "ui/time_layer.h"
#include <pebble.h>
#include <stddef.h>

static inline int32_t prv_tuple_int(const Tuple *t) {
	if (!t)
		return 0;
	if (t->length == 1)
		return (int32_t)t->value->int8;
	if (t->length == 2)
		return (int32_t)t->value->int16;
	return t->value->int32;
}

// Storage key for persisting last-received weather across cold starts
#define STORAGE_KEY_WEATHER 2
#define STORAGE_KEY_WEATHER_PART2 3

// GRAPH_LAYERS_H is the combined height of daylight+cloud+precip+event — also
// used for the icon bar overlay and the temp layer so all three match. Must be
// tall enough to fit the icon slots: >= 228 uses 22px icons (need 66px+),
// middle tier uses 18px icons (56px gives zone_h=18), small uses 14px icons.
#if defined(PBL_PLATFORM_EMERY) || (defined(PBL_DISPLAY_HEIGHT) && PBL_DISPLAY_HEIGHT >= 228)
#define DAYLIGHT_H 18
#define CLOUD_H 14
#define PRECIP_H 22
#define EVENT_H 12
// Sums to 66
#elif defined(PBL_PLATFORM_CHALK)
#define DAYLIGHT_H 14
#define CLOUD_H 15
#define PRECIP_H 15
#define EVENT_H 12
// Sums to 56
#else
#define DAYLIGHT_H 14
#define CLOUD_H 15
#define PRECIP_H 15
#define EVENT_H 12
// Sums to 56
#endif
#define GRAPH_LAYERS_H (DAYLIGHT_H + CLOUD_H + PRECIP_H + EVENT_H)

static Window *s_main_window;
static DaylightLayer *s_daylight_layer;
static CloudLayer *s_cloud_layer;
static PrecipLayer *s_precip_layer;
static EventLayer *s_event_layer;
static TimeLayer *s_time_layer;
static TempLayer *s_temp_layer;

static WeatherData s_weather;
static uint32_t s_request_seq;
static uint32_t s_last_sent_request_seq;
static uint32_t s_last_answered_seq;
#if !defined(DEMO_SCENARIO)
static uint32_t s_minutes_since_launch;
#endif

// Forward declarations
static void prv_request_weather(void);
static void prv_push_weather_to_layers(struct tm *now);
static void prv_update_pending_state(void);

/**
 * Ticks every minute; advances graph layers and requests fresh weather each
 * hour.
 *
 * @param tick_time      Current local time.
 * @param units_changed  Bitmask of which time units rolled over this tick.
 */
static void prv_tick_handler(struct tm *tick_time, TimeUnits units_changed) {
#if defined(DEMO_SCENARIO)
	// Demo mode: time display is frozen at the scenario's hour and date.
	(void)tick_time;
	(void)units_changed;
#else
	time_layer_update(s_time_layer, tick_time, settings_get());
	bool bt_conn = connection_service_peek_pebble_app_connection();
	bool quiet = quiet_time_is_active();
	time_layer_set_status(s_time_layer, bt_conn, quiet);
	daylight_layer_set_current_time(s_daylight_layer, tick_time->tm_hour, tick_time->tm_min);

	// Re-push graph layers each hour for display rollover, and request weather
	// at the configured minute cadence.
	if (units_changed & HOUR_UNIT) {
		prv_push_weather_to_layers(tick_time);
	}

	// Note: the potential for overflow is negligible: this resets on watchface
	// launch and would take 4082 years of continuous operation to overflow.
	s_minutes_since_launch += 1;
	uint8_t interval = settings_get()->fetch_interval_min;
	if (interval < 15) interval = 30;
	if (s_minutes_since_launch % interval == 0) {
		prv_request_weather();
	}
#endif
}

/**
 * Push current weather state to all graph layers.
 *
 * Builds offset-shifted views of the hourly arrays so that column 0 always
 * represents the current hour, regardless of when the data was fetched.
 *
 * @param now  Current local time; may be NULL on cold start before the first
 * tick.
 */
static void prv_push_weather_to_layers(struct tm *now) {
	uint8_t current_hour = now ? (uint8_t)now->tm_hour : 0;

	if (!s_weather.is_valid) {
		daylight_layer_set_data(s_daylight_layer, 6, 18, current_hour, true,
		                        true);
		temp_layer_set_current_hour(s_temp_layer, current_hour, 0);
		time_layer_set_condition(s_time_layer,
		                         WEATHER_CONDITION_UNKNOWN, true);
		layer_mark_dirty(window_get_root_layer(s_main_window));
		return;
	}

	// Calculate how many hours have elapsed since the fetch
	int data_offset = 0;
	if (s_weather.fetch_time > 0) {
		time_t now_t = time(NULL);
		long elapsed = (long)(now_t - s_weather.fetch_time);
		if (elapsed > 0) {
			data_offset = (int)(elapsed / 3600);
		}
	}
	if (data_offset < 0)
		data_offset = 0;
	if (data_offset >= WEATHER_HOURLY_COUNT)
		data_offset = WEATHER_HOURLY_COUNT - 1;

	int past_needed = graph_get_past_hours();
	int total_needed = graph_get_total_hours();
	if (total_needed > WEATHER_HOURLY_COUNT)
		total_needed = WEATHER_HOURLY_COUNT;

	int start_idx = WEATHER_PAST_HOURS - past_needed + data_offset;
	if (start_idx < 0)
		start_idx = 0;
	if (start_idx >= WEATHER_HOURLY_COUNT)
		start_idx = WEATHER_HOURLY_COUNT - 1;

	int copy_len = total_needed + 1;
	if (start_idx + copy_len > WEATHER_HOURLY_COUNT)
		copy_len = WEATHER_HOURLY_COUNT - start_idx;

	// Shifted views: window starting at past_needed hours ago
	uint8_t precip_view[MAX_GRAPH_HOURS + 1];
	int8_t temp_view[MAX_GRAPH_HOURS + 1];
	int8_t appar_view[MAX_GRAPH_HOURS + 1];
	uint8_t cloud_view[MAX_GRAPH_HOURS + 1];
	uint8_t code_view[MAX_GRAPH_HOURS + 1];
	memset(precip_view, 0, sizeof(precip_view));
	memset(temp_view, 0, sizeof(temp_view));
	memset(appar_view, 0, sizeof(appar_view));
	memset(cloud_view, 0, sizeof(cloud_view));
	memset(code_view, 0, sizeof(code_view));

	memcpy(precip_view, &s_weather.precip_prob[start_idx], copy_len);
	memcpy(temp_view, &s_weather.temp_hourly[start_idx], copy_len);
	memcpy(appar_view, &s_weather.apparent_temp_hourly[start_idx], copy_len);
	memcpy(cloud_view, &s_weather.cloud_cover[start_idx], copy_len);
	memcpy(code_view, &s_weather.hourly_weather_code[start_idx], copy_len);

	uint8_t hours_remaining = copy_len;

	int now_idx = WEATHER_PAST_HOURS + data_offset;
	if (now_idx >= WEATHER_HOURLY_COUNT)
		now_idx = WEATHER_HOURLY_COUNT - 1;

	int16_t display_temp = (s_weather.current_temp != WEATHER_TEMP_INVALID)
	                           ? s_weather.current_temp
	                           : (int16_t)s_weather.temp_hourly[now_idx];
	uint8_t display_code = (s_weather.weather_code != WEATHER_CODE_INVALID)
	                           ? s_weather.weather_code
	                           : s_weather.hourly_weather_code[now_idx];

	bool is_day = (current_hour >= s_weather.sunrise_hour &&
	               current_hour < s_weather.sunset_hour);
	daylight_layer_set_data(s_daylight_layer, s_weather.sunrise_hour,
	                        s_weather.sunset_hour, current_hour, false, false);
	cloud_layer_set_data(s_cloud_layer, cloud_view, code_view, current_hour);
	precip_layer_set_data(s_precip_layer, precip_view, code_view, current_hour);
	event_layer_set_data(s_event_layer, code_view, hours_remaining);
	time_layer_set_condition(s_time_layer,
	                         weather_code_to_condition(display_code), is_day);
	temp_layer_set_unit(s_temp_layer, settings_get()->temp_unit_celsius);
	temp_layer_set_data(s_temp_layer, display_temp, s_weather.high_temp,
	                    s_weather.low_temp, temp_view, appar_view, current_hour,
	                    hours_remaining);
	time_layer_set_city(s_time_layer, s_weather.city_name);
}

/**
 * Handle incoming AppMessage; parse weather fields and push to layers if
 * complete.
 *
 * @param iter     Incoming message dictionary iterator.
 * @param context  Unused callback context.
 */
static void prv_inbox_received(DictionaryIterator *iter, void *context) {
	// Check for settings changes first
	settings_apply_from_message(iter);
	temp_layer_set_unit(s_temp_layer, settings_get()->temp_unit_celsius);
	window_set_background_color(s_main_window,
	                            settings_get()->light_theme ? GColorWhite
	                                                        : GColorBlack);

	// Parse scalar weather fields — track whether any weather key was present
	// so a settings-only message can't corrupt the weather state.
	Tuple *t;
	bool got_weather = false;

	t = dict_find(iter, MESSAGE_KEY_WEATHER_TEMP);
	if (!t) t = dict_find(iter, 10001);
	if (t) {
		s_weather.current_temp = (int16_t)prv_tuple_int(t);
		got_weather = true;
	}

	t = dict_find(iter, MESSAGE_KEY_WEATHER_TEMP_HIGH);
	if (!t) t = dict_find(iter, 10002);
	if (t)
		s_weather.high_temp = (int16_t)prv_tuple_int(t);

	t = dict_find(iter, MESSAGE_KEY_WEATHER_TEMP_LOW);
	if (!t) t = dict_find(iter, 10003);
	if (t)
		s_weather.low_temp = (int16_t)prv_tuple_int(t);

	t = dict_find(iter, MESSAGE_KEY_WEATHER_CODE);
	if (!t) t = dict_find(iter, 10004);
	if (t)
		s_weather.weather_code = (uint8_t)prv_tuple_int(t);

	t = dict_find(iter, MESSAGE_KEY_WEATHER_SUNRISE_HOUR);
	if (!t) t = dict_find(iter, 10010);
	if (t)
		s_weather.sunrise_hour = (uint8_t)prv_tuple_int(t);

	t = dict_find(iter, MESSAGE_KEY_WEATHER_SUNSET_HOUR);
	if (!t) t = dict_find(iter, 10011);
	if (t)
		s_weather.sunset_hour = (uint8_t)prv_tuple_int(t);

	// Hourly byte arrays
	t = dict_find(iter, MESSAGE_KEY_WEATHER_PRECIP_PROB);
	if (!t) t = dict_find(iter, 10005);
	if (t && t->type == TUPLE_BYTE_ARRAY && t->length > 0) {
		int len = t->length < WEATHER_HOURLY_COUNT ? t->length : WEATHER_HOURLY_COUNT;
		memcpy(s_weather.precip_prob, t->value->data, len);
	}

	t = dict_find(iter, MESSAGE_KEY_WEATHER_TEMP_HOURLY);
	if (!t) t = dict_find(iter, 10006);
	if (t && t->type == TUPLE_BYTE_ARRAY && t->length > 0) {
		int len = t->length < WEATHER_HOURLY_COUNT ? t->length : WEATHER_HOURLY_COUNT;
		memcpy(s_weather.temp_hourly, t->value->data, len);
	}

	t = dict_find(iter, MESSAGE_KEY_WEATHER_APPARENT_TEMP_HOURLY);
	if (!t) t = dict_find(iter, 10007);
	if (t && t->type == TUPLE_BYTE_ARRAY && t->length > 0) {
		int len = t->length < WEATHER_HOURLY_COUNT ? t->length : WEATHER_HOURLY_COUNT;
		memcpy(s_weather.apparent_temp_hourly, t->value->data, len);
	}

	t = dict_find(iter, MESSAGE_KEY_WEATHER_CLOUD_COVER);
	if (!t) t = dict_find(iter, 10008);
	if (t && t->type == TUPLE_BYTE_ARRAY && t->length > 0) {
		int len = t->length < WEATHER_HOURLY_COUNT ? t->length : WEATHER_HOURLY_COUNT;
		memcpy(s_weather.cloud_cover, t->value->data, len);
	}

	t = dict_find(iter, MESSAGE_KEY_WEATHER_HOURLY_CODE);
	if (!t) t = dict_find(iter, 10009);
	if (t && t->type == TUPLE_BYTE_ARRAY && t->length > 0) {
		int len = t->length < WEATHER_HOURLY_COUNT ? t->length : WEATHER_HOURLY_COUNT;
		memcpy(s_weather.hourly_weather_code, t->value->data, len);
	}

	t = dict_find(iter, MESSAGE_KEY_CITY_NAME);
	if (!t) t = dict_find(iter, 10013);
	if (t && t->type == TUPLE_CSTRING) {
		strncpy(s_weather.city_name, t->value->cstring,
		        sizeof(s_weather.city_name) - 1);
		s_weather.city_name[sizeof(s_weather.city_name) - 1] = '\0';
	}

	if (got_weather) {
		t = dict_find(iter, MESSAGE_KEY_WEATHER_FETCH_TIME);
		if (!t) t = dict_find(iter, 10012);
		if (t) {
			s_weather.fetch_time = (time_t)prv_tuple_int(t);
		} else {
			s_weather.fetch_time = time(NULL);
		}

		s_weather.is_valid = true;
		s_weather.valid_hours = WEATHER_HOURLY_COUNT;
		s_last_answered_seq = s_last_sent_request_seq;

		// Persist for cold-start restoration across 2 storage keys
		persist_write_data(STORAGE_KEY_WEATHER, &s_weather, 256);
		persist_write_data(STORAGE_KEY_WEATHER_PART2,
		                   ((const uint8_t *)&s_weather) + 256,
		                   sizeof(s_weather) - 256);
	}

	// Apply settings and push weather to layers ONCE
	time_t now_s = time(NULL);
	struct tm *now_stm = localtime(&now_s);
	if (now_stm) {
		time_layer_update(s_time_layer, now_stm, settings_get());
		prv_push_weather_to_layers(now_stm);
	}

	prv_update_pending_state();
}

static void prv_inbox_dropped(AppMessageResult reason, void *context) {
	APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox dropped: %d", (int)reason);
}

static void prv_outbox_failed(DictionaryIterator *failed,
                              AppMessageResult reason, void *context) {
	(void)failed;
	(void)context;
	APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox failed: reason=%d seq=%lu",
	        (int)reason, (unsigned long)s_last_sent_request_seq);
}

static void prv_request_weather(void) {
	uint32_t seq = ++s_request_seq;
	DictionaryIterator *iter;
	AppMessageResult result = app_message_outbox_begin(&iter);
	if (result == APP_MSG_OK) {
		s_last_sent_request_seq = seq;
		dict_write_uint32(iter, MESSAGE_KEY_WEATHER_REQUEST, seq);
		result = app_message_outbox_send();
		if (result != APP_MSG_OK) {
			APP_LOG(APP_LOG_LEVEL_WARNING,
			        "Outbox send failed: reason=%d seq=%lu", (int)result,
			        (unsigned long)seq);
		}
	} else {
		APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox begin failed: reason=%d seq=%lu",
		        (int)result, (unsigned long)seq);
	}
	prv_update_pending_state();
}

static void prv_battery_handler(BatteryChargeState state) {
	daylight_layer_set_battery(s_daylight_layer, state.charge_percent,
	                           state.is_charging);
}

static void prv_update_pending_state(void) {
	// Pending state tracking without icon bar
}

static void prv_bt_handler(bool connected) {
	if (s_time_layer) {
		time_layer_set_status(s_time_layer, connected, quiet_time_is_active());
	}
	prv_update_pending_state();
}

static void prv_window_load(Window *window) {
	Layer *root = window_get_root_layer(window);
	GRect bounds = layer_get_bounds(root);
	int w = bounds.size.w;

	// Calculate vertical positions — daylight layer is flush to the top
	int y = 0;

	// Daylight line with battery and sun/moon hour markers
	s_daylight_layer = daylight_layer_create(GRect(0, y, w, DAYLIGHT_H));
	layer_add_child(root, daylight_layer_get_layer(s_daylight_layer));
	y += DAYLIGHT_H;

	BatteryChargeState init_batt = battery_state_service_peek();
	daylight_layer_set_battery(s_daylight_layer, init_batt.charge_percent,
	                           init_batt.is_charging);

	// Cloud cover layer
	s_cloud_layer = cloud_layer_create(GRect(0, y, w, CLOUD_H));
	layer_add_child(root, cloud_layer_get_layer(s_cloud_layer));
	y += CLOUD_H;

	// Precip graph
	s_precip_layer = precip_layer_create(GRect(0, y, w, PRECIP_H));
	layer_add_child(root, precip_layer_get_layer(s_precip_layer));
	y += PRECIP_H;

	// Event layer — positioned below precip, includes grouping visualization
	s_event_layer = event_layer_create(GRect(0, y, w, EVENT_H));
	layer_add_child(root, event_layer_get_layer(s_event_layer));
	y += EVENT_H;

	// Time block (city + time + date) — vertically centered on the screen
	int time_y = (bounds.size.h - TL_TIME_BLOCK_H) / 2;
	s_time_layer = time_layer_create(GRect(0, time_y, w, TL_TIME_BLOCK_H));
	layer_add_child(root, time_layer_get_layer(s_time_layer));

	bool init_bt = connection_service_peek_pebble_app_connection();
	bool init_quiet = quiet_time_is_active();
	time_layer_set_status(s_time_layer, init_bt, init_quiet);

	// Temp info + sparkline — same height as the top graph group, pinned to
	// bottom
	int temp_y = bounds.size.h - GRAPH_LAYERS_H;
	s_temp_layer = temp_layer_create(GRect(0, temp_y, w, GRAPH_LAYERS_H));
	layer_add_child(root, temp_layer_get_layer(s_temp_layer));

	// Seed time display immediately
#if defined(DEMO_SCENARIO)
	struct tm demo_now;
	demo_get_tm(&demo_now);
	struct tm *now = &demo_now;
#else
	time_t now_t = time(NULL);
	struct tm *now = localtime(&now_t);
#endif
	if (now) {
		time_layer_update(s_time_layer, now, settings_get());
		daylight_layer_set_current_time(s_daylight_layer, now->tm_hour, now->tm_min);
	}

	// Restore cached weather if available
	prv_push_weather_to_layers(now);
}

static void prv_window_unload(Window *window) {
	daylight_layer_destroy(s_daylight_layer);
	cloud_layer_destroy(s_cloud_layer);
	precip_layer_destroy(s_precip_layer);
	event_layer_destroy(s_event_layer);
	time_layer_destroy(s_time_layer);
	temp_layer_destroy(s_temp_layer);
}

//
// App lifecycle
//

static void init(void) {
	setlocale(LC_ALL, ""); // Use watch system locale for date formatting
	settings_init();

	// Restore persisted weather before anything renders
	memset(&s_weather, 0, sizeof(s_weather));
	s_weather.current_temp = WEATHER_TEMP_INVALID;
	s_weather.weather_code = WEATHER_CODE_INVALID;
#if defined(DEMO_SCENARIO)
	demo_data_load(&s_weather, settings_get());
#else
	if (persist_exists(STORAGE_KEY_WEATHER)) {
		persist_read_data(STORAGE_KEY_WEATHER, &s_weather, 256);
		if (persist_exists(STORAGE_KEY_WEATHER_PART2)) {
			persist_read_data(STORAGE_KEY_WEATHER_PART2,
			                  ((uint8_t *)&s_weather) + 256,
			                  sizeof(s_weather) - 256);
		}
	}
#endif

	s_main_window = window_create();
	window_set_background_color(s_main_window,
	                            settings_get()->light_theme ? GColorWhite
	                                                        : GColorBlack);
	window_set_window_handlers(s_main_window, (WindowHandlers){
	                                              .load = prv_window_load,
	                                              .unload = prv_window_unload,
	                                          });
	window_stack_push(s_main_window, true);

	tick_timer_service_subscribe(MINUTE_UNIT, prv_tick_handler);
	battery_state_service_subscribe(prv_battery_handler);
	connection_service_subscribe((ConnectionHandlers){
	    .pebble_app_connection_handler = prv_bt_handler,
	});

	// AppMessage: register callbacks BEFORE opening
#if !defined(DEMO_SCENARIO)
	app_message_register_inbox_received(prv_inbox_received);
	app_message_register_inbox_dropped(prv_inbox_dropped);
	app_message_register_outbox_failed(prv_outbox_failed);
	app_message_open(2048, 256);
#endif

	// Trigger initial weather fetch
#if !defined(DEMO_SCENARIO)
	prv_request_weather();
#endif
}

static void deinit(void) {
	tick_timer_service_unsubscribe();
	battery_state_service_unsubscribe();
	connection_service_unsubscribe();
	window_destroy(s_main_window);
}

int main(void) {
	init();
	app_event_loop();
	deinit();
	return 0;
}
