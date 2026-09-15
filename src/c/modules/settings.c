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

#define STORAGE_KEY_SETTINGS 0

static Settings s_settings;

// Default settings values. New fields must be appended to the end of the
// struct to maintain compatibility with old persisted data.
static const Settings s_defaults = {
    .temp_unit_celsius = true,
    .light_theme = true,
    .date_format = "%A, %b %d",
    .fetch_interval_min = 15,
    .infill_mode = INFILL_FUTURE,
    .needle_mode = NEEDLE_BOTH,
    .forecast_hours = DEFAULT_FORECAST_HOURS,
    .show_bt_alert = true,
    .show_silent_mode = true,
    .timeline_battery = TIMELINE_BATT_10_0,
    .show_step_count = true,
    .timeline_event = TIMELINE_EVENT_SPAN,
    .cloud_sensitivity = CLOUD_SENS_SENSITIVE,
    .sunlight_rays = false,
    .sunlight_sensitivity = SUN_SENS_OFF,
    .cloud_display_mode = CLOUD_DISPLAY_TOTAL,
    .step_size = STEP_SIZE_DEFAULT,
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
			/* Upgrade from pre-sensitivity builds: map legacy rays toggle. */
			if (stored_size <= (int)offsetof(Settings, sunlight_sensitivity)) {
				s_settings.sunlight_sensitivity =
				    s_settings.sunlight_rays ? SUN_SENS_SENSITIVE : SUN_SENS_OFF;
			}
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

	/* Unified Step Counter: Off / Normal / Large (key 10060). */
	t = dict_find(iter, KEY_SETTING_STEP_DISPLAY);
	if (!t) t = dict_find(iter, MESSAGE_KEY_SETTING_STEP_DISPLAY);
	if (!t) t = dict_find(iter, 10060);
	if (t) {
		int v = prv_tuple_int(t);
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
		if (!t) t = dict_find(iter, 10034);
		if (t) {
			s_settings.show_step_count = (prv_tuple_int(t) != 0);
		}
		t = dict_find(iter, KEY_SETTING_STEP_SIZE);
		if (!t) t = dict_find(iter, MESSAGE_KEY_SETTING_STEP_SIZE);
		if (t) {
			int v = prv_tuple_int(t);
			s_settings.step_size =
			    (v == 1) ? STEP_SIZE_LARGE : STEP_SIZE_DEFAULT;
		}
	}

	t = dict_find(iter, KEY_SETTING_TIMELINE_EVENT);
	if (!t) t = dict_find(iter, 10035);
	if (t) {
		int em = prv_tuple_int(t);
		if (em >= 0 && em <= 2) {
			s_settings.timeline_event = (TimelineEventMode)em;
		}
	}


	t = dict_find(iter, KEY_SETTING_CLOUD_SENSITIVITY);
	if (!t) t = dict_find(iter, 10051);
	if (t) {
		int cs = prv_tuple_int(t);
		if (cs >= 0 && cs <= 4) {
			s_settings.cloud_sensitivity = (CloudSensitivity)cs;
		}
	}

	t = dict_find(iter, KEY_SETTING_SUNLIGHT_SENSITIVITY);
	if (!t) t = dict_find(iter, 10054);
	if (t) {
		int ss = prv_tuple_int(t);
		if (ss >= 0 && ss <= 4) {
			s_settings.sunlight_sensitivity = (SunlightSensitivity)ss;
			s_settings.sunlight_rays = (ss != SUN_SENS_OFF);
		}
	} else {
		/* Legacy Clay toggle only */
		t = dict_find(iter, KEY_SETTING_SUNLIGHT_RAYS);
		if (!t) t = dict_find(iter, 10052);
		if (t) {
			s_settings.sunlight_rays = (prv_tuple_int(t) != 0);
			s_settings.sunlight_sensitivity =
			    s_settings.sunlight_rays ? SUN_SENS_SENSITIVE : SUN_SENS_OFF;
		}
	}


	t = dict_find(iter, KEY_SETTING_CLOUD_DISPLAY_MODE);
	if (!t) t = dict_find(iter, 10055);
	if (t) {
		int dm = prv_tuple_int(t);
		if (dm >= 0 && dm <= 1) {
			s_settings.cloud_display_mode = (CloudDisplayMode)dm;
		}
	}

	if (memcmp(&prev, &s_settings, sizeof(Settings)) != 0) {
		settings_save();
	}
}
