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
	TIMELINE_BATT_20_10_4_0 = 0, // 20% yellow, 10% orange, 4% red (battery-save), 0% deep red (default)
	TIMELINE_BATT_10_4_0 = 1,    // 10%, 4%, 0%
	TIMELINE_BATT_4_0 = 2,       // 4%, 0%
	TIMELINE_BATT_NONE = 3,      // None (legacy; kept for persisted values)
	TIMELINE_BATT_10_4 = 4,      // 10%, 4%
} TimelineBatteryMode;

typedef enum {
	TIMELINE_WINDOW_12H = 12, // 12h future + 3h past = 15h total
	TIMELINE_WINDOW_18H = 18, // 18h future + 4h past = 22h total (integer /4)
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
	PRECIP_SENS_VERY = 0,
	PRECIP_SENS_SENSITIVE = 1,  // default
	PRECIP_SENS_BALANCED = 2,
	PRECIP_SENS_INSENSITIVE = 3,
	PRECIP_SENS_OFF = 4,
} PrecipSensitivity;

typedef enum {
	WEATHER_EVENT_RANGE_ICON = 0, // range lines + icon (default)
	WEATHER_EVENT_ICON = 1,        // icon only
	WEATHER_EVENT_NONE = 2,        // hide; rain expands
} WeatherEventDisplay;

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
typedef enum {
	RAIN_HEIGHT_PROBABILITY = 0, /* bar height = chance (default) */
	RAIN_HEIGHT_AMOUNT = 1,      /* 2.9.6: height = mm/h, chance as shading */
} RainHeightMode;

typedef enum {
	LEADING_ZERO_AUTO = 0, /* 3.0.3: 24 h shows it, 12 h strips it (default) */
	LEADING_ZERO_ON = 1,
	LEADING_ZERO_OFF = 2,
} LeadingZeroMode;

typedef enum {
	CLOUD_THICKNESS_ON = 0,  /* 3.0.0: light / normal / thick clouds (default) */
	CLOUD_THICKNESS_OFF = 1, /* every cloud drawn normal */
} CloudThicknessMode;

#define MAX_GRAPH_HOURS 60

/* 3.0.3: watch-side names for the generated message keys (package.json is
 * the single source of the numbers; build/include/message_keys.auto.h). */
#define KEY_SETTING_INFILL_MODE MESSAGE_KEY_SETTING_INFILL_MODE
#define KEY_SETTING_NEEDLE_MODE MESSAGE_KEY_SETTING_NEEDLE_MODE
#define KEY_SETTING_FORECAST_HOURS MESSAGE_KEY_SETTING_FORECAST_HOURS
#define KEY_SETTING_LIGHT_THEME MESSAGE_KEY_SETTING_LIGHT_THEME
#define KEY_SETTING_SHOW_BT_ALERT MESSAGE_KEY_SETTING_SHOW_BT_ALERT
#define KEY_SETTING_SHOW_SILENT_MODE MESSAGE_KEY_SETTING_SHOW_SILENT_MODE
#define KEY_SETTING_TIMELINE_BATTERY MESSAGE_KEY_SETTING_TIMELINE_BATTERY
#define KEY_SETTING_SHOW_STEP_COUNT MESSAGE_KEY_SETTING_SHOW_STEP_COUNT
#define KEY_SETTING_TIMELINE_EVENT MESSAGE_KEY_SETTING_TIMELINE_EVENT
#define KEY_SETTING_CLOUD_SENSITIVITY MESSAGE_KEY_SETTING_CLOUD_SENSITIVITY
#define KEY_SETTING_SUNLIGHT_RAYS MESSAGE_KEY_SETTING_SUNLIGHT_RAYS
#define KEY_SETTING_SUNLIGHT_SENSITIVITY MESSAGE_KEY_SETTING_SUNLIGHT_SENSITIVITY
#define KEY_SETTING_CLOUD_DISPLAY_MODE MESSAGE_KEY_SETTING_CLOUD_DISPLAY_MODE
#define KEY_SETTING_STEP_SIZE MESSAGE_KEY_SETTING_STEP_SIZE
#define KEY_SETTING_STEP_DISPLAY MESSAGE_KEY_SETTING_STEP_DISPLAY
#define KEY_SETTING_PRECIP_SENSITIVITY MESSAGE_KEY_SETTING_PRECIP_SENSITIVITY
#define KEY_SETTING_WEATHER_EVENT_DISPLAY MESSAGE_KEY_SETTING_WEATHER_EVENT_DISPLAY
#define KEY_SETTING_ALARM_ROWS MESSAGE_KEY_SETTING_ALARM_ROWS       /* int32: byte i = row i (bit7 alarm, bits4-5 place, bits0-3 color) */
#define KEY_SETTING_BACKLIGHT_COLORS MESSAGE_KEY_SETTING_BACKLIGHT_COLORS
#define KEY_SETTING_WRIST_FLICK MESSAGE_KEY_SETTING_WRIST_FLICK
#define KEY_SETTING_FOG_INDICATOR MESSAGE_KEY_SETTING_FOG_INDICATOR
#define KEY_SETTING_FLICK_ACTION MESSAGE_KEY_SETTING_FLICK_ACTION
#define KEY_SETTING_RAIN_HEIGHT MESSAGE_KEY_SETTING_RAIN_HEIGHT /* 2.9.6: RainHeightMode */
#define KEY_SETTING_CLOUD_THICK MESSAGE_KEY_SETTING_CLOUD_THICK /* 3.0.0: CloudThicknessMode */
#define KEY_SETTING_LEADING_ZERO MESSAGE_KEY_SETTING_LEADING_ZERO /* 3.0.3: LeadingZeroMode */
#define KEY_SETTING_EVENT_FLAGS MESSAGE_KEY_SETTING_EVENT_FLAGS /* 2.9.3: EVENT_HIDE_* mask */
#define EVENT_HIDE_THUNDER 0x01
#define EVENT_HIDE_TORNADO 0x02
#define EVENT_HIDE_SNOW 0x04
#define EVENT_THUNDER_STRONG 0x08 /* 2.9.4: thunder "Strong only" */
#define EVENT_SNOW_LVL_SHIFT 4    /* 2.9.4: bits 4-5 snow minimum: 0 any, 1 moderate+, 2 heavy */
#define EVENT_SNOW_LVL_MASK 0x30
#define EVENT_FLAGS_MASK 0x3f

