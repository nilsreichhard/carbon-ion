/**
 * Carbon Ion — PebbleKit JS phone-side script
 *
 * 1. Gets device GPS location
 * 2. In parallel: fetches Open-Meteo weather + reverse geocode
 * 3. Sends all data to the watch via AppMessage
 *
 * Uses XMLHttpRequest (fetch() is not available in PebbleKit JS).
 * Uses localStorage to cache weather between refreshes.
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

/* 2.8.2: split into modules — net (HTTP), settings (Clay + settings dict),
 * calendar (ICS), wxsend (packing + cache), appmessage (transport), geo. */
var C = require('./constants');
var GEOCODE_BASE_URL = C.GEOCODE_BASE_URL, CACHE_KEY = C.CACHE_KEY, GEONAME_CACHE_KEY = C.GEONAME_CACHE_KEY;
var WEATHER_RETRY_ATTEMPTS = C.WEATHER_RETRY_ATTEMPTS, WEATHER_RETRY_BASE_DELAY_MS = C.WEATHER_RETRY_BASE_DELAY_MS;
var GEOCODE_RETRY_ATTEMPTS = C.GEOCODE_RETRY_ATTEMPTS, GEOCODE_RETRY_BASE_DELAY_MS = C.GEOCODE_RETRY_BASE_DELAY_MS;
var FETCH_DEDUPE_WINDOW_MS = C.FETCH_DEDUPE_WINDOW_MS, REQ_DEDUPE_WINDOW_MS = C.REQ_DEDUPE_WINDOW_MS;

var buildInfo = {
	version: C.APP_VERSION,
	hash: 'custom',
	branch: 'master',
	dirty: false,
	buildDate: '2026-10-15'
};
var eventLog = require('./eventlog');

var Clay = require('./clay');
var clayConfig = require('./config');
var clay = new Clay(clayConfig, require('./config/custom'), { autoHandleEvents: false });
clay.registerComponent(require('./config/debug'));

var net = require('./net');
var appmsg = require('./appmessage');
var settings = require('./settings');
var calendar = require('./calendar');
var wxsend = require('./wxsend');
var geo = require('./geo');
var weatherProviders = require('./weather/index');
var maskUrl = net.maskUrl, maskUrlsIn = net.maskUrlsIn, xhrRequest = net.xhrRequest, retryXhr = net.retryXhr;
var readClaySettings = settings.readClaySettings, getBoolSetting = settings.getBoolSetting;
var getWindGustKmh = settings.getWindGustKmh;
var getStringSetting = settings.getStringSetting, getTempUnit = settings.getTempUnit;
var getStaticLocation = settings.getStaticLocation, getLocalityLanguage = settings.getLocalityLanguage;
var fetchCalendarEvents = calendar.fetchCalendarEvents;
var readCache = wxsend.readCache, writeCache = wxsend.writeCache;
var getCachedGeoname = geo.getCachedGeoname, writeGeonameCache = geo.writeGeonameCache;

var s_fetchStartedAt = 0;
var s_lastHandledAt = 0;
var LAST_FETCH_KEY = 'carbon.lastfetch.v1';

/** Send a weather payload (dict build + duplicate-send guard). */
function sendToWatch(payload) {
	appmsg.sendDeduped(wxsend.buildWeatherDict(payload));
}

/** Remember provider/attribution/time of the last successful weather fetch
 * for the settings page. */
function recordLastFetch(wx, requested) {
	try {
		localStorage.setItem(LAST_FETCH_KEY, JSON.stringify({
			t: Date.now(), provider: wx.provider, requested: requested,
			attribution: wx.attribution || '', hours: wx.valid_hours,
		}));
	} catch (e) { }
}

/** A fallback cache is only resent while younger than STALE_CACHE_MAX_AGE_S. */
function cacheFresh(p) {
	return wxsend.cacheUsable(p, Math.floor(Date.now() / 1000), C.STALE_CACHE_MAX_AGE_S);
}

