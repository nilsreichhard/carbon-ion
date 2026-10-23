/**
 * GeoSphere Austria dataset API, NWP 1 h / 1 km (no key, CC BY 4.0).
 * https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v2-1h-1km
 * (3.1.2: replaces nwp-v1-1h-2500m.) Domain 43.0-51.5 N, 5.03-22.57 E, runs
 * every 3 h, 61 h from the run, no past hours.
 * Native: 2t (°C), 2r (%), tcc (%), tp and sf (kg m-2 = mm water in the
 * hour ending at the timestamp), ssrd (W m-2), 10fg (m/s gust), cape.
 * Past hours and the fields it lacks (rain probability, weather code,
 * visibility/fog, feels-like, sunrise) are filled from Open-Meteo best_match
 * by the orchestrator (blendOpenMeteo).
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v2-1h-1km';
var BBOX = [43.002, 5.0317, 51.498, 22.5682];

function inDomain(lat, lon) {
	return lat >= BBOX[0] && lat <= BBOX[2] && lon >= BBOX[1] && lon <= BBOX[3];
}

function buildUrl(lat, lon) {
	return BASE + '?lat_lon=' + Number(lat).toFixed(4) + ',' + Number(lon).toFixed(4) +
		'&parameters=2t,2r,tcc,tp,sf,ssrd,10fg,cape&output_format=geojson';
}

/* Raw grid (not finalised): the orchestrator blends and finalises. */
function parse(json, ctx) {
	if (!inDomain(ctx.lat, ctx.lon)) throw new Error('outside GeoSphere domain');
	var ts = json && json.timestamps;
	var f = json && json.features && json.features[0];
	var pr = f && f.properties && f.properties.parameters;
	if (!ts || !pr || !pr['2t']) throw new Error('invalid geosphere payload');
	var g = P.emptyGrid('geosphere', ctx.now);
	g.shortwave = P.nulls();
	function d(name) { return pr[name] && pr[name].data ? pr[name].data : []; }
	var t2m = d('2t'), rh = d('2r'), tcc = d('tcc'), tp = d('tp'), sf = d('sf'), sw = d('ssrd'), fg = d('10fg'), cape = d('cape');
	g.snowfall = P.nulls();
	for (var n = 0; n < ts.length; n++) {
		var i = P.slot(g, Date.parse(ts[n]) / 1000);
		if (i < 0) continue;
		P.setAt(g, 'temp', i, P.num(t2m[n]));
		P.setAt(g, 'rh', i, P.num(rh[n]));
		var c = P.num(tcc[n]);
		P.setAt(g, 'cloud', i, c === null ? null : Math.round(c));
		var mm = P.num(tp[n]);
		P.setAt(g, 'precip', i, mm === null ? null : Math.max(0, Math.round(mm * 10) / 10));
		var s = P.num(sf[n]);
		g.snowfall[i] = s === null ? null : Math.max(0, s) * 0.7; /* water mm → cm, as Open-Meteo */
		var r = P.num(sw[n]);
		P.setAt(g, 'shortwave', i, r === null ? null : Math.max(0, Math.round(r)));
		P.setAt(g, 'gust', i, P.msToKmh(P.num(fg[n])));
		P.setAt(g, 'cape', i, P.num(cape[n]));
	}
	return g;
}

module.exports = { id: 'geosphere', needsKey: false, label: 'GeoSphere Austria',
	attribution: 'Weather data by GeoSphere Austria (CC BY 4.0), gaps from Open-Meteo.com',
	buildUrl: buildUrl, parse: parse, inDomain: inDomain, blendOpenMeteo: true };
