/**
 * Weather provider base (pattern after ForecasWatch2 src/pkjs/weather/).
 *
 * Every provider turns its API response into one normalised object on a fixed
 * hourly grid: 72 slots starting 12 h before the current UTC hour.
 *
 *   { provider, start, time[72], temp[72] °C, feels[72] °C, pop[72] %,
 *     precip[72] mm, cloud[72] %, cloud_low/mid/high[72]|null,
 *     visibility_m[72]|null, wmo_code[72], fog[72] (0/1), shortwave[72]|null,
 *     gust[72]|null (km/h, 2.9.3: wind event), cape[72]|null (J/kg),
 *     thunder_prob[72]|null (%, 2.9.4: strong-storm level),
 *     cloud_base[72]|null (m above ground, 3.0.0: Tomorrow.io),
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
		wmo_code: nulls(), fog: nulls(), shortwave: null, gust: null, cape: null, thunder_prob: null,
		cloud_base: null, snowfall: null, rh: null, lat: null, lon: null,
		current_live: false, current_observed: false, current_precip: null, current_snowfall: null, current_rh: null,
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

/* m/s → km/h (2.9.3: gusts are kept in km/h on the grid). */
function msToKmh(v) { return v === null || v === undefined ? null : Math.round(v * 3.6 * 10) / 10; }

/* 2.9.3: wind-event bitmask (bit i = hour i) for gusts >= thresholdKmh;
 * 0 / falsy threshold = off. Hours without gust data never trigger. */
function windBits(g, thresholdKmh) {
	var b = [0, 0, 0, 0, 0, 0, 0, 0, 0];
	if (!thresholdKmh || !g.gust) return b;
	for (var i = 0; i < HOURS; i++) {
		var v = g.gust[i];
		if (v !== null && v !== undefined && v >= thresholdKmh) b[i >> 3] |= 1 << (i & 7);
	}
	return b;
}

/* 2.9.4: "strong" thunderstorm hours for the Thunder "Strong only" level:
 * a thunder code (95-99) that is a hail code (96-99) or comes with
 * CAPE >= STRONG_CAPE J/kg (Open-Meteo) or thunder probability >=
 * STRONG_THUNDER_PROB % (MET Norway). Sent as a bitmask whatever the level,
 * so changing the level never refetches. */
var STRONG_CAPE = 1500;
var STRONG_THUNDER_PROB = 50;
function stormBits(g) {
	var b = [0, 0, 0, 0, 0, 0, 0, 0, 0];
	for (var i = 0; i < HOURS; i++) {
		var c = g.wmo_code[i];
		if (c === null || c < 95 || c > 99) continue;
		var cape = g.cape ? g.cape[i] : null, tp = g.thunder_prob ? g.thunder_prob[i] : null;
		if (c >= 96 || (cape !== null && cape >= STRONG_CAPE) || (tp !== null && tp >= STRONG_THUNDER_PROB))
			b[i >> 3] |= 1 << (i & 7);
	}
	return b;
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
		['cloud_low', 'cloud_mid', 'cloud_high', 'visibility_m', 'shortwave', 'gust', 'cape', 'thunder_prob', 'snowfall', 'rh'].forEach(function (f) {
			if (src[f] && !g[f] && (f === 'gust' || f === 'cape' || f === 'thunder_prob' || f === 'snowfall' || f === 'rh')) g[f] = nulls();
			if (g[f] && src[f]) g[f][i] = src[f][j];
		});
	}
	return g;
}

/* 3.1.2: models sometimes call snow when it is too warm for it to reach
 * the ground (Villars 23 Oct 2026: ICON-CH1 heavy snow at 5-6 °C). Only
 * snow codes change; sleet/freezing codes are never touched.
 * With humidity (wet-bulb, Stull 2011): Tw >= 2 °C rain, Tw <= 0.5 °C snow,
 * between: rain and snow mixed (68/69).
 * Without humidity, from temperature and snowfall (cm per hour):
 *   < 3 °C unchanged; 3-5 °C rain if no snowfall, mix if snowfall;
 *   5-7 °C mix if snowfall >= 0.05 cm, else rain; >= 7 °C rain.
 *   No snowfall amount: unchanged below 5 °C, rain from 5 °C. */