/** Last weather error / fallback, shown on the settings page (2.8.5). */
var LAST_WX_ERROR_KEY = 'carbon.wxerr.v1';
function recordWxError(msg) {
	try {
		if (msg) localStorage.setItem(LAST_WX_ERROR_KEY, JSON.stringify({ t: Date.now(), msg: maskUrlsIn(String(msg)).slice(0, 120) }));
		else localStorage.removeItem(LAST_WX_ERROR_KEY);
	} catch (e) { }
}
function readWxError() {
	try { return JSON.parse(localStorage.getItem(LAST_WX_ERROR_KEY) || 'null'); } catch (e) { return null; }
}

function readLastFetch() {
	try { return JSON.parse(localStorage.getItem(LAST_FETCH_KEY) || 'null'); } catch (e) { return null; }
}

function fetchAndSend(lat, lon, isStaticLocation) {
	var weatherDone = false;
	var cityDone = false;
	var calendarDone = false;
	var weatherOk = false;
	var payload = {};
	/* 3.0.3: was `var settings` (`clay` is taken by the Clay instance), which shadowed the settings module in this
	 * whole function and made every fetch throw at settings.getFogVis. */
	var claySettings = readClaySettings();
	var geocodeEnabled = getBoolSetting(claySettings, 'SETTING_GEOCODE_ENABLED', true);
	var locationOverride = getStringSetting(claySettings, 'SETTING_LOCATION_OVERRIDE', '');
	var cachedGeoname;

	var tempUnit = getTempUnit();
	payload.temp_unit = tempUnit;
	payload.lat = lat;
	payload.lon = lon;

	function tryFinish() {
		if (!weatherDone || !cityDone || !calendarDone) return;

		if (!weatherOk) {
			// Weather fetch/parse failed; fall back to stale cache so the watch
			// doesn't receive zeroed-out data.
			var stale = readCache();
			if (stale && stale.payload && cacheFresh(stale.payload)) {
				console.log('Carbon: weather failed, using stale cache');
				eventLog.log('stale_send', 'cache_fallback');
				s_fetchStartedAt = 0;
				sendToWatch(stale.payload);
			} else {
				console.log('Carbon: weather failed, no cache — not sending');
				s_fetchStartedAt = 0;
			}
			return;
		}

		s_fetchStartedAt = 0;
		writeCache(payload);
		sendToWatch(payload);
	}

	fetchCalendarEvents(payload, function (updatedPayload) {
		payload = updatedPayload;
		calendarDone = true;
		tryFinish();
	});

	// Weather via the selected provider (src/pkjs/weather/): 72 hourly slots
	// starting 12 h ago; keyed-provider failures fall back to Open-Meteo.
	var wxSettings = readClaySettings();
	var wxOpts = {
		provider: getStringSetting(wxSettings, 'SETTING_WEATHER_PROVIDER', 'openmeteo'),
		apiKey: getStringSetting(wxSettings, 'SETTING_WEATHER_API_KEY', ''),
		/* Legacy MeteoSwiss toggle is migrated by sanitizeClaySettingsStorage. */
		model: getStringSetting(wxSettings, 'SETTING_OM_MODEL', '') || 'best_match',
		fogOn: settings.getFogVis(wxSettings) > 0,
		fogVis: settings.getFogVis(wxSettings),
		windKmh: getWindGustKmh(wxSettings),
		owmOneCall: getBoolSetting(wxSettings, 'SETTING_OWM_ONECALL', false),
		lat: lat, lon: lon, unit: tempUnit, now: Math.floor(Date.now() / 1000),
	};
	function wxGet(url, opts, cb) {
		var attempt = 1;
		(function tryOnce() {
			xhrRequest(url, opts, function (err, text, info) {
				/* Retry transient errors only; a rejected key/quota is final. */
				if (err && attempt < WEATHER_RETRY_ATTEMPTS && !/HTTP 4\d\d/.test(err)) {
					var d = WEATHER_RETRY_BASE_DELAY_MS * Math.pow(2, attempt - 1);
					attempt++;
					eventLog.log('wx_retry', 'a=' + attempt + ' err=' + err);
					setTimeout(tryOnce, d);
					return;
				}
				cb(err, text, info);
			});
		})();
	}
	var fellBack = '';
	weatherProviders.fetchWeather(wxOpts, {
		get: wxGet,
		store: {
			get: function (k) { return localStorage.getItem(k); },
			set: function (k, v) { localStorage.setItem(k, v); },
		},
		log: function (c, d) {
			eventLog.log(c, maskUrlsIn(String(d)));
			if (c === 'wx_fallback') fellBack = String(d);
		},
	}, function (err, wx) {
		recordWxError(err ? 'failed: ' + err : (fellBack ? 'fallback: ' + fellBack : ''));
		if (err) {
			eventLog.log('wx_fail', 'err=' + maskUrlsIn(String(err)));
			console.log('Carbon: weather error: ' + err);
		} else {
			Object.keys(wx).forEach(function (k) { payload[k] = wx[k]; });
			weatherOk = true;
			eventLog.log('wx_ok', wx.provider + ' h=' + wx.valid_hours);
			recordLastFetch(wx, wxOpts.provider);
		}
		weatherDone = true;
		tryFinish();
	});

	if (!geocodeEnabled) {
		payload.city_name = locationOverride;
		cityDone = true;
		tryFinish();
		return;
	}

	cachedGeoname = getCachedGeoname(lat, lon, !!isStaticLocation);
	if (cachedGeoname) {
		eventLog.log('geo_cache', 'name_hit');
		payload.city_name = cachedGeoname;
		cityDone = true;
		tryFinish();
		return;
	}

	// BigDataCloud reverse geocode for city name
	var localityLanguage = getLocalityLanguage();
	var geocodeUrl = GEOCODE_BASE_URL +
		'?latitude=' + lat +
		'&longitude=' + lon +
		'&localityLanguage=' + encodeURIComponent(localityLanguage);

	retryXhr(geocodeUrl, GEOCODE_RETRY_ATTEMPTS,
		GEOCODE_RETRY_BASE_DELAY_MS, 'geocode fetch',
		function (err, responseText) {
			if (err) {
				console.log('Carbon: geocode error: ' + err);
				payload.city_name = '';
				cityDone = true;
				tryFinish();
				return;
			}
			try {
				var json = JSON.parse(responseText);
				payload.city_name =
					(json && (json.locality || json.city || json.principalSubdivision)) ||
					'';
				writeGeonameCache(lat, lon, payload.city_name, !!isStaticLocation);
			} catch (e) {
				payload.city_name = '';
			}
			cityDone = true;
			tryFinish();
		});
}

