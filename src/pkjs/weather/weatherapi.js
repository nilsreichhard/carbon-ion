/**
 * WeatherAPI.com forecast.json (API key). days=3 (free tier) gives hourly from
 * local midnight today to the end of day 3 (48–72 h ahead; shorter tails padded).
 * Past hours before midnight are filled by the caller. No cloud layers.
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://api.weatherapi.com/v1/forecast.json';
function buildUrl(lat, lon, opts) {
	return BASE + '?key=' + encodeURIComponent(opts.apiKey || '') + '&q=' + lat + ',' + lon +
		'&days=3&aqi=no&alerts=no';
}

var CODE_WMO = { 1000: 0, 1003: 2, 1006: 3, 1009: 3, 1030: 3, 1063: 80, 1066: 85, 1069: 68,
	1072: 56, 1087: 95, 1114: 75, 1117: 75, 1135: 45, 1147: 48, 1150: 51, 1153: 53,
	1168: 56, 1171: 57, 1180: 61, 1183: 61, 1186: 63, 1189: 63, 1192: 65, 1195: 65,
	1198: 66, 1201: 67, 1204: 68, 1207: 69, 1210: 71, 1213: 71, 1216: 73, 1219: 73,
	1222: 75, 1225: 75, 1237: 77, 1240: 80, 1243: 81, 1246: 82, 1249: 68, 1252: 69,
	1255: 85, 1258: 86, 1261: 77, 1264: 77, 1273: 95, 1276: 96, 1279: 95, 1282: 96 };
function codeToWmo(c, visKm) {
	c = Number(c);
	if (c === 1030 && visKm !== null && visKm !== undefined && visKm < 1) return 45;
	return CODE_WMO.hasOwnProperty(c) ? CODE_WMO[c] : null;
}

/* "06:45 AM" → [h, m] (location-local clock) */
function clock(s) {
	var m = /^(\d{1,2}):(\d{2})\s*([AP]M)$/i.exec(String(s || '').trim());
	if (!m) return null;
	var h = Number(m[1]) % 12 + (m[3].toUpperCase() === 'PM' ? 12 : 0);
	return [h, Number(m[2])];
}

function parse(json, ctx) {
	var days = json && json.forecast && json.forecast.forecastday;
	if (!days) throw new Error('invalid weatherapi payload' + (json && json.error ? ' (' + String(json.error.message || '').slice(0, 60) + ')' : ''));
	var g = P.emptyGrid('weatherapi', ctx.now);
	g.visibility_m = P.nulls();
	days.forEach(function (day) {
		(day.hour || []).forEach(function (h) {
			var i = P.slot(g, h.time_epoch);
			if (i < 0) return;
			var visKm = P.num(h.vis_km);
			var code = h.condition ? Number(h.condition.code) : null;
			P.setAt(g, 'temp', i, P.num(h.temp_c));
			P.setAt(g, 'feels', i, P.num(h.feelslike_c));
			P.setAt(g, 'pop', i, Math.max(P.num(h.chance_of_rain) || 0, P.num(h.chance_of_snow) || 0));
			P.setAt(g, 'precip', i, P.num(h.precip_mm));
			P.setAt(g, 'cloud', i, P.num(h.cloud));
			P.setAt(g, 'visibility_m', i, visKm === null ? null : visKm * 1000);
			var w = codeToWmo(code, visKm);
			P.setAt(g, 'wmo_code', i, w === null ? 3 : w);
			g.fog[i] = (code === 1135 || code === 1147 || (visKm !== null && visKm < 1)) ? 1 : 0;
		});
	});
	var d0 = days[0];
	if (d0 && d0.day) { g.tmax = P.num(d0.day.maxtemp_c); g.tmin = P.num(d0.day.mintemp_c); }
	if (json.current) {
		g.current_temp = P.num(json.current.temp_c);
		if (json.current.condition) g.current_code = codeToWmo(json.current.condition.code, P.num(json.current.vis_km));
	}
	var sr = d0 && d0.astro && clock(d0.astro.sunrise), ss = d0 && d0.astro && clock(d0.astro.sunset);
	if (sr && ss) { g.sunriseLocal = sr; g.sunsetLocal = ss; g.sunrise = 0; g.sunset = 0; }
	g = P.finalize(g, ctx.now, ctx.lat, ctx.lon);
	return g;
}

module.exports = { id: 'weatherapi', needsKey: true, label: 'WeatherAPI.com',
	attribution: 'Weather data by WeatherAPI.com', buildUrl: buildUrl, parse: parse,
	codeToWmo: codeToWmo, needsPastFill: true };
