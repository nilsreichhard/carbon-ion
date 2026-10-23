/**
 * Settings module
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "settings.h"
#include <stddef.h>

#include "storage_keys.h"
#define TAP_SAVE_DELAY_MS 5000

static Settings s_settings;
bool g_high_contrast = false;
bool g_cloud_layers_available = true;
uint8_t g_wx_horizon_h = 255;
static uint8_t s_cloud_override; /* 0 none, 1 Total, 2 Split */
static uint8_t s_tap_range;
static AppTimer *s_tap_save_timer;

// Default settings values. New fields must be appended to the end of the
// struct to maintain compatibility with old persisted data.
static const Settings s_defaults = {
    .temp_unit_celsius = true,
    .light_theme = true,
    .date_format = "%A, %b %d",
    .fetch_interval_min = DEFAULT_FETCH_INTERVAL_MIN,
    .infill_mode = INFILL_FUTURE,
    .needle_mode = NEEDLE_BOTH,
    .forecast_hours = DEFAULT_FORECAST_HOURS,
    .show_bt_alert = true,
    .show_silent_mode = true,
    .timeline_battery = TIMELINE_BATT_20_10_4_0,
    .show_step_count = true,
    .timeline_event = TIMELINE_EVENT_SPAN,
    .cloud_sensitivity = CLOUD_SENS_SENSITIVE,
    .sunlight_rays = false,
    .sunlight_sensitivity = SUN_SENS_OFF,
    .cloud_display_mode = CLOUD_DISPLAY_TOTAL,
    .step_size = STEP_SIZE_DEFAULT,
    .precip_sensitivity = PRECIP_SENS_SENSITIVE,
    .weather_event_display = WEATHER_EVENT_RANGE_ICON,
    .alarm_rows = {0, 0, 0},
    .backlight_colors = false,
    .tap_cycle = false, /* only used with CARBON_TOUCH_ENABLED */
    .wrist_flick = false,
    .fog_indicator = true,
    .flick_action = FLICK_ACTION_RANGE,
    .event_hide = 0,
    .rain_height = RAIN_HEIGHT_PROBABILITY,
    .cloud_thick = CLOUD_THICKNESS_ON,
    .leading_zero = LEADING_ZERO_AUTO,
};

/* 2.9.3: event_hide is appended right where the 2.9.2 struct ended (no tail
 * padding before it), so an older persisted blob never covers it and it
 * keeps its default 0 = every event on. */
_Static_assert(offsetof(Settings, event_hide) % _Alignof(Settings) == 0,
               "event_hide must start at the old sizeof(Settings)");

/* 2.9.6/3.0.0: rain_height and cloud_thick may sit in an older blob's tail
 * padding (persisted as zeros), so their defaults must be 0. */
_Static_assert(RAIN_HEIGHT_PROBABILITY == 0 && CLOUD_THICKNESS_ON == 0 && LEADING_ZERO_AUTO == 0,
               "fields in old tail padding must default to 0");

void settings_init(void) {
	s_settings = s_defaults;
	if (persist_exists(STORAGE_KEY_SETTINGS)) {
		int stored_size = persist_get_size(STORAGE_KEY_SETTINGS);
		// Read min(stored, current) bytes so field migration works in both
		// directions: upgrading (stored < current) keeps new field defaults;
		// downgrading (stored > current) discards unknown trailing fields but
		// preserves all fields the current version does know about.
		if (stored_size > 0) {
			int read_size = stored_size < (int)sizeof(s_settings)
			                    ? stored_size
			                    : (int)sizeof(s_settings);
			persist_read_data(STORAGE_KEY_SETTINGS, &s_settings, read_size);
			/* Upgrade from pre-sensitivity builds: map legacy rays toggle. */
			if (stored_size <= (int)offsetof(Settings, sunlight_sensitivity)) {
				s_settings.sunlight_sensitivity =
				    s_settings.sunlight_rays ? SUN_SENS_SENSITIVE : SUN_SENS_OFF;
			}
		}
	}
	if (persist_exists(STORAGE_KEY_TAP_RANGE))
		s_tap_range = (uint8_t)persist_read_int(STORAGE_KEY_TAP_RANGE);
	if (persist_exists(STORAGE_KEY_CLOUD_OVERRIDE)) {
		int v = persist_read_int(STORAGE_KEY_CLOUD_OVERRIDE);
		s_cloud_override = (v == 1 || v == 2) ? (uint8_t)v : 0;
	}
	if (s_settings.flick_action > FLICK_ACTION_CLOUDS)
		s_settings.flick_action = FLICK_ACTION_RANGE;
	s_settings.event_hide &= EVENT_FLAGS_MASK;
	if (s_settings.leading_zero > LEADING_ZERO_OFF)
		s_settings.leading_zero = LEADING_ZERO_AUTO;
	if (s_settings.rain_height > RAIN_HEIGHT_AMOUNT_LINE)
		s_settings.rain_height = RAIN_HEIGHT_PROBABILITY;
}

