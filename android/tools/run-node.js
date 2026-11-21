#!/usr/bin/env node
/**
 * Headless check of the bundled JS with the same host contract the Android
 * app implements (globals from widget-entry.js), on a Mac/Linux box. HTTP
 * goes through curl synchronously, mirroring the Android bridge.
 *
 * Usage: node tools/run-node.js [lat] [lon] [--json out.json] [--record dir]
 *        [--settings '{"SETTING_WEATHER_PROVIDER":"metno"}']
 * Default location: Villars-sur-Ollon (46.30 N, 7.05 E).
 * --record writes every HTTP response to dir/ (host → file) for JVM tests.
 */
'use strict';
var fs = require('fs'), path = require('path'), os = require('os'), cp = require('child_process');
var args = process.argv.slice(2);
function opt(name) { var i = args.indexOf(name); if (i < 0) return null; var v = args[i + 1]; args.splice(i, 2); return v; }
var jsonOut = opt('--json'), recordDir = opt('--record'), settingsJson = opt('--settings');
var lat = parseFloat(args[0] || '46.30'), lon = parseFloat(args[1] || '7.05');

var bundle = path.join(os.tmpdir(), 'carbon-weather-node.js');
cp.execFileSync(process.execPath, [path.join(__dirname, 'bundle-pkjs.js'), bundle], { stdio: 'ignore' });

var store = {};
if (settingsJson) store['clay-settings'] = settingsJson;
global.__carbonStoreGet = function (k) { return k in store ? store[k] : null; };
global.__carbonStoreSet = function (k, v) { if (v === null) delete store[k]; else store[k] = v; };
global.__carbonLog = function (c, d) { console.error('[js] ' + c + ' ' + d); };
global.__carbonLocale = function () { return 'de-CH'; };
if (recordDir) fs.mkdirSync(recordDir, { recursive: true });
global.__carbonHttpGet = function (url, headersJson) {
	var tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'carbonjs-'));
	var hf = path.join(tmp, 'h'), bf = path.join(tmp, 'b');
	var a = ['-sS', '-L', '--max-time', '15', '-D', hf, '-o', bf, '-w', '%{http_code}'];
	var hs = JSON.parse(headersJson || '{}');
	Object.keys(hs).forEach(function (k) { a.push('-H', k + ': ' + hs[k]); });
	a.push(url);
	try {
		var code = parseInt(cp.execFileSync('curl', a, { encoding: 'utf8' }), 10);
		var headers = {};
		fs.readFileSync(hf, 'utf8').split(/\r?\n/).forEach(function (l) {
			var i = l.indexOf(':'); if (i > 0) headers[l.slice(0, i).trim().toLowerCase()] = l.slice(i + 1).trim();
		});
		var body = fs.readFileSync(bf, 'utf8');
		if (recordDir) fs.writeFileSync(path.join(recordDir, new URL(url).hostname + (/past_hours=12&forecast_hours=1\b/.test(url) ? '.past' : '') + '.json'), body);
		return JSON.stringify({ status: code, body: body, headers: headers });
	} catch (e) {
		return JSON.stringify({ status: 0, error: 'curl: ' + e.message.split('\n')[0] });
	} finally { fs.rmSync(tmp, { recursive: true, force: true }); }
};

(0, eval)(fs.readFileSync(bundle, 'utf8'));
var res = JSON.parse(global.carbonFetchWeather(JSON.stringify({ lat: lat, lon: lon, isStatic: false })));
if (res.error) { console.error('ERROR ' + res.error); process.exit(1); }
var p = res.payload;
if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify(p, null, 1));
console.log('city=' + res.city + ' provider=' + p.provider + ' now=' + p.current_temp + ' hi/lo=' + p.high_temp + '/' + p.low_temp +
	' sun ' + p.sunrise_hour + ':' + ('0' + p.sunrise_minute).slice(-2) + '-' + p.sunset_hour + ':' + ('0' + p.sunset_minute).slice(-2) +
	' valid_hours=' + p.valid_hours + ' fetch_time=' + new Date(p.fetch_time * 1000).toISOString());
console.log('settings dict: ' + JSON.stringify(res.settings));
console.log('used: ' + JSON.stringify(res.used));
console.log('slot  hour  temp  feels  cloud  rain%  mm/h  sw(W/m2)  wmo');
var t0 = p.fetch_time - 12 * 3600;
for (var i = 6; i < 36; i++) {
	var d = new Date((t0 + i * 3600) * 1000);
	console.log([String(i).padStart(4), String(d.getHours()).padStart(5) + 'h', p.temp_hourly[i].toFixed(1).padStart(5),
		p.apparent_temp_hourly[i].toFixed(1).padStart(6), String(p.cloud_cover[i]).padStart(6), String(p.precip_prob[i]).padStart(6),
		(p.precip_mm[i] / 10).toFixed(1).padStart(5), String(Math.round(p.shortwave_radiation[i])).padStart(9), String(p.hourly_weather_code[i]).padStart(4)].join(' ') + (i === 12 ? '  <- now' : ''));
}
var st = JSON.parse(global.carbonSettingsInit('{}'));
console.log('settings page: ' + st.length + ' items, ' + st.filter(function (x) { return x.key; }).length + ' settings; hidden: ' +
	st.filter(function (x) { return x.hidden; }).map(function (x) { return x.key || x.id; }).join(', '));