var SNOW_TO_RAIN = { 71: 61, 73: 63, 75: 65, 77: 51, 85: 80, 86: 81 };
var SNOW_TO_MIX = { 71: 68, 73: 69, 75: 69, 77: 68, 85: 68, 86: 69 };
var SNOW_MIN_TEMP = 3, SNOW_MAX_TEMP = 5, SNOW_RAIN_TEMP = 7, SNOWFALL_EPS_CM = 0.05;
var TW_RAIN = 2, TW_SNOW = 0.5;
function wetBulb(t, rh) {
	if (t === null || t === undefined || rh === null || rh === undefined || isNaN(rh) || rh <= 0) return null;
	rh = Math.min(100, rh);
	return t * Math.atan(0.151977 * Math.sqrt(rh + 8.313659)) + Math.atan(t + rh) - Math.atan(rh - 1.676331) +
		0.00391838 * Math.pow(rh, 1.5) * Math.atan(0.023101 * rh) - 4.686035;
}
function saneSnow(code, temp, snowfallCm, rh) {
	if (code === null || code === undefined || !SNOW_TO_RAIN[code]) return code;
	if (temp === null || temp === undefined) return code;
	var tw = wetBulb(temp, rh);
	if (tw !== null) return tw >= TW_RAIN ? SNOW_TO_RAIN[code] : tw <= TW_SNOW ? code : SNOW_TO_MIX[code];
	if (temp < SNOW_MIN_TEMP) return code;
	if (temp >= SNOW_RAIN_TEMP) return SNOW_TO_RAIN[code];
	var known = snowfallCm !== null && snowfallCm !== undefined;
	if (!known) return temp >= SNOW_MAX_TEMP ? SNOW_TO_RAIN[code] : code;
	return snowfallCm < SNOWFALL_EPS_CM ? SNOW_TO_RAIN[code] : SNOW_TO_MIX[code];
}
function isPrecipWmo(c) { return c !== null && c !== undefined && c >= 50; }

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
	/* 3.1.2: snow sanity check on every hour (idempotent: finalize can run twice). */
	for (var si = 0; si < HOURS; si++) {
		g.wmo_code[si] = saneSnow(g.wmo_code[si], g.temp[si], g.snowfall ? g.snowfall[si] : null, g.rh ? g.rh[si] : null);
	}
	/* The watch draws slot PAST as the current local hour (fetch_time's hour),
	 * so use that slot, not the nearest hour (round() picks the next from :30). */
	var curSlot = Math.floor((nowSec - g.start) / 3600);
	if (curSlot < 0 || curSlot >= HOURS) curSlot = -1;
	/* Observed current conditions (OWM, WeatherAPI) are not second-guessed;
	 * model "current" values (Open-Meteo 15-min) get the same snow check. */
	if (!g.current_observed) {
		g.current_code = saneSnow(g.current_code, g.current_temp,
			g.current_snowfall !== null ? g.current_snowfall : (g.snowfall && curSlot >= 0 ? g.snowfall[curSlot] : null),
			g.current_rh !== null ? g.current_rh : (g.rh && curSlot >= 0 ? g.rh[curSlot] : null));
	}
	/* 3.1.2: the live/15-min current reading overrides the current hour. */
	if (g.current_live && curSlot >= 0 && g.current_code !== null) {
		var wet = isPrecipWmo(g.current_code);
		g.wmo_code[curSlot] = g.current_code;
		if (g.current_precip !== null) {
			/* A precipitation code with 0 mm still shows a minimal bar, so the
			 * hour never has a rain icon/colour without a bar. */
			g.precip[curSlot] = wet ? Math.max(0.1, g.current_precip) : g.current_precip;
		} else if (wet) g.precip[curSlot] = Math.max(0.1, g.precip[curSlot] || 0);
		else if (g.precip[curSlot] !== null) g.precip[curSlot] = 0;
		/* Not falling now: rain chance capped at 10 % (the rest of the hour may still turn wet). */
		if (!wet && g.pop[curSlot] !== null) g.pop[curSlot] = Math.min(g.pop[curSlot], 10);
		if (g.snowfall && g.current_snowfall !== null) g.snowfall[curSlot] = g.current_snowfall;
	}
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
	/* 3.0.3: a layer can never cover more sky than the total (models mixed in
	 * a blend can disagree, e.g. total 0 % with low 98 %), so Split and Total
	 * show the same clouds. */
	if (g.has_layers) {
		for (var ci = 0; ci < HOURS; ci++) {
			var tot = g.cloud[ci];
			if (tot === null || tot === undefined) continue;
			['cloud_low', 'cloud_mid', 'cloud_high'].forEach(function (k) {
				var v = g[k][ci];
				if (v !== null && v !== undefined && v > tot) g[k][ci] = tot;
			});
		}
	}
	if (lat !== undefined && lat !== null) { g.lat = Number(lat); g.lon = Number(lon); }
	return g;
}