CloudDisplayMode settings_effective_cloud_mode(void) {
	if (!g_cloud_layers_available)
		return CLOUD_DISPLAY_TOTAL;
	if (s_cloud_override)
		return (CloudDisplayMode)(s_cloud_override - 1);
	return s_settings.cloud_display_mode;
}

static AppTimer *s_cloud_save_timer;

static void prv_cloud_override_write(void) {
	if (s_cloud_override)
		persist_write_int(STORAGE_KEY_CLOUD_OVERRIDE, s_cloud_override);
	else if (persist_exists(STORAGE_KEY_CLOUD_OVERRIDE))
		persist_delete(STORAGE_KEY_CLOUD_OVERRIDE);
}

static void prv_cloud_save_cb(void *ctx) {
	(void)ctx;
	s_cloud_save_timer = NULL;
	prv_cloud_override_write();
}

/* Flicks save after TAP_SAVE_DELAY_MS (like the range), so a burst of
 * toggles writes flash once; clears (Clay save) write at once. */
static void prv_cloud_override_set_ex(uint8_t v, bool deferred) {
	if (v == s_cloud_override && !s_cloud_save_timer)
		return;
	s_cloud_override = v;
	if (deferred) {
		if (s_cloud_save_timer)
			app_timer_reschedule(s_cloud_save_timer, TAP_SAVE_DELAY_MS);
		else
			s_cloud_save_timer = app_timer_register(TAP_SAVE_DELAY_MS, prv_cloud_save_cb, NULL);
		return;
	}
	if (s_cloud_save_timer) {
		app_timer_cancel(s_cloud_save_timer);
		s_cloud_save_timer = NULL;
	}
	prv_cloud_override_write();
}

static void prv_cloud_override_set(uint8_t v) { prv_cloud_override_set_ex(v, false); }

bool settings_toggle_cloud_mode(void) {
	if (!g_cloud_layers_available)
		return false;
	CloudDisplayMode next = settings_effective_cloud_mode() == CLOUD_DISPLAY_SPLIT
	                            ? CLOUD_DISPLAY_TOTAL : CLOUD_DISPLAY_SPLIT;
	/* Back at the Clay value → no override needed. */
	prv_cloud_override_set_ex(next == s_settings.cloud_display_mode ? 0 : (uint8_t)(next + 1), true);
	return true;
}

/* Drop every temporary flick/tap override (range + cloud layers). */
static void prv_clear_tap_range(void);
static void prv_clear_overrides(void) {
	prv_clear_tap_range();
	prv_cloud_override_set(0);
}

Settings *settings_get(void) { return &s_settings; }

static bool prv_valid_hours(int h) {
	return h == 12 || h == 18 || h == 24 || h == 36 || h == 48;
}

static uint8_t prv_clamp_horizon(uint8_t fh) {
	static const uint8_t opts[] = {48, 36, 24, 18, 12};
	if (fh <= g_wx_horizon_h)
		return fh;
	for (unsigned i = 0; i < sizeof(opts); i++)
		if (opts[i] < fh && opts[i] <= g_wx_horizon_h)
			return opts[i];
	return 12;
}

