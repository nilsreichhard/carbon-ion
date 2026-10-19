/**
 * Open-Meteo (default, no key). CC-BY 4.0 — https://open-meteo.com
 * Optional model (2.8.1 'Model' dropdown): requested as models=X,best_match;
 * every null field of an hour (beyond the model's horizon, or a variable the
 * model lacks) is filled from best_match. ICON-CH1 chains CH1 → CH2 → best.
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://api.open-meteo.com/v1/forecast';
var VARS = ['temperature_2m', 'apparent_temperature', 'precipitation_probability', 'precipitation',
	'cloud_cover', 'cloud_cover_low', 'cloud_cover_mid', 'cloud_cover_high', 'weather_code',
	'shortwave_radiation', 'visibility', 'wind_gusts_10m', 'cape'];
/* id → { label, chain (merge order before best_match), source attribution } */
var MODELS = {
	best_match: { label: 'Best match', chain: [], src: '' },
	meteoswiss_icon_ch1: { label: 'MeteoSwiss ICON-CH1 (1 km, then CH2)', chain: ['meteoswiss_icon_ch1', 'meteoswiss_icon_ch2'], src: 'MeteoSwiss' },
	meteoswiss_icon_ch2: { label: 'MeteoSwiss ICON-CH2 (2 km)', chain: ['meteoswiss_icon_ch2'], src: 'MeteoSwiss' },
	icon_d2: { label: 'DWD ICON-D2', chain: ['icon_d2'], src: 'DWD' },
	geosphere_arome_austria: { label: 'GeoSphere AROME Austria', chain: ['geosphere_arome_austria'], src: 'GeoSphere Austria' },
	knmi_harmonie_arome_europe: { label: 'KNMI HARMONIE', chain: ['knmi_harmonie_arome_europe'], src: 'KNMI' },
	dmi_harmonie_arome_europe: { label: 'DMI HARMONIE', chain: ['dmi_harmonie_arome_europe'], src: 'DMI' },
	meteofrance_arome_france_hd: { label: 'Météo-France AROME HD', chain: ['meteofrance_arome_france_hd'], src: 'Météo-France' },
	ecmwf_ifs: { label: 'ECMWF IFS', chain: ['ecmwf_ifs'], src: 'ECMWF' },
};
var KNOWN = {};
Object.keys(MODELS).forEach(function (m) { MODELS[m].chain.forEach(function (c, r) { KNOWN[c] = Math.max(KNOWN[c] || 1, r + 1); }); });
function modelOf(opts) {
	var m = opts && opts.model;
	if (!m && opts && opts.meteoswiss) m = 'meteoswiss_icon_ch1'; /* pre-2.8.1 toggle */
	return MODELS[m] ? m : 'best_match';
}

function buildUrl(lat, lon, opts) {
	opts = opts || {};
	var past = opts.pastOnly ? 12 : 12;
	var fc = opts.pastOnly ? 1 : 60;
	var chain = MODELS[modelOf(opts)].chain;
	return BASE + '?latitude=' + lat + '&longitude=' + lon +
		(opts.pastOnly ? '' : '&current=temperature_2m,weather_code') +
		'&hourly=' + VARS.join(',') +
		'&past_hours=' + past + '&forecast_hours=' + fc +
		(opts.pastOnly ? '' : '&daily=sunrise,sunset,temperature_2m_min,temperature_2m_max') +
		(chain.length && !opts.pastOnly ? '&models=' + chain.concat(['best_match']).join(',') : '') +
		'&temperature_unit=celsius&wind_speed_unit=kmh&timeformat=unixtime&timezone=auto';
}

/* Field from a block, merging "<name>_<model>" columns in chain order, then
 * best_match, per element (null → next source). */
function col(block, name) {
	if (!block) return null;
	if (block[name]) return block[name];
	var cols = [], best = block[name + '_best_match'];
	Object.keys(block).forEach(function (k) {
		if (k.indexOf(name + '_') === 0 && KNOWN[k.slice(name.length + 1)] && Array.isArray(block[k])) cols.push(k);
	});
	cols.sort(function (a, b) { return KNOWN[a.slice(name.length + 1)] - KNOWN[b.slice(name.length + 1)]; });
	if (best) cols.push(name + '_best_match');
	if (!cols.length) return null;
	var out = block[cols[0]].slice();
	for (var c = 1; c < cols.length; c++) {
		var b = block[cols[c]];
		for (var i = 0; i < out.length; i++) if (out[i] === null || out[i] === undefined) out[i] = b[i];
	}
	return out;
}

function first(block, name) {
	var c = col(block, name);
	if (c === null || c === undefined) return null;
	return Array.isArray(c) ? c[0] : c;
}

function parse(json, ctx) {
	if (!json || !json.hourly || !json.hourly.time) throw new Error('invalid open-meteo payload');
	var g = P.emptyGrid('openmeteo', ctx.now);
	var h = json.hourly;
	var map = { temp: 'temperature_2m', feels: 'apparent_temperature', pop: 'precipitation_probability',
		precip: 'precipitation', cloud: 'cloud_cover', wmo_code: 'weather_code',
		cloud_low: 'cloud_cover_low', cloud_mid: 'cloud_cover_mid', cloud_high: 'cloud_cover_high',
		visibility_m: 'visibility', shortwave: 'shortwave_radiation', gust: 'wind_gusts_10m', cape: 'cape' };
	var cols = {};
	Object.keys(map).forEach(function (k) { cols[k] = col(h, map[k]); });
	for (var n = 0; n < h.time.length; n++) {
		var i = P.slot(g, h.time[n]);
		if (i < 0) continue;
		Object.keys(cols).forEach(function (k) { if (cols[k]) P.setAt(g, k, i, P.num(cols[k][n])); });
	}
	if (typeof json.utc_offset_seconds === 'number') g.utcOffsetS = json.utc_offset_seconds; /* 2.8.7 */
	var cur = json.current;
	var ct = first(cur, 'temperature_2m');
	if (ct !== null) g.current_temp = ct;
	var cc = first(cur, 'weather_code');
	if (cc !== null) g.current_code = cc;
	var d = json.daily;
	var tmax = first(d, 'temperature_2m_max'), tmin = first(d, 'temperature_2m_min');
	if (tmax !== null) g.tmax = tmax;
	if (tmin !== null) g.tmin = tmin;
	var sr = first(d, 'sunrise'), ss = first(d, 'sunset');
	if (sr !== null && ss !== null) {
		g.sunrise = sr; g.sunset = ss;
		/* Location-local clock (utc_offset_seconds), as before 2.8.0. */
		var off = typeof json.utc_offset_seconds === 'number' ? json.utc_offset_seconds : null;
		if (off !== null) {
			var loc = function (t) { var s = ((t + off) % 86400 + 86400) % 86400; return [Math.floor(s / 3600), Math.floor((s % 3600) / 60)]; };
			g.sunriseLocal = loc(sr); g.sunsetLocal = loc(ss);
		}
	}
	return P.finalize(g, ctx.now, ctx.lat, ctx.lon);
}

function attributionFor(opts) {
	var src = MODELS[modelOf(opts)].src;
	return 'Weather data by Open-Meteo.com (CC BY 4.0)' + (src ? ', model ' + src : '');
}

module.exports = { id: 'openmeteo', needsKey: false, label: 'Open-Meteo',
	attribution: 'Weather data by Open-Meteo.com (CC BY 4.0)', attributionFor: attributionFor,
	MODELS: MODELS, modelOf: modelOf, buildUrl: buildUrl, parse: parse };
