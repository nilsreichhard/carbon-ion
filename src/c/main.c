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
#include "ui/arc.h"
#include "modules/touch.h"
#include "ui/graph_common.h"
#include "ui/precip_layer.h"
#include "modules/precip_map.h"
#include "ui/temp_layer.h"
#include "ui/time_layer.h"
#include <pebble.h>
#include <stddef.h>

#include "modules/storage_keys.h" /* 3.0.3: all persist keys in one place */
/* Weather header {version, size, fetch_time}: fetch_time lives there so the
 * data blocks are only rewritten when the forecast itself changes. */
#define WEATHER_PERSIST_VERSION 7 /* 3: + fog_bits, wx_flags (2.8.0); 4: + wind_bits (2.9.3); 5: + storm_bits (2.9.4); 6: + precip_mm (2.9.6); 7: + thick_bits (3.0.0) */
/* Oldest layout that is a prefix of the current one (fields only appended):
 * such data is kept and the newer fields start zeroed (3.0.0). */
#define WEATHER_PERSIST_MIN_PREFIX 5
#define WEATHER_BLOCKS 4
typedef struct {
	uint16_t version;
	uint16_t size;
	int32_t fetch_time;
} WeatherPersistHdr;

// graph stack is the combined height of daylight+cloud+precip+event — also
// used for the icon bar overlay and the temp layer so all three match. Must be
// tall enough to fit the icon slots: >= 228 uses 22px icons (need 66px+),
// middle tier uses 18px icons (56px gives zone_h=18), small uses 14px icons.
#if defined(PBL_ROUND)
/* Gabbro arcs (2.8.6): daylight row trimmed to 13 (track 2 rows from the
 * outer edge, clouds 6 rows under the track); band depth 55. */
#define DAYLIGHT_H 13
#define CLOUD_H 15
/* 2.9.6: rain ring 15 -> 19 (band depth 59); the ring's inner edge still
 * clears the city line by 23 px at the top centre. */
#define PRECIP_H 19
#define EVENT_H 12
#elif defined(PBL_PLATFORM_EMERY) || (defined(PBL_DISPLAY_HEIGHT) && PBL_DISPLAY_HEIGHT >= 228)
#define DAYLIGHT_H 18
#define CLOUD_H 14
/* 2.9.6: rain chart 26 -> 29: the 3 px TIME_BLOCK_TOP_GAP moved into it.
 * The city line keeps its y (ink at row 76); the event row's lowest ink
 * (row 72) still clears it by 3 px of the font's own top padding. */
#define PRECIP_H 29
#define EVENT_H 12
// Sums to 73 — time block stays at y 73 with TIME_BLOCK_TOP_GAP 0
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
#define GRAPH_LAYERS_H_BASE (DAYLIGHT_H + CLOUD_H + PRECIP_H + EVENT_H)
/* Gap between bottom of rain/events stack and top of time block (city). */
#if defined(PBL_PLATFORM_EMERY)
#define TIME_BLOCK_TOP_GAP 0 /* 2.9.6: was 3, given to the rain chart */
#else
#define TIME_BLOCK_TOP_GAP 3
#endif

#if defined(PBL_PLATFORM_EMERY)
#define TEMP_H 56
#else
#define TEMP_H 48
#endif

/* Round (gabbro, 2.8.1): the strips render straight into a scratch band
 * (ARC_SRC_*) and the arc warp layer re-projects them onto arcs concentric
 * with the bezel. STACK_TOP is the virtual stack origin. Rect: unchanged. */
#define STACK_TOP 0
/* Weather older than this is marked as stale (offline condition icon). */
#define WEATHER_STALE_SEC (6 * 3600)
/* Older than this (or past the last hourly slot) shows "--" (2.8.7). */
#define WEATHER_EXPIRED_SEC (24 * 3600)
/* High/low window: the next N hours from now. */
#define HILO_WINDOW_H 24
#define STACK_BOTTOM_PAD 0

/* 2.8.3 (emery): clouds/sun sit 1 px higher, into the empty bottom row of the
 * daylight strip; the rain chart gains that row. */
#if defined(PBL_PLATFORM_EMERY)
#define CLOUD_LIFT 1
#else
#define CLOUD_LIFT 0
#endif
/* 2.9.6: clouds (and their sun rays) sit CLOUD_RAISE px higher, closer to the
 * timeline; the rain chart starts that much higher too and gains the rows.
 * Event row, city line and everything below keep their positions. */
#define CLOUD_RAISE 2

/* Frame for a full-width row at y..y+h (screen coords). */
static GRect prv_row_frame(GRect bounds, int y, int h) {
#if defined(PBL_ROUND)
	(void)bounds;
	return GRect(ARC_SRC_X, ARC_SRC_Y + (y - STACK_TOP), ARC_SRC_W, h);
#else
	return GRect(0, y, bounds.size.w, h);
#endif
}

static GRect prv_temp_frame(GRect bounds) {
#if defined(PBL_ROUND)
	return GRect(ARC_SRC_X, ARC_SRC_Y + GRAPH_LAYERS_H_BASE, ARC_SRC_W, TEMP_H);
#else
	return prv_row_frame(bounds, bounds.size.h - STACK_BOTTOM_PAD - TEMP_H, TEMP_H);
#endif
}

static Window *s_main_window;
static DaylightLayer *s_daylight_layer;
static CloudLayer *s_cloud_layer;
static PrecipLayer *s_precip_layer;
static EventLayer *s_event_layer;
static TimeLayer *s_time_layer;
static TempLayer *s_temp_layer;
#if defined(PBL_ROUND)
static Layer *s_arc_layer;
#endif

static WeatherData s_weather;
static uint32_t s_request_seq;
static uint32_t s_last_sent_request_seq;
static uint32_t s_last_answered_seq;
#if !defined(DEMO_SCENARIO)
static uint32_t s_minutes_since_launch;
#endif

// Forward declarations
static void prv_request_weather(void);
/* 3.0.0: views are static (14 x 73 B off the stack); memset below. */
	static void prv_push_weather_to_layers(struct tm *now);
static void prv_relayout_graph_stack(void);
static void prv_update_steps(void);
static void prv_apply_timeline_events(DictionaryIterator *iter);

#if defined(PBL_HEALTH)
static void prv_health_event_handler(HealthEventType event, void *context) {
	if (event == HealthEventMovementUpdate) {
		prv_update_steps();
	}
}
#endif

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
	prv_update_steps();
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
	if (interval < 15) interval = DEFAULT_FETCH_INTERVAL_MIN;
	if (s_minutes_since_launch % interval == 0) {
		prv_request_weather();
	}