uint8_t settings_effective_forecast_hours(void) {
	if (prv_valid_hours(s_tap_range))
		return prv_clamp_horizon(s_tap_range);
	return prv_clamp_horizon(s_settings.forecast_hours);
}

static void prv_tap_range_write(void) {
	if (prv_valid_hours(s_tap_range))
		persist_write_int(STORAGE_KEY_TAP_RANGE, s_tap_range);
	else if (persist_exists(STORAGE_KEY_TAP_RANGE))
		persist_delete(STORAGE_KEY_TAP_RANGE);
}

static void prv_tap_save_cb(void *ctx) {
	(void)ctx;
	s_tap_save_timer = NULL;
	prv_tap_range_write();
}

static void prv_clear_tap_range(void) {
	if (!s_tap_range)
		return;
	s_tap_range = 0;
	if (s_tap_save_timer) {
		app_timer_cancel(s_tap_save_timer);
		s_tap_save_timer = NULL;
	}
	prv_tap_range_write();
}

void settings_cycle_forecast(void) {
	uint8_t fh = settings_effective_forecast_hours();
	s_tap_range = (fh < 18) ? 18 : (fh < 24) ? 24 : (fh < 36) ? 36 : (fh < 48) ? 48 : 12;
	if (s_tap_range > g_wx_horizon_h)
		s_tap_range = 12; /* wrap: beyond the provider's data */
	if (s_tap_save_timer)
		app_timer_reschedule(s_tap_save_timer, TAP_SAVE_DELAY_MS);
	else
		s_tap_save_timer = app_timer_register(TAP_SAVE_DELAY_MS, prv_tap_save_cb, NULL);
}

void settings_flush(void) {
	if (s_cloud_save_timer) {
		app_timer_cancel(s_cloud_save_timer);
		s_cloud_save_timer = NULL;
		prv_cloud_override_write();
	}
	if (s_tap_save_timer) {
		app_timer_cancel(s_tap_save_timer);
		s_tap_save_timer = NULL;
		prv_tap_range_write();
	}
}

void settings_save(void) {
	persist_write_data(STORAGE_KEY_SETTINGS, &s_settings, sizeof(s_settings));
}

/* Integer tuple only (INT/UINT, length 1/2/4); anything else reads as 0. */
int settings_tuple_int(const Tuple *t) {
	if (!t || (t->type != TUPLE_INT && t->type != TUPLE_UINT))
		return 0;
	bool s = t->type == TUPLE_INT;
	switch (t->length) {
	case 1: return s ? (int)t->value->int8 : (int)t->value->uint8;
	case 2: return s ? (int)t->value->int16 : (int)t->value->uint16;
	case 4: return s ? t->value->int32 : (int)t->value->uint32;
	default: return 0;
	}
}

