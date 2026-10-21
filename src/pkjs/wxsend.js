/**
 * Carbon Ion — weather payload packing + phone-side weather cache
 * (split out of index.js in 2.8.2; behaviour unchanged)
 *
 * @license GPL-3.0-or-later
 */
var C = require('./constants');
var settings = require('./settings');
var CACHE_KEY = C.CACHE_KEY, CACHE_TTL_MARGIN_MS = C.CACHE_TTL_MARGIN_MS, FORECAST_HOURS = C.FORECAST_HOURS;
var MAX_TIMELINE_EVENTS = C.MAX_TIMELINE_EVENTS;

/**
 * Pack up to hourlyCount values into a clamped uint8 array for AppMessage transport.
 *
 * @param   {number[]} values  Input values; missing entries default to 0.
 * @returns {number[]}         hourlyCount-element array with values clamped to [0, 255].
 */
function packUint8Array(values, hourlyCount) {
	if (hourlyCount == null) {
		hourlyCount = values ? values.length : 0;
	}
	var arr = [];
	for (var i = 0; i < hourlyCount; i++) {
		arr.push(Math.min(255, Math.max(0, Math.round(values[i] || 0))));
	}
	return arr;
}

/**
 * Pack shortwave radiation (W/m²) into uint8 as value/4, clamped to 0–255.
 * Watch multiplies by 4 to recover approximate W/m² (0–1020).
 *
 * @param {number[]} values
 * @param {number}   hourlyCount
 * @returns {number[]}
 */
function packShortwaveArray(values, hourlyCount) {
	var out = [];
	for (var i = 0; i < hourlyCount; i++) {
		var v = (values && values[i] != null) ? Number(values[i]) : 0;
		if (isNaN(v) || v < 0) v = 0;
		var packed = Math.round(v / 4);
		if (packed > 255) packed = 255;
		out.push(packed);
	}
	return out;
}

/**
 * Pack up to hourlyCount values into a clamped int8 array (two's complement) for AppMessage transport.
 *
 * @param   {number[]} values  Input values; missing entries default to 0.
 * @returns {number[]}         hourlyCount-element array clamped to [-128, 127], encoded as unsigned bytes.
 */
function packInt8Array(values, hourlyCount) {
	var arr = [];
	for (var i = 0; i < hourlyCount; i++) {
		var v = Math.round(values[i] || 0);
		v = Math.min(127, Math.max(-128, v));
		// Convert negative to unsigned byte (two's complement)
		arr.push(v < 0 ? v + 256 : v);
	}
	return arr;
}

/**
 * Pack uint32 values into a little-endian byte array for AppMessage transport.
 *
 * @param   {number[]} values
 * @returns {number[]}
 */
function packUint32Array(values) {
	var arr = [];
	for (var i = 0; i < values.length; i++) {
		var v = Math.floor(values[i] || 0);
		arr.push(v & 0xFF);
		arr.push((v >> 8) & 0xFF);
		arr.push((v >> 16) & 0xFF);
		arr.push((v >>> 24) & 0xFF);
	}
	return arr;
}

/**
 * Read the weather cache from localStorage, or null if absent/invalid.
 *
 * @returns {{expiresAt: number, payload: Object}|null}
 */
function readCache() {
	try {
		var raw = localStorage.getItem(CACHE_KEY);
		if (!raw) return null;
		var obj = JSON.parse(raw);
		if (!obj || !obj.payload || !obj.expiresAt) return null;
		return obj;
	} catch (e) {
		return null;
	}
}

/**
 * Persist payload to localStorage with a TTL-based expiry timestamp.
 *
 * @param {Object} payload  Weather data object to cache.
 */
/**
 * Cache lifetime: the chosen interval raised to the provider's minimum
 * (2.8.5), so watchface relaunches can't refetch faster than allowed.
 */
function cacheTtlMs(provider, intervalMin) {
	var eff = require('./weather/index').effectiveInterval(provider || 'openmeteo', intervalMin);
	var ttlMs = eff * 60 * 1000 - CACHE_TTL_MARGIN_MS;
	return ttlMs < 60 * 1000 ? 60 * 1000 : ttlMs;
}

/** True while a cached payload is younger than maxAgeS (by its fetch_time). */
function cacheUsable(payload, nowSec, maxAgeS) {
	if (!payload) return false;
	var ft = Number(payload.fetch_time);
	if (!ft) return false;
	return nowSec - ft <= maxAgeS;
}

function writeCache(payload) {
	var ttlMs = cacheTtlMs(settings.getWeatherProvider(), settings.getFetchIntervalMin());
	try {
		localStorage.setItem(CACHE_KEY, JSON.stringify({
			expiresAt: Date.now() + ttlMs,
			payload: payload
		}));
	} catch (e) { }
}

