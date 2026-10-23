/* Host test: TZ=Europe/Zurich node tools/test_provider_switch.js
 * 3.1.2: provider switching on the Clay page (custom.js, mocked Clay) for
 * every provider pair, per-provider API keys, and the phone-side settings
 * dict / fetch for each provider with stale dependent settings. */
var CONFIG = require('../src/pkjs/config.js');
var custom = require('../src/pkjs/config/custom.js');
var CAPS = require('../src/pkjs/weather/caps.js');
var S = require('../src/pkjs/settings.js');
var W = require('../src/pkjs/weather/index.js');
var fails = 0, n = 0;
function check(name, got, want) {
	n++; var ok = JSON.stringify(got) === JSON.stringify(want);
	if (!ok) fails++;
	if (!ok || process.env.VERBOSE) console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '\n   got  ' + JSON.stringify(got) + '\n   want ' + JSON.stringify(want)));
}
var PROVS = Object.keys(CAPS);

function walk(items, out) {
	(items || []).forEach(function (it) { if (it.items) walk(it.items, out); else out.push(it); });
	return out;
}
function mockClay(saved, userData) {
	var byKey = {}, byId = {};
	walk(CONFIG, []).forEach(function (def) {
		var opts = (def.options || []).map(function (o) { return { value: String(o.value), disabled: false, hidden: false, style: {} }; });
		var item = { def: def, value: saved.hasOwnProperty(def.messageKey) ? saved[def.messageKey] : def.defaultValue, visible: true, handlers: [],
			get: function () { return this.value; },
			set: function (v) { this.value = v; this.handlers.forEach(function (h) { h(); }); },
			show: function () { this.visible = true; }, hide: function () { this.visible = false; }, enabled: true,
			enable: function () { this.enabled = true; }, disable: function () { this.enabled = false; },
			on: function (ev, fn) { if (ev === 'change') this.handlers.push(fn); } };
		if (opts.length) item.$manipulatorTarget = [{ options: opts }];
		if (def.messageKey) byKey[def.messageKey] = item;
		if (def.id) byId[def.id] = item;
	});
	var after = [];
	var clay = { meta: { userData: userData }, EVENTS: { AFTER_BUILD: 'AFTER_BUILD' },
		getItemByMessageKey: function (k) { return byKey[k] || null; }, getItemById: function (k) { return byId[k] || null; },
		on: function (ev, fn) { after.push(fn); } };
	custom.call(clay);
	after.forEach(function (f) { f(); });
	byKey.__byId = byId;
	return byKey;
}
/* Everything at its most demanding value. */
function maxed(prov) {
	return { SETTING_WEATHER_PROVIDER: prov, SETTING_WEATHER_API_KEY: CAPS[prov].key ? 'KEY_' + prov : '',
		SETTING_OM_MODEL: 'meteoswiss_icon_ch1', SETTING_FETCH_INTERVAL: '15', SETTING_FORECAST_HOURS: '48',
		SETTING_CLOUD_DISPLAY_MODE: '1', SETTING_FLICK_ACTION: '1', SETTING_RAIN_HEIGHT: '1',
		SETTING_THUNDER_LEVEL: '1', SETTING_FOG_LEVEL: '1', SETTING_WRIST_FLICK: true };
}
function optOk(item, v) {
	if (!item.$manipulatorTarget) return true;
	var o = item.$manipulatorTarget[0].options.filter(function (x) { return x.value === String(v); })[0];
	return !!o && !o.disabled;
}
function invariants(K, b, tag) {
	var c = CAPS[b];
	var bad = [];
	if (K.SETTING_WEATHER_API_KEY.visible !== !!c.key) bad.push('key visibility');
	if (K.SETTING_OM_MODEL.visible !== (b === 'openmeteo')) bad.push('model visibility');
	if (Number(K.SETTING_FETCH_INTERVAL.get()) < (c.minInterval || 15)) bad.push('interval ' + K.SETTING_FETCH_INTERVAL.get());
	if (!c.layers && Number(K.SETTING_CLOUD_DISPLAY_MODE.get()) !== 0) bad.push('cloud mode');
	if (!c.layers && Number(K.SETTING_FLICK_ACTION.get()) !== 0) bad.push('flick action');
	var horizon = c.horizon;
	if (Number(K.SETTING_FORECAST_HOURS.get()) > horizon) bad.push('hours ' + K.SETTING_FORECAST_HOURS.get());
	if (c.strongStorm === false && Number(K.SETTING_THUNDER_LEVEL.get()) === 1) bad.push('thunder level');
	if (c.fogVis === false && [0, 3].indexOf(Number(K.SETTING_FOG_LEVEL.get())) < 0) bad.push('fog level');
	['SETTING_FETCH_INTERVAL', 'SETTING_FORECAST_HOURS', 'SETTING_CLOUD_DISPLAY_MODE', 'SETTING_THUNDER_LEVEL', 'SETTING_FOG_LEVEL'].forEach(function (k) {
		if (!optOk(K[k], K[k].get())) bad.push(k + ' value not an enabled option');
	});
	if (K.SETTING_WIND_GUST && K.SETTING_WIND_GUST.visible !== (c.gust !== false)) bad.push('gust visibility');
	if (K.SETTING_EVENT_TORNADO && K.SETTING_EVENT_TORNADO.visible !== (c.tornado !== false)) bad.push('tornado visibility');
	if (K.SETTING_CLOUD_THICK.enabled !== (c.thick !== false) || !K.SETTING_CLOUD_THICK.visible) bad.push('cloud thickness enabled/visible');
	return bad;
}