void settings_apply_from_message(DictionaryIterator *iter) {
	Settings prev = s_settings;
	Tuple *t;

	t = dict_find(iter, MESSAGE_KEY_SETTING_TEMP_UNIT);
	if (t)
		s_settings.temp_unit_celsius = (settings_tuple_int(t) == 0);

	t = dict_find(iter, MESSAGE_KEY_SETTING_DATE_FORMAT);
	if (t && t->type == TUPLE_CSTRING && t->length > 0) {
		strncpy(s_settings.date_format, t->value->cstring,
		        sizeof(s_settings.date_format) - 1);
		s_settings.date_format[sizeof(s_settings.date_format) - 1] = '\0';
	}

	t = dict_find(iter, MESSAGE_KEY_SETTING_FETCH_INTERVAL);
	if (t) {
		int interval = settings_tuple_int(t);
		if (interval == 15 || interval == 30 || interval == 60) {
			s_settings.fetch_interval_min = (uint8_t)interval;
		}
	}

	t = dict_find(iter, KEY_SETTING_FORECAST_HOURS);
	if (t) {
		int fh = settings_tuple_int(t);
		if (prv_valid_hours(fh)) {
			/* Clay save resets a tapped range only if the horizon changed. */
			if (fh != s_settings.forecast_hours)
				prv_clear_tap_range();
			s_settings.forecast_hours = (uint8_t)fh;
		}
	}

	t = dict_find(iter, KEY_SETTING_INFILL_MODE);
	if (t) {
		int im = settings_tuple_int(t);
		if (im >= 0 && im <= 3) {
			s_settings.infill_mode = (InfillMode)im;
		}
	}

	t = dict_find(iter, KEY_SETTING_NEEDLE_MODE);
	if (t) {
		int nm = settings_tuple_int(t);
		if (nm >= 0 && nm <= 3) {
			s_settings.needle_mode = (NeedleMode)nm;
		}
	}

	t = dict_find(iter, KEY_SETTING_LIGHT_THEME);
	if (t) {
		s_settings.light_theme = (settings_tuple_int(t) != 0);
	}

	t = dict_find(iter, KEY_SETTING_SHOW_BT_ALERT);
	if (t) {
		s_settings.show_bt_alert = (settings_tuple_int(t) != 0);
	}

	t = dict_find(iter, KEY_SETTING_SHOW_SILENT_MODE);
	if (t) {
		s_settings.show_silent_mode = (settings_tuple_int(t) != 0);
	}

	t = dict_find(iter, KEY_SETTING_TIMELINE_BATTERY);
	if (t) {
		int bm = settings_tuple_int(t);
		if (bm >= 0 && bm <= 4) {
			s_settings.timeline_battery = (TimelineBatteryMode)bm;
		}
	}

	/* Unified Step Counter: Off / Normal / Bold (key 10060). */
	t = dict_find(iter, KEY_SETTING_STEP_DISPLAY);
	if (t) {
		int v = settings_tuple_int(t);
		if (v == STEP_DISPLAY_OFF) {
			s_settings.show_step_count = false;
		} else if (v == STEP_DISPLAY_LARGE) {
			s_settings.show_step_count = true;
			s_settings.step_size = STEP_SIZE_LARGE;
		} else {
			s_settings.show_step_count = true;
			s_settings.step_size = STEP_SIZE_DEFAULT;
		}
	} else {
		/* Legacy: separate toggle + old size 0/1 */
		t = dict_find(iter, KEY_SETTING_SHOW_STEP_COUNT);
		if (t) {
			s_settings.show_step_count = (settings_tuple_int(t) != 0);
		}
		t = dict_find(iter, KEY_SETTING_STEP_SIZE);
		if (t) {
			int v = settings_tuple_int(t);
			s_settings.step_size =
			    (v == 1) ? STEP_SIZE_LARGE : STEP_SIZE_DEFAULT;
		}
	}

	t = dict_find(iter, KEY_SETTING_TIMELINE_EVENT);
	if (t) {
		int em = settings_tuple_int(t);
		if (em >= 0 && em <= 2) {
			s_settings.timeline_event = (TimelineEventMode)em;
		}
	}


	t = dict_find(iter, KEY_SETTING_CLOUD_SENSITIVITY);
	if (t) {
		int cs = settings_tuple_int(t);
		if (cs >= 0 && cs <= 4) {
			s_settings.cloud_sensitivity = (CloudSensitivity)cs;
		}
	}

	t = dict_find(iter, KEY_SETTING_SUNLIGHT_SENSITIVITY);
	if (t) {
		int ss = settings_tuple_int(t);
		if (ss >= 0 && ss <= 4) {
			s_settings.sunlight_sensitivity = (SunlightSensitivity)ss;
			s_settings.sunlight_rays = (ss != SUN_SENS_OFF);
		}
	} else {
		/* Legacy Clay toggle only */
		t = dict_find(iter, KEY_SETTING_SUNLIGHT_RAYS);
		if (t) {
			s_settings.sunlight_rays = (settings_tuple_int(t) != 0);
			s_settings.sunlight_sensitivity =
			    s_settings.sunlight_rays ? SUN_SENS_SENSITIVE : SUN_SENS_OFF;
		}
	}


	t = dict_find(iter, KEY_SETTING_CLOUD_DISPLAY_MODE);
	if (t) {
		int dm = settings_tuple_int(t);
		if (dm >= 0 && dm <= 1) {
			/* Clay save drops a flicked cloud mode only if the setting changed. */
			if (dm != (int)s_settings.cloud_display_mode)
				prv_cloud_override_set(0);
			s_settings.cloud_display_mode = (CloudDisplayMode)dm;
		}
	}


	t = dict_find(iter, KEY_SETTING_PRECIP_SENSITIVITY);
	if (t) {
		int v = settings_tuple_int(t);
		if (v >= 0 && v <= 4)
			s_settings.precip_sensitivity = (PrecipSensitivity)v;
	}

	t = dict_find(iter, KEY_SETTING_WEATHER_EVENT_DISPLAY);
	if (t) {
		int v = settings_tuple_int(t);
		if (v >= 0 && v <= 2)
			s_settings.weather_event_display = (WeatherEventDisplay)v;
	}

	t = dict_find(iter, KEY_SETTING_ALARM_ROWS);
	if (t) {
		uint32_t v = (uint32_t)settings_tuple_int(t);
		for (int i = 0; i < ALARM_ROW_COUNT; i++)
			s_settings.alarm_rows[i] = (uint8_t)((v >> (8 * i)) & 0xBF);
	}

	t = dict_find(iter, KEY_SETTING_BACKLIGHT_COLORS);
	if (t)
		s_settings.backlight_colors = (settings_tuple_int(t) != 0);

#if defined(CARBON_TOUCH_ENABLED)
	t = dict_find(iter, KEY_SETTING_TAP_CYCLE);
	if (t)
		s_settings.tap_cycle = (settings_tuple_int(t) != 0);
#endif

	t = dict_find(iter, KEY_SETTING_FOG_INDICATOR);
	if (t)
		s_settings.fog_indicator = (settings_tuple_int(t) != 0);

	t = dict_find(iter, KEY_SETTING_EVENT_FLAGS);
	if (t)
		s_settings.event_hide = (uint8_t)(settings_tuple_int(t) & EVENT_FLAGS_MASK);

	t = dict_find(iter, KEY_SETTING_RAIN_HEIGHT);
	if (t) {
		int v = settings_tuple_int(t);
		s_settings.rain_height = (v == RAIN_HEIGHT_AMOUNT || v == RAIN_HEIGHT_AMOUNT_LINE) ? (uint8_t)v : RAIN_HEIGHT_PROBABILITY;
	}

	t = dict_find(iter, KEY_SETTING_LEADING_ZERO); /* 3.0.3 */
	if (t) {
		int v = settings_tuple_int(t);
		s_settings.leading_zero = (v == LEADING_ZERO_ON || v == LEADING_ZERO_OFF) ? (uint8_t)v : LEADING_ZERO_AUTO;
	}

	t = dict_find(iter, KEY_SETTING_CLOUD_THICK);
	if (t)
		s_settings.cloud_thick = settings_tuple_int(t) == CLOUD_THICKNESS_OFF ? CLOUD_THICKNESS_OFF : CLOUD_THICKNESS_ON;

	t = dict_find(iter, KEY_SETTING_FLICK_ACTION);
	if (t) {
		int a = settings_tuple_int(t);
		if (a >= FLICK_ACTION_RANGE && a <= FLICK_ACTION_CLOUDS) {
			/* Switching action drops the other action's leftover override. */
			if (a != (int)s_settings.flick_action)
				prv_clear_overrides();
			s_settings.flick_action = (uint8_t)a;
		}
	}

	t = dict_find(iter, KEY_SETTING_WRIST_FLICK);
	if (t) {
		bool flick = (settings_tuple_int(t) != 0);
		/* Turning wrist flick off (and no double-tap) drops every temporary
		 * override (range key 7, cloud mode key 9): Clay values apply again. */
		if (!flick
#if defined(CARBON_TOUCH_ENABLED)
		    && !s_settings.tap_cycle
#endif
		)
			prv_clear_overrides();
		s_settings.wrist_flick = flick;
	}

	if (memcmp(&prev, &s_settings, sizeof(Settings)) != 0) {
		settings_save();
	}
}
