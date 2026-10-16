/**
 * Weather provider base (pattern after ForecasWatch2 src/pkjs/weather/).
 *
 * Every provider turns its API response into one normalised object on a fixed
 * hourly grid: 72 slots starting 12 h before the current UTC hour.
 *
 *   { provider, start, time[72], temp[72] °C, feels[72] °C, pop[72] %,
 *     precip[72] mm, cloud[72] %, cloud_low/mid/high[72]|null,
 *     visibility_m[72]|null, wmo_code[72], fog[72] (0/1), shortwave[72]|null,
 *     sunrise, sunset (unix s), tmin, tmax, current_temp, current_code,
 *     valid_hours, has_layers }
 *
 * Missing hours are null (sent to the watch as "no data": nothing is drawn).
 *
 * @license GPL-3.0-or-later
 */

var HOURS = 72;
var PAST = 12;
var KEYED_FAIL_RE = /HTTP (400|401|403|429)\b/;

/* Grid starts PAST hours before the top of the phone's LOCAL hour (2.8.5):
 * the watch counts slots from the local hour top, which differs from the UTC
 * hour top in zones with :30 / :45 offsets. tzOffMin = Date#getTimezoneOffset. */
function gridStart(nowSec, tzOffMin) {
	if (tzOffMin === undefined) tzOffMin = new Date(nowSec * 1000).getTimezoneOffset();
	var off = -tzOffMin * 60;
	return Math.floor((nowSec + off) / 3600) * 3600 - off - PAST * 3600;
}

function nulls() {
	var a = [];
	for (var i = 0; i < HOURS; i++) a.push(null);
	return a;
}

function emptyGrid(provider, nowSec) {
	var start = gridStart(nowSec);
	var time = [];
	for (var i = 0; i < HOURS; i++) time.push(start + i * 3600);
	return {
		provider: provider, start: start, time: time,
		temp: nulls(), feels: nulls(), pop: nulls(), precip: nulls(), cloud: nulls(),
		cloud_low: null, cloud_mid: null, cloud_high: null, visibility_m: null,
		wmo_code: nulls(), fog: nulls(), shortwave: null,
		sunrise: null, sunset: null, tmin: null, tmax: null,
		current_temp: null, current_code: null, valid_hours: 0, has_layers: false,
	};
}

/* Slot index for a unix time (nearest hour), or -1 outside the grid. */
function slot(g, tSec) {
	var i = Math.round((tSec - g.start) / 3600);
	return (i >= 0 && i < HOURS) ? i : -1;
}

function setAt(g, field, i, v) {
	if (i < 0 || v === null || v === undefined || (typeof v === 'number' && isNaN(v))) return;
	if (!g[field]) g[field] = nulls();
	g[field][i] = v;
}

function num(v) {
	if (v === null || v === undefined || v === '') return null;
	var n = Number(v);
	return isNaN(n) ? null : n;
}

/* Fog from WMO code or visibility (< 1000 m). */
function isFogWmo(code, visM) {
	return code === 45 || code === 48 || (visM !== null && visM !== undefined && visM < 1000);
}

/* Steadman / BoM apparent temperature (°C) from T, RH %, wind m/s. */
function apparentTemp(t, rh, ws) {
	if (t === null || rh === null) return t;
	var e = rh / 100 * 6.105 * Math.exp(17.27 * t / (237.7 + t));
	return t + 0.33 * e - 0.70 * (ws || 0) - 4.00;
}

/* NOAA sunrise/sunset (unix s) for the phone's local calendar day containing
 * tSec (2.8.5: was the UTC day, i.e. yesterday between 00:00 and 02:00 in
 * Zurich); {polar:'day'|'night'} when the sun doesn't rise/set (2.8.7).
 * tzOffMin = Date#getTimezoneOffset sign convention (minutes, west positive);
 * pass the location's offset when known so the location's day is used. */