/**
 * 3.0.3: fetchAndSend behind a guard. Any exception clears the in-flight
 * marker (or the dedupe window would block the next request) and falls back
 * to a still-usable cache, so one bug can no longer stop all updates.
 */
function safeFetchAndSend(lat, lon, isStatic, cache) {
	try {
		fetchAndSend(lat, lon, isStatic);
	} catch (e) {
		s_fetchStartedAt = 0;
		eventLog.log('fetch_crash', String(e && e.message || e).slice(0, 80));
		console.log('Carbon: weather fetch crashed: ' + e);
		var c = cache || readCache();
		if (c && c.payload && cacheFresh(c.payload)) {
			console.log('Carbon: using stale cache');
			sendToWatch(c.payload);
		}
	}
}

/**
 * Check the local cache and send if still valid; otherwise acquire geolocation
 * and call fetchAndSend.
 */
function getWeather() {
	var nowMs = Date.now();
	if (s_lastHandledAt > 0 && nowMs - s_lastHandledAt < REQ_DEDUPE_WINDOW_MS) {
		return 'dedupe_req';
	}
	// Timestamp moves only on handled calls so bursts cannot self-starve.
	s_lastHandledAt = nowMs;

	var claySettings = readClaySettings(); /* 3.0.3: no shadowing of the settings module */

	// Check cache first
	var cache = readCache();
	if (cache && cache.expiresAt > Date.now()) {
		console.log('Carbon: using cached weather');
		eventLog.log('cache_hit', 'ttl=' + (cache.expiresAt - Date.now()));
		// Re-evaluate unit in case locale changed; re-fetch if unit differs
		var cachedUnit = cache.payload && cache.payload.temp_unit;
		if (cachedUnit && cachedUnit === getTempUnit()) {
			fetchCalendarEvents(cache.payload, function (payload) {
				sendToWatch(payload);
			});
			return;
		}
		console.log('Carbon: temp unit changed, refreshing weather');
	}

	// Layer 1 guard; later dedupe layers catch slow-cycle late arrivals.
	if (s_fetchStartedAt > 0 &&
		nowMs - s_fetchStartedAt < FETCH_DEDUPE_WINDOW_MS) {
		eventLog.aggregate('dedupe_fetch', 'ms=' + (nowMs - s_fetchStartedAt));
		return;
	}
	s_fetchStartedAt = nowMs;

	var staticLocation = getStaticLocation(claySettings);
	if (staticLocation) {
		eventLog.log('geo_static',
			'lat=' + staticLocation.lat.toFixed(4) +
			' lon=' + staticLocation.lon.toFixed(4));
		safeFetchAndSend(staticLocation.lat, staticLocation.lon, true, cache);
		return;
	}

	navigator.geolocation.getCurrentPosition(
		function (pos) {
			eventLog.log('geo_ok',
				'lat=' + pos.coords.latitude.toFixed(4) +
				' lon=' + pos.coords.longitude.toFixed(4));
			safeFetchAndSend(pos.coords.latitude, pos.coords.longitude, false, cache);
		},
		function (err) {
			s_fetchStartedAt = 0;
			eventLog.log('geo_fail', (err && err.message) ? err.message : 'unknown');
			console.log('Carbon: geolocation error: ' + err.message);
			// Fall back to stale cache if available
			if (cache && cacheFresh(cache.payload)) {
				console.log('Carbon: using stale cache');
				sendToWatch(cache.payload);
			}
		},
		{ timeout: 15000, maximumAge: 300000 }
	);
}

