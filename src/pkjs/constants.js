/**
 * Shared constants for Carbon PebbleKit JS scripts.
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 */

module.exports = {
	DEFAULT_FETCH_INTERVAL: 15, /* 3.1.2: single JS default refresh (min); C: DEFAULT_FETCH_INTERVAL_MIN */
	APP_VERSION: '3.2.0', /* 3.0.3: single JS copy of the version (MET User-Agent, debug info); test_payload checks it against package.json */
	WEATHER_BASE_URL: 'https://api.open-meteo.com/v1/forecast',
	GEOCODE_BASE_URL: 'https://api.bigdatacloud.net/data/reverse-geocode-client',
	CACHE_KEY: 'carbon.weather.v6',
	GEONAME_CACHE_KEY: 'carbon.geoname.v1',
	GEONAME_TTL_MS: 24 * 60 * 60 * 1000,
	GEONAME_COORD_PRECISION: 2,
	STALE_CACHE_MAX_AGE_S: 24 * 3600, // never resend a fallback cache older than this
	CACHE_TTL_MARGIN_MS: 2 * 60 * 1000, // Interval-minus-margin avoids alternating cache hits.
	XHR_TIMEOUT_MS: 10 * 1000, // Fail fast enough to allow retries.
	FORECAST_HOURS: 72,
	WEATHER_RETRY_ATTEMPTS: 3,
	WEATHER_RETRY_BASE_DELAY_MS: 2 * 1000, // 2s/4s smooths top-of-hour API bursts.
	GEOCODE_RETRY_ATTEMPTS: 2,
	GEOCODE_RETRY_BASE_DELAY_MS: 2 * 1000, // Short retries recover transient geocode errors.
	SEND_RETRY_ATTEMPTS: 3,
	SEND_RETRY_BASE_DELAY_MS: 1000, // 1s/2s/4s clears watch inbox nacks quickly.
	FETCH_DEDUPE_WINDOW_MS: 30 * 1000, // Covers slow cycles and in-flight crash safety.
	SEND_DEDUPE_WINDOW_MS: 10 * 1000, // Covers launch race without blocking real sends.
	REQ_DEDUPE_WINDOW_MS: 5 * 1000, // Collapses wake bursts and startup races only.
	LOG_KEY_CUR: 'carbon.eventlog.cur',
	LOG_KEY_PREV: 'carbon.eventlog.prev',
	MAX_LOG_ENTRIES: 60,
	MAX_TIMELINE_EVENTS: 12, // Must match MAX_TIMELINE_EVENTS in src/c/ui/daylight_layer.h.
};
