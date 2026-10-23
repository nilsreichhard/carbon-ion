/**
 * Carbon Ion — Clay settings: reading, migration, watch settings dict
 * (split out of index.js in 2.8.2; behaviour unchanged)
 *
 * @license GPL-3.0-or-later
 */
var weatherProviders = require('./weather/index');

/**
 * Returns true if the watch/phone locale indicates Fahrenheit (en_US).
 * Checks the watch locale first (most reliable), then navigator.language.
 *
 * @returns {boolean}
 */
function shouldUseFahrenheit() {
	try {
		var info = Pebble.getActiveWatchInfo();
		if (info && info.language) {
			return info.language === 'en_US';
		}
	} catch (e) { }
	var lang = (navigator && navigator.language) || '';
	return lang === 'en-US' || lang === 'en_US';
}

/**
 * Returns 'celsius' or 'fahrenheit'.
 * Reads the stored Clay setting first; if it is -1 (auto) or absent, falls
 * back to locale detection via shouldUseFahrenheit().
 *
 * @returns {'celsius'|'fahrenheit'}
 */
function getTempUnit() {
	try {
		var raw = localStorage.getItem('clay-settings');
		if (raw) {
			var s = JSON.parse(raw);
			// Clay stores select values as strings from the HTML form;
			// always parse to int before comparing.
			var unit = parseInt(s.SETTING_TEMP_UNIT, 10);
			if (unit === 0) return 'celsius';
			if (unit === 1) return 'fahrenheit';
			// -1 (auto) or NaN: fall through to locale detection
		}
	} catch (e) { }
	return shouldUseFahrenheit() ? 'fahrenheit' : 'celsius';
}

/**
 * Returns the configured fetch interval in minutes from Clay settings.
 * Falls back to 30 when unset or invalid.
 *
 * @returns {number}
 */
/* 3.1.2: one default refresh interval (matches Clay default and main.c). */
var DEFAULT_FETCH_INTERVAL = require('./constants').DEFAULT_FETCH_INTERVAL;

function getFetchIntervalMin() {
	try {
		var raw = localStorage.getItem('clay-settings');
		if (raw) {
			var s = JSON.parse(raw);
			var interval = parseInt(s.SETTING_FETCH_INTERVAL, 10);
			if (interval === 15 || interval === 30 || interval === 60) {
				return interval;
			}
		}
	} catch (e) { }
	return DEFAULT_FETCH_INTERVAL;
}

/** Selected weather provider id from saved Clay settings. */
function getWeatherProvider() {
	try {
		var s = JSON.parse(localStorage.getItem('clay-settings') || '{}');
		var v = s.SETTING_WEATHER_PROVIDER;
		if (v && typeof v === 'object') v = v.value;
		return typeof v === 'string' && v ? v : 'openmeteo';
	} catch (e) { return 'openmeteo'; }
}

/**
 * Resolve the locale language used for reverse geocoding.
 * Prefers watch locale, then phone locale, then falls back to English.
 *
 * @returns {string}
 */
function getLocalityLanguage() {
	var rawLang = '';

	try {
		var info = Pebble.getActiveWatchInfo();
		if (info && info.language) rawLang = String(info.language);
	} catch (e) { }

	if (!rawLang && navigator && navigator.language) {
		rawLang = String(navigator.language);
	}

	rawLang = rawLang.replace('_', '-');
	var match = /^[A-Za-z]{2,3}/.exec(rawLang);
	if (!match) return 'en';
	return match[0].toLowerCase();
}

/**
 * Read Clay settings from localStorage.
 *
 * @returns {Object}
 */
function readClaySettings() {
	try {
		var raw = localStorage.getItem('clay-settings');
		return raw ? (JSON.parse(raw) || {}) : {};
	} catch (e) {
		return {};
	}
}

/**
 * Flatten Clay settings that may contain nested {value: ...} wrappers.
 *
 * @param   {Object} rawSettings
 * @returns {Object}
 */
function flattenClaySettings(rawSettings) {
	var flatSettings = {};
	Object.keys(rawSettings || {}).forEach(function (k) {
		var v = rawSettings[k];
		flatSettings[k] = (v !== null && typeof v === 'object' && 'value' in v)
			? v.value : v;
	});
	return flatSettings;
}

