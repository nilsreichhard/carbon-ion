/* Host test: node tools/test_fetch.js
 * 3.0.3: runs the real src/pkjs/index.js end to end (ready → WEATHER_REQUEST →
 * geolocation → provider HTTP → parse → AppMessage) with mocked HTTP,
 * geolocation, localStorage and Pebble.sendAppMessage. One child process per
 * scenario so module state never leaks. No network. */
var path = require('path'), fs = require('fs'), cp = require('child_process');
var ROOT = path.join(__dirname, '..');

if (process.argv[2] !== '--child') {
	var scenarios = [
		{ name: 'Open-Meteo: fetch → weather sent', sc: 'openmeteo', want: { sent: true, crash: false } },
		{ name: 'MET Norway: fetch → weather sent', sc: 'metno', want: { sent: true, crash: false } },
		{ name: 'Error: HTTP 500, no cache → nothing sent, next request fetches again', sc: 'http500', want: { sent: false, crash: false, refetch: true } },
		{ name: 'Error: fetch throws → caught, fresh cache sent, next request fetches again', sc: 'throw', want: { sent: true, crash: true, refetch: true, cacheSent: true } }
	];
	var fails = 0;
	scenarios.forEach(function (s) {
		var out = cp.spawnSync(process.execPath, [__filename, '--child', s.sc], { encoding: 'utf8', timeout: 20000 });
		var res; try { res = JSON.parse(out.stdout.trim().split('\n').pop()); } catch (e) { res = { parseError: out.stdout.slice(-400) + out.stderr.slice(-400) }; }
		var got = {}; Object.keys(s.want).forEach(function (k) { got[k] = res[k]; });
		var ok = JSON.stringify(got) === JSON.stringify(s.want) && !res.tooBig && !(res.unknown && res.unknown.length);
		if (!ok) fails++;
		console.log((ok ? 'PASS ' : 'FAIL ') + s.name + (ok ? '' : '\n   got  ' + JSON.stringify(res) + '\n   want ' + JSON.stringify(s.want)));
	});
	console.log((scenarios.length - fails) + '/' + scenarios.length + ' passed');
	process.exit(fails ? 1 : 0);
}