/**
 * Send a weather payload to the watch via AppMessage.
 *
 * @param {Object}   payload                      Weather data object.
 * @param {number[]} payload.precip_prob           Hourly precipitation probability (0–100).
 * @param {number[]} payload.temp_hourly           Hourly temperature values.
 * @param {number[]} payload.apparent_temp_hourly  Hourly apparent temperature values.
 * @param {number[]} payload.cloud_cover           Hourly cloud cover (0–100).
 * @param {number[]} payload.cloud_cover_low       Hourly low-altitude cloud cover (0–100).
 * @param {number[]} payload.cloud_cover_mid       Hourly mid-altitude cloud cover (0–100).
 * @param {number[]} payload.cloud_cover_high      Hourly high-altitude cloud cover (0–100).
 * @param {number[]} payload.hourly_weather_code   Hourly WMO weather codes.
 * @param {number[]} payload.shortwave_radiation   Hourly shortwave radiation (W/m²).
 * @param {number[]} [payload.precip_mm]          Hourly amount, 0.1 mm/h steps (0–255).
 * @param {string}   payload.city_name             City label for the time layer.
 * @param {string}   payload.temp_unit             'celsius' or 'fahrenheit'.
 * @param {number}  [payload.current_temp]         Current temperature (omitted when null).
 * @param {number}  [payload.high_temp]            Day high temperature (omitted when null).
 * @param {number}  [payload.low_temp]             Day low temperature (omitted when null).
 * @param {number}  [payload.weather_code]         Current WMO code (omitted when null).
 * @param {number}  [payload.sunrise_hour]         Sunrise hour 0–23 (omitted when null).
 * @param {number}  [payload.sunset_hour]          Sunset hour 0–23 (omitted when null).
 * @param {number}  [payload.fetch_time]           Unix fetch timestamp (omitted when null).
 */