/**
 * Repair corrupted clay-settings in localStorage (e.g. '[object Object]' URLs).
 */
function sanitizeClaySettingsStorage() {
	try {
		var raw = localStorage.getItem('clay-settings');
		if (!raw) return;
		var settings = JSON.parse(raw);
		if (!settings || typeof settings !== 'object') return;

		var flatSettings = flattenClaySettings(settings);
		function bootRepairCal(key, cacheKey) {
			var v = flatSettings[key];
			if (v === '[object Object]' || typeof v === 'object') {
				flatSettings[key] = localStorage.getItem(cacheKey) || '';
			}
		}
		bootRepairCal('SETTING_CALENDAR_ICS_URL', 'cached_calendar_url');
		bootRepairCal('SETTING_CALENDAR_ICS_URL_2', 'cached_calendar_url_2');
		bootRepairCal('SETTING_CALENDAR_ICS_URL_3', 'cached_calendar_url_3');

		/* 2.8.1: MeteoSwiss toggle → Open-Meteo Model dropdown. */
		if (flatSettings.SETTING_OM_MODEL === undefined || flatSettings.SETTING_OM_MODEL === '') {
			var ms = flatSettings.SETTING_METEOSWISS;
			flatSettings.SETTING_OM_MODEL = (ms === true || ms === 1 || ms === '1' || ms === 'true')
				? 'meteoswiss_icon_ch1' : 'best_match';
		}

		/* 2.9.4: event toggles → level dropdowns (on → default level 0, off → Off). */
		function lvl(newKey, oldKey, offVal) {
			if (flatSettings[newKey] !== undefined && flatSettings[newKey] !== '') return;
			if (flatSettings[oldKey] === undefined) return;
			flatSettings[newKey] = extractBool(flatSettings[oldKey]) === 0 ? offVal : 0;
		}
		lvl('SETTING_THUNDER_LEVEL', 'SETTING_EVENT_THUNDER', THUNDER_OFF);
		lvl('SETTING_SNOW_LEVEL', 'SETTING_EVENT_SNOW', SNOW_OFF);
		lvl('SETTING_FOG_LEVEL', 'SETTING_FOG_INDICATOR', FOG_OFF);

		if (JSON.stringify(flatSettings) !== raw) {
			localStorage.setItem('clay-settings', JSON.stringify(flatSettings));
		}
	} catch (e) { }
}

/**
 * Unwrap a raw value that may be stored as {value: ...}.
 *
 * @param   {*} value
 * @returns {*}
 */
function unwrapSettingValue(value) {
	if (value !== null && typeof value === 'object' && 'value' in value) {
		return value.value;
	}
	return value;
}

/**
 * Parse a boolean setting with a default fallback.
 *
 * @param   {Object}  settings
 * @param   {string}  key
 * @param   {boolean} defaultValue
 * @returns {boolean}
 */
function getBoolSetting(settings, key, defaultValue) {
	if (!settings || !(key in settings)) return defaultValue;
	var value = unwrapSettingValue(settings[key]);
	if (value === 'false' || value === '0' || value === 0) return false;
	if (value === 'true' || value === '1' || value === 1) return true;
	return !!value;
}

/**
 * Parse a string setting with a default fallback.
 *
 * @param   {Object} settings
 * @param   {string} key
 * @param   {string} defaultValue
 * @returns {string}
 */
function getStringSetting(settings, key, defaultValue) {
	if (!settings || !(key in settings)) return defaultValue;
	var value = unwrapSettingValue(settings[key]);
	if (value === null || value === undefined || value === '[object Object]') {
		return defaultValue;
	}
	var str = String(value);
	return str === '[object Object]' ? defaultValue : str;
}

/* 2.9.4 event levels (Clay values; Off is the last option, like the cloud /
 * sun / rain sensitivity selects). */
var THUNDER_OFF = 2; /* 0 any storm, 1 strong only, 2 off */
var SNOW_OFF = 3;    /* 0 any, 1 moderate+, 2 heavy only, 3 off */
var FOG_OFF = 3;     /* 0 vis < 1000 m, 1 < 500 m, 2 < 200 m, 3 off */
var FOG_VIS = [1000, 500, 200, 0];

