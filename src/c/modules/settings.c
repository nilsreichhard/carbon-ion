/**
 * Settings module
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "settings.h"

#define STORAGE_KEY_SETTINGS 0

static Settings s_settings;

// Default settings values. New fields must be appended to the end of the
// struct to maintain compatibility with old persisted data.
static const Settings s_defaults = {
    .temp_unit_celsius = true,
    .light_theme = false,
    .date_format = "%A, %m/%d",
    .accent_color = {.argb = 0b11111111}, // GColorWhite
    .battery_display = BATTERY_DISPLAY_ICON,
    .show_timezone = false,
    .fetch_interval_min = 30,
    .infill_mode = INFILL_FUTURE,
    .needle_mode = NEEDLE_BOTH,
    .forecast_hours = DEFAULT_FORECAST_HOURS,
    .show_bt_alert = true,
    .show_silent_mode = true,
    .timeline_battery = TIMELINE_BATT_10_0,
    .show_step_count = true,
};

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
		}
	}
}

Settings *settings_get(void) { return &s_settings; }

void settings_save(void) {
	persist_write_data(STORAGE_KEY_SETTINGS, &s_settings, sizeof(s_settings));
}

static int prv_tuple_int(Tuple *t) {
	if (!t) return 0;
	if (t->length == 1) return (int)t->value->int8;
	if (t->length == 2) return (int)t->value->int16;
	if (t->length == 4) return (int)t->value->int32;
	return (int)t->value->int32;
}

void settings_apply_from_message(DictionaryIterator *iter) {
	Settings prev = s_settings;
	Tuple *t;

	t = dict_find(iter, MESSAGE_KEY_SETTING_TEMP_UNIT);
	if (!t) t = dict_find(iter, 10014);
	if (t)
		s_settings.temp_unit_celsius = (prv_tuple_int(t) == 0);

	t = dict_find(iter, MESSAGE_KEY_SETTING_DATE_FORMAT);
	if (!t) t = dict_find(iter, 10017);
	if (t && t->type == TUPLE_CSTRING && t->length > 0) {
		strncpy(s_settings.date_format, t->value->cstring,
		        sizeof(s_settings.date_format) - 1);
		s_settings.date_format[sizeof(s_settings.date_format) - 1] = '\0';
	}

	t = dict_find(iter, MESSAGE_KEY_SETTING_ACCENT_COLOR);
	if (!t) t = dict_find(iter, 10016);
	if (t) {
		s_settings.accent_color = GColorFromHEX(t->value->int32);
	}

	t = dict_find(iter, MESSAGE_KEY_SETTING_BATTERY_DISPLAY);
	if (!t) t = dict_find(iter, 10018);
	if (t) {
		int bd = prv_tuple_int(t);
		if (bd >= 0 && bd < 2) {
			s_settings.battery_display = (BatteryDisplay)bd;
		}
	}

	t = dict_find(iter, MESSAGE_KEY_SETTING_SHOW_TIMEZONE);
	if (!t) t = dict_find(iter, 10019);
	if (t)
		s_settings.show_timezone = (prv_tuple_int(t) != 0);

	t = dict_find(iter, MESSAGE_KEY_SETTING_FETCH_INTERVAL);
	if (!t) t = dict_find(iter, 10015);
	if (t) {
		int interval = prv_tuple_int(t);
		if (interval == 15 || interval == 30 || interval == 60) {
			s_settings.fetch_interval_min = (uint8_t)interval;
		}
	}

	t = dict_find(iter, KEY_SETTING_FORECAST_HOURS);
	if (!t) t = dict_find(iter, 10029);
	if (t) {
		int fh = prv_tuple_int(t);
		if (fh == 12 || fh == 18 || fh == 24 || fh == 36 || fh == 48) {
			s_settings.forecast_hours = (uint8_t)fh;
		}
	}

	t = dict_find(iter, KEY_SETTING_INFILL_MODE);
	if (!t) t = dict_find(iter, 10027);
	if (t) {
		int im = prv_tuple_int(t);
		if (im >= 0 && im <= 3) {
			s_settings.infill_mode = (InfillMode)im;
		}
	}

	t = dict_find(iter, KEY_SETTING_NEEDLE_MODE);
	if (!t) t = dict_find(iter, 10028);
	if (t) {
		int nm = prv_tuple_int(t);
		if (nm >= 0 && nm <= 3) {
			s_settings.needle_mode = (NeedleMode)nm;
		}
	}

	t = dict_find(iter, KEY_SETTING_LIGHT_THEME);
	if (!t) t = dict_find(iter, 10030);
	if (t) {
		s_settings.light_theme = (prv_tuple_int(t) != 0);
	}

	t = dict_find(iter, KEY_SETTING_SHOW_BT_ALERT);
	if (!t) t = dict_find(iter, 10031);
	if (t) {
		s_settings.show_bt_alert = (prv_tuple_int(t) != 0);
	}

	t = dict_find(iter, KEY_SETTING_SHOW_SILENT_MODE);
	if (!t) t = dict_find(iter, 10032);
	if (t) {
		s_settings.show_silent_mode = (prv_tuple_int(t) != 0);
	}

	t = dict_find(iter, KEY_SETTING_TIMELINE_BATTERY);
	if (!t) t = dict_find(iter, 10033);
	if (t) {
		int bm = prv_tuple_int(t);
		if (bm >= 0 && bm <= 3) {
			s_settings.timeline_battery = (TimelineBatteryMode)bm;
		}
	}

	t = dict_find(iter, KEY_SETTING_SHOW_STEP_COUNT);
	if (!t) t = dict_find(iter, 10034);
	if (t) {
		s_settings.show_step_count = (prv_tuple_int(t) != 0);
	}

	if (memcmp(&prev, &s_settings, sizeof(Settings)) != 0) {
		settings_save();
	}
}