#endif
}

/* 3.0.3: helpers split out of prv_push_weather_to_layers (no behaviour change). */

/* Hours elapsed since the top of the fetch hour (slot WEATHER_PAST_HOURS is
 * the local hour the fetch happened in). */
static int prv_weather_data_offset(void) {
	int data_offset = 0;
	if (s_weather.fetch_time > 0) {
		/* Hourly slot WEATHER_PAST_HOURS is the local hour the fetch happened
		 * in, so count from the top of that hour, not the exact fetch time. */
		time_t fetch_t = s_weather.fetch_time;
		struct tm *ft = localtime(&fetch_t);
		time_t hour_top = fetch_t - (ft ? (ft->tm_min * 60 + ft->tm_sec) : 0);
		time_t now_t = time(NULL);
		long elapsed = (long)(now_t - hour_top);
		if (elapsed > 0) {
			data_offset = (int)(elapsed / 3600);
		}
	}
	if (data_offset < 0)
		data_offset = 0;
	return data_offset;
}

typedef struct {
	uint8_t precip[MAX_GRAPH_HOURS + 1];
	int8_t temp[MAX_GRAPH_HOURS + 1];
	int8_t appar[MAX_GRAPH_HOURS + 1];
	uint8_t cloud[MAX_GRAPH_HOURS + 1];
	uint8_t cloud_low[MAX_GRAPH_HOURS + 1];
	uint8_t cloud_mid[MAX_GRAPH_HOURS + 1];
	uint8_t cloud_high[MAX_GRAPH_HOURS + 1];
	uint8_t code[MAX_GRAPH_HOURS + 1];
	uint8_t shortwave[MAX_GRAPH_HOURS + 1];
	uint8_t mm[MAX_GRAPH_HOURS + 1];
	uint8_t thick[MAX_GRAPH_HOURS + 1];
	uint8_t fog[MAX_GRAPH_HOURS + 1];
	uint8_t wind[MAX_GRAPH_HOURS + 1];
	uint8_t storm[MAX_GRAPH_HOURS + 1];
} WxViews;

static void prv_bit_view(uint8_t *dst, const uint8_t *bits, int start_idx, int copy_len) {
	for (int i = 0; i < copy_len; i++) {
		int k = start_idx + i;
		dst[i] = (bits[k >> 3] >> (k & 7)) & 1;
	}
}

/* Offset-shifted copies of the hourly arrays; static: off the stack (3.0.0). */
static WxViews *prv_fill_views(int start_idx, int copy_len) {
	static WxViews v;
	memset(&v, 0, sizeof(v));
	memcpy(v.precip, &s_weather.precip_prob[start_idx], copy_len);
	memcpy(v.temp, &s_weather.temp_hourly[start_idx], copy_len);
	memcpy(v.appar, &s_weather.apparent_temp_hourly[start_idx], copy_len);
	memcpy(v.cloud, &s_weather.cloud_cover[start_idx], copy_len);
	memcpy(v.cloud_low, &s_weather.cloud_cover_low[start_idx], copy_len);
	memcpy(v.cloud_mid, &s_weather.cloud_cover_mid[start_idx], copy_len);
	memcpy(v.cloud_high, &s_weather.cloud_cover_high[start_idx], copy_len);
	memcpy(v.code, &s_weather.hourly_weather_code[start_idx], copy_len);
	memcpy(v.shortwave, &s_weather.shortwave_radiation[start_idx], copy_len);
	memcpy(v.mm, &s_weather.precip_mm[start_idx], copy_len);
	for (int i = 0; i < copy_len; i++)
		v.thick[i] = cloud_thick_unpack(s_weather.thick_bits, start_idx + i);
	prv_bit_view(v.fog, s_weather.fog_bits, start_idx, copy_len);
	prv_bit_view(v.wind, s_weather.wind_bits, start_idx, copy_len);
	prv_bit_view(v.storm, s_weather.storm_bits, start_idx, copy_len);
	return &v;
}

/* High/low over a rolling HILO_WINDOW_H hours from now (2.8.5), from the
 * hourly slots; falls back to the fetched values without data. */