/* ---- every ordered provider pair on the Clay page ---- */
PROVS.forEach(function (a) {
	PROVS.forEach(function (b) {
		if (a === b) return;
		var keys = {}; PROVS.forEach(function (p) { if (CAPS[p].key) keys[p] = 'KEY_' + p; });
		var K = mockClay(maxed(a), { wxCaps: CAPS, wxKeys: keys });
		check(a + ' initial invariants', invariants(K, a), []);
		K.SETTING_WEATHER_PROVIDER.set(b);
		check(a + ' → ' + b + ' invariants', invariants(K, b), []);
		check(a + ' → ' + b + ' key = ' + b + "'s own key", K.SETTING_WEATHER_API_KEY.get(), CAPS[b].key ? 'KEY_' + b : (CAPS[a].key ? 'KEY_' + a : ''));
		/* round trip on the phone: dict values valid for b, interval clamped */
		var raw = {}; Object.keys(K).forEach(function (k) { if (k !== '__byId') raw[k] = K[k].get(); });
		var d = S.buildSettingsDict(raw);
		check(a + ' → ' + b + ' phone interval >= provider min', d.SETTING_FETCH_INTERVAL >= (CAPS[b].minInterval || 15), true);
		K.SETTING_WEATHER_PROVIDER.set(a);
		check(a + ' → ' + b + ' → ' + a + ' key restored', K.SETTING_WEATHER_API_KEY.get(), CAPS[a].key ? 'KEY_' + a : (CAPS[b].key ? 'KEY_' + b : ''));
	});
});
/* A key typed on the page for one provider is kept when switching away and back. */
var K2 = mockClay(maxed('owm'), { wxCaps: CAPS, wxKeys: {} });
K2.SETTING_WEATHER_API_KEY.set('TYPED_OWM');
K2.SETTING_WEATHER_PROVIDER.set('tomorrow');
check('typed OWM key not carried to Tomorrow.io', K2.SETTING_WEATHER_API_KEY.get(), '');
K2.SETTING_WEATHER_PROVIDER.set('owm');
check('typed OWM key back on OWM', K2.SETTING_WEATHER_API_KEY.get(), 'TYPED_OWM');
check('rememberProviderKey: keyed only, empty clears', [S.rememberProviderKey({}, 'owm', ' K ', CAPS), S.rememberProviderKey({ owm: 'K' }, 'owm', '', CAPS), S.rememberProviderKey({}, 'metno', 'K', CAPS)], [{ owm: 'K' }, {}, {}]);

/* ---- phone fetch per provider with stale dependent settings ---- */
function fakeDeps(urls) {
	var mem = {};
	return { store: { get: function (k) { return mem[k] || null; }, set: function (k, v) { mem[k] = v; } }, log: function () { },
		get: function (url, o, cb) { urls.push(url); cb('HTTP 503 test'); } };
}
PROVS.forEach(function (p) {
	var urls = [];
	W.fetchWeather({ provider: p, apiKey: CAPS[p].key ? 'K' : '', model: 'meteoswiss_icon_ch1', lat: 46.3, lon: 7.05, now: 1792764900, unit: 'celsius', fogOn: true, owmOneCall: true }, fakeDeps(urls), function () { });
	var own = urls.filter(function (u) { return !/open-meteo/.test(u); });
	var omFallback = urls.filter(function (u) { return /open-meteo/.test(u); });
	check(p + ': stale ICON-CH1 model only reaches Open-Meteo when it is the chosen provider',
		omFallback.some(function (u) { return /models=meteoswiss/.test(u); }), p === 'openmeteo');
	check(p + ': own request issued', p === 'openmeteo' ? omFallback.length > 0 : own.length > 0, true);
});
var urlsNK = [];
W.fetchWeather({ provider: 'owm', apiKey: '', lat: 46.3, lon: 7.05, now: 1792764900, unit: 'celsius' }, fakeDeps(urlsNK), function () { });
check('keyed provider without key → no keyed request, Open-Meteo only', urlsNK.every(function (u) { return /open-meteo/.test(u); }), true);
check('provider/key/model changes trigger refetch', ['SETTING_WEATHER_PROVIDER', 'SETTING_WEATHER_API_KEY', 'SETTING_OM_MODEL', 'SETTING_OWM_ONECALL'].map(function (k) {
	var o = {}, nw = {}; o[k] = 'a'; nw[k] = 'b'; return S.weatherSettingsChanged(o, nw); }), [true, true, true, true]);
