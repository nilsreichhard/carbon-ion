/**
 * Meteoblue packages API (API key): basic-1h + clouds-1h.
 * https://docs.meteoblue.com/en/weather-apis/packages-api/overview
 * 1 h steps for 7 days, no past hours (filled by caller). clouds-1h adds
 * low/mid/high cloud layers, visibility and fog probability; if the key's
 * plan lacks clouds-1h the response simply has no layers (1-layer clouds).
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://my.meteoblue.com/packages/basic-1h_clouds-1h';
function buildUrl(lat, lon, opts) {
	return BASE + '?lat=' + Number(lat).toFixed(4) + '&lon=' + Number(lon).toFixed(4) +
		'&format=json&timeformat=timestamp_utc&tz=utc&apikey=' + encodeURIComponent((opts && opts.apiKey) || '');
}

/* Hourly pictocode (1–35) → WMO. */
var PICTO_WMO = [null, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 1, 1, 1, 45, 45, 45, 3, 3, 3, 3,
	63, 73, 65, 75, 95, 95, 75, 95, 80, 85, 61, 71, 68];
function pictoToWmo(p) {
	p = Number(p);
	return (p >= 1 && p <= 35) ? PICTO_WMO[p] : null;
}

function parse(json, ctx) {
	var h = json && json.data_1h;
	if (!h || !h.time) throw new Error('invalid meteoblue payload' + (json && json.error_message ? ' (' + String(json.error_message).slice(0, 60) + ')' : ''));
	var g = P.emptyGrid('meteoblue', ctx.now);
	var layers = !!(h.lowclouds && h.midclouds && h.highclouds);
	if (layers) { g.cloud_low = P.nulls(); g.cloud_mid = P.nulls(); g.cloud_high = P.nulls(); }
	if (h.visibility) g.visibility_m = P.nulls();
	function at(a, n) { return a ? P.num(a[n]) : null; }
	for (var n = 0; n < h.time.length; n++) {
		var t = typeof h.time[n] === 'number' ? h.time[n] : Date.parse(String(h.time[n]).replace(' ', 'T') + 'Z') / 1000;
		var i = P.slot(g, t);
		if (i < 0) continue;
		P.setAt(g, 'temp', i, at(h.temperature, n));
		P.setAt(g, 'feels', i, at(h.felttemperature, n));
		P.setAt(g, 'pop', i, at(h.precipitation_probability, n));
		P.setAt(g, 'precip', i, at(h.precipitation, n));
		if (h.relativehumidity) P.setAt(g, 'rh', i, at(h.relativehumidity, n));
		/* 3.1.2: snowfraction (0-1, basic-1h) × water mm × 0.7 ≈ snow cm. */
		if (h.snowfraction) { var sf = at(h.snowfraction, n), pr = at(h.precipitation, n); if (!g.snowfall) g.snowfall = P.nulls(); g.snowfall[i] = (sf === null || pr === null) ? null : sf * pr * 0.7; }
		P.setAt(g, 'cloud', i, at(h.totalcloudcover, n));
		if (layers) {
			P.setAt(g, 'cloud_low', i, at(h.lowclouds, n));
			P.setAt(g, 'cloud_mid', i, at(h.midclouds, n));
			P.setAt(g, 'cloud_high', i, at(h.highclouds, n));
		}
		var vis = at(h.visibility, n);
		if (g.visibility_m) P.setAt(g, 'visibility_m', i, vis);
		var pc = at(h.pictocode, n);
		var code = pictoToWmo(pc);
		P.setAt(g, 'wmo_code', i, code === null ? 3 : code);
		var fp = at(h.fog_probability, n);
		g.fog[i] = ((pc >= 16 && pc <= 18) || (fp !== null && fp >= 50) || (vis !== null && vis < 1000)) ? 1 : 0;
	}
	return P.finalize(g, ctx.now, ctx.lat, ctx.lon);
}

module.exports = { id: 'meteoblue', needsKey: true, label: 'Meteoblue',
	attribution: 'Weather data by meteoblue', buildUrl: buildUrl, parse: parse,
	pictoToWmo: pictoToWmo, needsPastFill: true };
