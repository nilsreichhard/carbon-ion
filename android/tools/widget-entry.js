/**
 * Android widget entry for the bundled PebbleKit JS (see bundle-pkjs.js).
 *
 * Thin shim between the watchface's own phone-side modules (weather
 * providers, Clay config + custom function, settings, geocode cache) and the
 * host app. The host (Kotlin/QuickJS on Android, tools/run-node.js on a Mac)
 * defines these globals before evaluating the bundle:
 *
 *   __carbonHttpGet(url, headersJson) -> JSON string
 *        {"status": 200, "body": "...", "headers": {"expires": "..."}, "error": null}
 *        Synchronous; Android runs it on a background thread.
 *   __carbonStoreGet(key) -> string | null      (backs localStorage)
 *   __carbonStoreSet(key, value | null)         (null removes)
 *   __carbonLog(code, detail)
 *   __carbonLocale() -> "de-CH"                 (navigator.language)
 *
 * Exposed to the host (all take/return JSON strings):
 *   carbonFetchWeather(hostJson)  hostJson {lat, lon, isStatic}
 *        -> {payload, city, settings} | {error}
 *        Options, provider fallback, city lookup and the settings dict are
 *        built exactly like src/pkjs/index.js / settings.js do for the watch.
 *   carbonLocation()              -> {lat, lon} | null   (Clay "Fixed coordinates")
 *   carbonSettingsDict()          -> the integer dict the watch receives
 *   carbonSettingsInit(userDataJson), carbonSettingsChange(key, valueJson),
 *   carbonSettingsSave(extraJson) -> settings page model (see settingsModel)
 *
 * @license GPL-3.0-or-later
 */
var G = typeof globalThis !== 'undefined' ? globalThis : this;

/* ---- PebbleKit JS environment stand-ins ------------------------------- */
G.localStorage = {
	getItem: function (k) { var v = G.__carbonStoreGet(String(k)); return v === undefined ? null : v; },
	setItem: function (k, v) { G.__carbonStoreSet(String(k), String(v)); },
	removeItem: function (k) { G.__carbonStoreSet(String(k), null); },
};
if (typeof G.console === 'undefined' || !G.console || !G.console.log) {
	var clog = function () { G.__carbonLog('console', Array.prototype.join.call(arguments, ' ')); };
	G.console = { log: clog, warn: clog, error: clog, info: clog };
}
var NAV = { language: typeof G.__carbonLocale === 'function' ? String(G.__carbonLocale()) : 'en-US' };
try { Object.defineProperty(G, 'navigator', { value: NAV, configurable: true, writable: true }); } catch (e) { G.navigator = NAV; }
/* No watch: settings.js / geo.js fall back to navigator.language. */
G.Pebble = { getActiveWatchInfo: function () { throw new Error('no watch'); } };
/* The host is synchronous, so retries (net.js retryXhr) just run now. */
if (typeof G.setTimeout !== 'function') G.setTimeout = function (fn) { fn(); return 0; };

/* Synchronous XMLHttpRequest over the host bridge (used by net.js). */
function HostXHR() { this.status = 0; this.responseText = ''; this.readyState = 0; this._h = {}; this._rh = {}; }
HostXHR.prototype.open = function (m, url) { this._url = url; this.readyState = 1; };
HostXHR.prototype.setRequestHeader = function (k, v) { this._h[k] = v; };
HostXHR.prototype.getResponseHeader = function (n) { var v = this._rh[String(n).toLowerCase()]; return v === undefined ? null : v; };
HostXHR.prototype.send = function () {
	var res;
	try { res = JSON.parse(G.__carbonHttpGet(this._url, JSON.stringify(this._h))); } catch (e) { res = { status: 0, error: String(e) }; }
	this._rh = res.headers || {};
	this.status = res.status || 0;
	this.responseText = res.body || '';
	this.readyState = 4;
	if (res.error) { if (this.onerror) this.onerror(); return; }
	if (this.onload) this.onload();
	if (this.onreadystatechange) this.onreadystatechange();
};
G.XMLHttpRequest = HostXHR;

/* ---- the watchface's own modules -------------------------------------- */
var wx = require('../../src/pkjs/weather/index.js');
var settings = require('../../src/pkjs/settings.js');
var geo = require('../../src/pkjs/geo.js');
var net = require('../../src/pkjs/net.js');
var C = require('../../src/pkjs/constants.js');
var CLAY_CONFIG = require('../../src/pkjs/config.js');
var CLAY_CUSTOM = require('../../src/pkjs/config/custom.js');