static void prv_window_hilo(int now_idx, int16_t display_temp, int16_t *hi_out, int16_t *lo_out) {
	int16_t hi = s_weather.high_temp, lo = s_weather.low_temp;
	bool any = false;
	int end = now_idx + HILO_WINDOW_H;
	if (end > (int)s_weather.valid_hours)
		end = s_weather.valid_hours;
	for (int i = now_idx; i < end && i < WEATHER_HOURLY_COUNT; i++) {
		int16_t t = s_weather.temp_hourly[i];
		if (!any || t > hi) hi = t;
		if (!any || t < lo) lo = t;
		any = true;
	}
	if (any && display_temp != WEATHER_TEMP_INVALID) {
		if (display_temp > hi) hi = display_temp;
		if (display_temp < lo) lo = display_temp;
	}
	*hi_out = hi;
	*lo_out = lo;
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
		daylight_layer_set_data(s_daylight_layer, 6, 0, 18, 0, current_hour, true,
		                        true);
		temp_layer_set_current_hour(s_temp_layer, current_hour, 0);
		temp_layer_set_expired(s_temp_layer, false);
		time_layer_set_condition(s_time_layer,
		                         WEATHER_CONDITION_UNKNOWN, true);
		layer_mark_dirty(window_get_root_layer(s_main_window));
		return;
	}

	int data_offset = prv_weather_data_offset();
	/* Unclamped: past the provider's last valid slot the numbers expire. */
	bool data_ran_out = WEATHER_PAST_HOURS + data_offset >= (int)s_weather.valid_hours;
	if (data_offset >= WEATHER_HOURLY_COUNT)
		data_offset = WEATHER_HOURLY_COUNT - 1;

	/* Clamp the range (Clay value or flick) to the provider's real horizon. */
	{
		int ahead = (int)s_weather.valid_hours - WEATHER_PAST_HOURS - 1 - data_offset; /* points beyond now */
		g_wx_horizon_h = (s_weather.valid_hours == 0 || s_weather.valid_hours >= WEATHER_HOURLY_COUNT)
		                     ? 255 : (uint8_t)(ahead < 12 ? 12 : ahead);
	}
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
	WxViews *v = prv_fill_views(start_idx, copy_len);

	/* Providers with a shorter horizon pad the tail: draw nothing there. */
	int valid_left = (int)s_weather.valid_hours - start_idx;
	if (valid_left < 1)
		valid_left = 1;
	uint8_t hours_remaining = copy_len < valid_left ? copy_len : valid_left;
	bool layers = !(s_weather.wx_flags & WX_FLAG_NO_CLOUD_LAYERS);
	if (layers != g_cloud_layers_available) {
		g_cloud_layers_available = layers;
		prv_relayout_graph_stack(); /* Split strip height may change */
	}

	int now_idx = WEATHER_PAST_HOURS + data_offset;
	if (now_idx >= WEATHER_HOURLY_COUNT)
		now_idx = WEATHER_HOURLY_COUNT - 1;

	/* The fetch's "current" values only describe the fetch hour; once the
	 * clock has moved on, read the hourly slot for now (2.8.5). */
	bool fetch_hour = (data_offset == 0);
	int16_t display_temp = (fetch_hour && s_weather.current_temp != WEATHER_TEMP_INVALID)
	                           ? s_weather.current_temp
	                           : (int16_t)s_weather.temp_hourly[now_idx];
	uint8_t display_code = (fetch_hour && s_weather.weather_code != WEATHER_CODE_INVALID)
	                           ? s_weather.weather_code
	                           : s_weather.hourly_weather_code[now_idx];

	/* High/low cover a rolling HILO_WINDOW_H hours from now (2.8.5), taken
	 * from the hourly slots; falls back to the fetched values without data. */
	int16_t hi, lo;
	prv_window_hilo(now_idx, display_temp, &hi, &lo);

	/* Weather older than WEATHER_STALE_SEC is marked: the condition icon
	 * shows the "offline" glyph until fresh data arrives. */
	bool stale = s_weather.fetch_time > 0 &&
	             (long)(time(NULL) - s_weather.fetch_time) > WEATHER_STALE_SEC;
	bool expired = data_ran_out ||
	               (s_weather.fetch_time > 0 &&
	                (long)(time(NULL) - s_weather.fetch_time) > WEATHER_EXPIRED_SEC);
	temp_layer_set_expired(s_temp_layer, expired);

	int now_min = (int)current_hour * 60 + (now ? now->tm_min : 0);
	int rise_min = (int)s_weather.sunrise_hour * 60 + s_weather.sunrise_minute;
	int set_min = (int)s_weather.sunset_hour * 60 + s_weather.sunset_minute;
	bool is_day = (now_min >= rise_min && now_min < set_min);
	daylight_layer_set_data(s_daylight_layer, s_weather.sunrise_hour,
	                        s_weather.sunrise_minute, s_weather.sunset_hour,
	                        s_weather.sunset_minute, current_hour, false, false);
	cloud_layer_set_data(s_cloud_layer, v->cloud, v->code, v->shortwave,
	                       v->cloud_low, v->cloud_mid, v->cloud_high,
	                       v->thick, v->mm, (s_weather.wx_flags & WX_FLAG_HAS_MM) != 0,
	                       current_hour);
	precip_layer_set_data(s_precip_layer, v->precip, v->code, v->mm,
	                      (s_weather.wx_flags & WX_FLAG_HAS_MM) != 0, current_hour);
	event_layer_set_data(s_event_layer, v->code, v->fog, v->wind, v->storm, hours_remaining);
	time_layer_set_condition(s_time_layer,
	                         stale ? WEATHER_CONDITION_UNKNOWN
	                               : weather_code_to_condition(display_code),
	                         is_day);
	temp_layer_set_unit(s_temp_layer, settings_get()->temp_unit_celsius);
	temp_layer_set_data(s_temp_layer, display_temp, hi,
	                    lo, v->temp, v->appar, current_hour,
	                    hours_remaining);
	time_layer_set_city(s_time_layer, s_weather.city_name);
}

/**
 * Parse timeline event start/end timestamp arrays from AppMessage.
 */
#define MAX_TIMELINE_EVENTS_MSG MAX_TIMELINE_EVENTS

static uint32_t prv_read_uint32_le(const uint8_t *bytes, int offset, int total_len) {
	if (!bytes || offset < 0 || offset + 4 > total_len)
		return 0;
	return (uint32_t)bytes[offset] | ((uint32_t)bytes[offset + 1] << 8) |
	       ((uint32_t)bytes[offset + 2] << 16) |
	       ((uint32_t)bytes[offset + 3] << 24);
}

/* Skip flash writes when the stored blob is already identical. */
static void prv_persist_write_if_changed(uint32_t key, const void *data, int len) {
	if (len <= 0 || len > PERSIST_DATA_MAX_LENGTH)
		return;
	uint8_t cur[PERSIST_DATA_MAX_LENGTH];
	if (persist_exists(key) && persist_get_size(key) == len &&
	    persist_read_data(key, cur, len) == len && memcmp(cur, data, len) == 0)
		return;
	persist_write_data(key, data, len);
}

static const uint32_t s_weather_keys[WEATHER_BLOCKS] = {
	STORAGE_KEY_WEATHER, STORAGE_KEY_WEATHER_PART2, STORAGE_KEY_WEATHER_PART3,
	STORAGE_KEY_WEATHER_PART4};

static WeatherData s_weather_scratch; /* static: keep ~700 B off the stack */

/* Blocks hold WeatherData with fetch_time zeroed; header carries fetch_time. */
static void prv_weather_persist(void) {
	s_weather_scratch = s_weather;
	s_weather_scratch.fetch_time = 0;
	const uint8_t *raw = (const uint8_t *)&s_weather_scratch;
	int remain = (int)sizeof(s_weather_scratch);
	for (int i = 0; i < WEATHER_BLOCKS && remain > 0; i++) {
		int n = remain < PERSIST_DATA_MAX_LENGTH ? remain : PERSIST_DATA_MAX_LENGTH;
		prv_persist_write_if_changed(s_weather_keys[i], raw + i * PERSIST_DATA_MAX_LENGTH, n);
		remain -= n;
	}
	WeatherPersistHdr hdr = {WEATHER_PERSIST_VERSION, (uint16_t)sizeof(WeatherData),
	                         (int32_t)s_weather.fetch_time};
	prv_persist_write_if_changed(STORAGE_KEY_WEATHER_HDR, &hdr, sizeof(hdr));
}

/* Read `size` bytes of blocks into dst; false unless every block has exactly
 * the expected size. */
static bool prv_weather_read_blocks(WeatherData *dst, int size) {
	uint8_t *raw = (uint8_t *)dst;
	int remain = size;
	for (int i = 0; i < WEATHER_BLOCKS && remain > 0; i++) {
		int n = remain < PERSIST_DATA_MAX_LENGTH ? remain : PERSIST_DATA_MAX_LENGTH;
		if (!persist_exists(s_weather_keys[i]) || persist_get_size(s_weather_keys[i]) != n ||
		    persist_read_data(s_weather_keys[i], raw + i * PERSIST_DATA_MAX_LENGTH, n) != n)
			return false;
		remain -= n;
	}
	return true;
}