/**
 * Build a debug snapshot for the Clay config page.
 * Returns a plain object whose keys become collapsible sections in the
 * debug-info component; values are serialised as JSON in the display.
 *
 * @returns {Object}
 */
function formatDebugInfo() {
	var result = {
		buildInfo,
	};
	function mapLogEntries(entries) {
		return entries.map(function (entry) {
			var ts = entry && typeof entry.t === 'number' ? entry.t : null;
			var tsEnd = entry && typeof entry.tn === 'number' ? entry.tn : null;
			return {
				t: ts,
				iso: ts ? new Date(ts).toISOString() : null,
				tn: tsEnd,
				isoEnd: tsEnd ? new Date(tsEnd).toISOString() : null,
				e: entry ? entry.e : null,
				d: entry ? maskUrlsIn(entry.d) : null,
				n: entry && typeof entry.n === 'number' ? entry.n : null,
			};
		});
	}
	try {
		var rawCache = localStorage.getItem(CACHE_KEY);
		result.cache = rawCache ? JSON.parse(rawCache) : null;
	} catch (e) {
		result.cache = null;
	}
	try {
		var rawSettings = localStorage.getItem('clay-settings');
		result.settings = rawSettings ? JSON.parse(rawSettings) : null;
		if (result.settings) {
			Object.keys(result.settings).forEach(function (k) {
				if (/API_KEY/i.test(k)) {
					var kv = result.settings[k];
					if (kv && typeof kv === 'object') kv = kv.value;
					result.settings[k] = kv ? '***(' + String(kv).length + ' chars)' : '';
				} else if (/ICS_URL/i.test(k)) {
					var v = result.settings[k];
					if (v && typeof v === 'object') v = v.value;
					result.settings[k] = maskUrl(typeof v === 'string' ? v : '');
				}
				else if (typeof result.settings[k] === 'string') result.settings[k] = maskUrlsIn(result.settings[k]);
			});
		}
	} catch (e) {
		result.settings = null;
	}
	try {
		var logData = eventLog.read();
		result.eventLog = {
			current: mapLogEntries(logData.current || []),
			previous: mapLogEntries(logData.previous || []),
		};
	} catch (e) {
		result.eventLog = { current: [], previous: [] };
	}
	return result;
}