function sunTimes(lat, lon, tSec, tzOffMin) {
	var rad = Math.PI / 180;
	if (tzOffMin === undefined) tzOffMin = new Date(tSec * 1000).getTimezoneOffset();
	var day = Math.floor((tSec - tzOffMin * 60) / 86400);
	var n = day + 0.5 - 10957.5 + 0.0008 - lon / 360; /* days since J2000 at local noon */
	var jstar = Math.round(n - 0.0008 + lon / 360) + 0.0008 - lon / 360;
	var M = (357.5291 + 0.98560028 * jstar) % 360;
	var C = 1.9148 * Math.sin(M * rad) + 0.02 * Math.sin(2 * M * rad) + 0.0003 * Math.sin(3 * M * rad);
	var L = (M + C + 180 + 102.9372) % 360;
	var jtransit = jstar + 0.0053 * Math.sin(M * rad) - 0.0069 * Math.sin(2 * L * rad);
	var dec = Math.asin(Math.sin(L * rad) * Math.sin(23.4397 * rad));
	var cosw = (Math.sin(-0.833 * rad) - Math.sin(lat * rad) * Math.sin(dec)) /
		(Math.cos(lat * rad) * Math.cos(dec));
	/* 2.8.7: polar day (sun never sets) / polar night (never rises). */
	if (cosw < -1) return { polar: 'day' };
	if (cosw > 1) return { polar: 'night' };
	var w = Math.acos(cosw) / rad;
	var toUnix = function (j) { return Math.round((j + 10957.5) * 86400); };
	return { sunrise: toUnix(jtransit - w / 360), sunset: toUnix(jtransit + w / 360) };
}

/* Fill fields only where the target slot is still empty. */
function fillFrom(g, src, maxSlot) {
	if (!src) return g;
	var fields = ['temp', 'feels', 'pop', 'precip', 'cloud', 'wmo_code', 'fog'];
	for (var i = 0; i < HOURS && i < maxSlot; i++) {
		if (g.temp[i] !== null) continue;
		var j = slot(src, g.time[i]);
		if (j < 0 || src.temp[j] === null) continue;
		fields.forEach(function (f) { if (src[f]) g[f][i] = src[f][j]; });
		['cloud_low', 'cloud_mid', 'cloud_high', 'visibility_m', 'shortwave'].forEach(function (f) {
			if (g[f] && src[f]) g[f][i] = src[f][j];
		});
	}
	return g;
}

/* Derive fog, valid_hours, current, tmin/tmax, sunrise fallback. */
function finalize(g, nowSec, lat, lon) {
	var nowSlot = slot(g, nowSec);
	for (var i = 0; i < HOURS; i++) {
		if (g.fog[i] === null && g.temp[i] !== null) {
			g.fog[i] = isFogWmo(g.wmo_code[i], g.visibility_m ? g.visibility_m[i] : null) ? 1 : 0;
		}
		if (g.temp[i] !== null) g.valid_hours = i + 1;
	}
	if (g.current_temp === null && nowSlot >= 0) g.current_temp = g.temp[nowSlot];
	if (g.current_code === null && nowSlot >= 0) g.current_code = g.wmo_code[nowSlot];
	/* Rolling next 24 h from now (2.8.5; the watch recomputes the same). */
	g.tmin = null; g.tmax = null;
	for (var k = Math.max(0, nowSlot); k < HOURS && k < Math.max(0, nowSlot) + 24; k++) {
		var t = g.temp[k];
		if (t === null) continue;
		if (g.tmin === null || t < g.tmin) g.tmin = t;
		if (g.tmax === null || t > g.tmax) g.tmax = t;
	}
	if ((g.sunrise === null || g.sunset === null) && lat !== undefined) {
		var hasOff = typeof g.utcOffsetS === 'number';
		var st = sunTimes(lat, lon, nowSec, hasOff ? -g.utcOffsetS / 60 : undefined);
		if (st && st.polar) {
			/* All day light: 00:00-23:59; all night: rise == set (never day). */
			g.sunriseLocal = st.polar === 'day' ? [0, 0] : [12, 0];
			g.sunsetLocal = st.polar === 'day' ? [23, 59] : [12, 0];
			g.sunrise = 0; g.sunset = 0;
		} else if (st) { g.sunrise = st.sunrise; g.sunset = st.sunset; }
	}
	g.has_layers = !!(g.cloud_low && g.cloud_mid && g.cloud_high);
	return g;
}

/**
 * Normalised grid → existing watch payload fields (+ fog bitmask, flags).
 * @param {Object} g        normalised grid
 * @param {string} unit     'celsius' | 'fahrenheit'
 * @param {boolean} fogOn   Fog indicator setting (promotes fog hours to WMO 45
 *                          so the weather-event strip shows them too)
 * @param {number} nowSec
 */
