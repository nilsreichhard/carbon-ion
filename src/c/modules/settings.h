/**
 * Settings module
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>

typedef enum {
	BATTERY_DISPLAY_ICON = 0,
	BATTERY_DISPLAY_PERCENT,
} BatteryDisplay;

typedef enum {
	INFILL_FUTURE = 0, // Infill after current time bar (default)
	INFILL_PAST,       // Infill before current time bar
	INFILL_ALL,        // Infill across full 30h timeline
	INFILL_NONE,       // No infill
} InfillMode;

typedef enum {
	NEEDLE_BOTH = 0,   // Needle on both top and bottom charts (default)
	NEEDLE_ABOVE,      // Needle on top chart only
	NEEDLE_BELOW,      // Needle on bottom chart only
	NEEDLE_NONE,       // No needle
} NeedleMode;

typedef enum {
	TIMELINE_BATT_20_10_0 = 0, // 20% yellow, 10% orange, 0% red
	TIMELINE_BATT_10_0 = 1,    // 10% orange, 0% red (default)
	TIMELINE_BATT_0 = 2,       // 0% red
	TIMELINE_BATT_NONE = 3,    // None
} TimelineBatteryMode;

typedef enum {
	TIMELINE_WINDOW_12H = 12, // 12h future + 3h past = 15h total
	TIMELINE_WINDOW_18H = 18, // 18h future + 4.5h past = 22.5h total
	TIMELINE_WINDOW_24H = 24, // 24h future + 6h past = 30h total (default)
	TIMELINE_WINDOW_36H = 36, // 36h future + 9h past = 45h total
	TIMELINE_WINDOW_48H = 48, // 48h future + 12h past = 60h total
} TimelineWindow;

#define MIN_FORECAST_HOURS 12
#define MAX_FORECAST_HOURS 48
#define DEFAULT_FORECAST_HOURS 24
#define MAX_GRAPH_HOURS 60

// Direct message key tags for robust compilation across CloudPebble environments
#define KEY_SETTING_INFILL_MODE 10027
#define KEY_SETTING_NEEDLE_MODE 10028
#define KEY_SETTING_FORECAST_HOURS 10029
#define KEY_SETTING_LIGHT_THEME 10030
#define KEY_SETTING_SHOW_BT_ALERT 10031
#define KEY_SETTING_SHOW_SILENT_MODE 10032
#define KEY_SETTING_TIMELINE_BATTERY 10033

typedef struct {
	bool temp_unit_celsius;
	bool light_theme;
	char date_format[32];
	GColor accent_color;
	BatteryDisplay battery_display;
	bool show_timezone;
	bool show_ampm;
	uint8_t fetch_interval_min;
	InfillMode infill_mode;
	NeedleMode needle_mode;
	uint8_t forecast_hours; // 12, 24, 36, or 48 (default 24)
	bool show_bt_alert;
	bool show_silent_mode;
	TimelineBatteryMode timeline_battery;
} Settings;

void settings_init(void);
Settings *settings_get(void);
void settings_save(void);
void settings_apply_from_message(DictionaryIterator *iter);