/* ---- 3.0.0: cloud thickness ---------------------------------------------
 * Level per hour: 0 no data (drawn normal), 1 light, 2 normal, 3 thick.
 * Day (sun >= THICK_MIN_ELEV at mid-hour, radiation known): clear-sky ratio
 * kt = shortwave / clear-sky GHI (Haurwitz). Radiation is the mean of the
 * hour before the slot time, so the sun is taken at slot time - 30 min.
 * Otherwise a fallback from rain, the layer mix, the cloud base (Tomorrow.io)
 * and total cover. Rain >= THICK_RAIN_MM always makes the hour thick. */
var THICK_LIGHT_KT = 0.70, THICK_THICK_KT = 0.35, THICK_MIN_ELEV = 10, THICK_RAIN_MM = 0.3;

/* Solar elevation in degrees (NOAA low-precision; ~0.5 deg). */
function sunElevation(lat, lon, tSec) {
	var rad = Math.PI / 180;
	var d = tSec / 86400 - 10957.5; /* days since J2000.0 */
	var g = (357.529 + 0.98560028 * d) % 360;
	var q = (280.459 + 0.98564736 * d) % 360;
	var L = q + 1.915 * Math.sin(g * rad) + 0.020 * Math.sin(2 * g * rad);
	var e = 23.439 - 0.00000036 * d;
	var ra = Math.atan2(Math.cos(e * rad) * Math.sin(L * rad), Math.cos(L * rad)) / rad;
	var dec = Math.asin(Math.sin(e * rad) * Math.sin(L * rad));
	var gmst = (18.697374558 + 24.06570982441908 * d) % 24;
	var ha = ((gmst * 15 + lon - ra) % 360 + 540) % 360 - 180;
	var sinEl = Math.sin(lat * rad) * Math.sin(dec) + Math.cos(lat * rad) * Math.cos(dec) * Math.cos(ha * rad);
	return Math.asin(Math.max(-1, Math.min(1, sinEl))) / rad;
}

/* Haurwitz clear-sky global horizontal irradiance (W/m2). */
function clearSkyGhi(elevDeg) {
	var cz = Math.sin(elevDeg * Math.PI / 180);
	return cz <= 0 ? 0 : 1098 * cz * Math.exp(-0.057 / cz);
}

function at(a, i) { var v = a ? a[i] : null; return v === null || v === undefined || isNaN(v) ? null : v; }

function thickLevel(g, i) {
	var cover = at(g.cloud, i);
	if (cover === null) return 0;
	var mm = at(g.precip, i);
	if (mm !== null && mm >= THICK_RAIN_MM) return 3;
	var sw = at(g.shortwave, i);
	if (sw !== null && g.lat !== null && g.lat !== undefined && g.time) {
		var el = sunElevation(g.lat, g.lon, g.time[i] - 1800);
		if (el >= THICK_MIN_ELEV) {
			var kt = sw / clearSkyGhi(el);
			return kt >= THICK_LIGHT_KT ? 1 : (kt >= THICK_THICK_KT ? 2 : 3);
		}
	}
	var base = at(g.cloud_base, i);
	if (base !== null && base < 1000 && cover >= 85) return 3;
	var lo = at(g.cloud_low, i), mi = at(g.cloud_mid, i), hi = at(g.cloud_high, i);
	if (g.has_layers && lo !== null && mi !== null && hi !== null) {
		if (lo >= 80 && cover >= 90) return 3;
		if (lo < 15 && mi < 15 && hi >= 15) return 1; /* high cloud only: thin cirrus */
		return 2;
	}
	return 2; /* 3.0.3: no layer or radiation data: never call it light from cover alone */
}

