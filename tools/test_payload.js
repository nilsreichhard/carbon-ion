/* Host test (2.8.2): TZ=Europe/Zurich node tools/test_payload.js
 * OWM free 2.5 forecast, haze ≠ fog, per-provider refresh minimum, settings
 * dict + migration, MET User-Agent drop log, Clay keys, and the worst-case
 * AppMessage sizes against the watch inbox (app_message_open(2048, 256)). */
var store = {};
global.localStorage = { getItem: function (k) { return k in store ? store[k] : null; }, setItem: function (k, v) { store[k] = String(v); }, removeItem: function (k) { delete store[k]; } };
global.Pebble = { getActiveWatchInfo: function () { return { language: 'de_CH' }; } };
global.navigator = { language: 'de-CH' };
var W = require('../src/pkjs/weather/index.js');
var P = require('../src/pkjs/weather/provider.js');
var settings = require('../src/pkjs/settings.js');
var wxsend = require('../src/pkjs/wxsend.js');
var pkg = require('../package.json');
var fails = 0, n = 0;
function check(name, got, want) {
	n++; var ok = JSON.stringify(got) === JSON.stringify(want);
	if (!ok) fails++;
	console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '\n   got  ' + JSON.stringify(got) + '\n   want ' + JSON.stringify(want)));
}
var NOW = Date.UTC(2026, 9, 15, 16, 20) / 1000, CTX = { now: NOW, lat: 46.3, lon: 7.0 };

/* ---- OWM 2.5 (free) ---- */
var O = W.PROVIDERS.owm;
check('OWM default URL = free 2.5 forecast', /data\/2\.5\/forecast\?/.test(O.buildUrl(1, 2, { apiKey: 'K' })), true);
check('OWM One Call only when enabled', /data\/3\.0\/onecall/.test(O.buildUrl(1, 2, { apiKey: 'K', owmOneCall: true })), true);
var t0 = Date.UTC(2026, 9, 15, 18) / 1000;
var list = []; for (var i = 0; i < 40; i++) list.push({ dt: t0 + i * 10800, main: { temp: 10 + (i % 2) * 3, feels_like: 9 }, pop: 0.3, clouds: { all: 60 }, visibility: 10000,
	rain: i === 1 ? { '3h': 3 } : undefined, weather: [{ id: i === 2 ? 741 : i === 3 ? 721 : 803 }] });
var g = O.parse({ list: list, city: { sunrise: t0 - 40000, sunset: t0 - 1000 } }, CTX);
var s18 = P.slot(g, t0);
check('OWM 2.5 interpolates 3h → 1h (10, 11, 12, 13)', [g.temp[s18], g.temp[s18 + 1], g.temp[s18 + 2], g.temp[s18 + 3]], [10, 11, 12, 13]);
check('OWM 2.5 rain 3h → per hour', g.precip[s18 + 3], 1);
check('OWM 741 = fog; 721 haze ≠ fog', [g.fog[s18 + 6], g.fog[s18 + 9], g.wmo_code[s18 + 9]], [1, 0, 3]);
check('OWM 2.5 horizon ~54 h of grid (valid ≥ 66 slots)', g.valid_hours >= 66, true);

/* ---- refresh minimum per provider ---- */
check('effectiveInterval: meteoblue ≥ 60, metno ≥ 30, openmeteo 15', [W.effectiveInterval('meteoblue', 15), W.effectiveInterval('metno', 15), W.effectiveInterval('openmeteo', 15), W.effectiveInterval('tomorrow', 60)], [60, 30, 15, 60]);
var d = settings.buildSettingsDict({ SETTING_WEATHER_PROVIDER: 'meteoblue', SETTING_FETCH_INTERVAL: '15' });
check('Settings dict enforces Meteoblue 60 min', d['SETTING_FETCH_INTERVAL'], 60);
d = settings.buildSettingsDict({ SETTING_FETCH_INTERVAL: { value: '15' } });
check('Settings dict keeps 15 min for Open-Meteo', d['SETTING_FETCH_INTERVAL'], 15);

/* ---- migration of old saved settings (2.6.x) ---- */
d = settings.buildSettingsDict({ SETTING_SHOW_STEP_COUNT: { value: true }, SETTING_STEP_SIZE: '1', SETTING_SUNLIGHT_RAYS: { value: true } });
check('Legacy steps (size 1) → Bold; legacy rays → Sensitive', [d['SETTING_STEP_DISPLAY'], d['SETTING_SUNLIGHT_SENSITIVITY']], [2, 1]);
store['clay-settings'] = JSON.stringify({ SETTING_METEOSWISS: true, SETTING_FORECAST_HOURS: '36' });
settings.sanitizeClaySettingsStorage();
check('MeteoSwiss toggle → ICON-CH1 model, other keys kept', [JSON.parse(store['clay-settings']).SETTING_OM_MODEL, JSON.parse(store['clay-settings']).SETTING_FORECAST_HOURS], ['meteoswiss_icon_ch1', '36']);

/* ---- MET Norway User-Agent refused ---- */
var logs = [];
W.fetchWeather({ provider: 'metno', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true }, {
	store: { get: function () { return null; }, set: function () { } }, log: function (c, x) { logs.push(c + ' ' + x); },
	get: function (url, o, cb) { cb('HTTP 403 for api.met.no', null, { status: 403, droppedHeaders: ['User-Agent'], header: function () { return null; } }); },
}, function () { });
check('MET UA drop logged', logs.some(function (l) { return /wx_met_ua/.test(l); }), true);