static void prv_weather_restore(void) {
	WeatherPersistHdr hdr;
	bool have_hdr = persist_exists(STORAGE_KEY_WEATHER_HDR) &&
	                persist_get_size(STORAGE_KEY_WEATHER_HDR) == (int)sizeof(hdr) &&
	                persist_read_data(STORAGE_KEY_WEATHER_HDR, &hdr, sizeof(hdr)) == (int)sizeof(hdr);
	int size = (int)sizeof(WeatherData);
	if (have_hdr && (hdr.version != WEATHER_PERSIST_VERSION || hdr.size != sizeof(WeatherData))) {
		/* 3.0.0: an older appended-only layout keeps its data; the newer
		 * tail (e.g. precip_mm, thick_bits) starts zeroed = "no data". */
		if (hdr.version < WEATHER_PERSIST_MIN_PREFIX || hdr.version >= WEATHER_PERSIST_VERSION ||
		    hdr.size == 0 || hdr.size >= sizeof(WeatherData))
			return; /* unknown layout: start empty rather than misread */
		size = hdr.size;
	}
	memset(&s_weather_scratch, 0, sizeof(s_weather_scratch));
	if (!prv_weather_read_blocks(&s_weather_scratch, size))
		return; /* missing or partial (pre-2.7.6 sizes differ only if layout changed) */
	s_weather_scratch.city_name[sizeof(s_weather_scratch.city_name) - 1] = '\0';
	/* Fields newer than the stored layout may sit in its tail padding: clear them. */
	if (have_hdr && hdr.version < 7)
		memset(s_weather_scratch.thick_bits, 0, sizeof(s_weather_scratch.thick_bits));
	if (have_hdr && hdr.version < 6) {
		memset(s_weather_scratch.precip_mm, 0, sizeof(s_weather_scratch.precip_mm));
		s_weather_scratch.wx_flags &= (uint8_t)~WX_FLAG_HAS_MM;
	}
	if (s_weather_scratch.valid_hours > WEATHER_HOURLY_COUNT)
		s_weather_scratch.valid_hours = WEATHER_HOURLY_COUNT;
	if (have_hdr)
		s_weather_scratch.fetch_time = (time_t)hdr.fetch_time;
	/* else: pre-2.7.6 blob already carries fetch_time; migrate to the header format. */
	s_weather = s_weather_scratch;
	if (!have_hdr || size != (int)sizeof(WeatherData))
		prv_weather_persist(); /* rewrite in the current layout */
}

static void prv_clear_timeline_events(void) {
	if (persist_exists(STORAGE_KEY_EVENTS))
		persist_delete(STORAGE_KEY_EVENTS);
	if (s_daylight_layer) {
		daylight_layer_set_events(s_daylight_layer, NULL, 0);
	}
}

static void prv_apply_timeline_events(DictionaryIterator *iter) {
	Tuple *starts = dict_find(iter, MESSAGE_KEY_TIMELINE_EVENT_STARTS);
	Tuple *ends = dict_find(iter, MESSAGE_KEY_TIMELINE_EVENT_ENDS);
	Tuple *colors = dict_find(iter, MESSAGE_KEY_TIMELINE_EVENT_COLORS);
	Tuple *places = dict_find(iter, MESSAGE_KEY_TIMELINE_EVENT_PLACES);

	TimelineEvent events[MAX_TIMELINE_EVENTS_MSG];
	uint8_t count = 0;

	if (starts && starts->type == TUPLE_BYTE_ARRAY) {
		if (starts->length >= 4) {
			int num = starts->length / 4;
			if (num > MAX_TIMELINE_EVENTS_MSG)
				num = MAX_TIMELINE_EVENTS_MSG;
			for (int i = 0; i < num; i++) {
				uint32_t start_ts =
				    prv_read_uint32_le(starts->value->data, i * 4, starts->length);
				events[i].start_time = start_ts;
				events[i].end_time = start_ts + 3600;
				events[i].color = 0;
				events[i].place = 0;
				count++;
			}
		}
		// Empty byte array is an intentional clear (calendar off / URL cleared /
		// successful empty fetch). Do not treat as "omit keys".
	} else {
		// Settings-only messages omit timeline keys; keep cached events.
		return;
	}

	if (ends && ends->type == TUPLE_BYTE_ARRAY && count > 0) {
		for (uint8_t i = 0; i < count; i++) {
			if (ends->length >= (i + 1) * 4) {
				events[i].end_time =
				    prv_read_uint32_le(ends->value->data, i * 4, ends->length);
			}
		}
	}

	if (colors && colors->type == TUPLE_BYTE_ARRAY && count > 0) {
		const uint8_t *color_bytes = colors->value->data;
		int color_len = colors->length;
		for (uint8_t i = 0; i < count; i++) {
			if (i < color_len) {
				events[i].color = color_bytes[i] & 0x0F;
			}
		}
	}

	if (places && places->type == TUPLE_BYTE_ARRAY && count > 0) {
		const uint8_t *place_bytes = places->value->data;
		int place_len = places->length;
		for (uint8_t i = 0; i < count; i++) {
			if (i < place_len) {
				uint8_t pl = place_bytes[i] & 0x03;
				events[i].place = (pl > 2) ? 0 : pl;
			}
		}
	}

	if (count > 0) {
		prv_persist_write_if_changed(STORAGE_KEY_EVENTS, events, count * sizeof(TimelineEvent));
		if (s_daylight_layer) {
			daylight_layer_set_events(s_daylight_layer, events, count);
		}
	} else {
		prv_clear_timeline_events();
	}
}


/* ---- 2.7.x: backlight-aware colors + tap / wrist-flick range cycling ---- */
static void prv_full_refresh(void) {
	time_t now_t = time(NULL);
	struct tm *now = localtime(&now_t);
	if (now) {
		time_layer_update(s_time_layer, now, settings_get());
		prv_push_weather_to_layers(now);
	}
	prv_relayout_graph_stack();
}

/* High-contrast flag changed: re-colour time text and redraw — no relayout. */
static void prv_redraw_contrast(void) {
	if (!s_main_window)
		return;
	time_t now_t = time(NULL);
	struct tm *now = localtime(&now_t);
	if (now && s_time_layer)
		time_layer_update(s_time_layer, now, settings_get());
	layer_mark_dirty(window_get_root_layer(s_main_window));
}