/* Level from the new dropdown, else migrated from the pre-2.9.4 toggle. */
function eventLevel(s, newKey, oldKey, offVal) {
	s = s || {};
	var v = extractInt(s[newKey]);
	if (v !== null && v !== undefined && !isNaN(v) && v >= 0 && v <= offVal) return v;
	if (s[oldKey] !== undefined && s[oldKey] !== null && extractBool(s[oldKey]) === 0) return offVal;
	return 0;
}
function getThunderLevel(s) { return eventLevel(s, 'SETTING_THUNDER_LEVEL', 'SETTING_EVENT_THUNDER', THUNDER_OFF); }
function getSnowLevel(s) { return eventLevel(s, 'SETTING_SNOW_LEVEL', 'SETTING_EVENT_SNOW', SNOW_OFF); }
/** Fog level as max visibility in m (0 = off). */
function getFogVis(s) { return FOG_VIS[eventLevel(s || readClaySettings(), 'SETTING_FOG_LEVEL', 'SETTING_FOG_INDICATOR', FOG_OFF)]; }

/** Wind-event gust threshold in km/h (2.9.3): 0 = off, default 50. */
var WIND_GUST_CHOICES = [0, 40, 50, 60, 75];
function getWindGustKmh(settings) {
	var v = parseInt(getStringSetting(settings || readClaySettings(), 'SETTING_WIND_GUST', '50'), 10);
	return WIND_GUST_CHOICES.indexOf(v) >= 0 ? v : 50;
}

/**
 * Parse a static location from Clay settings.
 * Returns null when static mode is off or values are invalid.
 *
 * @param   {Object=} settings
 * @returns {{lat:number,lon:number}|null}
 */
function getStaticLocation(settings) {
	var allSettings = settings || readClaySettings();
	if (!getBoolSetting(allSettings, 'SETTING_USE_STATIC_LOCATION', false)) {
		return null;
	}

	var lat = parseFloat(getStringSetting(allSettings, 'SETTING_STATIC_LAT', ''));
	var lon = parseFloat(getStringSetting(allSettings, 'SETTING_STATIC_LON', ''));

	if (!isFinite(lat) || !isFinite(lon)) return null;
	if (lat < -90 || lat > 90) return null;
	if (lon < -180 || lon > 180) return null;

	return { lat: lat, lon: lon };
}

/**
 * Returns the configured forecast horizon in hours from Clay settings.
 *
 * @returns {number}
 */
function getForecastHoursFromSettings() {
	try {
		var raw = localStorage.getItem('clay-settings');
		if (raw) {
			var s = JSON.parse(raw);
			var fh = parseInt(s.SETTING_FORECAST_HOURS, 10);
			if (fh === 12 || fh === 18 || fh === 24 || fh === 36 || fh === 48) {
				return fh;
			}
		}
	} catch (e) { }
	return 24;
}

/**
 * Extract an integer from a raw Clay setting value.
 * Clay sends either a bare string ("0") or an object ({value:"0",label:"…"}).
 *
 * @param   {string|{value:string}} setting
 * @returns {number}  Parsed integer, or NaN if unparseable.
 */
function extractInt(setting) {
	var v = (setting !== null && typeof setting === 'object' && 'value' in setting)
		? setting.value : setting;
	return parseInt(v, 10);
}

/**
 * Extract a boolean toggle from a raw Clay setting value as 1/0.
 * With convert=false, Clay wraps toggle values in an object
 * ({value:false}); the object itself is always truthy, so we must
 * unwrap .value before testing it. Returns null if the setting is absent.
 *
 * @param   {boolean|string|{value:boolean}} setting
 * @returns {number|null}  1 (on), 0 (off), or null if unset.
 */
function extractBool(setting) {
	if (setting === null || setting === undefined) return null;
	var v = (typeof setting === 'object' && 'value' in setting)
		? setting.value : setting;
	// Guard against the string "false", which is truthy in JS.
	if (v === 'false' || v === '0') return 0;
	return v ? 1 : 0;
}

/**
 * Extract a string value from a raw Clay setting.
 *
 * @param   {string|{value:string}} setting
 * @returns {string}
 */