/* ---- Clay: every messageKey is declared ---- */
var keys = []; (function walk(a) { a.forEach(function (it) { if (it.messageKey) keys.push(it.messageKey); if (it.items) walk(it.items); }); })(require('../src/pkjs/config.js'));
check('All Clay keys in package.json messageKeys', keys.filter(function (k) { return !(k in pkg.pebble.messageKeys); }), []);
var labels = []; (function walk(a) { a.forEach(function (it) { if (it.label) labels.push(it.label); if (it.items) walk(it.items); }); })(require('../src/pkjs/config.js'));
var PROPER = /^(ICS|API|One|Call|Quiet|Time|OWM)$/;
check('Clay labels are sentence case', labels.filter(function (l) { return l.split(' ').slice(1).some(function (w) { return /^[A-Z][a-z]/.test(w) && !PROPER.test(w); }); }), []);

/* ---- worst-case AppMessage size ---- */
function dictBytes(dict) {
	var mk = pkg.pebble.messageKeys, seen = {}, total = 1;
	Object.keys(dict).forEach(function (k) {
		var id = isNaN(Number(k)) ? mk[k] : Number(k);
		if (id === undefined) throw new Error('unknown key ' + k);
		if (seen[id]) return; seen[id] = 1;
		var v = dict[k], len;
		if (Array.isArray(v)) len = v.length;
		else if (typeof v === 'string') len = unescape(encodeURIComponent(v)).length + 1;
		else len = 4;
		total += 7 + len;
	});
	return total;
}
function arr(v) { var a = []; for (var i = 0; i < 72; i++) a.push(v); return a; }
var evs = []; for (var e = 0; e < 20; e++) evs.push({ start: 4e9, end: 4e9, color: 15, place: 3 });
var worst = { precip_prob: arr(100), temp_hourly: arr(-40), apparent_temp_hourly: arr(-40), cloud_cover: arr(100), cloud_cover_low: arr(100),
	cloud_cover_mid: arr(100), cloud_cover_high: arr(100), hourly_weather_code: arr(99), shortwave_radiation: arr(1000),
	city_name: 'Ünterwäldnerstrassenbürg-Långnamn', temp_unit: 'fahrenheit', current_temp: -40, high_temp: 120, low_temp: -40,
	weather_code: 99, sunrise_hour: 6, sunset_hour: 20, sunrise_minute: 59, sunset_minute: 59, fetch_time: 4e9,
	fog_bits: [255, 255, 255, 255, 255, 255, 255, 255, 255], wind_bits: [255, 255, 255, 255, 255, 255, 255, 255, 255], storm_bits: [255, 255, 255, 255, 255, 255, 255, 255, 255], precip_mm: arr(255), wx_flags: 3, valid_hours: 72, timeline_events: evs };
var wb = dictBytes(wxsend.buildWeatherDict(worst));
console.log('   weather dict worst case = ' + wb + ' bytes');
check('Worst-case weather dict fits 2048-byte inbox (with 10% headroom)', wb <= 2048 * 0.9, true);
var all = {}; keys.forEach(function (k) { all[k] = '1'; }); all.SETTING_DATE_FORMAT = '%A, %b %d';
var sb = dictBytes(settings.buildSettingsDict(all));
console.log('   settings dict worst case = ' + sb + ' bytes');
check('Settings dict fits inbox', sb <= 2048, true);

check('Worst-case weather dict carries WEATHER_WIND', 'WEATHER_WIND' in wxsend.buildWeatherDict(worst), true);
check('Wind gust setting: default 50, Off = 0, unknown → 50', [settings.getWindGustKmh({}), settings.getWindGustKmh({ SETTING_WIND_GUST: '0' }), settings.getWindGustKmh({ SETTING_WIND_GUST: '75' }), settings.getWindGustKmh({ SETTING_WIND_GUST: '33' })], [50, 0, 75, 50]);
check('Wind gust change refetches weather', settings.weatherSettingsChanged({ SETTING_WIND_GUST: '50' }, { SETTING_WIND_GUST: '60' }), true);

/* ---- 2.9.3: per-event toggles ---- */
check('Event flags default all on (missing keys → 0)', settings.buildSettingsDict({}).SETTING_EVENT_FLAGS, 0);
check('Event flags: thunder off = 1, tornado off = 2, snow off = 4, all off = 7',
	[settings.buildSettingsDict({ SETTING_EVENT_THUNDER: false }).SETTING_EVENT_FLAGS, settings.buildSettingsDict({ SETTING_EVENT_TORNADO: 'false' }).SETTING_EVENT_FLAGS,
	 settings.buildSettingsDict({ SETTING_EVENT_SNOW: 0 }).SETTING_EVENT_FLAGS, settings.buildSettingsDict({ SETTING_EVENT_THUNDER: false, SETTING_EVENT_TORNADO: false, SETTING_EVENT_SNOW: false }).SETTING_EVENT_FLAGS], [1, 2, 4, 7]);
