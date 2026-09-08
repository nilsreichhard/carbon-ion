/**
 * Settings module
 *
 * @author    Cory Hughart <cory@coryhughart.com>
 * @copyright 2026 Cory Hughart
 * @license   https://www.gnu.org/licenses/gpl-3.0.html GPL-3.0-or-later
 * @link      https://cr0ybot.com/project/pebble-watchface-carbon
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
} Settings;

void settings_init(void);
Settings *settings_get(void);
void settings_save(void);
void settings_apply_from_message(DictionaryIterator *iter);