function extractString(setting) {
	var v = (typeof setting === 'object' && setting !== null && 'value' in setting)
		? setting.value : setting;
	if (v === null || v === undefined || v === '[object Object]') return '';
	return String(v);
}

/**
 * Build the settings AppMessage dict from raw (convert=false) Clay settings.
 * Pure apart from the locale fallback, so it is unit-testable.
 *
 * @param {Object} rawSettings
 * @returns {Object}
 */
function buildSettingsDict(rawSettings) {
	var tempUnit = extractInt(rawSettings['SETTING_TEMP_UNIT']);
	if (isNaN(tempUnit) || tempUnit < 0) {
		tempUnit = shouldUseFahrenheit() ? 1 : 0;
	}

	var dict = {};
	dict['SETTING_TEMP_UNIT'] = tempUnit;

	// Date format is a strftime string, not an integer — extract the raw value.
	var rawDateFmt = rawSettings['SETTING_DATE_FORMAT'];
	var dateFormat = (rawDateFmt !== null && typeof rawDateFmt === 'object' &&
		'value' in rawDateFmt)
		? rawDateFmt.value : rawDateFmt;
	if (typeof dateFormat === 'string' && dateFormat.length > 0) {
		dict['SETTING_DATE_FORMAT'] = dateFormat;
	}

	var fetchInterval = extractInt(rawSettings['SETTING_FETCH_INTERVAL']);
	if (fetchInterval === 15 || fetchInterval === 30 || fetchInterval === 60) {
		dict['SETTING_FETCH_INTERVAL'] = fetchInterval;
	}

	var forecastHours = extractInt(rawSettings['SETTING_FORECAST_HOURS']);
	if (!isNaN(forecastHours)) {
		dict['SETTING_FORECAST_HOURS'] = forecastHours;
	}

	var infillMode = extractInt(rawSettings['SETTING_INFILL_MODE']);
	if (!isNaN(infillMode)) {
		dict['SETTING_INFILL_MODE'] = infillMode;
	}

	var needleMode = extractInt(rawSettings['SETTING_NEEDLE_MODE']);
	if (!isNaN(needleMode)) {
		dict['SETTING_NEEDLE_MODE'] = needleMode;
	}

	var timelineBattery = extractInt(rawSettings['SETTING_TIMELINE_BATTERY']);
	if (!isNaN(timelineBattery)) {
		dict['SETTING_TIMELINE_BATTERY'] = timelineBattery;
	}

	var lightTheme = extractInt(rawSettings['SETTING_LIGHT_THEME']) === 1;
	dict['SETTING_LIGHT_THEME'] = lightTheme ? 1 : 0;

	var showBtAlert = extractBool(rawSettings['SETTING_SHOW_BT_ALERT']);
	if (showBtAlert !== null) {
		dict['SETTING_SHOW_BT_ALERT'] = showBtAlert;
	}

	var showSilentMode = extractBool(rawSettings['SETTING_SHOW_SILENT_MODE']);
	if (showSilentMode !== null) {
		dict['SETTING_SHOW_SILENT_MODE'] = showSilentMode;
	}

	var stepDisplay = extractInt(rawSettings['SETTING_STEP_DISPLAY']);
	if (isNaN(stepDisplay)) {
		/* Migrate old toggle + size (0 default / 1 large) */
		var legacyShow = extractBool(rawSettings['SETTING_SHOW_STEP_COUNT']);
		var legacySize = extractInt(rawSettings['SETTING_STEP_SIZE']);
		if (legacyShow === 0) {
			stepDisplay = 0; /* Off */
		} else if (legacySize === 1) {
			stepDisplay = 2; /* Bold */
		} else {
			stepDisplay = 1; /* Normal */
		}
	}
	if (stepDisplay < 0 || stepDisplay > 2) stepDisplay = 1;
	dict['SETTING_STEP_DISPLAY'] = stepDisplay;
	/* 3.1.2: the legacy SETTING_SHOW_STEP_COUNT / SETTING_STEP_SIZE keys are no
	 * longer sent; the watch reads SETTING_STEP_DISPLAY (legacy keys are only
	 * read above, to migrate old Clay settings). */

	var timelineEvent = extractInt(rawSettings['SETTING_TIMELINE_EVENT']);
	if (!isNaN(timelineEvent) && timelineEvent >= 0 && timelineEvent <= 2) {
		dict['SETTING_TIMELINE_EVENT'] = timelineEvent;
	}

	var cloudSensitivity = extractInt(rawSettings['SETTING_CLOUD_SENSITIVITY']);
	if (isNaN(cloudSensitivity)) cloudSensitivity = 1; // Sensitive default
	if (cloudSensitivity >= 0 && cloudSensitivity <= 4) {
		dict['SETTING_CLOUD_SENSITIVITY'] = cloudSensitivity;
	}

	var cloudDisplayMode = extractInt(rawSettings['SETTING_CLOUD_DISPLAY_MODE']);
	if (isNaN(cloudDisplayMode)) cloudDisplayMode = 0; // Total default
	if (cloudDisplayMode >= 0 && cloudDisplayMode <= 1) {
		dict['SETTING_CLOUD_DISPLAY_MODE'] = cloudDisplayMode;
	}

	var sunlightSens = extractInt(rawSettings['SETTING_SUNLIGHT_SENSITIVITY']);
	if (isNaN(sunlightSens)) {
		/* Migrate legacy toggle if sensitivity not yet in Clay store */
		var legacyRays = extractBool(rawSettings['SETTING_SUNLIGHT_RAYS']);
		sunlightSens = legacyRays ? 1 : 4;
	}
	if (sunlightSens >= 0 && sunlightSens <= 4) {
		dict['SETTING_SUNLIGHT_SENSITIVITY'] = sunlightSens;
	}

	var precipSens = extractInt(rawSettings['SETTING_PRECIP_SENSITIVITY']);
	if (isNaN(precipSens)) precipSens = 1;
	if (precipSens >= 0 && precipSens <= 4) {
		dict['SETTING_PRECIP_SENSITIVITY'] = precipSens;
	}

	/* 2.9.6: rain bar height (0 probability, 1 amount); watch-side only, no refetch. */
	dict['SETTING_RAIN_HEIGHT'] = extractInt(rawSettings['SETTING_RAIN_HEIGHT']) === 1 ? 1 : 0;
	/* 3.0.0: cloud thickness (0 on, 1 off); watch-side only, no refetch. */
	dict['SETTING_CLOUD_THICK'] = extractInt(rawSettings['SETTING_CLOUD_THICK']) === 1 ? 1 : 0;
	/* 3.0.3: leading zero (0 auto, 1 on, 2 off); watch-side only, no refetch. */
	var lz = extractInt(rawSettings['SETTING_LEADING_ZERO']);
	dict['SETTING_LEADING_ZERO'] = lz === 1 || lz === 2 ? lz : 0;

	var weatherEventDisp = extractInt(rawSettings['SETTING_WEATHER_EVENT_DISPLAY']);
	if (isNaN(weatherEventDisp)) weatherEventDisp = 0;
	if (weatherEventDisp >= 0 && weatherEventDisp <= 2) {
		dict['SETTING_WEATHER_EVENT_DISPLAY'] = weatherEventDisp;
	}

	dict['SETTING_ALARM_ROWS'] = require('./calendar').packAlarmRows(rawSettings);
	var backlightColors = extractBool(rawSettings['SETTING_BACKLIGHT_COLORS']);
	dict['SETTING_BACKLIGHT_COLORS'] = backlightColors ? 1 : 0;
	var wristFlick = extractBool(rawSettings['SETTING_WRIST_FLICK']);
	dict['SETTING_WRIST_FLICK'] = wristFlick ? 1 : 0;
	/* 2.9.4: the watch keeps its fog on/off flag; the level is applied phone-side. */
	dict['SETTING_FOG_INDICATOR'] = getFogVis(rawSettings) > 0 ? 1 : 0;
	/* Event levels packed into one mask for the watch (2.9.3/2.9.4), applied
	 * there so changing them never refetches weather:
	 * bit0 thunder off, bit1 tornado off, bit2 snow off, bit3 thunder strong
	 * only, bits4-5 snow minimum (0 any, 1 moderate+, 2 heavy only). 0 = defaults. */
	function evOn(k) { return rawSettings[k] === undefined || rawSettings[k] === null ? true : !!extractBool(rawSettings[k]); }
	var thL = getThunderLevel(rawSettings), snL = getSnowLevel(rawSettings);
	dict['SETTING_EVENT_FLAGS'] = (thL === THUNDER_OFF ? 1 : 0) | (evOn('SETTING_EVENT_TORNADO') ? 0 : 2) |
		(snL === SNOW_OFF ? 4 : 0) | (thL === 1 ? 8 : 0) | (snL > 0 && snL < SNOW_OFF ? snL << 4 : 0);
	var flickAction = extractInt(rawSettings['SETTING_FLICK_ACTION']);
	dict['SETTING_FLICK_ACTION'] = flickAction === 1 ? 1 : 0;
	/* 2.8.2: never refresh faster than the provider allows (quota/cadence). */
	var provider = extractString(rawSettings['SETTING_WEATHER_PROVIDER']) || 'openmeteo';
	var eff = weatherProviders.effectiveInterval(provider, dict['SETTING_FETCH_INTERVAL'] || DEFAULT_FETCH_INTERVAL);
	dict['SETTING_FETCH_INTERVAL'] = eff;
	return dict;
}