check('Event toggles on → 0', settings.buildSettingsDict({ SETTING_EVENT_THUNDER: true, SETTING_EVENT_TORNADO: 1, SETTING_EVENT_SNOW: 'true' }).SETTING_EVENT_FLAGS, 0);
check('Thunder/tornado/snow toggles do not refetch weather (watch-side)',
	[settings.weatherSettingsChanged({ SETTING_EVENT_THUNDER: true }, { SETTING_EVENT_THUNDER: false }), settings.weatherSettingsChanged({ SETTING_EVENT_SNOW: true }, { SETTING_EVENT_SNOW: false }), settings.weatherSettingsChanged({ SETTING_EVENT_TORNADO: true }, { SETTING_EVENT_TORNADO: false })], [false, false, false]);
check('Fog and wind still refetch (phone-side)', [settings.weatherSettingsChanged({ SETTING_FOG_INDICATOR: true }, { SETTING_FOG_INDICATOR: false }), settings.weatherSettingsChanged({ SETTING_WIND_GUST: '50' }, { SETTING_WIND_GUST: '0' })], [true, true]);
var capsT = require('../src/pkjs/weather/caps.js');
check('Tornado toggle shown only for providers reporting WMO 19/99', Object.keys(capsT).filter(function (k) { return capsT[k].tornado; }).sort(), ['geosphere', 'openmeteo', 'owm']);
var setSrc = require('fs').readFileSync(__dirname + '/../src/c/modules/settings.h', 'utf8');
check('Settings struct: event_hide, rain_height, cloud_thick, leading_zero appended last (upgrade keeps defaults 0)', /uint8_t event_hide;[^\n]*\n\tuint8_t rain_height;[^\n]*\n\tuint8_t cloud_thick;[^\n]*\n[^\n]*\n\tuint8_t leading_zero;[^\n]*\n[^\n]*\n\} Settings;/.test(setSrc), true);
check('Watch event-flag key matches package.json', (/KEY_SETTING_EVENT_FLAGS MESSAGE_KEY_SETTING_EVENT_FLAGS\b/.test(setSrc) ? pkg.pebble.messageKeys.SETTING_EVENT_FLAGS : -1), pkg.pebble.messageKeys.SETTING_EVENT_FLAGS);

/* ---- 2.9.4: event sensitivity levels ---- */
var F = function (o) { return settings.buildSettingsDict(o).SETTING_EVENT_FLAGS; };
check('Levels default (no keys) → flags 0, fog on at 1000 m', [F({}), settings.buildSettingsDict({}).SETTING_FOG_INDICATOR, settings.getFogVis({})], [0, 1, 1000]);
check('Thunder: Any 0, Strong only 8, Off 1', [F({ SETTING_THUNDER_LEVEL: '0' }), F({ SETTING_THUNDER_LEVEL: '1' }), F({ SETTING_THUNDER_LEVEL: '2' })], [0, 8, 1]);
check('Snow: Any 0, Moderate+ 16, Heavy 32, Off 4', [F({ SETTING_SNOW_LEVEL: '0' }), F({ SETTING_SNOW_LEVEL: '1' }), F({ SETTING_SNOW_LEVEL: '2' }), F({ SETTING_SNOW_LEVEL: '3' })], [0, 16, 32, 4]);
check('Fog level → visibility 1000/500/200/off and watch flag', [0, 1, 2, 3].map(function (l) { var o = { SETTING_FOG_LEVEL: String(l) }; return settings.getFogVis(o) + '/' + settings.buildSettingsDict(o).SETTING_FOG_INDICATOR; }), ['1000/1', '500/1', '200/1', '0/0']);
check('Migration: old toggles off → Off, on → default level',
	[F({ SETTING_EVENT_THUNDER: false, SETTING_EVENT_SNOW: 'false' }), F({ SETTING_EVENT_THUNDER: true, SETTING_EVENT_SNOW: 1 }), settings.getFogVis({ SETTING_FOG_INDICATOR: false }), settings.getFogVis({ SETTING_FOG_INDICATOR: true })], [5, 0, 0, 1000]);
check('New level wins over a stale old toggle; junk level → default', [F({ SETTING_EVENT_SNOW: false, SETTING_SNOW_LEVEL: '2' }), F({ SETTING_THUNDER_LEVEL: '9' }), F({ SETTING_SNOW_LEVEL: 'x' })], [32, 0, 0]);
check('Tornado toggle unchanged (off = 2), combined with levels', F({ SETTING_EVENT_TORNADO: false, SETTING_THUNDER_LEVEL: '1', SETTING_SNOW_LEVEL: '2' }), 2 | 8 | 32);
check('Thunder/snow level changes do not refetch; fog level does',
	[settings.weatherSettingsChanged({ SETTING_THUNDER_LEVEL: '0' }, { SETTING_THUNDER_LEVEL: '1' }), settings.weatherSettingsChanged({ SETTING_SNOW_LEVEL: '0' }, { SETTING_SNOW_LEVEL: '2' }), settings.weatherSettingsChanged({ SETTING_FOG_LEVEL: '0' }, { SETTING_FOG_LEVEL: '2' })], [false, false, true]);
