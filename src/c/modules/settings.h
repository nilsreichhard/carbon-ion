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


typedef enum {
	CLOUD_SENS_VERY = 0,       // clear 5, small<25, med<55
	CLOUD_SENS_SENSITIVE = 1,  // clear 15, small<40, med<70 (default)
	CLOUD_SENS_BALANCED = 2,   // clear 25, small<50, med<75
	CLOUD_SENS_INSENSITIVE = 3,// clear 40, small<65, med<85
	CLOUD_SENS_OFF = 4,        // never draw lobes
} CloudSensitivity;

typedef enum {
	CLOUD_DISPLAY_TOTAL = 0, // one row of lobes from total cloud_cover
	CLOUD_DISPLAY_SPLIT = 1, // stacked high / mid / low bands
} CloudDisplayMode;

typedef enum {
	SUN_SENS_VERY = 0,        // weak sun still shows; full scale ~500 W/m²
	SUN_SENS_SENSITIVE = 1,   // prior on-toggle feel; full ~800
	SUN_SENS_BALANCED = 2,    // full ~950
	SUN_SENS_INSENSITIVE = 3, // only strong sun; full ~1100
	SUN_SENS_OFF = 4,         // never draw rays
} SunlightSensitivity;

typedef enum {
	TIMELINE_EVENT_NONE = 0,
	TIMELINE_EVENT_BAR = 1,
	TIMELINE_EVENT_SPAN = 2,
} TimelineEventMode;

typedef enum {
	STEP_SIZE_DEFAULT = 0, /* Gothic 18 + 14 K (Normal) */
	STEP_SIZE_LARGE = 1,   /* Bold: Gothic 18 Bold for number and K */
} StepSize;

/* Clay single control: Off / Normal / Bold */
typedef enum {
	STEP_DISPLAY_OFF = 0,
	STEP_DISPLAY_NORMAL = 1,
	STEP_DISPLAY_LARGE = 2, /* Bold */
} StepDisplay;

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
#define KEY_SETTING_SHOW_STEP_COUNT 10034
#define KEY_SETTING_TIMELINE_EVENT 10035
#define KEY_SETTING_CLOUD_SENSITIVITY 10051
#define KEY_SETTING_SUNLIGHT_RAYS 10052
#define KEY_SETTING_SUNLIGHT_SENSITIVITY 10054
#define KEY_SETTING_CLOUD_DISPLAY_MODE 10055
#define KEY_SETTING_STEP_SIZE 10059
#define KEY_SETTING_STEP_DISPLAY 10060

typedef struct {
	bool temp_unit_celsius;
	bool light_theme;
	char date_format[32];
	uint8_t fetch_interval_min;
	InfillMode infill_mode;
	NeedleMode needle_mode;
	uint8_t forecast_hours; // 12, 24, 36, or 48 (default 24)
	bool show_bt_alert;
	bool show_silent_mode;
	TimelineBatteryMode timeline_battery;
	bool show_step_count;
	TimelineEventMode timeline_event;
	CloudSensitivity cloud_sensitivity;
	bool sunlight_rays; /* legacy toggle; migrated into sunlight_sensitivity */
	SunlightSensitivity sunlight_sensitivity;
	CloudDisplayMode cloud_display_mode; /* append for persist compat */
	StepSize step_size; /* append for persist compat */
} Settings;

void settings_init(void);
Settings *settings_get(void);
void settings_save(void);
void settings_apply_from_message(DictionaryIterator *iter);