/* Settings whose change makes the cached weather payload wrong (2.8.7):
 * provider/key/model, location (incl. city label), unit, fog indicator,
 * wind gust threshold (2.9.3). */
var WEATHER_SETTING_KEYS = [
	'SETTING_WEATHER_PROVIDER', 'SETTING_WEATHER_API_KEY', 'SETTING_OM_MODEL', 'SETTING_METEOSWISS',
	'SETTING_OWM_ONECALL', 'SETTING_USE_STATIC_LOCATION', 'SETTING_STATIC_LAT', 'SETTING_STATIC_LON',
	'SETTING_LOCATION_OVERRIDE', 'SETTING_GEOCODE_ENABLED', 'SETTING_TEMP_UNIT', 'SETTING_FOG_INDICATOR', 'SETTING_FOG_LEVEL',
	'SETTING_WIND_GUST',
];

/** True when a weather-relevant setting differs between two flat/raw settings objects. */
function weatherSettingsChanged(oldS, newS) {
	oldS = oldS || {}; newS = newS || {};
	for (var i = 0; i < WEATHER_SETTING_KEYS.length; i++) {
		var k = WEATHER_SETTING_KEYS[i];
		if (extractString(oldS[k]) !== extractString(newS[k])) return true;
	}
	return false;
}

