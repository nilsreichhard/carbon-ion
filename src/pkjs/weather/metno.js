/**
 * MET Norway Locationforecast 2.0 /complete (no key). CC-BY 4.0 / NLOD.
 * Terms: identifying User-Agent with contact; honour Expires and send
 * If-Modified-Since; ≤ 4 coordinate decimals. No past hours: the caller fills
 * the past 12 h from the previous MET fetch or Open-Meteo past_hours.
 * Hourly steps for ~60 h, then 6-hourly (gaps stay "no data").
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');

var BASE = 'https://api.met.no/weatherapi/locationforecast/2.0/complete';
var USER_AGENT = 'CarbonIon-Pebble/2.8 github.com/nilsreichhard/carbon-ion';

function r4(v) { return Math.round(Number(v) * 10000) / 10000; }
function buildUrl(lat, lon) { return BASE + '?lat=' + r4(lat) + '&lon=' + r4(lon); }

/* symbol_code (without _day/_night/_polartwilight) → WMO 4677 */
var SYMBOL_WMO = {
	clearsky: 0, fair: 1, partlycloudy: 2, cloudy: 3, fog: 45,
	lightrain: 61, rain: 63, heavyrain: 65,
	lightrainshowers: 80, rainshowers: 81, heavyrainshowers: 82,
	lightsleet: 68, sleet: 68, heavysleet: 69,
	lightsleetshowers: 68, sleetshowers: 68, heavysleetshowers: 69,
	lightsnow: 71, snow: 73, heavysnow: 75,
	lightsnowshowers: 85, snowshowers: 85, heavysnowshowers: 86,
};
function symbolToWmo(sym) {
	if (!sym) return null;
	var s = String(sym).replace(/_(day|night|polartwilight)$/, '');
	if (/thunder/.test(s)) return /heavy/.test(s) ? 96 : 95;
	return SYMBOL_WMO.hasOwnProperty(s) ? SYMBOL_WMO[s] : null;
}

function parse(json, ctx) {
	var ts = json && json.properties && json.properties.timeseries;
	if (!ts || !ts.length) throw new Error('invalid met.no payload');
	var g = P.emptyGrid('metno', ctx.now);
	g.cloud_low = P.nulls(); g.cloud_mid = P.nulls(); g.cloud_high = P.nulls();
	for (var n = 0; n < ts.length; n++) {
		var t = Date.parse(ts[n].time) / 1000;
		var i = P.slot(g, t);
		if (i < 0) continue;
		var d = (ts[n].data && ts[n].data.instant && ts[n].data.instant.details) || {};
		var n1 = ts[n].data && ts[n].data.next_1_hours;
		if (!n1) continue; /* 6-hourly tail: leave as no data */
		var temp = P.num(d.air_temperature);
		P.setAt(g, 'temp', i, temp);
		var feels = P.num(d.apparent_air_temperature);
		if (feels === null) feels = P.apparentTemp(temp, P.num(d.relative_humidity), P.num(d.wind_speed));
		P.setAt(g, 'feels', i, feels);
		P.setAt(g, 'cloud', i, P.num(d.cloud_area_fraction));
		P.setAt(g, 'cloud_low', i, P.num(d.cloud_area_fraction_low));
		P.setAt(g, 'cloud_mid', i, P.num(d.cloud_area_fraction_medium));
		P.setAt(g, 'cloud_high', i, P.num(d.cloud_area_fraction_high));
		P.setAt(g, 'gust', i, P.msToKmh(P.num(d.wind_speed_of_gust)));
		var nd = n1.details || {};
		P.setAt(g, 'precip', i, P.num(nd.precipitation_amount));
		P.setAt(g, 'thunder_prob', i, P.num(nd.probability_of_thunder)); /* 2.9.4 */
		var pop = P.num(nd.probability_of_precipitation);
		if (pop === null) { /* not provided outside the Nordics: derive from amount */
			var mm = P.num(nd.precipitation_amount) || 0, mx = P.num(nd.precipitation_amount_max);
			pop = mm >= 0.1 ? Math.min(100, 40 + mm * 30) : (mx && mx > 0.1 ? 20 : 0);
		}
		P.setAt(g, 'pop', i, Math.round(pop));
		var code = symbolToWmo(n1.summary && n1.summary.symbol_code);
		if (code === null) code = (P.num(d.cloud_area_fraction) || 0) > 85 ? 3 : 2;
		P.setAt(g, 'wmo_code', i, code);
		var fogFrac = P.num(d.fog_area_fraction);
		g.fog[i] = (code === 45 || (fogFrac !== null && fogFrac >= 50)) ? 1 : 0;
	}
	return P.finalize(g, ctx.now, ctx.lat, ctx.lon);
}

module.exports = { id: 'metno', needsKey: false, label: 'MET Norway', userAgent: USER_AGENT,
	attribution: 'Weather data by MET Norway (CC BY 4.0)', buildUrl: buildUrl, parse: parse,
	symbolToWmo: symbolToWmo, needsPastFill: true };