static void prv_set_high_contrast(bool hc) {
	if (hc == g_high_contrast)
		return;
	g_high_contrast = hc;
	prv_redraw_contrast();
}

#if defined(CARBON_MODERN_SERVICES)
static void prv_backlight_handler(bool on) {
	prv_set_high_contrast(on && settings_get()->backlight_colors);
}
#endif

/* Wrist flick / double-tap action (Clay "Wrist flick action"). */
static void prv_cycle_range(void) {
	if (settings_get()->flick_action == FLICK_ACTION_CLOUDS) {
		if (settings_toggle_cloud_mode())
			prv_relayout_graph_stack();
		return; /* provider without cloud layers: ignored */
	}
	settings_cycle_forecast();
	prv_full_refresh();
}

static uint32_t prv_now_ms(void) {
	time_t sec;
	uint16_t ms;
	time_ms(&sec, &ms);
	return (uint32_t)sec * 1000u + ms;
}

#define FLICK_DEBOUNCE_MS 2000
static uint32_t s_last_flick_ms;
static void prv_accel_tap_handler(AccelAxisType axis, int32_t direction) {
	(void)axis;
	(void)direction;
	uint32_t now = prv_now_ms();
	if (s_last_flick_ms != 0 && now - s_last_flick_ms < FLICK_DEBOUNCE_MS)
		return;
	s_last_flick_ms = now;
	prv_cycle_range();
}

static bool s_accel_on, s_backlight_on;

/* Idempotent: (un)subscribe input services to match settings. Touch is only
 * compiled in with CARBON_TOUCH_ENABLED (firmware does not deliver touch to
 * watchfaces yet); wrist flick and backlight are opt-in settings. */
static void prv_apply_input_services(void) {
	Settings *st = settings_get();
	bool hc = false;
#if defined(CARBON_MODERN_SERVICES)
	if (st->backlight_colors && !s_backlight_on) {
		backlight_service_subscribe(prv_backlight_handler);
		s_backlight_on = true;
	} else if (!st->backlight_colors && s_backlight_on) {
		backlight_service_unsubscribe();
		s_backlight_on = false;
	}
	hc = st->backlight_colors && light_is_on();

#if defined(CARBON_TOUCH_ENABLED)
	touch_input_apply(s_main_window, st->tap_cycle, prv_cycle_range);
#endif
#endif
	prv_set_high_contrast(hc);

	/* Wrist flick is its own opt-in (it also triggers motion backlight). */
	if (st->wrist_flick && !s_accel_on) {
		accel_tap_service_subscribe(prv_accel_tap_handler);
		s_accel_on = true;
	} else if (!st->wrist_flick && s_accel_on) {
		accel_tap_service_unsubscribe();
		s_accel_on = false;
	}
}

static void prv_unsubscribe_input_services(void) {
#if defined(CARBON_MODERN_SERVICES)
	touch_input_stop();
	if (s_backlight_on)
		backlight_service_unsubscribe();
#endif
	if (s_accel_on)
		accel_tap_service_unsubscribe();
	s_backlight_on = s_accel_on = false;
}

/**
 * Handle incoming AppMessage; parse weather fields and push to layers if
 * complete.
 *
 * @param iter     Incoming message dictionary iterator.
 * @param context  Unused callback context.
 */
/* 3.0.3: prv_inbox_received split into helpers (no behaviour change). */
typedef struct { bool temp, code, hi, lo; } WxGot;

/* Scalar weather fields; returns true when any of them marks a weather message. */
static bool prv_inbox_weather_scalars(DictionaryIterator *iter, WxGot *g) {
	Tuple *t;
	bool got_weather = false;

	t = dict_find(iter, MESSAGE_KEY_WEATHER_TEMP);
	if (t) {
		s_weather.current_temp = (int16_t)settings_tuple_int(t);
		got_weather = g->temp = true;
	}
	t = dict_find(iter, MESSAGE_KEY_WEATHER_TEMP_HIGH);
	if (t) {
		g->hi = true;
		s_weather.high_temp = (int16_t)settings_tuple_int(t);
		got_weather = true;
	}
	t = dict_find(iter, MESSAGE_KEY_WEATHER_TEMP_LOW);
	if (t) {
		g->lo = true;
		s_weather.low_temp = (int16_t)settings_tuple_int(t);
		got_weather = true;
	}
	t = dict_find(iter, MESSAGE_KEY_WEATHER_CODE);
	if (t) {
		s_weather.weather_code = (uint8_t)settings_tuple_int(t);
		got_weather = g->code = true;
	}
	t = dict_find(iter, MESSAGE_KEY_WEATHER_SUNRISE_HOUR);
	if (t)
		s_weather.sunrise_hour = (uint8_t)settings_tuple_int(t);
	t = dict_find(iter, MESSAGE_KEY_WEATHER_SUNSET_HOUR);
	if (t)
		s_weather.sunset_hour = (uint8_t)settings_tuple_int(t);
	t = dict_find(iter, MESSAGE_KEY_WEATHER_SUNRISE_MINUTE);
	if (t) {
		int m = settings_tuple_int(t);
		s_weather.sunrise_minute = (uint8_t)(m < 0 ? 0 : (m > 59 ? 59 : m));
	}
	t = dict_find(iter, MESSAGE_KEY_WEATHER_SUNSET_MINUTE);
	if (t) {
		int m = settings_tuple_int(t);
		s_weather.sunset_minute = (uint8_t)(m < 0 ? 0 : (m > 59 ? 59 : m));
	}
	return got_weather;
}

/* Hourly byte arrays (one table instead of nine copies). */
static bool prv_inbox_hourly_arrays(DictionaryIterator *iter) {
	Tuple *t;
	bool got_weather = false;
	struct { uint32_t key; void *dst; } arrs[] = {
		{MESSAGE_KEY_WEATHER_PRECIP_PROB, s_weather.precip_prob},
		{MESSAGE_KEY_WEATHER_TEMP_HOURLY, s_weather.temp_hourly},
		{MESSAGE_KEY_WEATHER_APPARENT_TEMP_HOURLY, s_weather.apparent_temp_hourly},
		{MESSAGE_KEY_WEATHER_CLOUD_COVER, s_weather.cloud_cover},
		{MESSAGE_KEY_WEATHER_CLOUD_COVER_LOW, s_weather.cloud_cover_low},
		{MESSAGE_KEY_WEATHER_CLOUD_COVER_MID, s_weather.cloud_cover_mid},
		{MESSAGE_KEY_WEATHER_CLOUD_COVER_HIGH, s_weather.cloud_cover_high},
		{MESSAGE_KEY_WEATHER_HOURLY_CODE, s_weather.hourly_weather_code},
		{MESSAGE_KEY_WEATHER_SHORTWAVE_RADIATION, s_weather.shortwave_radiation},
		{MESSAGE_KEY_WEATHER_PRECIP_MM, s_weather.precip_mm},
	};
	for (unsigned i = 0; i < ARRAY_LENGTH(arrs); i++) {
		t = dict_find(iter, arrs[i].key);
		if (t && t->type == TUPLE_BYTE_ARRAY && t->length > 0) {
			int len = t->length < WEATHER_HOURLY_COUNT ? t->length : WEATHER_HOURLY_COUNT;
			memcpy(arrs[i].dst, t->value->data, len);
			got_weather = true;
		}
	}
	return got_weather;
}