/* ------------------------------ child ------------------------------ */
var SC = process.argv[3];
var pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json')));
var mk = (pkg.pebble && pkg.pebble.messageKeys) || {};
if (Array.isArray(mk)) { var o = {}; mk.forEach(function (k, i) { o[k.replace(/\[.*/, '')] = 10000 + i; }); mk = o; }
var realLog = console.log, logs = [];
console.log = function () { logs.push(Array.prototype.join.call(arguments, ' ')); };

var NOWS = Math.floor(Date.now() / 1000), H = Math.floor(NOWS / 3600) * 3600;
function iso(t) { return new Date(t * 1000).toISOString().replace('.000', ''); }
function omBody() {
	var t = []; for (var i = -24; i < 96; i++) t.push(H + i * 3600);
	var h = { time: t };
	['temperature_2m', 'apparent_temperature', 'precipitation_probability', 'precipitation', 'cloud_cover', 'cloud_cover_low',
		'cloud_cover_mid', 'cloud_cover_high', 'shortwave_radiation', 'relative_humidity_2m', 'wind_speed_10m', 'wind_gusts_10m', 'cape'].forEach(function (k) {
		h[k] = t.map(function (_, i) { return (i * 7) % 50; });
	});
	h.weather_code = t.map(function () { return 2; });
	h.visibility = t.map(function () { return 24000; });
	return { utc_offset_seconds: 7200, current: { temperature_2m: 12, weather_code: 2, time: NOWS },
		daily: { time: [H - 12 * 3600, H + 12 * 3600, H + 36 * 3600, H + 60 * 3600], sunrise: [H - 6 * 3600, H + 18 * 3600, H + 42 * 3600, H + 66 * 3600],
			sunset: [H + 4 * 3600, H + 28 * 3600, H + 52 * 3600, H + 76 * 3600], temperature_2m_max: [15, 15, 15, 15], temperature_2m_min: [5, 5, 5, 5] },
		hourly: h };
}
function metBody() {
	var ts = []; for (var i = 0; i < 90; i++) {
		ts.push({ time: iso(H + i * 3600), data: { instant: { details: { air_temperature: 8 + i % 5, relative_humidity: 70, wind_speed: 3,
			cloud_area_fraction: 60, cloud_area_fraction_low: 20, cloud_area_fraction_medium: 30, cloud_area_fraction_high: 40, fog_area_fraction: 0 } },
			next_1_hours: { summary: { symbol_code: 'cloudy' }, details: { precipitation_amount: i === 3 ? 1.5 : 0 } } } });
	}
	return { properties: { meta: { updated_at: iso(NOWS) }, timeseries: ts } };
}

var xhrUrls = [], THROW = false;
function route(u) {
	if (/open-meteo\.com/.test(u)) {
		if (SC === 'http500') return [500, 'boom'];
		return [200, JSON.stringify(omBody())];
	}
	if (/met\.no/.test(u)) return [200, JSON.stringify(metBody())];
	if (/bigdatacloud/.test(u)) return [200, JSON.stringify({ city: 'Zürich', locality: 'Zürich', countryCode: 'CH' })];
	return [404, ''];
}
function XHR() { this.readyState = 0; this.headers = {}; }
XHR.prototype.open = function (m, u) { this.u = u; };
XHR.prototype.setRequestHeader = function (k, v) { this.headers[k] = v; };
XHR.prototype.send = function () {
	var s = this; xhrUrls.push(s.u);
	if (THROW && /open-meteo\.com/.test(s.u)) throw new Error('mock synchronous XHR failure');
	setTimeout(function () {
		var r = route(s.u); s.status = r[0]; s.responseText = r[1]; s.readyState = 4;
		if (s.onreadystatechange) s.onreadystatechange();
		if (s.onload) s.onload(); else if (s.status >= 400 && s.onerror) s.onerror();
	}, 2);
};
XHR.prototype.abort = function () {};
global.XMLHttpRequest = XHR;

var store = {};
global.localStorage = { getItem: function (k) { return k in store ? store[k] : null; }, setItem: function (k, v) { store[k] = String(v); },
	removeItem: function (k) { delete store[k]; }, clear: function () { store = {}; } };
store['clay-settings'] = JSON.stringify({ SETTING_WEATHER_PROVIDER: SC === 'metno' ? 'metno' : 'openmeteo' });

var sends = [], unknown = [], tooBig = false, L = {};
global.Pebble = {
	addEventListener: function (n, f) { (L[n] = L[n] || []).push(f); },
	sendAppMessage: function (d, ok, bad) {
		var keys = Object.keys(d), size = 1;
		keys.forEach(function (k) {
			if (!(k in mk) && isNaN(+k)) unknown.push(k);
			var v = d[k]; size += 7 + (Array.isArray(v) ? v.length : typeof v === 'string' ? Buffer.byteLength(v) + 1 : 4);
		});
		if (size > 2048) tooBig = true;
		sends.push(keys);
		setTimeout(function () { if (ok) ok({}); }, 2);
	},
	getActiveWatchInfo: function () { return { platform: 'emery', firmware: { major: 4, minor: 3 } }; },
	openURL: function () {}, getTimelineToken: function (ok, bad) { if (bad) bad('n/a'); }, timelineSubscriptions: function () {},
	getAccountToken: function () { return 'x'; }, getWatchToken: function () { return 'y'; }
};
Object.defineProperty(global, 'navigator', { configurable: true, writable: true, value: { userAgent: 'node', language: 'en-US',
	geolocation: { getCurrentPosition: function (ok) { setTimeout(function () { ok({ coords: { latitude: 47.3769, longitude: 8.5417, accuracy: 20 } }); }, 2); } } } });
global.window = global;
var Mod = require('module'), mkFile = path.join(require('os').tmpdir(), 'carbon_test_fetch_mk_' + process.pid + '.js');
fs.writeFileSync(mkFile, 'module.exports=' + JSON.stringify(mk));
Mod._resolveFilename = (function (orig) { return function (r) { try { return orig.apply(this, arguments); } catch (e) { if (r === 'message_keys') return mkFile; throw e; } }; })(Mod._resolveFilename);

function isWeather(keys) { return keys.indexOf('WEATHER_TEMP') >= 0 || keys.some(function (k) { return /^WEATHER_/.test(k) && k !== 'WEATHER_REQUEST'; }) && keys.length > 10; }

require(path.join(ROOT, 'src/pkjs/index.js'));
(L.ready || []).forEach(function (f) { f({}); });

function request(seq) { (L.appmessage || []).forEach(function (f) { f({ payload: { WEATHER_REQUEST: seq } }); }); }
var realNow = Date.now, skew = 0;
Date.now = function () { return realNow() + skew; };
var firstXhr = 0;
setTimeout(function () { request(1); }, 50);
if (SC === 'throw') {
	/* request 1 succeeds and fills the cache; then the cache expires (but is
	 * still usable) and request 2 throws inside fetchAndSend */
	setTimeout(function () { skew += 90 * 60 * 1000; THROW = true; sends.length = 0; logs.length = 0; request(2); }, 1000);
	setTimeout(function () { firstXhr = xhrUrls.length; skew += 10 * 60 * 1000; request(3); }, 2000);
} else {
	/* past the request dedupe window, ask again: a stuck in-flight marker would block this */
	setTimeout(function () { firstXhr = xhrUrls.length; skew += 10 * 60 * 1000; request(2); }, 1500);
}
setTimeout(function () {
	var weatherSends = sends.filter(isWeather);
	var res = { sent: weatherSends.length > 0, crash: logs.some(function (l) { return /fetch crashed/.test(l); }),
		refetch: xhrUrls.slice(firstXhr).some(function (u) { return /open-meteo|met\.no/.test(u); }),
		cacheSent: logs.some(function (l) { return /using stale cache/.test(l); }) && weatherSends.length > 0,
		sentLog: logs.some(function (l) { return /weather sent/i.test(l); }), unknown: unknown, tooBig: tooBig, xhr: xhrUrls.length };
	try { fs.unlinkSync(mkFile); } catch (e) { }
	realLog(JSON.stringify(res));
	process.exit(0);
}, 3000);
