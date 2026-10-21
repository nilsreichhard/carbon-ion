/**
 * Weather module
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#pragma once
#include <pebble.h>

#define WEATHER_HOURLY_COUNT 72
#define WEATHER_PAST_HOURS 12
#define WEATHER_FORECAST_HOURS 60
#define WEATHER_CITY_MAX_LEN 24
#define WEATHER_TEMP_INVALID INT16_MIN
#define WEATHER_CODE_INVALID 0xFF

typedef enum {
	WEATHER_CONDITION_CLEAR = 0,
	WEATHER_CONDITION_PARTLY_CLOUDY,
	WEATHER_CONDITION_MOSTLY_CLOUDY,
	WEATHER_CONDITION_CLOUDY,
	WEATHER_CONDITION_FOG,
	WEATHER_CONDITION_WINDY,
	WEATHER_CONDITION_DRIZZLE,
	WEATHER_CONDITION_RAIN,
	WEATHER_CONDITION_RAIN_HEAVY,
	WEATHER_CONDITION_SLEET,
	WEATHER_CONDITION_SNOW,
	WEATHER_CONDITION_SNOW_HEAVY,
	WEATHER_CONDITION_HAIL,
	WEATHER_CONDITION_STORM,
	WEATHER_CONDITION_STORM_SEVERE,
	WEATHER_CONDITION_TORNADO,
	WEATHER_CONDITION_UNKNOWN,
} WeatherCondition;

typedef struct {
	int16_t current_temp;
	int16_t high_temp;
	int16_t low_temp;
	uint8_t weather_code;
	uint8_t sunrise_hour;
	uint8_t sunset_hour;
	uint8_t precip_prob[WEATHER_HOURLY_COUNT];
	int8_t temp_hourly[WEATHER_HOURLY_COUNT];
	int8_t apparent_temp_hourly[WEATHER_HOURLY_COUNT];
	uint8_t cloud_cover[WEATHER_HOURLY_COUNT];
	uint8_t hourly_weather_code[WEATHER_HOURLY_COUNT];
	char city_name[WEATHER_CITY_MAX_LEN];
	bool is_valid;
	time_t fetch_time;   // unix timestamp of last successful fetch
	uint8_t valid_hours; // hourly entries valid starting from fetch_time
	                     // (0-WEATHER_HOURLY_COUNT)
	uint8_t sunrise_minute; // 0-59 (appended for persist compat)
	uint8_t sunset_minute;  // 0-59
	uint8_t shortwave_radiation[WEATHER_HOURLY_COUNT]; // packed W/m²/4
	uint8_t cloud_cover_low[WEATHER_HOURLY_COUNT];  /* append for persist */
	uint8_t cloud_cover_mid[WEATHER_HOURLY_COUNT];
	uint8_t cloud_cover_high[WEATHER_HOURLY_COUNT];
	uint8_t fog_bits[(WEATHER_HOURLY_COUNT + 7) / 8]; /* 2.8.0: bit i = fog in hour i */
	uint8_t wx_flags;                                  /* 2.8.0: WX_FLAG_* */
	uint8_t wind_bits[(WEATHER_HOURLY_COUNT + 7) / 8]; /* 2.9.3: bit i = strong gusts in hour i */
	uint8_t storm_bits[(WEATHER_HOURLY_COUNT + 7) / 8]; /* 2.9.4: bit i = strong thunderstorm */
	uint8_t precip_mm[WEATHER_HOURLY_COUNT]; /* 2.9.6: mm/h in 0.1 steps, capped 255 */
	uint8_t thick_bits[WEATHER_HOURLY_COUNT / 4]; /* 3.0.0: 2 bits/hour cloud thickness (0 none, 1 light, 2 normal, 3 thick) */
} WeatherData;

#define WX_FLAG_NO_CLOUD_LAYERS 0x01 /* provider has no low/mid/high cloud cover */
#define WX_FLAG_HAS_MM 0x02          /* 2.9.6: precip_mm holds provider amounts */

_Static_assert(sizeof(WeatherData) <= (PERSIST_DATA_MAX_LENGTH * 4),
               "WeatherData exceeds 1024 bytes (4 persist blocks)");

/**
 * Converts a WMO weather code (0-99) to a WeatherCondition enum.
 */
WeatherCondition weather_code_to_condition(uint8_t wmo_code);
const char *weather_condition_to_icon(WeatherCondition cond, bool is_day);