function buildWeatherDict(payload) {
	var hourlyCount = FORECAST_HOURS;

	var precipProb = (payload.precip_prob || []).slice(0, hourlyCount);
	var tempHourly = (payload.temp_hourly || []).slice(0, hourlyCount);
	var apparentHourly = (payload.apparent_temp_hourly || []).slice(0, hourlyCount);
	var cloudCover = (payload.cloud_cover || []).slice(0, hourlyCount);
	var cloudCoverLow = (payload.cloud_cover_low || []).slice(0, hourlyCount);
	var cloudCoverMid = (payload.cloud_cover_mid || []).slice(0, hourlyCount);
	var cloudCoverHigh = (payload.cloud_cover_high || []).slice(0, hourlyCount);
	var hourlyCode = (payload.hourly_weather_code || []).slice(0, hourlyCount);
	var shortwave = (payload.shortwave_radiation || []).slice(0, hourlyCount);
	var precipMm = (payload.precip_mm || []).slice(0, hourlyCount);

	while (precipProb.length < hourlyCount) precipProb.push(0);
	while (tempHourly.length < hourlyCount) tempHourly.push(0);
	while (apparentHourly.length < hourlyCount) apparentHourly.push(0);
	while (cloudCover.length < hourlyCount) cloudCover.push(0);
	while (cloudCoverLow.length < hourlyCount) cloudCoverLow.push(0);
	while (cloudCoverMid.length < hourlyCount) cloudCoverMid.push(0);
	while (cloudCoverHigh.length < hourlyCount) cloudCoverHigh.push(0);
	while (hourlyCode.length < hourlyCount) hourlyCode.push(0);
	while (shortwave.length < hourlyCount) shortwave.push(0);
	while (precipMm.length < hourlyCount) precipMm.push(0);

	// 0 = celsius, 1 = fahrenheit  (matches settings.c convention)
	var tempUnitFlag = (payload.temp_unit === 'fahrenheit') ? 1 : 0;
	var cityName = (payload.city_name === null || payload.city_name === undefined)
		? ''
		: String(payload.city_name);
	if (cityName === 'Unknown') cityName = '';

	var dict = {
		'WEATHER_PRECIP_PROB': packUint8Array(precipProb, hourlyCount),
		'WEATHER_TEMP_HOURLY': packInt8Array(tempHourly, hourlyCount),
		'WEATHER_APPARENT_TEMP_HOURLY': packInt8Array(apparentHourly, hourlyCount),
		'WEATHER_CLOUD_COVER': packUint8Array(cloudCover, hourlyCount),
		'WEATHER_CLOUD_COVER_LOW': packUint8Array(cloudCoverLow, hourlyCount),
		'WEATHER_CLOUD_COVER_MID': packUint8Array(cloudCoverMid, hourlyCount),
		'WEATHER_CLOUD_COVER_HIGH': packUint8Array(cloudCoverHigh, hourlyCount),
		'WEATHER_HOURLY_CODE': packUint8Array(hourlyCode, hourlyCount),
		'WEATHER_SHORTWAVE_RADIATION': packShortwaveArray(shortwave, hourlyCount),
		/* 2.9.6: already packed by toPayload (0.1 mm/h steps, uint8). */
		'WEATHER_PRECIP_MM': packUint8Array(precipMm, hourlyCount),
		'CITY_NAME': cityName.substring(0, 23),
		'SETTING_TEMP_UNIT': tempUnitFlag,
	};

	// Scalar weather fields are only included when the value is actually present;
	// omitting a key is the AppMessage equivalent of null.
	if (payload.current_temp != null) {
		var curT = Math.round(payload.current_temp);
		dict['WEATHER_TEMP'] = curT;
	}
	if (payload.high_temp != null) {
		var hiT = Math.round(payload.high_temp);
		dict['WEATHER_TEMP_HIGH'] = hiT;
	}
	if (payload.low_temp != null) {
		var loT = Math.round(payload.low_temp);
		dict['WEATHER_TEMP_LOW'] = loT;
	}
	if (payload.weather_code != null) {
		dict['WEATHER_CODE'] = payload.weather_code;
	}
	if (payload.sunrise_hour != null) {
		dict['WEATHER_SUNRISE_HOUR'] = payload.sunrise_hour;
	}
	if (payload.sunset_hour != null) {
		dict['WEATHER_SUNSET_HOUR'] = payload.sunset_hour;
	}
	if (payload.sunrise_minute != null) {
		dict['WEATHER_SUNRISE_MINUTE'] = payload.sunrise_minute;
	}
	if (payload.sunset_minute != null) {
		dict['WEATHER_SUNSET_MINUTE'] = payload.sunset_minute;
	}
	/* Always sent (2.8.5): the watch keys "this is weather" and the hour
	 * alignment on it, even when the current temperature is missing. */
	dict['WEATHER_FETCH_TIME'] = Math.floor(payload.fetch_time != null ? payload.fetch_time : Date.now() / 1000);

	/* 2.8.0: fog bitmask (bit i = hour i), provider flags, valid hours. */
	var fogBits = (payload.fog_bits || []).slice(0, 9);
	while (fogBits.length < 9) fogBits.push(0);
	dict['WEATHER_FOG'] = fogBits;
	/* 2.9.3: wind-event bitmask (gusts over the chosen threshold). */
	var windB = (payload.wind_bits || []).slice(0, 9);
	while (windB.length < 9) windB.push(0);
	dict['WEATHER_WIND'] = windB;
	/* 2.9.4: strong-thunderstorm bitmask (Thunder "Strong only" level). */
	var stormB = (payload.storm_bits || []).slice(0, 9);
	while (stormB.length < 9) stormB.push(0);
	dict['WEATHER_STORM'] = stormB;
	/* 3.0.0: cloud thickness, 2 bits per hour. */
	var thickB = (payload.cloud_thick || []).slice(0, 18);
	while (thickB.length < 18) thickB.push(0);
	dict['WEATHER_CLOUD_THICK'] = packUint8Array(thickB, 18);
	dict['WEATHER_FLAGS'] = (payload.wx_flags | 0) & 0xFF;
	var validHours = payload.valid_hours ? Math.max(1, Math.min(hourlyCount, payload.valid_hours | 0)) : hourlyCount;
	dict['WEATHER_VALID_HOURS'] = validHours;

	var timelineEvents = payload.timeline_events || [];
	var starts = [];
	var ends = [];
	var colors = [];
	var places = [];
	for (var ei = 0; ei < timelineEvents.length && ei < MAX_TIMELINE_EVENTS; ei++) {
		starts.push(timelineEvents[ei].start);
		ends.push(timelineEvents[ei].end);
		colors.push((timelineEvents[ei].color | 0) & 0x0F);
		places.push((timelineEvents[ei].place | 0) & 0x03);
	}
	dict['TIMELINE_EVENT_STARTS'] = packUint32Array(starts);
	dict['TIMELINE_EVENT_ENDS'] = packUint32Array(ends);
	dict['TIMELINE_EVENT_COLORS'] = packUint8Array(colors, colors.length);
	dict['TIMELINE_EVENT_PLACES'] = packUint8Array(places, places.length);

	return dict;
}

module.exports = {
	packUint8Array: packUint8Array, packInt8Array: packInt8Array, packUint32Array: packUint32Array,
	packShortwaveArray: packShortwaveArray, readCache: readCache, writeCache: writeCache,
	buildWeatherDict: buildWeatherDict, cacheTtlMs: cacheTtlMs, cacheUsable: cacheUsable,
};