/* ---- 3.1.2: interval restored after a forced raise ---- */
var KI = mockClay(maxed('openmeteo'), { wxCaps: CAPS, wxKeys: {} });
KI.SETTING_WEATHER_PROVIDER.set('meteoblue');
var iv1 = KI.SETTING_FETCH_INTERVAL.get();
KI.SETTING_WEATHER_PROVIDER.set('metno');
var iv2 = KI.SETTING_FETCH_INTERVAL.get();
KI.SETTING_WEATHER_PROVIDER.set('openmeteo');
check('Interval 15: Meteoblue forces 60, MET 30, back on Open-Meteo restores 15', [iv1, iv2, KI.SETTING_FETCH_INTERVAL.get()].map(String), ['60', '30', '15']);
KI.SETTING_WEATHER_PROVIDER.set('meteoblue'); KI.SETTING_FETCH_INTERVAL.set('120'); KI.SETTING_WEATHER_PROVIDER.set('openmeteo');
check('Interval chosen on the forcing provider (120) is kept as the user value', String(KI.SETTING_FETCH_INTERVAL.get()), '120');
var KT = mockClay(maxed('openmeteo'), { wxCaps: CAPS, wxKeys: {} });
KT.SETTING_CLOUD_THICK.set('0');
check('Cloud thickness: greyed out (not hidden, value kept) on OWM / WeatherAPI, enabled elsewhere', PROVS.map(function (p) { KT.SETTING_WEATHER_PROVIDER.set(p); return p + ':' + (KT.SETTING_CLOUD_THICK.enabled ? 'on' : 'grey') + (KT.SETTING_CLOUD_THICK.visible ? '' : ':hidden') + ':' + KT.SETTING_CLOUD_THICK.get(); }),
	PROVS.map(function (p) { return p + ':' + (CAPS[p].thick === false ? 'grey' : 'on') + ':0'; }));
/* ---- 3.1.2: fallback warning on the settings page ---- */
function attrHtml(msg, prov) {
	var ud = { wxCaps: CAPS, wxKeys: {}, lastWxError: { t: Date.UTC(2026, 9, 23, 16, 5), msg: msg },
		lastFetch: { t: Date.UTC(2026, 9, 23, 16, 5), provider: 'openmeteo', requested: prov } };
	return String(mockClay(maxed(prov), ud).__byId.wx_attribution.get());
}
var hKey = attrHtml('fallback: owm key/quota rejected (HTTP 401 for api.openweathermap.org) → open-meteo', 'owm');
check('Settings page: rejected key → clear warning naming provider + Open-Meteo + check key', [/OpenWeatherMap rejected the API key/.test(hKey), /Open-Meteo data is shown instead/.test(hKey), /Check the API key/.test(hKey), /\(fallback\)/.test(hKey)], [true, true, true, true]);
var hNo = attrHtml('fallback: tomorrow: no API key → open-meteo', 'tomorrow');
check('Settings page: missing key warning', /Tomorrow\.io has no API key, so Open-Meteo data is shown instead/.test(hNo), true);
var hGeo = attrHtml('fallback: geosphere failed (outside GeoSphere domain) → open-meteo', 'geosphere');
check('Settings page: outside GeoSphere domain warning (no key advice)', [/GeoSphere Austria does not cover this location/.test(hGeo), /Check the API key/.test(hGeo)], [true, false]);
var hErr = attrHtml('failed: HTTP 503', 'openmeteo');
check('Settings page: plain failure keeps "Last problem" text', /Last problem \d\d:\d\d: failed: HTTP 503/.test(hErr), true);
console.log((n - fails) + '/' + n + ' passed');
process.exit(fails ? 1 : 0);