/* Packed hourly bit arrays: cleared, then filled from the message if present. */
static void prv_inbox_bits(DictionaryIterator *iter, uint32_t key, uint8_t *dst, int size) {
	Tuple *t = dict_find(iter, key);
	memset(dst, 0, size);
	if (t && t->type == TUPLE_BYTE_ARRAY && t->length > 0)
		memcpy(dst, t->value->data, t->length < size ? t->length : size);
}

/* A weather message arrived: fill the rest of s_weather and persist it. */
static void prv_inbox_commit_weather(DictionaryIterator *iter, Tuple *ft, const WxGot *g) {
	Tuple *t;
	/* Absent scalars mean "no value": fall back to the hourly slot. */
	if (!g->temp)
		s_weather.current_temp = WEATHER_TEMP_INVALID;
	if (!g->code)
		s_weather.weather_code = WEATHER_CODE_INVALID;
	/* 2.8.7: a missing high/low must not keep an older fetch's value. */
	if (!g->hi)
		s_weather.high_temp = WEATHER_TEMP_INVALID;
	if (!g->lo)
		s_weather.low_temp = WEATHER_TEMP_INVALID;
	s_weather.fetch_time = ft ? (time_t)settings_tuple_int(ft) : time(NULL);

	s_weather.is_valid = true;
	s_weather.valid_hours = WEATHER_HOURLY_COUNT;
	t = dict_find(iter, MESSAGE_KEY_WEATHER_VALID_HOURS);
	if (t) {
		int vh = settings_tuple_int(t);
		if (vh >= 1 && vh <= WEATHER_HOURLY_COUNT)
			s_weather.valid_hours = (uint8_t)vh;
	}
	prv_inbox_bits(iter, MESSAGE_KEY_WEATHER_FOG, s_weather.fog_bits, sizeof(s_weather.fog_bits));
	prv_inbox_bits(iter, MESSAGE_KEY_WEATHER_WIND, s_weather.wind_bits, sizeof(s_weather.wind_bits)); /* 2.9.3 */
	prv_inbox_bits(iter, MESSAGE_KEY_WEATHER_STORM, s_weather.storm_bits, sizeof(s_weather.storm_bits)); /* 2.9.4 */
	prv_inbox_bits(iter, MESSAGE_KEY_WEATHER_CLOUD_THICK, s_weather.thick_bits, sizeof(s_weather.thick_bits)); /* 3.0.0 */
	t = dict_find(iter, MESSAGE_KEY_WEATHER_FLAGS);
	s_weather.wx_flags = t ? (uint8_t)settings_tuple_int(t) : 0;
	s_last_answered_seq = s_last_sent_request_seq;

	prv_weather_persist();
}

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
	// Check for settings changes first
	settings_apply_from_message(iter);
	prv_apply_input_services();
	prv_relayout_graph_stack();
	temp_layer_set_unit(s_temp_layer, settings_get()->temp_unit_celsius);
	window_set_background_color(s_main_window,
	                            settings_get()->light_theme ? GColorWhite
	                                                        : GColorBlack);

	/* A message is weather when it carries any weather field (2.8.5: not only
	 * WEATHER_TEMP, which the phone omits when the current hour has no value). */
	WxGot got = {false, false, false, false};
	bool got_weather = prv_inbox_weather_scalars(iter, &got);

	if (prv_inbox_hourly_arrays(iter))
		got_weather = true;

	Tuple *t = dict_find(iter, MESSAGE_KEY_CITY_NAME);
	if (t && t->type == TUPLE_CSTRING) {
		strncpy(s_weather.city_name, t->value->cstring,
		        sizeof(s_weather.city_name) - 1);
		s_weather.city_name[sizeof(s_weather.city_name) - 1] = '\0';
	}

	prv_apply_timeline_events(iter);

	Tuple *ft = dict_find(iter, MESSAGE_KEY_WEATHER_FETCH_TIME);
	if (ft)
		got_weather = true;

	if (got_weather)
		prv_inbox_commit_weather(iter, ft, &got);

	// Apply settings and push weather to layers ONCE
	time_t now_s = time(NULL);
	struct tm *now_stm = localtime(&now_s);
	if (now_stm) {
		time_layer_update(s_time_layer, now_stm, settings_get());
		prv_update_steps();
		prv_push_weather_to_layers(now_stm);
	}
}

static void prv_inbox_dropped(AppMessageResult reason, void *context) {
	APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox dropped: %d", (int)reason);
}

/* 3.0.3: a failed weather request is retried once, about 30 s later. A new
 * scheduled request re-arms the single retry. */
#define WEATHER_RETRY_MS 30000
static AppTimer *s_weather_retry_timer = NULL;
static bool s_weather_retry_used = false;
static void prv_send_weather_request(bool is_retry);

static void prv_weather_retry_cb(void *context) {
	(void)context;
	s_weather_retry_timer = NULL;
	prv_send_weather_request(true);
}

static void prv_schedule_weather_retry(void) {
	if (s_weather_retry_used || s_weather_retry_timer)
		return;
	s_weather_retry_used = true;
	s_weather_retry_timer = app_timer_register(WEATHER_RETRY_MS, prv_weather_retry_cb, NULL);
	APP_LOG(APP_LOG_LEVEL_INFO, "Weather request retry in %d ms", WEATHER_RETRY_MS);
}

static void prv_outbox_failed(DictionaryIterator *failed,
                              AppMessageResult reason, void *context) {
	(void)context;
	APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox failed: reason=%d seq=%lu",
	        (int)reason, (unsigned long)s_last_sent_request_seq);
	if (failed && dict_find(failed, MESSAGE_KEY_WEATHER_REQUEST))
		prv_schedule_weather_retry();
}