check('Settings dict worst case still small with level keys', sb < 400, true);
var gS = { wmo_code: arr(0), cape: arr(null), thunder_prob: arr(null) };
gS.wmo_code[0] = 95; gS.wmo_code[1] = 95; gS.cape[1] = 1600; gS.wmo_code[2] = 95; gS.thunder_prob[2] = 60; gS.wmo_code[3] = 96; gS.wmo_code[4] = 95; gS.cape[4] = 900; gS.thunder_prob[4] = 30; gS.wmo_code[9] = 99; gS.cape[10] = 3000;
check('Storm bits: 95 plain no; CAPE ≥1500, thunder prob ≥50, hail 96/99 yes; CAPE without storm no', P.stormBits(gS), [2 | 4 | 8, 2, 0, 0, 0, 0, 0, 0, 0]);
check('Strong thresholds', [P.STRONG_CAPE, P.STRONG_THUNDER_PROB], [1500, 50]);
var capsL = require('../src/pkjs/weather/caps.js');
check('Strong only hidden for providers without strength data', Object.keys(capsL).filter(function (k) { return capsL[k].strongStorm === false || !capsL[k].strongStorm; }).sort(), ['meteoblue', 'tomorrow']);
check('Stricter fog levels hidden without visibility data', Object.keys(capsL).filter(function (k) { return !capsL[k].fogVis; }).sort(), ['metno']);
check('Worst-case weather dict carries WEATHER_STORM (9 bytes)', wxsend.buildWeatherDict(worst).WEATHER_STORM.length, 9);
check('Default weather dict sends zero storm bits when absent', wxsend.buildWeatherDict(noTstorm()).WEATHER_STORM, [0, 0, 0, 0, 0, 0, 0, 0, 0]);
function noTstorm() { return { precip_prob: arr(10), temp_hourly: arr(12), hourly_weather_code: arr(2), valid_hours: 60, fetch_time: 1760540400 }; }
var cfgItems = []; (function walk(a) { a.forEach(function (it) { if (it.messageKey) cfgItems.push(it); if (it.items) walk(it.items); }); })(require('../src/pkjs/config.js'));
var byKey = {}; cfgItems.forEach(function (it) { byKey[it.messageKey] = it; });
check('Clay: thunder/snow/fog are selects with Off last, default 0; toggles gone; tornado toggle kept',
	[byKey.SETTING_THUNDER_LEVEL.type, byKey.SETTING_SNOW_LEVEL.type, byKey.SETTING_FOG_LEVEL.type,
	 byKey.SETTING_THUNDER_LEVEL.options.slice(-1)[0].label, byKey.SETTING_SNOW_LEVEL.options.slice(-1)[0].label, byKey.SETTING_FOG_LEVEL.options.slice(-1)[0].label,
	 byKey.SETTING_THUNDER_LEVEL.defaultValue + byKey.SETTING_SNOW_LEVEL.defaultValue + byKey.SETTING_FOG_LEVEL.defaultValue,
	 !!byKey.SETTING_EVENT_THUNDER, !!byKey.SETTING_EVENT_SNOW, !!byKey.SETTING_FOG_INDICATOR, byKey.SETTING_EVENT_TORNADO.type],
	['select', 'select', 'select', 'Off', 'Off', 'Off', 0, false, false, false, 'toggle']);
check('Level option counts: thunder 3, snow 4, fog 4', [byKey.SETTING_THUNDER_LEVEL.options.length, byKey.SETTING_SNOW_LEVEL.options.length, byKey.SETTING_FOG_LEVEL.options.length], [3, 4, 4]);
check('New message keys 10086-10089 in package.json and appinfo.json',
	['SETTING_THUNDER_LEVEL', 'SETTING_SNOW_LEVEL', 'SETTING_FOG_LEVEL', 'WEATHER_STORM'].map(function (k) { return pkg.pebble.messageKeys[k] + '/' + require('../appinfo.json').appKeys[k]; }),
	['10086/10086', '10087/10087', '10088/10088', '10089/10089']);
var evC = require('fs').readFileSync(__dirname + '/../src/c/ui/event_layer.c', 'utf8');
check('Watch: snow levels any/moderate+/heavy code sets', [/level >= 2\)\s*return code == 75 \|\| code == 86;/.test(evC), /level == 1\)\s*return code == 73 \|\| code == 75 \|\| code == 86;/.test(evC)], [true, true]);
check('Watch: Strong only = hail code or storm bit', /EVENT_THUNDER_STRONG\)\s*\|\|\s*code >= 96 \|\| strong/.test(evC), true);
check('Watch flag bits match phone packing', [/EVENT_THUNDER_STRONG 0x08/.test(setSrc), /EVENT_SNOW_LVL_SHIFT 4/.test(setSrc), /EVENT_SNOW_LVL_MASK 0x30/.test(setSrc), /EVENT_FLAGS_MASK 0x3f/.test(setSrc)], [true, true, true, true]);
check('Weather persist version bumped for thick_bits (7), prefix migration from 5', /WEATHER_PERSIST_VERSION 7[\s\S]*WEATHER_PERSIST_MIN_PREFIX 5/.test(require('fs').readFileSync(__dirname + '/../src/c/main.c', 'utf8')), true);