/* 3.1.2: API keys remembered per keyed provider, so switching provider
 * no longer sends one provider's key to another (401 → silent fallback). */
var WX_KEYS_STORE = 'carbon.wxkeys.v1';
function rememberProviderKey(map, provider, key, caps) {
	map = map && typeof map === 'object' ? map : {};
	if (caps && caps[provider] && caps[provider].key) {
		var k = String(key || '').trim();
		if (k) map[provider] = k; else delete map[provider];
	}
	return map;
}

module.exports = {
	rememberProviderKey: rememberProviderKey, WX_KEYS_STORE: WX_KEYS_STORE,
	weatherSettingsChanged: weatherSettingsChanged, WEATHER_SETTING_KEYS: WEATHER_SETTING_KEYS,
	shouldUseFahrenheit: shouldUseFahrenheit, getTempUnit: getTempUnit,
	getFetchIntervalMin: getFetchIntervalMin, DEFAULT_FETCH_INTERVAL: DEFAULT_FETCH_INTERVAL, getWeatherProvider: getWeatherProvider, getLocalityLanguage: getLocalityLanguage,
	readClaySettings: readClaySettings, flattenClaySettings: flattenClaySettings,
	sanitizeClaySettingsStorage: sanitizeClaySettingsStorage, unwrapSettingValue: unwrapSettingValue,
	getBoolSetting: getBoolSetting, getStringSetting: getStringSetting,
	getStaticLocation: getStaticLocation, getForecastHoursFromSettings: getForecastHoursFromSettings,
	extractInt: extractInt, extractBool: extractBool, extractString: extractString,
	buildSettingsDict: buildSettingsDict, getWindGustKmh: getWindGustKmh,
	getThunderLevel: getThunderLevel, getSnowLevel: getSnowLevel, getFogVis: getFogVis,
};