static void prv_send_weather_request(bool is_retry) {
	if (!is_retry) {
		s_weather_retry_used = false;
		if (s_weather_retry_timer) {
			app_timer_cancel(s_weather_retry_timer);
			s_weather_retry_timer = NULL;
		}
	}
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
			prv_schedule_weather_retry();
		}
	} else {
		APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox begin failed: reason=%d seq=%lu",
		        (int)result, (unsigned long)seq);
		prv_schedule_weather_retry();
	}
}

static time_t s_last_req_time; /* 3.1.2: last weather request sent */
static void prv_request_weather(void) {
	s_last_req_time = time(NULL);
	prv_send_weather_request(false);
}

static void prv_battery_handler(BatteryChargeState state) {
	daylight_layer_set_battery(s_daylight_layer, state.charge_percent,
	                           state.is_charging, state.is_plugged);
}

static void prv_update_steps(void) {
	if (!s_time_layer)
		return;
#if defined(DEMO_SCENARIO)
	time_layer_set_steps(s_time_layer, 8420);
#elif defined(PBL_HEALTH)
	time_layer_set_steps(s_time_layer,
	                     (int)health_service_sum_today(HealthMetricStepCount));
#else
	time_layer_set_steps(s_time_layer, 0);
#endif
}

static void prv_bt_handler(bool connected) {
	if (s_time_layer) {
		time_layer_set_status(s_time_layer, connected, quiet_time_is_active());
	}
#if !defined(DEMO_SCENARIO)
	/* 3.1.2: on reconnect, refresh right away if the data is older than the
	 * refresh interval instead of waiting for the next interval tick. */
	if (connected) {
		int interval = settings_get()->fetch_interval_min;
		if (interval < 15)
			interval = DEFAULT_FETCH_INTERVAL_MIN;
		time_t now_t = time(NULL);
		/* Throttle: BT flapping must not spam AppMessages. */
		bool recent_req = s_last_req_time > 0 && (long)(now_t - s_last_req_time) < 90;
		if (!recent_req && (s_weather.fetch_time <= 0 ||
		    (long)(now_t - s_weather.fetch_time) >= (long)interval * 60))
			prv_request_weather();
	}
#endif
}


static int prv_cloud_strip_h_raw(void) {
	if (settings_effective_cloud_mode() == CLOUD_DISPLAY_SPLIT)
		return CLOUD_H * 2;
	return CLOUD_H;
}

static int prv_event_strip_h(void) {
	if (settings_get()->weather_event_display == WEATHER_EVENT_NONE)
		return 0;
	return EVENT_H;
}

/** Cloud + precip (+ optional event) heights; stack stays GRAPH_LAYERS_H_BASE. */
static void prv_top_strip_heights(int *cloud_h_out, int *precip_h_out,
                                  int *event_h_out) {
	int cloud_h = prv_cloud_strip_h_raw();
	int event_h = prv_event_strip_h();
	int precip_h = GRAPH_LAYERS_H_BASE - DAYLIGHT_H - cloud_h - event_h;
	if (precip_h < 8) {
		precip_h = 8;
		cloud_h = GRAPH_LAYERS_H_BASE - DAYLIGHT_H - precip_h - event_h;
		if (cloud_h < CLOUD_H)
			cloud_h = CLOUD_H;
	}
	*cloud_h_out = cloud_h;
	*precip_h_out = precip_h;
	*event_h_out = event_h;
}

#if !defined(PBL_ROUND)
static int prv_graph_layers_h(void) {
	int cloud_h, precip_h, event_h;
	prv_top_strip_heights(&cloud_h, &precip_h, &event_h);
	return DAYLIGHT_H + cloud_h + precip_h + event_h;
}
#endif

static int prv_time_y(GRect bounds) {
#if defined(PBL_ROUND)
	/* Centred between the two arc bands. */
	return (bounds.size.h - TL_TIME_BLOCK_H) / 2;
#else
	int graph_h = STACK_TOP + prv_graph_layers_h();
	int time_y = graph_h + TIME_BLOCK_TOP_GAP;
	int temp_y = bounds.size.h - STACK_BOTTOM_PAD - TEMP_H;
	if (time_y + TL_TIME_BLOCK_H > temp_y)
		time_y = temp_y - TL_TIME_BLOCK_H;
	if (time_y < graph_h)
		time_y = graph_h;
	return time_y;
#endif
}

#if defined(PBL_ROUND)
static GPoint prv_map_top(int x, int y) { return arc_map_top(x, y); }
static GPoint prv_map_bottom(int x, int y) { return arc_map_bottom(x, y); }

/* Runs after every strip has drawn straight into the scratch band and before
 * the time block: warp to arcs, then draw text/glyphs upright on top. */
static void prv_arc_update_proc(Layer *layer, GContext *ctx) {
	(void)layer;
	bool light = settings_get()->light_theme;
	arc_set_geometry(GRAPH_LAYERS_H_BASE, TEMP_H);
	/* Without the scratch buffer the strips are blanked; skip overlays so
	 * nothing lands at curved positions over flat leftovers. */
	if (!arc_warp(ctx, light ? GColorWhite : GColorBlack))
		return;
	/* 2.8.4: glyphs at native (emery) size, only rotated to the tangent;
	 * the warp stretches just the time axis (track, spans, rain, curve). */
	cloud_layer_draw_mapped(s_cloud_layer, ctx);
	daylight_layer_draw_marks_mapped(s_daylight_layer, ctx);
	event_layer_draw_icons_mapped(s_event_layer, ctx, prv_map_top);
	temp_layer_draw_labels_mapped(s_temp_layer, ctx, prv_map_bottom);
}
#endif

/** Reposition cloud/precip/event/time after Cloud Display mode changes. */
static void prv_relayout_graph_stack(void) {
	if (!s_main_window || !s_cloud_layer || !s_precip_layer || !s_event_layer ||
	    !s_time_layer)
		return;

	Layer *root = window_get_root_layer(s_main_window);
	GRect bounds = layer_get_bounds(root);
	int w = bounds.size.w;
	int cloud_h, precip_h, event_h;
	prv_top_strip_heights(&cloud_h, &precip_h, &event_h);
	int y = STACK_TOP + DAYLIGHT_H;

	layer_set_frame(cloud_layer_get_layer(s_cloud_layer),
	                prv_row_frame(bounds, y - CLOUD_LIFT - CLOUD_RAISE, cloud_h));
	y += cloud_h;

	layer_set_frame(precip_layer_get_layer(s_precip_layer),
	                prv_row_frame(bounds, y - CLOUD_LIFT - CLOUD_RAISE, precip_h + CLOUD_LIFT + CLOUD_RAISE));
	y += precip_h;

	layer_set_frame(event_layer_get_layer(s_event_layer),
	                prv_row_frame(bounds, y - CLOUD_LIFT, (event_h > 0 ? event_h : 1) + CLOUD_LIFT));
	layer_set_hidden(event_layer_get_layer(s_event_layer), event_h == 0);
	y += event_h;

	int time_y = prv_time_y(bounds);

	layer_set_frame(time_layer_get_layer(s_time_layer),
	                GRect(0, time_y, w, TL_TIME_BLOCK_H));

	layer_mark_dirty(cloud_layer_get_layer(s_cloud_layer));
	layer_mark_dirty(precip_layer_get_layer(s_precip_layer));
	layer_mark_dirty(event_layer_get_layer(s_event_layer));
	layer_mark_dirty(root);
}

