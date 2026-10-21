/**
 * Persistent storage keys (3.0.3: collected from main.c, settings.c and
 * daylight_layer.c; every value is unchanged).
 *
 * NEVER reuse or renumber a key. Watches keep old blobs across updates, so a
 * reused number would be read with the wrong layout. Retire a key by leaving
 * its line here, commented "retired", and take the next free number.
 * Next free key: 11.
 *
 * @license   GPL-3.0-or-later
 */
#pragma once

#define STORAGE_KEY_SETTINGS 0       /* Settings struct (settings.c) */
#define STORAGE_KEY_BATT_HIST 1      /* battery history blob (daylight_layer.c) */
#define STORAGE_KEY_WEATHER 2        /* WeatherData block 1 (main.c) */
#define STORAGE_KEY_WEATHER_PART2 3  /* WeatherData block 2 */
#define STORAGE_KEY_EVENTS 4         /* TimelineEvent[] */
#define STORAGE_KEY_WEATHER_PART3 5  /* WeatherData block 3 */
#define STORAGE_KEY_BATT_LEARNED 6   /* uint32 secs_per_pct; survives charges */
#define STORAGE_KEY_TAP_RANGE 7      /* uint8 tapped forecast hours; 0/absent = Clay value */
#define STORAGE_KEY_WEATHER_HDR 8    /* weather header {version, size, fetch_time} */
#define STORAGE_KEY_CLOUD_OVERRIDE 9 /* uint8 1+CloudDisplayMode; absent = Clay value */
#define STORAGE_KEY_WEATHER_PART4 10 /* 2.9.6: WeatherData block 4 */