//
// Event listeners
//

Pebble.addEventListener('ready', function () {
	console.log('Carbon: PebbleKit JS ready');
	settings.sanitizeClaySettingsStorage();
	eventLog.log('ready', 'pkjs_ready');
	if (getWeather() === 'dedupe_req') {
		eventLog.aggregate('dedupe_req', 'ready');
	}
});

function readWxKeys() {
	try { return JSON.parse(localStorage.getItem(settings.WX_KEYS_STORE) || '{}') || {}; } catch (e) { return {}; }
}

Pebble.addEventListener('showConfiguration', function () {
	settings.sanitizeClaySettingsStorage();
	var userData = clay.meta.userData || {};
	var lastKnown = geo.getLastKnownCoordsFromCache();

	userData.debugInfo = formatDebugInfo();
	if (lastKnown) {
		userData.lastKnownLat = Number(lastKnown.lat.toFixed(4));
		userData.lastKnownLon = Number(lastKnown.lon.toFixed(4));
	} else {
		delete userData.lastKnownLat;
		delete userData.lastKnownLon;
	}

	/* Provider table (single source: weather/caps.js) + last fetch. */
	userData.wxCaps = weatherProviders.CAPS;
	userData.lastFetch = readLastFetch();
	userData.lastWxError = readWxError();
	/* 3.1.2: per-provider API keys (seeded from the saved single key). */
	var wxKeys = readWxKeys(), curS = readClaySettings();
	wxKeys = settings.rememberProviderKey(wxKeys, getStringSetting(curS, 'SETTING_WEATHER_PROVIDER', 'openmeteo'),
		getStringSetting(curS, 'SETTING_WEATHER_API_KEY', ''), weatherProviders.CAPS);
	userData.wxKeys = wxKeys;

	clay.meta.userData = userData;
	Pebble.openURL(clay.generateUrl());
});