/* Same storage keys as src/pkjs/index.js (not exported there). */
var LAST_FETCH_KEY = 'carbon.lastfetch.v1';
var LAST_WX_ERROR_KEY = 'carbon.wxerr.v1';

function J(o) { return JSON.stringify(o); }

/* ---- weather (mirrors index.js fetchAndSend) --------------------------- */
function httpGet(url, opts, cb) {
	/* index.js wxGet → net.xhrRequest; retries are left to WorkManager. */
	net.xhrRequest(url, opts, cb);
}

G.carbonFetchWeather = function (hostJson) {
	var host = JSON.parse(hostJson || '{}');
	ensureSettingsSeeded();
	var lat = host.lat, lon = host.lon;
	var wxSettings = settings.readClaySettings();
	var gs = settings.getStringSetting, gb = settings.getBoolSetting;
	/* src/pkjs/index.js wxOpts */
	var wxOpts = {
		provider: gs(wxSettings, 'SETTING_WEATHER_PROVIDER', 'openmeteo'),
		apiKey: gs(wxSettings, 'SETTING_WEATHER_API_KEY', ''),
		model: gs(wxSettings, 'SETTING_OM_MODEL', '') || 'best_match',
		fogOn: settings.getFogVis(wxSettings) > 0,
		fogVis: settings.getFogVis(wxSettings),
		windKmh: settings.getWindGustKmh(wxSettings),
		owmOneCall: gb(wxSettings, 'SETTING_OWM_ONECALL', false),
		lat: lat, lon: lon, unit: settings.getTempUnit(), now: Math.floor(Date.now() / 1000),
	};
	var out = null, fellBack = '';
	wx.fetchWeather(wxOpts, {
		get: httpGet,
		store: { get: function (k) { return localStorage.getItem(k); }, set: function (k, v) { localStorage.setItem(k, v); } },
		log: function (c, d) {
			G.__carbonLog(String(c), net.maskUrlsIn(String(d)));
			if (c === 'wx_fallback') fellBack = String(d);
		},
	}, function (err, payload) {
		/* index.js recordWxError / recordLastFetch (shown on the settings page) */
		var msg = err ? 'failed: ' + err : (fellBack ? 'fallback: ' + fellBack : '');
		if (msg) localStorage.setItem(LAST_WX_ERROR_KEY, J({ t: Date.now(), msg: net.maskUrlsIn(String(msg)).slice(0, 120) }));
		else localStorage.removeItem(LAST_WX_ERROR_KEY);
		if (err) { out = { error: String(err) }; return; }
		localStorage.setItem(LAST_FETCH_KEY, J({ t: Date.now(), provider: payload.provider, requested: wxOpts.provider,
			attribution: payload.attribution || '', hours: payload.valid_hours }));
		out = { payload: payload };
		/* 1.1.4: what the data really came from (weather/index.js fetchWeather: a keyed provider without a key
		 * keeps the Open-Meteo model; a rejected key or failure retries Open-Meteo with best_match). */
		var usedProv = payload.provider || wxOpts.provider;
		out.used = {
			provider: usedProv, requested: wxOpts.provider,
			model: usedProv !== 'openmeteo' ? '' : (fellBack && !/no API key/.test(fellBack) ? 'best_match' : wxOpts.model),
			fallback: fellBack ? net.maskUrlsIn(fellBack).slice(0, 120) : '',
		};
	});
	if (!out) out = { error: 'weather callback did not complete (host HTTP bridge must be synchronous)' };
	if (out.error) return J(out);
	out.city = cityName(lat, lon, !!host.isStatic, wxSettings);
	out.settings = androidDict(settings.readClaySettings());
	return J(out);
};

/* index.js city lookup: override text, geoname cache, BigDataCloud. */
function cityName(lat, lon, isStatic, s) {
	if (!settings.getBoolSetting(s, 'SETTING_GEOCODE_ENABLED', true))
		return settings.getStringSetting(s, 'SETTING_LOCATION_OVERRIDE', '');
	var cached = geo.getCachedGeoname(lat, lon, isStatic);
	if (cached) return cached;
	var url = C.GEOCODE_BASE_URL + '?latitude=' + lat + '&longitude=' + lon +
		'&localityLanguage=' + encodeURIComponent(settings.getLocalityLanguage());
	var name = '';
	net.retryXhr(url, 2, 1000, 'geocode fetch', function (err, text) {
		if (err) return;
		try {
			var json = JSON.parse(text);
			name = (json && (json.locality || json.city || json.principalSubdivision)) || '';
			geo.writeGeonameCache(lat, lon, name, isStatic);
		} catch (e) { name = ''; }
	});
	return name;
}