/* 2 bits per hour, hour i in bits (i & 3) * 2 of byte i >> 2 (18 bytes). */
function thickBits(g) {
	var b = [];
	for (var k = 0; k < HOURS / 4; k++) b.push(0);
	for (var i = 0; i < HOURS; i++) b[i >> 2] |= (thickLevel(g, i) & 3) << ((i & 3) * 2);
	return b;
}

/**
 * Normalised grid → existing watch payload fields (+ fog bitmask, flags).
 * @param {Object} g        normalised grid
 * @param {string} unit     'celsius' | 'fahrenheit'
 * @param {boolean} fogOn   Fog indicator setting (promotes fog hours to WMO 45
 *                          so the weather-event strip shows them too)
 * @param {number} nowSec
 * @param {number} [windKmh] gust threshold for the wind event (0 = off, 2.9.3)
 * @param {number} [fogVis]  fog level: max visibility in m (1000 default, 500, 200; 2.9.4).
 *                           Stricter levels only narrow the provider's fog hours;
 *                           fog codes 45/48 and hours without visibility data stay fog.
 */
/** mm/h → uint8 tenths (0.1 mm steps, 255 = 25.5 mm/h or more); null → 0. */
function packMm(a) {
	var out = [];
	for (var i = 0; i < HOURS; i++) {
		var v = a ? a[i] : null;
		out.push(v === null || v === undefined || !(v > 0) ? 0 : Math.min(255, Math.round(v * 10)));
	}
	return out;
}
/** True when the provider gave any hourly amount (WX_FLAG_HAS_MM). */
function hasMm(a) {
	if (!a) return false;
	for (var i = 0; i < a.length; i++) if (a[i] !== null && a[i] !== undefined) return true;
	return false;
}

function toPayload(g, unit, fogOn, nowSec, windKmh, fogVis) {
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
		var fogHere = fogOn && g.fog[i];
		if (fogHere && fogVis && fogVis < 1000) {
			var wc = g.wmo_code[i], vm = g.visibility_m ? g.visibility_m[i] : null;
			fogHere = wc === 45 || wc === 48 || vm === null || vm === undefined || vm < fogVis;
		}
		if (fogHere) {
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
		wind_bits: windBits(g, windKmh),
		storm_bits: stormBits(g),
		/* 2.9.6: hourly amount, 0.1 mm/h steps capped at 25.5 (uint8). */
		precip_mm: packMm(g.precip),
		/* 3.0.0: cloud thickness, 2 bits per hour (the watch splits it per layer). */
		cloud_thick: thickBits(g),
		wx_flags: (g.has_layers ? 0 : 1) | (hasMm(g.precip) ? 2 : 0),
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
	nulls: nulls, isFogWmo: isFogWmo, saneSnow: saneSnow, wetBulb: wetBulb, apparentTemp: apparentTemp, sunTimes: sunTimes,
	fillFrom: fillFrom, finalize: finalize, toPayload: toPayload,
	packMm: packMm, hasMm: hasMm, sunElevation: sunElevation, clearSkyGhi: clearSkyGhi, thickLevel: thickLevel, thickBits: thickBits,
	THICK_LIGHT_KT: THICK_LIGHT_KT, THICK_THICK_KT: THICK_THICK_KT, THICK_MIN_ELEV: THICK_MIN_ELEV, THICK_RAIN_MM: THICK_RAIN_MM, msToKmh: msToKmh, windBits: windBits, stormBits: stormBits, STRONG_CAPE: STRONG_CAPE, STRONG_THUNDER_PROB: STRONG_THUNDER_PROB,
};