static void prv_window_load(Window *window) {
	Layer *root = window_get_root_layer(window);
	GRect bounds = layer_get_bounds(root);
	int w = bounds.size.w;

	// Calculate vertical positions — daylight layer is flush to the top
	int y = STACK_TOP;

	// Daylight line with battery and sun/moon hour markers
	s_daylight_layer = daylight_layer_create(prv_row_frame(bounds, y, DAYLIGHT_H));
	layer_add_child(root, daylight_layer_get_layer(s_daylight_layer));
	y += DAYLIGHT_H;

	BatteryChargeState init_batt = battery_state_service_peek();
	daylight_layer_set_battery(s_daylight_layer, init_batt.charge_percent,
	                           init_batt.is_charging, init_batt.is_plugged);

	// Cloud + precip — Split grows clouds, shrinks rain; stack height fixed
	int cloud_h, precip_h, event_h;
	prv_top_strip_heights(&cloud_h, &precip_h, &event_h);
	s_cloud_layer = cloud_layer_create(prv_row_frame(bounds, y - CLOUD_LIFT - CLOUD_RAISE, cloud_h));
	layer_add_child(root, cloud_layer_get_layer(s_cloud_layer));
	y += cloud_h;

	// Precip graph (grows when Split clouds or weather events are off)
	s_precip_layer = precip_layer_create(prv_row_frame(bounds, y - CLOUD_LIFT - CLOUD_RAISE, precip_h + CLOUD_LIFT + CLOUD_RAISE));
	layer_add_child(root, precip_layer_get_layer(s_precip_layer));
	y += precip_h;

	// Event layer — WMO weather condition icons (not calendar ICS events)
	/* 2.8.3 (emery): one extra row on top (overlapping the rain chart's last
	 * row, transparent) so icons lifted by 1 px aren't clipped. */
	s_event_layer = event_layer_create(prv_row_frame(bounds, y - CLOUD_LIFT, (event_h > 0 ? event_h : EVENT_H) + CLOUD_LIFT));
	layer_add_child(root, event_layer_get_layer(s_event_layer));
	layer_set_hidden(event_layer_get_layer(s_event_layer), event_h == 0);
	y += event_h;

	// Time block — centered between top graphs and bottom meteogram
	int time_y = prv_time_y(bounds);
	s_time_layer = time_layer_create(GRect(0, time_y, w, TL_TIME_BLOCK_H));
	layer_add_child(root, time_layer_get_layer(s_time_layer));

	bool init_bt = connection_service_peek_pebble_app_connection();
	bool init_quiet = quiet_time_is_active();
	time_layer_set_status(s_time_layer, init_bt, init_quiet);

	// Temp info + sparkline — pinned to bottom
	s_temp_layer = temp_layer_create(prv_temp_frame(bounds));
#if defined(PBL_ROUND)
	/* Order: strips, meteogram, arc warp, then the time block on top. */
	layer_insert_below_sibling(temp_layer_get_layer(s_temp_layer),
	                           time_layer_get_layer(s_time_layer));
	arc_init(); /* failure is handled per frame by arc_warp() */
	s_arc_layer = layer_create(bounds);
	layer_set_update_proc(s_arc_layer, prv_arc_update_proc);
	layer_insert_below_sibling(s_arc_layer, time_layer_get_layer(s_time_layer));
#else
	layer_add_child(root, temp_layer_get_layer(s_temp_layer));
#endif

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
		prv_update_steps();
		daylight_layer_set_current_time(s_daylight_layer, now->tm_hour, now->tm_min);
	}

	// Restore cached weather if available
	prv_push_weather_to_layers(now);

#if defined(DEMO_SCENARIO)
	{
		time_t demo_now_t = time(NULL);
		TimelineEvent demo_events[1]; /* color defaults 0 */
		demo_events[0].start_time = (uint32_t)demo_now_t + 9000; // now + 2.5h
		demo_events[0].end_time = demo_events[0].start_time + 45 * 60;
		demo_events[0].color = 0;
		demo_events[0].place = 0;
		daylight_layer_set_events(s_daylight_layer, demo_events, 1);
	}
#else
	if (persist_exists(STORAGE_KEY_EVENTS)) {
		TimelineEvent stored_events[MAX_TIMELINE_EVENTS_MSG];
		int read_bytes = persist_read_data(STORAGE_KEY_EVENTS, stored_events, sizeof(stored_events));
		if (read_bytes > 0 && (read_bytes % (int)sizeof(TimelineEvent)) == 0) {
			uint8_t cnt = read_bytes / sizeof(TimelineEvent);
			daylight_layer_set_events(s_daylight_layer, stored_events, cnt);
		}
	}
#endif
}

static void prv_window_unload(Window *window) {
	daylight_layer_destroy(s_daylight_layer);
	cloud_layer_destroy(s_cloud_layer);
	precip_layer_destroy(s_precip_layer);
	event_layer_destroy(s_event_layer);
#if defined(PBL_ROUND)
	layer_destroy(s_arc_layer);
	s_arc_layer = NULL;
	arc_deinit();
#endif
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
	prv_weather_restore();
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

#if defined(PBL_HEALTH)
	health_service_events_subscribe(prv_health_event_handler, NULL);
#endif
#if !defined(DEMO_SCENARIO)
	prv_apply_input_services();
#endif

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
	prv_unsubscribe_input_services();
	if (s_weather_retry_timer) { /* 3.0.3 */
		app_timer_cancel(s_weather_retry_timer);
		s_weather_retry_timer = NULL;
	}
	settings_flush();
#if defined(PBL_HEALTH)
	health_service_events_unsubscribe();
#endif
	window_destroy(s_main_window);
}

int main(void) {
	init();
	app_event_loop();
	deinit();
	return 0;
}