G.carbonLocation = function () {
	ensureSettingsSeeded();
	return J(settings.getStaticLocation(settings.readClaySettings()));
};

G.carbonSettingsDict = function () {
	ensureSettingsSeeded();
	return J(androidDict(settings.readClaySettings()));
};

/* ---- settings page (Clay config + custom function, headless) ------------ */

/* Watch-only items: no phone equivalent (see android/README.md). Whole
 * sections are dropped when every item in them is skipped. */
var SKIP_KEYS = {
	SETTING_WRIST_FLICK: 1, SETTING_FLICK_ACTION: 1,
	SETTING_TIMELINE_EVENT: 1, SETTING_CALENDAR_SOURCE_1: 1, SETTING_CALENDAR_ICS_URL: 1, SETTING_CALENDAR_COLOR_1: 1, SETTING_CALENDAR_PLACE_1: 1,
	SETTING_CALENDAR_SOURCE_2: 1, SETTING_CALENDAR_ICS_URL_2: 1, SETTING_CALENDAR_COLOR_2: 1, SETTING_CALENDAR_PLACE_2: 1,
	SETTING_CALENDAR_SOURCE_3: 1, SETTING_CALENDAR_ICS_URL_3: 1, SETTING_CALENDAR_COLOR_3: 1, SETTING_CALENDAR_PLACE_3: 1,
	SETTING_BACKLIGHT_COLORS: 1, SETTING_SHOW_BT_ALERT: 1, SETTING_SHOW_SILENT_MODE: 1,
	SETTING_STEP_DISPLAY: 1,
	SETTING_PHONE_ALARM: 1, /* the watch's Advanced toggle; 1.1.3 shows it next to the date instead (ANDROID_ITEMS_AFTER) */
};

/* 1.1.3: phone-only items placed right after a watch item. "Show next alarm" reuses the watch's
 * SETTING_PHONE_ALARM key (widget date row + app header); "Send next alarm to watch" gates PhoneAlarmSync. */
var ANDROID_ITEMS_AFTER = {
	SETTING_DATE_FORMAT: [
		{ type: 'toggle', messageKey: 'SETTING_PHONE_ALARM', label: 'Show next alarm',
			description: 'Shows the phone\u2019s next alarm after the date, on the widget and in the app.', defaultValue: true },
		{ type: 'toggle', messageKey: 'SETTING_ANDROID_ALARM_WATCH', label: 'Send next alarm to watch',
			description: 'Shares the next alarm with the Carbon Ion watch face, which shows it when its own \u201cShow next phone alarm\u201d setting is on.', defaultValue: true },
	],
};
var SKIP_TYPES = { 'debug-info': 1 };

function walkConfig(items, fn, section) {
	items.forEach(function (it) {
		if (it.type === 'section') walkConfig(it.items || [], fn, it);
		else fn(it, section);
	});
}

function defaultValueOf(it) {
	if (it.type === 'toggle') return !!it.defaultValue;
	if (it.defaultValue === undefined || it.defaultValue === null) return '';
	return it.type === 'select' || it.type === 'input' ? String(it.defaultValue) : it.defaultValue;
}

/* What Clay saves on first submit: every item's default. */
function configDefaults() {
	var d = {};
	walkConfig(CLAY_CONFIG, function (it) { if (it.messageKey) d[it.messageKey] = defaultValueOf(it); });
	Object.keys(ANDROID_ITEMS_AFTER).forEach(function (k) {
		ANDROID_ITEMS_AFTER[k].forEach(function (it) { if (d[it.messageKey] === undefined) d[it.messageKey] = defaultValueOf(it); });
	});
	return d;
}

/* Android-only theme value: Follow system (also the default). 0 Dark / 1 Light as on the watch. */
var THEME_SYSTEM = 2;
var THEME_MIGRATED_KEY = 'carbon.android.theme.v2';
/* 4.0.0: battery markers use the watch's own list and values (settings.js normalizeBattery:
 * 20/10/5/0, 10/5/0, 5/0, 10/0, 0, None); sanitizeClaySettingsStorage maps the old phone values
 * (20 / 0 % → 20/10/5/0, Off 8 → None 3). The 1.1.1 seeding already rewrote the watch's None (3)
 * to 0 % only (5), so a stored 3 is always the new None. */