/* ---- 2.8.5: weather without a current temperature ---- */
var noT = { precip_prob: arr(10), temp_hourly: arr(12), hourly_weather_code: arr(2), valid_hours: 60, fetch_time: 1760540400 };
var dn = wxsend.buildWeatherDict(noT);
check('No current temp: WEATHER_TEMP omitted, fetch time + hourly still sent', ['WEATHER_TEMP' in dn, dn.WEATHER_FETCH_TIME, dn.WEATHER_TEMP_HOURLY.length], [false, 1760540400, 72]);
delete noT.fetch_time;
check('fetch time is always sent (falls back to now)', typeof wxsend.buildWeatherDict(noT).WEATHER_FETCH_TIME, 'number');
var mainC = require('fs').readFileSync(__dirname + '/../src/c/main.c', 'utf8');
var inbox = mainC.slice(mainC.indexOf('/* 3.0.3: prv_inbox_received split'), mainC.indexOf('static void prv_inbox_dropped')); /* 3.0.3: incl. helpers */
check('Watch treats hourly arrays / fetch time as weather (not only WEATHER_TEMP)',
	[/memcpy\(arrs\[i\]\.dst[\s\S]{0,40}got_weather = true/.test(inbox), /if \(ft\)\s*got_weather = true/.test(inbox), /if \(!g->temp\)\s*s_weather\.current_temp = WEATHER_TEMP_INVALID/.test(inbox) && /if \(prv_inbox_hourly_arrays\(iter\)\)\s*got_weather = true/.test(inbox)], [true, true, true]);
check('Weather dict has no duplicate numeric keys', Object.keys(dn).filter(function (k) { return !isNaN(Number(k)); }), []);

/* ---- 2.8.5: cache timeout follows the provider minimum ---- */
check('cacheTtlMs: meteoblue 15→60 min, metno 15→30, openmeteo 15, tomorrow 60 (minus 2 min margin)',
	[wxsend.cacheTtlMs('meteoblue', 15), wxsend.cacheTtlMs('metno', 15), wxsend.cacheTtlMs('openmeteo', 15), wxsend.cacheTtlMs('tomorrow', 60)].map(function (v) { return v / 60000; }),
	[58, 28, 13, 58]);
store['clay-settings'] = JSON.stringify({ SETTING_WEATHER_PROVIDER: 'meteoblue', SETTING_FETCH_INTERVAL: '15' });
var t0w = Date.now(); wxsend.writeCache({ fetch_time: 1 });
var ttl = JSON.parse(store[require('../src/pkjs/constants').CACHE_KEY]).expiresAt - t0w;
check('writeCache uses the saved provider (Meteoblue: ~58 min, not 13)', Math.round(ttl / 60000), 58);
check('Fallback cache only within 24 h', [wxsend.cacheUsable({ fetch_time: 1000 }, 1000 + 86400, 86400), wxsend.cacheUsable({ fetch_time: 1000 }, 1001 + 86400, 86400), wxsend.cacheUsable({}, 5, 86400)], [true, false, false]);

/* ---- 2.8.7 ---- */
var d7 = settings.buildSettingsDict({ SETTING_TEMP_UNIT: '0', SETTING_FETCH_INTERVAL: '30', SETTING_LIGHT_THEME: '1', SETTING_WRIST_FLICK: true });
check('Settings dict uses named keys only (no numeric duplicates)', Object.keys(d7).filter(function (k) { return /^\d+$/.test(k); }), []);
check('Settings dict still carries the values', [d7.SETTING_TEMP_UNIT, d7.SETTING_FETCH_INTERVAL, d7.SETTING_LIGHT_THEME], [0, 30, 1]);
var base7 = { SETTING_WEATHER_PROVIDER: 'openmeteo', SETTING_LIGHT_THEME: '0', SETTING_STATIC_LAT: '47.3' };
function with7(o) { var r = JSON.parse(JSON.stringify(base7)); for (var k in o) r[k] = o[k]; return r; }
check('Theme-only save keeps the weather cache', settings.weatherSettingsChanged(base7, with7({ SETTING_LIGHT_THEME: '1', SETTING_FORECAST_HOURS: '36' })), false);
check('Provider / API key / model / location / unit / fog changes refetch',
	[{ SETTING_WEATHER_PROVIDER: 'metno' }, { SETTING_WEATHER_API_KEY: 'k' }, { SETTING_OM_MODEL: 'icon_d2' }, { SETTING_STATIC_LAT: '48' }, { SETTING_TEMP_UNIT: '1' }, { SETTING_FOG_INDICATOR: false }].map(function (o) { return settings.weatherSettingsChanged(base7, with7(o)); }),
	[true, true, true, true, true, true]);
check('Clay {value} wrappers compare by value', settings.weatherSettingsChanged({ SETTING_WEATHER_PROVIDER: 'metno' }, { SETTING_WEATHER_PROVIDER: { value: 'metno' } }), false);
var wd7 = wxsend.buildWeatherDict({ temp_unit: 'fahrenheit', fetch_time: 5 });
check('Weather dict: no high/low keys when missing (watch resets them)', ['WEATHER_TEMP_HIGH', 'WEATHER_TEMP_LOW', 'WEATHER_TEMP'].filter(function (k) { return k in wd7; }), []);
check('Weather dict keeps SETTING_TEMP_UNIT (watch learns the locale unit before any settings save)', wd7.SETTING_TEMP_UNIT, 1);

/* ---- 2.9.5: status icons share one footprint ---- */
var tlSrc = require('fs').readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8');
function bitsOf(name) { var m = new RegExp(name + '\\[STATUS_ICON_H\\] = \\{([\\s\\S]*?)\\};').exec(tlSrc); return m ? (m[1].match(/"[^"]*"/g) || []).map(function (r) { return r.slice(1, -1); }) : []; }
var bBt = bitsOf('s_bt_off_bits'), bQt = bitsOf('s_quiet_bits');
check('Status icons: both 15x17 maps', [bBt.length, bQt.length, bBt.every(function (r) { return r.length === 15; }), bQt.every(function (r) { return r.length === 15; })], [17, 17, true, true]);
check('Status icons: identical badge outline (same drawn pixels)', bBt.map(function (r) { return r.replace(/[#.]/g, 'x'); }), bQt.map(function (r) { return r.replace(/[#.]/g, 'x'); }));
check('Status icons: same slash diagonal (1,2)-(13,14) in both', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13].every(function (i) { return bBt[i + 1][i] === '.'; }) && [1, 2, 3, 13].every(function (i) { return bQt[i + 1][i] === '.'; }), true);
check('Version 3.1.0 everywhere', [pkg.version, require('../appinfo.json').versionLabel, require('../src/pkjs/constants').APP_VERSION === '3.1.0' && /version: C\.APP_VERSION/.test(require('fs').readFileSync(__dirname + '/../src/pkjs/index.js', 'utf8'))], ['3.1.0', '3.1.0', true]);

/* ---- 2.9.6: rain bar height + hourly mm ---- */
var mainSrc = require('fs').readFileSync(__dirname + '/../src/c/main.c', 'utf8');
var wxH = require('fs').readFileSync(__dirname + '/../src/c/modules/weather.h', 'utf8');
check('Worst-case weather dict carries WEATHER_PRECIP_MM (72 bytes, capped 255)', [wxsend.buildWeatherDict(worst).WEATHER_PRECIP_MM.length, Math.max.apply(null, wxsend.buildWeatherDict(worst).WEATHER_PRECIP_MM)], [72, 255]);
check('Rain bar height: default Probability 0, Amount 1, junk → 0', [settings.buildSettingsDict({}).SETTING_RAIN_HEIGHT, settings.buildSettingsDict({ SETTING_RAIN_HEIGHT: '1' }).SETTING_RAIN_HEIGHT, settings.buildSettingsDict({ SETTING_RAIN_HEIGHT: { value: 1 } }).SETTING_RAIN_HEIGHT, settings.buildSettingsDict({ SETTING_RAIN_HEIGHT: '7' }).SETTING_RAIN_HEIGHT], [0, 1, 1, 0]);
check('Rain bar height change does not refetch weather', settings.weatherSettingsChanged({ SETTING_RAIN_HEIGHT: '0' }, { SETTING_RAIN_HEIGHT: '1' }), false);
check('Watch rain-height + mm keys match package.json', [(/KEY_SETTING_RAIN_HEIGHT MESSAGE_KEY_SETTING_RAIN_HEIGHT\b/.test(setSrc) ? pkg.pebble.messageKeys.SETTING_RAIN_HEIGHT : -1), pkg.pebble.messageKeys.WEATHER_PRECIP_MM], [pkg.pebble.messageKeys.SETTING_RAIN_HEIGHT, 10091]);
check('appinfo.json carries the new keys', [require('../appinfo.json').appKeys ? 1 : 1, JSON.stringify(require('../appinfo.json')).indexOf('WEATHER_PRECIP_MM') > 0], [1, true]);
check('Watch reads WEATHER_PRECIP_MM into precip_mm; HAS_MM flag 0x02', [/MESSAGE_KEY_WEATHER_PRECIP_MM, s_weather\.precip_mm/.test(mainSrc), /WX_FLAG_HAS_MM 0x02/.test(wxH)], [true, true]);
check('WeatherData persisted in 4 blocks (new key 10)', [/WEATHER_BLOCKS 4/.test(mainSrc), /STORAGE_KEY_WEATHER_PART4 10 /.test(require('fs').readFileSync(__dirname + '/../src/c/modules/storage_keys.h', 'utf8')), /PERSIST_DATA_MAX_LENGTH \* 4/.test(wxH)], [true, true, true]);
var rh = null; (function walk(a) { a.forEach(function (it) { if (it.messageKey === 'SETTING_RAIN_HEIGHT') rh = it; if (it.items) walk(it.items); }); })(require('../src/pkjs/config.js'));
check('Clay "Rain bar height": Probability (default) / Amount', [rh && rh.defaultValue, rh && rh.options.map(function (o) { return o.label + '=' + o.value; }).join(',')], [0, 'Probability=0,Amount=1']);
check('Clay hides Amount without mm (custom.js)', /c\.mm === false/.test(require('fs').readFileSync(__dirname + '/../src/pkjs/config/custom.js', 'utf8')), true);
var cc = require('child_process').spawnSync('sh', ['-c', 'cc -std=c99 -Wall -Wextra -Werror -o /tmp/carbon_test_precip_map ' + __dirname + '/test_precip_map.c && /tmp/carbon_test_precip_map'], { encoding: 'utf8' });
console.log(cc.stdout.split('\n').filter(function (l) { return /FAIL|passed/.test(l); }).map(function (l) { return '   [C] ' + l; }).join('\n'));
check('Precip mapping C tests (tools/test_precip_map.c) pass', cc.status, 0);

/* ---- 2.9.6: cloud palette (size + heavy only) ---- */
var cloudSrc = require('fs').readFileSync(__dirname + '/../src/c/ui/cloud_layer.c', 'utf8');
check('Clouds: no Celeste snow colour, heavy = DarkGray (light) / LightGray (dark)', [/Celeste/.test(cloudSrc), /heavy\)\s*return is_light \? GColorDarkGray : GColorLightGray/.test(cloudSrc), /return is_light \? GColorLightGray : GColorWhite/.test(cloudSrc)], [false, true, true]);
check('Clouds: dead Split lobe sizes removed; fog dotting gone (3.0.0)', [/return 1;\s*if \(cover < med_th\)\s*return 2;/.test(cloudSrc), /fog/.test(cloudSrc)], [false, false]);
/* ---- 3.0.0: cloud thickness ---- */
var wdT = wxsend.buildWeatherDict(worst);
check('Weather dict carries WEATHER_CLOUD_THICK (18 bytes, also without phone data)', [wdT.WEATHER_CLOUD_THICK.length, wxsend.buildWeatherDict({}).WEATHER_CLOUD_THICK.length], [18, 18]);
check('Worst case with thickness <= 90 % of the inbox', dictBytes(wxsend.buildWeatherDict(Object.assign({ cloud_thick: arr(255).slice(0, 18) }, worst))) <= 2048 * 0.9, true);
check('Cloud thickness setting: default On 0, Off 1, junk → 0', [settings.buildSettingsDict({}).SETTING_CLOUD_THICK, settings.buildSettingsDict({ SETTING_CLOUD_THICK: '1' }).SETTING_CLOUD_THICK, settings.buildSettingsDict({ SETTING_CLOUD_THICK: '5' }).SETTING_CLOUD_THICK], [0, 1, 0]);
check('Watch cloud-thickness keys match package.json', [(/KEY_SETTING_CLOUD_THICK MESSAGE_KEY_SETTING_CLOUD_THICK\b/.test(setSrc) ? pkg.pebble.messageKeys.SETTING_CLOUD_THICK : -1), pkg.pebble.messageKeys.WEATHER_CLOUD_THICK], [pkg.pebble.messageKeys.SETTING_CLOUD_THICK, 10093]);
check('appinfo.json carries the 3.0.0 keys in both key maps', (JSON.stringify(require('../appinfo.json')).match(/WEATHER_CLOUD_THICK/g) || []).length, 2);
check('Watch reads WEATHER_CLOUD_THICK into thick_bits (18 bytes)', [/MESSAGE_KEY_WEATHER_CLOUD_THICK/.test(mainSrc), /thick_bits\[WEATHER_HOURLY_COUNT \/ 4\]/.test(wxH)], [true, true]);
check('Thick palette: LightGray + DarkGray checker; light = background checker', [/\*base = GColorLightGray;\s*\*dither = GColorDarkGray;/.test(cloudSrc), /is_light \? GColorWhite : GColorBlack/.test(cloudSrc)], [true, true]);

/* ---- 3.0.1 ---- */
check('Time drawn on a fixed box (digits as 0; 12h always "00:00", 3.0.3), steps flank uses it too', [/prv_time_template\("00:00", layer->time_tmpl/.test(require('fs').readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8')), /prv_time_origin\(tl, time_font, w, &box_right\)/.test(require('fs').readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8'))], [true, true]);
check('Light theme light rain = VividCerulean, heavy CobaltBlue; dark unchanged', /\(light \? GColorCobaltBlue : GColorPictonBlue\)\s*: \(light \? GColorVividCerulean : GColorCobaltBlue\)/.test(require('fs').readFileSync(__dirname + '/../src/c/ui/precip_layer.c', 'utf8')), true);
var evSrc = require('fs').readFileSync(__dirname + '/../src/c/ui/event_layer.c', 'utf8');
var tlSrc = require('fs').readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8');
check('Fog: DarkGray checkered with Black (light) / LightGray (dark), icon and range', [/light_theme \? GColorBlack : GColorLightGray/.test(evSrc), /gx_mask2\(ctx, &t, mask, FOG_W/.test(evSrc), /prv_line2\(ctx, GPoint\(x_start, center_y\)/.test(evSrc)], [true, true, true]);
check('Status icons 2 px clear of the digits (3.0.2), same gap and size', [/STATUS_ICON_X 3 /.test(tlSrc), /STATUS_ICON_GAP 6 /.test(tlSrc), /STATUS_ICON_W 15\n#define STATUS_ICON_H 17/.test(tlSrc)], [true, true, true]);
check('3.0.3: 12h blank lead cell, steps capped at 99K in one font size (no 14/09 fallback), weather request retried once after 30 s', [/time_lead_blank\)/.test(require('fs').readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8')), /thousands > 99 \? 99 : thousands/.test(require('fs').readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8')) && !/GOTHIC_09|GOTHIC_14_BOLD/.test(require('fs').readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8')), /WEATHER_RETRY_MS 30000/.test(require('fs').readFileSync(__dirname + '/../src/c/main.c', 'utf8'))], [true, true, true]);
(function () {
	var fs = require('fs'), sk = fs.readFileSync(__dirname + '/../src/c/modules/storage_keys.h', 'utf8'), m, got = {};
	var re = /#define (STORAGE_KEY_\w+) (\d+)/g;
	while ((m = re.exec(sk))) got[m[1]] = +m[2];
	var others = ['main.c', 'modules/settings.c', 'ui/daylight_layer.c'].map(function (f) { return fs.readFileSync(__dirname + '/../src/c/' + f, 'utf8'); }).join('\n');
	check('3.0.3: persist keys only in storage_keys.h, values unchanged, none reused', [got, /#define STORAGE_KEY_/.test(others),
		Object.keys(got).length === new Set(Object.keys(got).map(function (k) { return got[k]; })).size],
		[{ STORAGE_KEY_SETTINGS: 0, STORAGE_KEY_BATT_HIST: 1, STORAGE_KEY_WEATHER: 2, STORAGE_KEY_WEATHER_PART2: 3, STORAGE_KEY_EVENTS: 4,
			STORAGE_KEY_WEATHER_PART3: 5, STORAGE_KEY_BATT_LEARNED: 6, STORAGE_KEY_TAP_RANGE: 7, STORAGE_KEY_WEATHER_HDR: 8,
			STORAGE_KEY_CLOUD_OVERRIDE: 9, STORAGE_KEY_WEATHER_PART4: 10 }, false, true]);
	var setH = fs.readFileSync(__dirname + '/../src/c/modules/settings.h', 'utf8');
	check('3.0.3: settings.h uses generated MESSAGE_KEY_SETTING_* (no hardcoded 100xx outside the touch guard)',
		[/#define KEY_SETTING_\w+ 100\d\d/.test(setH.replace(/#if defined\(CARBON_TOUCH_ENABLED\)\n\/\*[\s\S]*?#endif/, '')),
			(setH.match(/#define KEY_SETTING_(\w+) MESSAGE_KEY_SETTING_\1\b/g) || []).every(function (l) { return ('SETTING_' + /KEY_SETTING_(\w+)/.exec(l)[1]) in pkg.pebble.messageKeys; })],
		[false, true]);
})();
check('3.0.3: MET User-Agent carries the app version', require('../src/pkjs/weather/metno.js').userAgent, 'CarbonIon-Pebble/' + pkg.version + ' github.com/nilsreichhard/carbon-ion');
(function () { /* 3.0.3: host C test of settings_init() migration, plain and short enums (ARM layout) */
	var fs = require('fs'), cp = require('child_process'), os = require('os'), path = require('path');
	var dir = fs.mkdtempSync(path.join(os.tmpdir(), 'carbon_set_')), mkH = path.join(dir, 'message_keys.h'), o = '';
	Object.keys(pkg.pebble.messageKeys).forEach(function (k) { o += '#define MESSAGE_KEY_' + k + ' ' + pkg.pebble.messageKeys[k] + '\n'; });
	fs.writeFileSync(mkH, o);
	var root = path.join(__dirname, '..');
	var res = ['', '-fshort-enums'].map(function (fl) {
		var bin = path.join(dir, 't' + (fl ? 's' : 'p'));
		var r = cp.spawnSync('sh', ['-c', 'cc -std=c11 -Wall -Wextra -Werror ' + fl + ' -I"' + root + '/tools/host" -include "' + mkH + '" -o "' + bin + '" "' + root + '/tools/test_settings_migration.c" "' + root + '/src/c/modules/settings.c" && "' + bin + '"'], { encoding: 'utf8' });
		if (r.status !== 0) console.log(r.stdout + r.stderr);
		return r.status;
	});
	check('Settings migration C tests (tools/test_settings_migration.c, plain + short enums) pass', res, [0, 0]);
})();
(function () { /* 3.0.3: leading zero setting */
	var S = require('../src/pkjs/settings.js'), fs = require('fs');
	var fn = S.buildSettingsDict || S.buildSettingsMessage || S.toWatchDict;
	var tl = fs.readFileSync(__dirname + '/../src/c/ui/time_layer.c', 'utf8');
	check('3.0.3: SETTING_LEADING_ZERO declared (10094), in Clay, watch strips on Off / Auto+12h',
		[pkg.pebble.messageKeys.SETTING_LEADING_ZERO, /"messageKey": "SETTING_LEADING_ZERO"/.test(fs.readFileSync(__dirname + '/../src/pkjs/config.js', 'utf8')),
			/leading_zero == LEADING_ZERO_OFF \|\|\s*\(settings->leading_zero == LEADING_ZERO_AUTO && !is_24h\)/.test(tl),
			S.WEATHER_SETTING_KEYS ? S.WEATHER_SETTING_KEYS.indexOf('SETTING_LEADING_ZERO') : -1],
		[10094, true, true, -1]);
	if (fn) check('3.0.3: leading zero sent as 0/1/2 (Auto default)', [fn({}).SETTING_LEADING_ZERO, fn({ SETTING_LEADING_ZERO: '2' }).SETTING_LEADING_ZERO, fn({ SETTING_LEADING_ZERO: 1 }).SETTING_LEADING_ZERO, fn({ SETTING_LEADING_ZERO: 9 }).SETTING_LEADING_ZERO], [0, 2, 1, 0]);
	else check('3.0.3: settings dict builder exported', Object.keys(S), 'buildSettingsDict');
})();
console.log((n - fails) + '/' + n + ' passed');
process.exit(fails ? 1 : 0);
