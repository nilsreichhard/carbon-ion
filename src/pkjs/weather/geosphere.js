/**
 * GeoSphere Austria dataset API, NWP 1 h / 2.5 km (no key, CC BY 4.0).
 * https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m
 * Domain ≈ 43.0–51.8 N, 5.5–22.1 E, ~60 h from the latest run, no past hours.
 * Native: temperature, total cloud (1 layer), precipitation, radiation.
 * Past hours and the fields it lacks (rain probability, weather code,
 * visibility/fog, feels-like, sunrise) are filled from Open-Meteo best_match
 * by the orchestrator (blendOpenMeteo).
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m';
var BBOX = [42.981, 5.498, 51.819, 22.102];

function inDomain(lat, lon) {
	return lat >= BBOX[0] && lat <= BBOX[2] && lon >= BBOX[1] && lon <= BBOX[3];
}

function buildUrl(lat, lon) {
	return BASE + '?lat_lon=' + Number(lat).toFixed(4) + ',' + Number(lon).toFixed(4) +
		'&parameters=t2m,tcc,rr_acc,grad&output_format=geojson';
}

/* Raw grid (not finalised): the orchestrator blends and finalises. */
function parse(json, ctx) {
	if (!inDomain(ctx.lat, ctx.lon)) throw new Error('outside GeoSphere domain');
	var ts = json && json.timestamps;
	var f = json && json.features && json.features[0];
	var pr = f && f.properties && f.properties.parameters;
	if (!ts || !pr || !pr.t2m) throw new Error('invalid geosphere payload');
	var g = P.emptyGrid('geosphere', ctx.now);
	g.shortwave = P.nulls();
	function d(name) { return pr[name] && pr[name].data ? pr[name].data : []; }
	var t2m = d('t2m'), tcc = d('tcc'), rr = d('rr_acc'), grad = d('grad');
	for (var n = 0; n < ts.length; n++) {
		var i = P.slot(g, Date.parse(ts[n]) / 1000);
		if (i < 0) continue;
		P.setAt(g, 'temp', i, P.num(t2m[n]));
		var c = P.num(tcc[n]);
		P.setAt(g, 'cloud', i, c === null ? null : Math.round(c * 100));
		/* Accumulated since the run start → hourly increments. */
		if (n > 0) {
			var a = P.num(rr[n]), b = P.num(rr[n - 1]);
			P.setAt(g, 'precip', i, (a === null || b === null) ? null : Math.max(0, Math.round((a - b) * 10) / 10));
			var ga = P.num(grad[n]), gb = P.num(grad[n - 1]);
			P.setAt(g, 'shortwave', i, (ga === null || gb === null) ? null : Math.max(0, Math.round((ga - gb) / 3600)));
		}
	}
	return g;
}

module.exports = { id: 'geosphere', needsKey: false, label: 'GeoSphere Austria',
	attribution: 'Weather data by GeoSphere Austria (CC BY 4.0), gaps from Open-Meteo.com',
	buildUrl: buildUrl, parse: parse, inDomain: inDomain, blendOpenMeteo: true };