function ensureSettingsSeeded() {
	settings.sanitizeClaySettingsStorage();
	var cur = settings.readClaySettings();
	var d = configDefaults(), changed = false;
	Object.keys(d).forEach(function (k) { if (cur[k] === undefined) { cur[k] = d[k]; changed = true; } });
	/* 1.0.1: Follow system is the default. 1.0.0 seeded Light (1), so a stored Light becomes Follow system once. */
	if (!localStorage.getItem(THEME_MIGRATED_KEY)) {
		if (settings.extractInt(cur.SETTING_LIGHT_THEME) !== 0) { cur.SETTING_LIGHT_THEME = THEME_SYSTEM; changed = true; }
		localStorage.setItem(THEME_MIGRATED_KEY, '1');
	}
	if (changed) localStorage.setItem('clay-settings', J(cur));
}

/* settings.js buildSettingsDict keeps only 0/1 for the theme (the watch has no "system"); pass 2 through. */
function androidDict(flat) {
	var dict = settings.buildSettingsDict(flat);
	dict.SETTING_ANDROID_ALARM_WATCH = settings.extractBool(flat.SETTING_ANDROID_ALARM_WATCH) === 0 ? 0 : 1;
	var t = settings.extractInt(flat.SETTING_LIGHT_THEME);
	dict.SETTING_LIGHT_THEME = (t === 0 || t === 1) ? t : THEME_SYSTEM;
	return dict;
}

/* Options on the phone only (config.js, i.e. the watch's own list, is unchanged):
 * `first`/`add` extend the watch's list, `replace` swaps it, `description` overrides its text. */
var ANDROID_OPTIONS = {
	SETTING_LIGHT_THEME: { first: [{ value: String(THEME_SYSTEM), label: 'Follow system (auto)' }] },
	/* 4.0.0: the watch's option list as is (WidgetSettings.batteryLevels); only the text says "phone". */
	SETTING_TIMELINE_BATTERY: {
		description: 'When the phone battery is expected to reach each level (while not charging).',
	},
};

function addAndroidOptions(key, options) {
	var x = ANDROID_OPTIONS[key];
	if (!x) return options;
	function mk(o) { return { value: o.value, label: o.label, disabled: false, hidden: false, style: {} }; }
	var out = (x.first || []).map(mk).concat(x.replace ? x.replace.map(mk) : options);
	if (x.add) {
		var at = -1;
		out.forEach(function (o, i) { if (o.value === x.after) at = i; });
		Array.prototype.splice.apply(out, [at < 0 ? out.length : at + 1, 0].concat(x.add.map(mk)));
	}
	return out;
}

var page = null; /* live settings page state */

function buildPage(userData) {
	ensureSettingsSeeded();
	var saved = settings.readClaySettings();
	var items = [], byKey = {}, byId = {}, handlers = [];
	var sectionKeep = {};
	/* Pass 1: which sections keep at least one setting. */
	walkConfig(CLAY_CONFIG, function (it, sec) {
		if (sec && it.messageKey && !SKIP_KEYS[it.messageKey]) sectionKeep[CLAY_CONFIG.indexOf(sec)] = true;
	});
	function add(it) {
		var m = {
			type: it.type, key: it.messageKey || null, id: it.id || null, label: it.label || '',
			description: (it.messageKey && ANDROID_OPTIONS[it.messageKey] && ANDROID_OPTIONS[it.messageKey].description) || it.description || '',
			attributes: it.attributes || {},
			value: it.messageKey ? (saved[it.messageKey] !== undefined ? saved[it.messageKey] : defaultValueOf(it)) : (it.defaultValue || ''),
			hidden: false, disabled: false, options: null, _h: [],
		};
		if (it.type === 'select') {
			m.value = String(m.value);
			m.options = addAndroidOptions(it.messageKey, (it.options || []).map(function (o) { return { value: String(o.value), label: o.label, disabled: false, hidden: false, style: {} }; }));
		}
		if (it.type === 'toggle') m.value = settings.extractBool(m.value) === 1;
		items.push(m);
		if (m.key) byKey[m.key] = m;
		if (m.id) byId[m.id] = m;
		(it.messageKey && ANDROID_ITEMS_AFTER[it.messageKey] || []).forEach(add);
	}
	walkConfig(CLAY_CONFIG, function (it, sec) {
		if (SKIP_TYPES[it.type]) return;
		if (it.messageKey && SKIP_KEYS[it.messageKey]) return;
		if (sec && !sectionKeep[CLAY_CONFIG.indexOf(sec)]) return;
		add(it);
	});

	function api(m) {
		if (!m) return null;
		if (m._api) return m._api;
		var depth = 0;
		m._api = {
			get: function () { return m.value; },
			set: function (v) {
				m.value = m.type === 'select' || m.type === 'input' ? String(v) : (m.type === 'toggle' ? !!v : v);
				if (depth > 4) return;
				depth++;
				try { m._h.forEach(function (h) { h(); }); } finally { depth--; }
			},
			show: function () { m.hidden = false; }, hide: function () { m.hidden = true; },
			enable: function () { m.disabled = false; }, disable: function () { m.disabled = true; },
			on: function (ev, fn) { if (ev === 'change') m._h.push(fn); },
			$manipulatorTarget: [m.options ? { options: m.options } : {}],
		};
		return m._api;
	}
	var clay = {
		meta: { userData: userData || {} },
		EVENTS: { AFTER_BUILD: 'AFTER_BUILD' },
		getItemByMessageKey: function (k) { return api(byKey[k]); },
		getItemById: function (id) { return api(byId[id]); },
		on: function (ev, fn) { if (ev === 'AFTER_BUILD') handlers.push(fn); },
	};
	CLAY_CUSTOM.call(clay);
	handlers.forEach(function (h) { h(); });
	return { items: items, byKey: byKey, clay: clay };
}