typedef enum {
	FLICK_ACTION_RANGE = 0,  /* cycle timeline range (default) */
	FLICK_ACTION_CLOUDS = 1, /* toggle 1/3 cloud layers (Total <-> Split) */
} FlickAction;

#define ALARM_ROW_COUNT 3
#define ALARM_ROW_ON 0x80

/* Platforms whose watchfaces use the SDK 4.33 touch/backlight/alarm services. */
#if defined(PBL_PLATFORM_EMERY) || defined(PBL_PLATFORM_GABBRO)
#define CARBON_MODERN_SERVICES 1
#endif

/* Watchface touch (double-tap range cycle). Off: stock PebbleOS does not
 * deliver touch to watchfaces yet. Build with -DCARBON_WATCHFACE_TOUCH=1 to
 * re-enable once firmware supports it (also restore the Clay toggle). */
#if defined(CARBON_MODERN_SERVICES) && defined(CARBON_WATCHFACE_TOUCH) && CARBON_WATCHFACE_TOUCH
#define CARBON_TOUCH_ENABLED 1
#endif
#if defined(CARBON_TOUCH_ENABLED)
/* 3.0.3: not in package.json (touch is off). When enabling touch, declare
 * "SETTING_TAP_CYCLE": 10068 there and switch this to MESSAGE_KEY_SETTING_TAP_CYCLE. */
#define KEY_SETTING_TAP_CYCLE 10068
#endif

typedef struct {
	bool temp_unit_celsius;
	bool light_theme;
	char date_format[32];
	uint8_t fetch_interval_min;
	InfillMode infill_mode;
	NeedleMode needle_mode;
	uint8_t forecast_hours; // 12, 18, 24, 36, or 48 (default 24)
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
	PrecipSensitivity precip_sensitivity;
	WeatherEventDisplay weather_event_display;
	uint8_t alarm_rows[ALARM_ROW_COUNT]; /* 2.7.0: calendar row shows next alarm */
	bool backlight_colors;               /* 2.7.0: high contrast while lit */
	bool tap_cycle;                      /* touch builds only; kept for persist layout */
	bool wrist_flick;                    /* 2.7.1: wrist flick cycles range (default off) */
	bool fog_indicator;                  /* 2.8.1: fog weather event (default on) */
	uint8_t flick_action;                /* 2.8.0: FlickAction (flick / double-tap) */
	uint8_t event_hide;                  /* 2.9.3/2.9.4: EVENT_* flags (0 = defaults) */
	uint8_t rain_height;                 /* 2.9.6: RainHeightMode (0 = probability) */
	uint8_t cloud_thick;                 /* 3.0.0: CloudThicknessMode (0 = on); 0 also
	                                      * when an older blob's tail padding covers it */
	uint8_t leading_zero;                /* 3.0.3: LeadingZeroMode (0 = auto); 0 also from
	                                      * older blobs (shorter, or tail padding) */
} Settings;

/* 2.8.0: false when the weather provider has no low/mid/high cloud layers;
 * the cloud strip then always uses the 1-layer (Total) view. */
extern bool g_cloud_layers_available;
/* 2.8.1: forecast hours the provider really covers ahead of now (255 = all). */
extern uint8_t g_wx_horizon_h;

/* Cloud mode actually drawn: provider capability > flicked override > Clay. */
CloudDisplayMode settings_effective_cloud_mode(void);
/* Flick/double-tap "Toggle 1/3 cloud layers": false if layers unavailable. */
bool settings_toggle_cloud_mode(void);

/* Runtime-only: true while backlight is on and backlight_colors is enabled. */
extern bool g_high_contrast;

/* Flicked range override (own persist key 7; 0 = Clay forecast_hours).
 * Cleared when Forecast Horizon changes or wrist flick is turned off.
 * Cycle 12→18→24→36→48→12; persist is debounced ~5s, flush on exit. */
void settings_cycle_forecast(void);
uint8_t settings_effective_forecast_hours(void);
void settings_flush(void);

void settings_init(void);
Settings *settings_get(void);
void settings_save(void);
void settings_apply_from_message(DictionaryIterator *iter);

/* Integer tuple only (INT/UINT, length 1/2/4); anything else reads as 0. */
int settings_tuple_int(const Tuple *t);
