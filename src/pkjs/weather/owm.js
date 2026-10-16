/**
 * OpenWeatherMap (API key). Default: the free 5-day / 3-hour forecast
 * (data/2.5/forecast, works with every free key), linearly interpolated to the
 * hourly grid (~54 h of the 72-slot grid ahead). Optional: One Call 3.0
 * (opts.owmOneCall, separate "One Call by Call" subscription), hourly 48 h.
 * No past hours, no cloud layers.
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://api.openweathermap.org/data/3.0/onecall';
var BASE25 = 'https://api.openweathermap.org/data/2.5/forecast';
function buildUrl(lat, lon, opts) {
	opts = opts || {};
	if (!opts.owmOneCall)
		return BASE25 + '?lat=' + lat + '&lon=' + lon + '&units=metric&cnt=40&appid=' + encodeURIComponent(opts.apiKey || '');
	return BASE + '?lat=' + lat + '&lon=' + lon + '&exclude=minutely,alerts&units=metric&appid=' +
		encodeURIComponent(opts.apiKey || '');
}

/* OWM condition id (+ visibility m) → WMO 4677 */
function idToWmo(id, vis) {
	id = Number(id);
	if (id >= 200 && id < 300) return (id === 202 || id === 212 || id === 221 || id === 232) ? 96 : 95;
	if (id >= 300 && id < 400) return id <= 301 ? 51 : id <= 311 ? 53 : 55;
	if (id === 500) return 61;
	if (id === 501) return 63;
	if (id >= 502 && id <= 504) return 65;
	if (id === 511) return 67;
	if (id === 520) return 80;
	if (id === 521) return 81;
	if (id === 522 || id === 531) return 82;
	if (id === 600) return 71;
	if (id === 601) return 73;
	if (id === 602) return 75;
	if (id >= 611 && id <= 616) return 68;
	if (id === 620 || id === 621) return 85;
	if (id === 622) return 86;
	if (id === 741) return 45;
	if (id === 701) return (vis !== null && vis !== undefined && vis < 1000) ? 45 : 3;
	if (id === 781) return 19;
	if (id === 771) return 18;
	if (id >= 700 && id < 800) return 3;
	if (id === 800) return 0;
	if (id === 801) return 1;
	if (id === 802) return 2;
	if (id === 803 || id === 804) return 3;
	return null;
}

/* 2.5 forecast: 3-hourly steps → hourly. Continuous fields are linearly
 * interpolated between steps; codes/fog are taken from the nearest step. */
function parse25(json, ctx) {
	if (!json || !json.list || !json.list.length) throw new Error('invalid owm payload' + (json && json.message ? ' (' + String(json.message).slice(0, 60) + ')' : ''));
	var g = P.emptyGrid('owm', ctx.now);
	g.visibility_m = P.nulls();
	var L = json.list.filter(function (s) { return s && s.main && isFinite(s.dt); });
	function step(s) {
		var vis = P.num(s.visibility);
		var id = s.weather && s.weather[0] ? Number(s.weather[0].id) : null;
		return { t: s.dt, temp: P.num(s.main.temp), feels: P.num(s.main.feels_like),
			pop: s.pop !== undefined ? Math.round(Number(s.pop) * 100) : null,
			precip: (((s.rain && s.rain['3h']) || 0) + ((s.snow && s.snow['3h']) || 0)) / 3,
			cloud: s.clouds ? P.num(s.clouds.all) : null, vis: vis, id: id };
	}
	var S = L.map(step);
	function lerp(a, b, f) { return (a === null || b === null) ? (f < 0.5 ? a : b) : a + (b - a) * f; }
	for (var i = 0; i < P.HOURS; i++) {
		var t = g.time[i];
		if (t < S[0].t || t > S[S.length - 1].t) continue;
		var k = 0;
		while (k < S.length - 2 && S[k + 1].t < t) k++;
		var a = S[k], b = S[Math.min(k + 1, S.length - 1)];
		var f = b.t > a.t ? (t - a.t) / (b.t - a.t) : 0;
		if (f < 0) f = 0; if (f > 1) f = 1;
		var near = f < 0.5 ? a : b;
		['temp', 'feels', 'pop', 'precip', 'cloud'].forEach(function (fl) { P.setAt(g, fl, i, lerp(a[fl], b[fl], f)); });
		P.setAt(g, 'visibility_m', i, lerp(a.vis, b.vis, f));
		var code = idToWmo(near.id, near.vis);
		P.setAt(g, 'wmo_code', i, code === null ? 3 : code);
		g.fog[i] = (near.id === 741 || (near.id === 701 && near.vis !== null && near.vis < 1000)) ? 1 : 0;
	}
	if (json.city && typeof json.city.timezone === 'number') g.utcOffsetS = json.city.timezone; /* 2.8.7 */
	if (json.city && json.city.sunrise) { g.sunrise = json.city.sunrise; g.sunset = json.city.sunset; }
	g.current_temp = S[0].temp;
	g.current_code = idToWmo(S[0].id, S[0].vis);
	return P.finalize(g, ctx.now, ctx.lat, ctx.lon);
}

function parse(json, ctx) {
	if (json && json.list) return parse25(json, ctx);
	if (!json || !json.hourly) throw new Error('invalid owm payload' + (json && json.message ? ' (' + String(json.message).slice(0, 60) + ')' : ''));
	var g = P.emptyGrid('owm', ctx.now);
	g.visibility_m = P.nulls();
	json.hourly.forEach(function (h) {
		var i = P.slot(g, h.dt);
		if (i < 0) return;
		var vis = P.num(h.visibility);
		var id = h.weather && h.weather[0] ? h.weather[0].id : null;
		P.setAt(g, 'temp', i, P.num(h.temp));
		P.setAt(g, 'feels', i, P.num(h.feels_like));
		P.setAt(g, 'pop', i, h.pop !== undefined ? Math.round(Number(h.pop) * 100) : null);
		P.setAt(g, 'precip', i, ((h.rain && h.rain['1h']) || 0) + ((h.snow && h.snow['1h']) || 0));
		P.setAt(g, 'cloud', i, P.num(h.clouds));
		P.setAt(g, 'visibility_m', i, vis);
		var code = idToWmo(id, vis);
		P.setAt(g, 'wmo_code', i, code === null ? 3 : code);
		g.fog[i] = (Number(id) === 741 || (Number(id) === 701 && vis !== null && vis < 1000)) ? 1 : 0;
	});
	if (typeof json.timezone_offset === 'number') g.utcOffsetS = json.timezone_offset; /* 2.8.7 */
	if (json.current) {
		g.current_temp = P.num(json.current.temp);
		if (json.current.weather && json.current.weather[0]) g.current_code = idToWmo(json.current.weather[0].id, P.num(json.current.visibility));
		if (json.current.sunrise) { g.sunrise = json.current.sunrise; g.sunset = json.current.sunset; }
	}
	if (json.daily && json.daily[0] && json.daily[0].temp) {
		g.tmax = P.num(json.daily[0].temp.max); g.tmin = P.num(json.daily[0].temp.min);
	}
	return P.finalize(g, ctx.now, ctx.lat, ctx.lon);
}

module.exports = { id: 'owm', needsKey: true, label: 'OpenWeatherMap',
	attribution: 'Weather data by OpenWeather', buildUrl: buildUrl, parse: parse,
	idToWmo: idToWmo, parse25: parse25, needsPastFill: true };