function pageState() {
	return J(page.items.map(function (m) {
		return {
			type: m.type, key: m.key, id: m.id, label: m.label, description: m.description,
			attributes: m.attributes, value: m.value, hidden: m.hidden, disabled: m.disabled,
			options: m.options && m.options.filter(function (o) { return !o.disabled && !o.hidden; })
				.map(function (o) { return { value: o.value, label: o.label }; }),
		};
	}));
}

/* index.js showConfiguration userData. */
G.carbonSettingsInit = function (userDataJson) {
	var ud = JSON.parse(userDataJson || '{}');
	var curS = settings.readClaySettings();
	var keys = {};
	try { keys = JSON.parse(localStorage.getItem(settings.WX_KEYS_STORE) || '{}') || {}; } catch (e) { keys = {}; }
	ud.wxCaps = wx.CAPS;
	try { ud.lastFetch = JSON.parse(localStorage.getItem(LAST_FETCH_KEY) || 'null'); } catch (e) { }
	try { ud.lastWxError = JSON.parse(localStorage.getItem(LAST_WX_ERROR_KEY) || 'null'); } catch (e) { }
	ud.wxKeys = settings.rememberProviderKey(keys, settings.getStringSetting(curS, 'SETTING_WEATHER_PROVIDER', 'openmeteo'),
		settings.getStringSetting(curS, 'SETTING_WEATHER_API_KEY', ''), wx.CAPS);
	page = buildPage(ud);
	return pageState();
};

G.carbonSettingsChange = function (key, valueJson) {
	var m = page.byKey[key];
	if (m) m._api ? m._api.set(JSON.parse(valueJson)) : (m.value = JSON.parse(valueJson));
	return pageState();
};

/* index.js webviewclosed: flatten, keep per-provider keys, clear cache. */
G.carbonSettingsSave = function () {
	var raw = settings.readClaySettings(); /* skipped (watch-only) keys keep their values */
	page.items.forEach(function (m) { if (m.key) raw[m.key] = m.value; });
	var flat = settings.flattenClaySettings(raw);
	localStorage.setItem('clay-settings', J(flat));
	var keys = {};
	try { keys = JSON.parse(localStorage.getItem(settings.WX_KEYS_STORE) || '{}') || {}; } catch (e) { keys = {}; }
	localStorage.setItem(settings.WX_KEYS_STORE, J(settings.rememberProviderKey(keys,
		settings.extractString(flat.SETTING_WEATHER_PROVIDER) || 'openmeteo', settings.extractString(flat.SETTING_WEATHER_API_KEY), wx.CAPS)));
	if (settings.extractBool(flat.SETTING_CLEAR_CACHE) === 1) {
		[C.GEONAME_CACHE_KEY, 'carbon.metno.v1', 'carbon.wxprev.v1', LAST_FETCH_KEY, LAST_WX_ERROR_KEY].forEach(function (k) { localStorage.removeItem(k); });
		flat.SETTING_CLEAR_CACHE = false; /* one-shot on the phone */
		localStorage.setItem('clay-settings', J(flat));
	}
	return J(androidDict(flat));
};
