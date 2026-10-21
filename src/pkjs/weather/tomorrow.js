/**
 * Tomorrow.io Weather API v4 /weather/forecast (API key). 1 h steps ~120 h,
 * no past hours (filled by caller), no cloud layers (1-layer clouds).
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://api.tomorrow.io/v4/weather/forecast';
function buildUrl(lat, lon, opts) {
	return BASE + '?location=' + lat + ',' + lon + '&timesteps=1h,1d&units=metric&apikey=' +
		encodeURIComponent(opts.apiKey || '');
}

var CODE_WMO = { 1000: 0, 1100: 1, 1101: 2, 1102: 3, 1001: 3, 2000: 45, 2100: 45,
	4000: 51, 4200: 61, 4001: 63, 4201: 65, 5001: 71, 5100: 71, 5000: 73, 5101: 75,
	6000: 56, 6200: 66, 6001: 67, 6201: 67, 7102: 77, 7000: 77, 7101: 77, 8000: 95 };
function codeToWmo(c) {
	c = Number(c);
	if (CODE_WMO.hasOwnProperty(c)) return CODE_WMO[c];
	/* 5-digit day/night variants (e.g. 10000) → base code */
	var base = Math.floor(c / 10);
	return CODE_WMO.hasOwnProperty(base) ? CODE_WMO[base] : null;
}

function parse(json, ctx) {
	var hourly = json && json.timelines && json.timelines.hourly;
	if (!hourly) throw new Error('invalid tomorrow.io payload' + (json && json.message ? ' (' + String(json.message).slice(0, 60) + ')' : ''));
	var g = P.emptyGrid('tomorrow', ctx.now);
	g.visibility_m = P.nulls();
	hourly.forEach(function (h) {
		var i = P.slot(g, Date.parse(h.time) / 1000);
		if (i < 0) return;
		var v = h.values || {};
		P.setAt(g, 'temp', i, P.num(v.temperature));
		P.setAt(g, 'feels', i, P.num(v.temperatureApparent));
		P.setAt(g, 'pop', i, P.num(v.precipitationProbability));
		var mm = (P.num(v.rainIntensity) || 0) + (P.num(v.snowIntensity) || 0) +
			(P.num(v.sleetIntensity) || 0) + (P.num(v.freezingRainIntensity) || 0);
		P.setAt(g, 'precip', i, mm);
		P.setAt(g, 'cloud', i, P.num(v.cloudCover));
		P.setAt(g, 'gust', i, P.msToKmh(P.num(v.windGust))); /* units=metric: m/s */
		var cb = P.num(v.cloudBase); /* km; null without cloud (3.0.0: thickness) */
		P.setAt(g, 'cloud_base', i, cb === null ? null : cb * 1000);
		var vis = P.num(v.visibility);
		P.setAt(g, 'visibility_m', i, vis === null ? null : vis * 1000);
		var code = codeToWmo(v.weatherCode);
		P.setAt(g, 'wmo_code', i, code === null ? 3 : code);
		g.fog[i] = (Number(v.weatherCode) === 2000 || Number(v.weatherCode) === 2100) ? 1 : 0;
	});
	var daily = json.timelines.daily;
	if (daily && daily[0] && daily[0].values) {
		var dv = daily[0].values;
		if (dv.sunriseTime && dv.sunsetTime) {
			g.sunrise = Date.parse(dv.sunriseTime) / 1000; g.sunset = Date.parse(dv.sunsetTime) / 1000;
		}
		if (dv.temperatureMax !== undefined) g.tmax = P.num(dv.temperatureMax);
		if (dv.temperatureMin !== undefined) g.tmin = P.num(dv.temperatureMin);
	}
	return P.finalize(g, ctx.now, ctx.lat, ctx.lon);
}

module.exports = { id: 'tomorrow', needsKey: true, label: 'Tomorrow.io',
	attribution: 'Weather data by Tomorrow.io', buildUrl: buildUrl, parse: parse,
	codeToWmo: codeToWmo, needsPastFill: true };