Pebble.addEventListener('webviewclosed', function (e) {
	if (!e.response) return;
	var oldSettings = readClaySettings();

	// Use convert=false to get raw string-keyed settings; Clay's HTML <select>
	// always returns string values even when the config defines number options,
	// so we parse each integer value ourselves instead of relying on Clay's
	// type conversion (which leaves strings as-is and breaks C int8 parsing).
	var rawSettings = clay.getSettings(e.response, false);
	var dict = settings.buildSettingsDict(rawSettings);
	var extractString = settings.extractString, extractBool = settings.extractBool;

	try {
		var flatSettings = settings.flattenClaySettings(rawSettings);
		function repairCalUrl(key, cacheKey) {
			if (flatSettings[key] === '[object Object]' ||
				typeof flatSettings[key] === 'object') {
				flatSettings[key] = localStorage.getItem(cacheKey) || '';
			}
		}
		repairCalUrl('SETTING_CALENDAR_ICS_URL', 'cached_calendar_url');
		repairCalUrl('SETTING_CALENDAR_ICS_URL_2', 'cached_calendar_url_2');
		repairCalUrl('SETTING_CALENDAR_ICS_URL_3', 'cached_calendar_url_3');
		localStorage.setItem('clay-settings', JSON.stringify(flatSettings));
		localStorage.setItem(settings.WX_KEYS_STORE, JSON.stringify(settings.rememberProviderKey(readWxKeys(),
			extractString(rawSettings['SETTING_WEATHER_PROVIDER']) || 'openmeteo', extractString(rawSettings['SETTING_WEATHER_API_KEY']), weatherProviders.CAPS)));

		function cacheCalUrl(rawKey, cacheKey) {
			var cleanUrl = extractString(rawSettings[rawKey]).trim();
			if (cleanUrl && cleanUrl !== '[object Object]') {
				localStorage.setItem(cacheKey, cleanUrl);
				return true;
			}
			try { localStorage.removeItem(cacheKey); } catch (e) { }
			return false;
		}
		var anyUrl = false;
		anyUrl = cacheCalUrl('SETTING_CALENDAR_ICS_URL', 'cached_calendar_url') || anyUrl;
		anyUrl = cacheCalUrl('SETTING_CALENDAR_ICS_URL_2', 'cached_calendar_url_2') || anyUrl;
		anyUrl = cacheCalUrl('SETTING_CALENDAR_ICS_URL_3', 'cached_calendar_url_3') || anyUrl;
		if (!anyUrl) {
			try { localStorage.removeItem('cached_timeline_events'); } catch (clearErr) { }
			calendar.clearCalEventCaches(); /* 3.1.2 */
		}
	} catch (err) { }


	var clearCacheRequested = extractBool(rawSettings['SETTING_CLEAR_CACHE']) === 1;

	appmsg.sendToWatchWithRetry(dict);

	var newSettings = readClaySettings();

	if (clearCacheRequested) {
		localStorage.removeItem(GEONAME_CACHE_KEY);
		['cached_calendar_url', 'cached_calendar_url_2', 'cached_calendar_url_3'].forEach(function (k) {
			try { localStorage.removeItem(k); } catch (e) { }
		});
		['carbon.metno.v1', 'carbon.wxprev.v1', LAST_FETCH_KEY, LAST_WX_ERROR_KEY].forEach(function (k) {
			try { localStorage.removeItem(k); } catch (e) { }
		});
		calendar.clearCalEventCaches(); /* 3.1.2: per-calendar caches too */
		try { localStorage.removeItem('cached_timeline_events'); } catch (e) { }
		eventLog.clear();
		eventLog.log('cache_cleared', 'via_settings');

		try {
			newSettings['SETTING_CLEAR_CACHE'] = false;
			localStorage.setItem('clay-settings', JSON.stringify(newSettings));
		} catch (err) { }
	}

	var staticLatChanged =
		extractString(oldSettings['SETTING_STATIC_LAT']) !==
		extractString(newSettings['SETTING_STATIC_LAT']);
	var staticLonChanged =
		extractString(oldSettings['SETTING_STATIC_LON']) !==
		extractString(newSettings['SETTING_STATIC_LON']);

	if (staticLatChanged || staticLonChanged) {
		localStorage.removeItem(GEONAME_CACHE_KEY);
	}

	/* 2.8.7: only a weather-relevant change (or "Clear cache") drops the
	 * weather cache and refetches; anything else resends the cached weather
	 * (calendar is re-read on that path), so saves can't burn provider quota. */
	if (clearCacheRequested || settings.weatherSettingsChanged(oldSettings, newSettings)) {
		localStorage.removeItem(CACHE_KEY);
		/* 3.1.2: the saved past-hours grid belongs to the old provider. */
		if (extractString(oldSettings['SETTING_WEATHER_PROVIDER']) !== extractString(newSettings['SETTING_WEATHER_PROVIDER'])) {
			try { localStorage.removeItem('carbon.wxprev.v1'); } catch (e) { }
		}
		s_fetchStartedAt = 0;
		eventLog.log('settings_refetch', 'weather_changed');
	} else {
		eventLog.log('settings_resend', 'cache_kept');
	}
	s_lastHandledAt = 0;
	appmsg.resetDedupe();
	getWeather();
});

Pebble.addEventListener('appmessage', function (e) {
	if (e.payload && (e.payload['WEATHER_REQUEST'] !== undefined || e.payload[10000] !== undefined || e.payload['10000'] !== undefined)) {
		var seq = e.payload['WEATHER_REQUEST'] || e.payload[10000] || e.payload['10000'];
		var dropReason = getWeather();
		if (dropReason === 'dedupe_req') {
			eventLog.aggregate('dedupe_req', 'seq=' + seq);
			return;
		}
		eventLog.log('req', 'seq=' + seq);
		console.log('Carbon: weather refresh requested');
	}
});