function toPayload(g, unit, fogOn, nowSec) {
	var f = unit === 'fahrenheit';
	function T(v) { return v === null ? null : (f ? v * 9 / 5 + 32 : v); }
	function arr(a, conv) {
		var out = [];
		for (var i = 0; i < HOURS; i++) {
			var v = a ? a[i] : null;
			out.push(v === null || v === undefined ? 0 : (conv ? conv(v) : v));
		}
		return out;
	}
	/* Pad temperatures past the data with the last value so the scale isn't
	 * skewed; the watch stops drawing at valid_hours anyway. */
	function temps(a) {
		var out = [], last = 0;
		for (var i = 0; i < HOURS; i++) {
			if (a[i] !== null) last = T(a[i]);
			out.push(a[i] === null ? last : T(a[i]));
		}
		/* Leading gap (no past data): back-fill with the first value. */
		var first = null;
		for (var j = 0; j < HOURS && first === null; j++) if (a[j] !== null) first = T(a[j]);
		for (var k = 0; k < HOURS && a[k] === null && first !== null; k++) out[k] = first;
		return out;
	}
	var fogBytes = [0, 0, 0, 0, 0, 0, 0, 0, 0];
	var codes = arr(g.wmo_code);
	for (var i = 0; i < HOURS; i++) {
		if (fogOn && g.fog[i]) {
			fogBytes[i >> 3] |= 1 << (i & 7);
			if (codes[i] <= 3) codes[i] = 45;
		}
	}
	var sr = g.sunrise !== null ? new Date(g.sunrise * 1000) : null;
	var ss = g.sunset !== null ? new Date(g.sunset * 1000) : null;
	var p = {
		precip_prob: arr(g.pop),
		temp_hourly: temps(g.temp),
		apparent_temp_hourly: temps(g.feels.map(function (v, i) { return v === null ? g.temp[i] : v; })),
		cloud_cover: arr(g.cloud),
		cloud_cover_low: arr(g.cloud_low),
		cloud_cover_mid: arr(g.cloud_mid),
		cloud_cover_high: arr(g.cloud_high),
		hourly_weather_code: codes,
		shortwave_radiation: arr(g.shortwave),
		fog_bits: fogBytes,
		wx_flags: g.has_layers ? 0 : 1,
		valid_hours: Math.max(1, g.valid_hours),
		current_temp: T(g.current_temp),
		weather_code: g.current_code,
		high_temp: T(g.tmax !== null ? g.tmax : g.current_temp),
		low_temp: T(g.tmin !== null ? g.tmin : g.current_temp),
		sunrise_hour: sr ? sr.getHours() : 6,
		sunrise_minute: sr ? sr.getMinutes() : 0,
		sunset_hour: ss ? ss.getHours() : 20,
		sunset_minute: ss ? ss.getMinutes() : 0,
		/* Watch counts slot 12 as the hour of fetch_time. */
		fetch_time: Math.max(nowSec, g.start + PAST * 3600),
		provider: g.provider,
	};
	if (!g.sunriseLocal && typeof g.utcOffsetS === 'number' && g.sunrise && g.sunset) {
		/* 2.8.7: location's clock when its UTC offset is known. */
		var off = g.utcOffsetS;
		var loc = function (t) { var s = ((t + off) % 86400 + 86400) % 86400; return [Math.floor(s / 3600), Math.floor((s % 3600) / 60)]; };
		p.sunrise_hour = loc(g.sunrise)[0]; p.sunrise_minute = loc(g.sunrise)[1];
		p.sunset_hour = loc(g.sunset)[0]; p.sunset_minute = loc(g.sunset)[1];
	}
	if (g.sunriseLocal) { /* provider already gave location-local clock values */
		p.sunrise_hour = g.sunriseLocal[0]; p.sunrise_minute = g.sunriseLocal[1];
		p.sunset_hour = g.sunsetLocal[0]; p.sunset_minute = g.sunsetLocal[1];
	}
	return p;
}

module.exports = {
	HOURS: HOURS, PAST: PAST, KEYED_FAIL_RE: KEYED_FAIL_RE,
	gridStart: gridStart, emptyGrid: emptyGrid, slot: slot, setAt: setAt, num: num,
	nulls: nulls, isFogWmo: isFogWmo, apparentTemp: apparentTemp, sunTimes: sunTimes,
	fillFrom: fillFrom, finalize: finalize, toPayload: toPayload,
};
