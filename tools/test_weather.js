/* Host test: TZ=Europe/Zurich node tools/test_weather.js
 * Fixture tests for every weather provider normaliser, fog mapping, padding,
 * past fill and keyed-provider fallback (no network, no API keys). */
var W = require('../src/pkjs/weather/index.js');
var P = require('../src/pkjs/weather/provider.js');
var NOW = Date.UTC(2026, 9, 15, 16, 20) / 1000, H0 = Date.UTC(2026, 9, 15, 4) / 1000;
var CTX = { now: NOW, lat: 46.3, lon: 7.0 };
var fails = 0, n = 0;
function check(name, got, want) {
	n++; var ok = JSON.stringify(got) === JSON.stringify(want);
	if (!ok) fails++;
	console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '\n   got  ' + JSON.stringify(got) + '\n   want ' + JSON.stringify(want)));
}
function hours(from, count) { var a = []; for (var i = 0; i < count; i++) a.push(from + i * 3600); return a; }
function iso(t) { return new Date(t * 1000).toISOString().replace('.000', ''); }

/* ---- Open-Meteo (incl. MeteoSwiss merge) ---- */
var t72 = hours(H0, 72);
var om = { utc_offset_seconds: 7200, current: { temperature_2m: 16.1, weather_code: 2 },
	daily: { sunrise: [Date.UTC(2026, 9, 15, 5, 49) / 1000], sunset: [Date.UTC(2026, 9, 15, 16, 45) / 1000], temperature_2m_max: [18], temperature_2m_min: [10] },
	hourly: { time: t72 } };
['temperature_2m', 'apparent_temperature', 'precipitation_probability', 'precipitation', 'cloud_cover', 'cloud_cover_low', 'cloud_cover_mid', 'cloud_cover_high', 'shortwave_radiation'].forEach(function (k, j) {
	om.hourly[k] = t72.map(function (_, i) { return i + j; });
});
om.hourly.weather_code = t72.map(function (_, i) { return i === 20 ? 45 : i === 21 ? 48 : 2; });
om.hourly.visibility = t72.map(function (_, i) { return i === 30 ? 800 : 24000; });
var g = W.PROVIDERS.openmeteo.parse(om, CTX);
var p = P.toPayload(g, 'celsius', true, NOW);
check('OM grid start = now-12h', iso(g.start), '2026-10-15T04:00:00Z');
check('OM valid 72, layers', [g.valid_hours, g.has_layers, p.wx_flags], [72, true, 0]);
check('OM fog: WMO45/48 + vis<1000', [g.fog[20], g.fog[21], g.fog[30], g.fog[31]], [1, 1, 1, 0]);
check('OM fog bitmask bytes', p.fog_bits, [0, 0, 48, 64, 0, 0, 0, 0, 0]);
check('OM fog hour promoted to WMO 45 (code<=3)', p.hourly_weather_code[30], 45);
check('OM fog off: no bits, no promotion', [P.toPayload(g, 'celsius', false, NOW).fog_bits.join(''), P.toPayload(g, 'celsius', false, NOW).hourly_weather_code[30]], ['000000000', 2]);
check('OM sunrise local clock (offset)', [p.sunrise_hour, p.sunrise_minute, p.sunset_hour, p.sunset_minute], [7, 49, 18, 45]);
check('OM °F conversion', P.toPayload(g, 'fahrenheit', true, NOW).temp_hourly[10], 50);
var oms = JSON.parse(JSON.stringify(om)); delete oms.hourly.temperature_2m;
oms.hourly.temperature_2m_meteoswiss_icon_ch1 = t72.map(function (_, i) { return i < 45 ? 100 + i : null; });
oms.hourly.temperature_2m_best_match = t72.map(function (_, i) { return 200 + i; });
var gs = W.PROVIDERS.openmeteo.parse(oms, CTX);
check('MeteoSwiss ICON-CH1 then best_match beyond horizon', [gs.temp[44], gs.temp[45]], [144, 245]);
check('OM URL legacy MeteoSwiss toggle → CH1,CH2,best_match', /models=meteoswiss_icon_ch1,meteoswiss_icon_ch2,best_match&/.test(W.PROVIDERS.openmeteo.buildUrl(46.3, 7, { meteoswiss: true })), true);
check('OM URL requests visibility', /visibility/.test(W.PROVIDERS.openmeteo.buildUrl(46.3, 7, {})), true);

/* ---- MET Norway ---- */
var ts = hours(Date.UTC(2026, 9, 15, 16) / 1000, 62).map(function (t, i) {
	return { time: iso(t), data: { instant: { details: { air_temperature: 10 + i * 0.1, relative_humidity: 80, wind_speed: 2,
		cloud_area_fraction: 50, cloud_area_fraction_low: 10, cloud_area_fraction_medium: 20, cloud_area_fraction_high: 30,
		fog_area_fraction: i === 3 ? 60 : 0 } },
		next_1_hours: { summary: { symbol_code: i === 5 ? 'fog' : i === 6 ? 'heavyrainandthunder' : i === 7 ? 'lightsnowshowers_night' : 'partlycloudy_day' },
			details: { precipitation_amount: i === 6 ? 2 : 0 } } } };
});
ts.push({ time: iso(Date.UTC(2026, 9, 18, 6) / 1000), data: { instant: { details: { air_temperature: 3 } }, next_6_hours: {} } });
var gm = W.PROVIDERS.metno.parse({ properties: { timeseries: ts } }, CTX);
check('MET past empty, forecast from now slot', [gm.temp[11], gm.temp[12] !== null], [null, true]);
check('MET fog: fog_area_fraction>=50 / fog symbol', [gm.fog[15], gm.fog[17], gm.fog[16]], [1, 1, 0]);
check('MET symbols → WMO', [gm.wmo_code[12], gm.wmo_code[17], gm.wmo_code[18], gm.wmo_code[19]], [2, 45, 96, 85]);
check('MET layers + feels (Steadman)', [gm.has_layers, Math.round(gm.feels[12] * 10) / 10], [true, 7.8]);
check('MET 6-hourly tail ignored', gm.valid_hours, 72);
check('MET pop derived from amount (2mm → 100)', gm.pop[18], 100);
check('MET sunrise via NOAA calc', [new Date(gm.sunrise * 1000).toISOString().slice(11, 16), new Date(gm.sunset * 1000).toISOString().slice(11, 16)], ['05:49', '16:48']);
check('MET URL ≤4 decimals', W.PROVIDERS.metno.buildUrl(46.123456, 7.987654), 'https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=46.1235&lon=7.9877');

/* ---- Tomorrow.io ---- */
var tm = { timelines: { hourly: hours(Date.UTC(2026, 9, 15, 16) / 1000, 120).map(function (t, i) {
	return { time: iso(t), values: { temperature: 12, temperatureApparent: 11, precipitationProbability: 5, rainIntensity: 0.2, cloudCover: 40,
		visibility: i === 2 ? 0.5 : 16, weatherCode: i === 1 ? 2000 : i === 2 ? 1001 : i === 3 ? 2100 : i === 4 ? 8000 : 1101 } };
}), daily: [{ values: { sunriseTime: '2026-10-15T05:49:00Z', sunsetTime: '2026-10-15T16:46:00Z', temperatureMax: 18, temperatureMin: 9 } }] } };
var gt = W.PROVIDERS.tomorrow.parse(tm, CTX);
check('Tomorrow fog: 2000/2100 only (vis 0.5 km not mapped)', [gt.fog[13], gt.fog[14], gt.fog[15]], [1, 0, 1]);
check('Tomorrow codes → WMO', [gt.wmo_code[12], gt.wmo_code[13], gt.wmo_code[16]], [2, 45, 95]);
check('Tomorrow no layers → flag 1', [gt.has_layers, P.toPayload(gt, 'celsius', true, NOW).wx_flags], [false, 1]);
check('Tomorrow 120h covers grid tail', gt.valid_hours, 72);

/* ---- OpenWeatherMap One Call 3.0 ---- */
var ow = { current: { temp: 15, weather: [{ id: 802 }], visibility: 10000, sunrise: Date.UTC(2026, 9, 15, 5, 49) / 1000, sunset: Date.UTC(2026, 9, 15, 16, 46) / 1000 },
	daily: [{ temp: { min: 8, max: 17 } }],
	hourly: hours(Date.UTC(2026, 9, 15, 16) / 1000, 48).map(function (t, i) {
		return { dt: t, temp: 14, feels_like: 13, pop: 0.35, clouds: 75, visibility: i === 2 ? 600 : 10000,
			rain: i === 4 ? { '1h': 1.5 } : undefined, weather: [{ id: i === 1 ? 741 : i === 2 ? 701 : i === 3 ? 701 : i === 4 ? 501 : 803 }] };
	}) };
var go = W.PROVIDERS.owm.parse(ow, CTX);
check('OWM fog: 741, 701+vis<1000 (701 at 10 km = no)', [go.fog[13], go.fog[14], go.fog[15]], [1, 1, 0]);
check('OWM 48h → valid 60, pad no-data', [go.valid_hours, go.temp[60], go.temp[59]], [60, null, 14]);
check('OWM pop 0..1 → %, rain → precip, 501 → 63', [go.pop[12], go.precip[16], go.wmo_code[16]], [35, 1.5, 63]);
check('OWM payload valid_hours', P.toPayload(go, 'celsius', true, NOW).valid_hours, 60);

/* ---- WeatherAPI.com ---- */
var day0 = Date.UTC(2026, 9, 14, 22) / 1000; /* local midnight Oct 15 (CEST) */
var wa = { current: { temp_c: 15, condition: { code: 1003 }, vis_km: 10 }, forecast: { forecastday: [0, 1, 2].map(function (d) {
	return { astro: { sunrise: '07:49 AM', sunset: '06:46 PM' }, day: { maxtemp_c: 17, mintemp_c: 9 },
		hour: hours(day0 + d * 86400, 24).map(function (t) {
			var rel = Math.round((t - H0) / 3600);
			return { time_epoch: t, temp_c: 10, feelslike_c: 9, cloud: 60, chance_of_rain: 20, chance_of_snow: 40, precip_mm: 0,
				vis_km: rel === 22 ? 0.6 : 10, condition: { code: rel === 20 ? 1135 : rel === 21 ? 1147 : rel === 23 ? 1030 : 1006 } };
		}) };
}) } };
var gw = W.PROVIDERS.weatherapi.parse(wa, CTX);
check('WeatherAPI fog: 1135/1147/vis<1km', [gw.fog[20], gw.fog[21], gw.fog[22], gw.fog[23]], [1, 1, 1, 0]);
check('WeatherAPI pop = max(rain,snow)', gw.pop[30], 40);
check('WeatherAPI ends day 3 (Oct 17 23:00 local) → valid 66', gw.valid_hours, 66);
check('WeatherAPI astro clock', (function (q) { return [q.sunrise_hour, q.sunrise_minute, q.sunset_hour, q.sunset_minute]; })(P.toPayload(gw, 'celsius', true, NOW)), [7, 49, 18, 46]);

/* ---- Orchestration: past fill + fallback + masking ---- */
function deps(responses, logs) {
	var mem = {};
	return { store: { get: function (k) { return mem[k] || null; }, set: function (k, v) { mem[k] = v; } },
		log: function (c, d) { logs.push(c + ' ' + d); },
		get: function (url, o, cb) {
			for (var i = 0; i < responses.length; i++) if (responses[i][0].test(url)) { var r = responses[i]; return cb(r[1], r[2], { status: r[3] || 200, header: function () { return null; } }); }
			cb('HTTP 404 for ' + url.split('/')[2]);
		} };
}
var omPast = JSON.parse(JSON.stringify(om)); omPast.hourly.time = hours(H0, 13);
Object.keys(omPast.hourly).forEach(function (k) { if (k !== 'time') omPast.hourly[k] = omPast.hourly[k].slice(0, 13); });
var logs = [], out;
W.fetchWeather({ provider: 'metno', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/met\.no/, null, JSON.stringify({ properties: { timeseries: ts } })], [/open-meteo.*forecast_hours=1/, null, JSON.stringify(omPast)]], logs),
	function (e, pl, gg) { out = [e, gg.temp[0], gg.temp[11], pl.provider]; });
check('MET past 12h filled from Open-Meteo past_hours', out, [null, 0, 11, 'metno']);
logs = [];
W.fetchWeather({ provider: 'owm', apiKey: 'SECRETKEY123', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/openweathermap/, 'HTTP 401 for api.openweathermap.org'], [/open-meteo/, null, JSON.stringify(om)]], logs),
	function (e, pl) { out = [e, pl.provider]; });
check('OWM bad key → Open-Meteo fallback', out, [null, 'openmeteo']);
check('Fallback log masked, no key', [/key\/quota rejected/.test(logs.join()), /SECRETKEY/.test(logs.join())], [true, false]);
logs = [];
W.fetchWeather({ provider: 'weatherapi', apiKey: '', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/open-meteo/, null, JSON.stringify(om)]], logs), function (e, pl) { out = pl.provider; });
check('Keyed provider without key → Open-Meteo', out, 'openmeteo');
check('URLs carry key only in query (masked by maskUrl host-only)', W.PROVIDERS.tomorrow.buildUrl(1, 2, { apiKey: 'K' }).indexOf('api.tomorrow.io/'), 8);

/* ---- 2.8.1: Open-Meteo Model dropdown ---- */
var OMP = W.PROVIDERS.openmeteo;
check('Model URLs: X,best_match; best_match → no models param', [
	/models=icon_d2,best_match&/.test(OMP.buildUrl(1, 2, { model: 'icon_d2' })),
	/models=ecmwf_ifs,best_match&/.test(OMP.buildUrl(1, 2, { model: 'ecmwf_ifs' })),
	/models=/.test(OMP.buildUrl(1, 2, { model: 'best_match' })),
	/models=/.test(OMP.buildUrl(1, 2, { model: 'bogus' })),
	/models=/.test(OMP.buildUrl(1, 2, { model: 'icon_d2', pastOnly: true }))], [true, true, false, false, false]);
check('All 9 dropdown models known', Object.keys(OMP.MODELS).length, 9);
var omc = JSON.parse(JSON.stringify(om)); delete omc.hourly.temperature_2m; delete omc.hourly.cloud_cover_low;
omc.hourly.temperature_2m_meteoswiss_icon_ch2 = t72.map(function (_, i) { return 300 + i; });
omc.hourly.temperature_2m_best_match = t72.map(function (_, i) { return i < 70 ? 200 + i : null; });
omc.hourly.temperature_2m_meteoswiss_icon_ch1 = t72.map(function (_, i) { return i < 33 ? 100 + i : null; });
omc.hourly.cloud_cover_low_icon_d2 = t72.map(function () { return null; });
omc.hourly.cloud_cover_low_best_match = t72.map(function () { return 7; });
var gc = OMP.parse(omc, CTX);
check('CH1 → CH2 → best_match per hour (column order independent)', [gc.temp[32], gc.temp[33], gc.temp[71]], [132, 333, 371]);
check('Null field of a model filled from best_match', gc.cloud_low[5], 7);
var omq = { hourly: { time: t72, temperature_2m_icon_d2: t72.map(function () { return 1; }), precipitation_icon_d2: t72.map(function () { return 0.5; }),
	precipitation_probability_icon_d2: t72.map(function () { return null; }), precipitation_probability_best_match: t72.map(function () { return 40; }) } };
var gq = OMP.parse(omq, CTX);
check('precipitation not confused with precipitation_probability_*', [gq.precip[3], gq.pop[3]], [0.5, 40]);
check('Attribution names model source', [OMP.attributionFor({ model: 'icon_d2' }), OMP.attributionFor({ model: 'best_match' })],
	['Weather data by Open-Meteo.com (CC BY 4.0), model DWD', 'Weather data by Open-Meteo.com (CC BY 4.0)']);
logs = [];
W.fetchWeather({ provider: 'openmeteo', model: 'meteofrance_arome_france_hd', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/models=meteofrance_arome_france_hd,best_match/, null, JSON.stringify(om)]], logs), function (e, pl) { out = [e, pl.attribution]; });
check('fetchWeather passes model + attribution', out, [null, 'Weather data by Open-Meteo.com (CC BY 4.0), model Météo-France']);

/* ---- 2.8.1: GeoSphere Austria (blend with Open-Meteo) ---- */
var gts = hours(Date.UTC(2026, 9, 15, 16) / 1000, 57);
var geo = { timestamps: gts.map(function (t) { return new Date(t * 1000).toISOString().slice(0, 16) + '+00:00'; }),
	features: [{ properties: { parameters: {
		t2m: { data: gts.map(function (_, i) { return 15 + i * 0.1; }) },
		tcc: { data: gts.map(function (_, i) { return i === 0 ? 1 : 0.5; }) },
		rr_acc: { data: gts.map(function (_, i) { return i < 3 ? 0 : 1.2 * (i - 2); }) },
		grad: { data: gts.map(function (_, i) { return 3600 * 100 * i; }) } } } }] };
var GEO = W.PROVIDERS.geosphere;
check('GeoSphere URL', GEO.buildUrl(46.3, 7), 'https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m?lat_lon=46.3000,7.0000&parameters=t2m,tcc,rr_acc,grad&output_format=geojson');
logs = [];
W.fetchWeather({ provider: 'geosphere', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/geosphere\.at/, null, JSON.stringify(geo)], [/open-meteo/, null, JSON.stringify(om)]], logs),
	function (e, pl, gg) { out = [e, pl.provider, gg.has_layers, pl.wx_flags, gg.valid_hours, gg.temp[0], gg.temp[12], gg.cloud[12], gg.cloud[13],
		gg.precip[15], gg.shortwave[13], gg.pop[20], gg.wmo_code[20], gg.fog[20], gg.fog[30], /by GeoSphere Austria/.test(pl.attribution)]; });
check('GeoSphere native temp/cloud/precip, OM past + pop/code/fog, 1-layer',
	out, [null, 'geosphere', false, 1, 69, 0, 15, 100, 50, 1.2, 100, 22, 45, 1, 1, true]);
logs = [];
W.fetchWeather({ provider: 'geosphere', lat: 40.4, lon: -3.7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/open-meteo/, null, JSON.stringify(om)]], logs), function (e, pl) { out = [e, pl.provider, /outside/.test(logs.join())]; });
check('GeoSphere outside domain → Open-Meteo (logged)', out, [null, 'openmeteo', true]);

/* ---- 2.8.1: Meteoblue (basic-1h + clouds-1h) ---- */
var mbt = hours(Date.UTC(2026, 9, 15, 16) / 1000, 168);
function mbFix(withClouds) {
	var d = { time: mbt, temperature: mbt.map(function () { return 11; }), felttemperature: mbt.map(function () { return 9; }),
		precipitation: mbt.map(function (_, i) { return i === 4 ? 2.5 : 0; }), precipitation_probability: mbt.map(function () { return 15; }),
		pictocode: mbt.map(function (_, i) { return [7, 16, 22, 27, 25, 34, 19][i] || 1; }) };
	if (withClouds) {
		d.totalcloudcover = mbt.map(function () { return 60; }); d.lowclouds = mbt.map(function () { return 10; });
		d.midclouds = mbt.map(function () { return 20; }); d.highclouds = mbt.map(function () { return 30; });
		d.visibility = mbt.map(function (_, i) { return i === 6 ? 700 : 20000; });
		d.fog_probability = mbt.map(function (_, i) { return i === 2 ? 80 : 0; });
	}
	return { metadata: {}, data_1h: d };
}
var MB = W.PROVIDERS.meteoblue;
var gb = MB.parse(mbFix(true), CTX);
check('Meteoblue pictocodes → WMO', [gb.wmo_code[12], gb.wmo_code[13], gb.wmo_code[14], gb.wmo_code[15], gb.wmo_code[16], gb.wmo_code[17]], [2, 45, 3, 95, 65, 71]);
check('Meteoblue fog: picto 16-18 / fog_probability ≥50 / vis <1 km', [gb.fog[13], gb.fog[14], gb.fog[18], gb.fog[12]], [1, 1, 1, 0]);
check('Meteoblue clouds-1h → 3 layers, 7 d covers grid', [gb.has_layers, gb.cloud_mid[20], gb.valid_hours, gb.precip[16], gb.feels[12]], [true, 20, 72, 2.5, 9]);
var gb2 = MB.parse(mbFix(false), CTX);
check('Meteoblue basic-1h only → 1 layer flag', [gb2.has_layers, P.toPayload(gb2, 'celsius', true, NOW).wx_flags], [false, 1]);
check('Meteoblue URL packages + key in query only', /packages\/basic-1h_clouds-1h\?lat=46\.3000&lon=7\.0000&.*apikey=K$/.test(MB.buildUrl(46.3, 7, { apiKey: 'K' })), true);
logs = [];
W.fetchWeather({ provider: 'meteoblue', apiKey: 'MBSECRET99', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/meteoblue/, 'HTTP 403 for my.meteoblue.com'], [/open-meteo/, null, JSON.stringify(om)]], logs),
	function (e, pl) { out = [e, pl.provider, /MBSECRET/.test(logs.join())]; });
check('Meteoblue bad key → Open-Meteo, key not logged', out, [null, 'openmeteo', false]);
logs = [];
W.fetchWeather({ provider: 'meteoblue', apiKey: 'K', lat: 46.3, lon: 7, now: NOW, unit: 'celsius', fogOn: true },
	deps([[/meteoblue/, null, JSON.stringify(mbFix(true))], [/open-meteo.*forecast_hours=1/, null, JSON.stringify(omPast)]], logs),
	function (e, pl, gg) { out = [e, pl.provider, gg.temp[0], gg.temp[12]]; });
check('Meteoblue past hours filled from Open-Meteo', out, [null, 'meteoblue', 0, 11]);

/* ---- 2.8.2: one provider table (caps.js) drives Clay and JS ---- */
var customSrc = require('fs').readFileSync(__dirname + '/../src/pkjs/config/custom.js', 'utf8');
check('custom.js has no hard-coded provider table, reads userData.wxCaps', [/horizon: 57/.test(customSrc), /wxCaps/.test(customSrc)], [false, true]);
check('Every CAPS entry has label/horizon/minInterval/src', Object.keys(W.CAPS).filter(function (k) { var c = W.CAPS[k]; return !(c.label && c.horizon && c.minInterval && c.src); }), []);
check('Every provider has CAPS', Object.keys(W.PROVIDERS).filter(function (k) { return !W.CAPS[k]; }), []);

/* ---- 2.8.5 ---- */
var P5 = require('../src/pkjs/weather/provider.js');
var tI = Date.UTC(2026, 9, 15, 10, 50) / 1000; /* 16:20 IST (+5:30) */
check('gridStart aligns to the LOCAL hour top (+5:30 → 16:00 IST - 12 h)', P5.gridStart(tI, -330), Date.UTC(2026, 9, 15, 10, 30) / 1000 - 12 * 3600);
check('gridStart unchanged for whole-hour zones', P5.gridStart(tI, -120), Date.UTC(2026, 9, 15, 10) / 1000 - 12 * 3600);
var stA = P5.sunTimes(47.4, 8.5, Date.UTC(2026, 5, 20, 22, 30) / 1000); /* 00:30 Zurich, Jun 21 */
check('sunTimes just after local midnight uses the new local day', new Date(stA.sunrise * 1000).toISOString().slice(0, 10), '2026-06-21');
var gF = P5.emptyGrid('openmeteo', NOW5()); for (var q = 0; q < 72; q++) { gF.temp[q] = 5; gF.wmo_code[q] = q === 20 ? 48 : (q === 21 ? 45 : 2); gF.fog[q] = (q === 20 || q === 21) ? 1 : 0; }
gF.current_code = 45;
var pOff = P5.toPayload(gF, 'celsius', false, NOW5()), pOn = P5.toPayload(gF, 'celsius', true, NOW5());
check('Fog indicator off keeps provider fog codes 45/48 (no fog bits)', [pOff.hourly_weather_code[20], pOff.hourly_weather_code[21], pOff.weather_code, pOff.fog_bits[2]], [48, 45, 45, 0]);
check('Fog indicator on keeps fog codes', [pOn.hourly_weather_code[20], pOn.hourly_weather_code[21], pOn.weather_code], [48, 45, 45]);
var gR = P5.emptyGrid('openmeteo', NOW5()); for (var r = 0; r < 72; r++) gR.temp[r] = r; gR.tmin = -50; gR.tmax = 99;
P5.finalize(gR, NOW5(), 47, 8);
check('High/low = rolling next 24 h from now (slots 12..35)', [gR.tmin, gR.tmax], [12, 35]);

/* ---- 2.8.7: polar fallback + location time zone for sun times ---- */
var NOW7 = Date.UTC(2026, 5, 21, 10) / 1000; /* Jun 21 */
check('sunTimes polar day at 78N in June', P5.sunTimes(78.2, 15.6, NOW7).polar, 'day');
check('sunTimes polar night at 78N in December', P5.sunTimes(78.2, 15.6, Date.UTC(2026, 11, 21, 10) / 1000).polar, 'night');
var gPd = P5.emptyGrid('metno', NOW7); for (var q7 = 0; q7 < 72; q7++) gPd.temp[q7] = 5;
P5.finalize(gPd, NOW7, 78.2, 15.6); var pPd = P5.toPayload(gPd, 'celsius', true, NOW7);
check('Polar day -> 00:00-23:59 daylight', [pPd.sunrise_hour, pPd.sunrise_minute, pPd.sunset_hour, pPd.sunset_minute], [0, 0, 23, 59]);
var NOW7w = Date.UTC(2026, 11, 21, 10) / 1000;
var gPn = P5.emptyGrid('metno', NOW7w); for (var q8 = 0; q8 < 72; q8++) gPn.temp[q8] = -10;
P5.finalize(gPn, NOW7w, 78.2, 15.6); var pPn = P5.toPayload(gPn, 'celsius', true, NOW7w);
check('Polar night -> sunrise == sunset (no daylight)', [pPn.sunrise_hour, pPn.sunrise_minute, pPn.sunset_hour, pPn.sunset_minute], [12, 0, 12, 0]);
var gTk = P5.emptyGrid('metno', NOW7); for (var q9 = 0; q9 < 72; q9++) gTk.temp[q9] = 20; gTk.utcOffsetS = 9 * 3600;
P5.finalize(gTk, NOW7, 35.68, 139.69); var pTk = P5.toPayload(gTk, 'celsius', true, NOW7);
check('Tokyo location (+9) sun times on the Tokyo clock (~04:25 / ~19:00)', [pTk.sunrise_hour, pTk.sunset_hour], [4, 19]);
check('Location offset picks the location day (Tokyo Jun 22 after its midnight)', new Date(P5.sunTimes(35.68, 139.69, Date.UTC(2026, 5, 21, 15, 30) / 1000, -540).sunrise * 1000 + 9 * 3600000).toISOString().slice(0, 10), '2026-06-22');
var gZ = P5.emptyGrid('metno', NOW7); for (var qa = 0; qa < 72; qa++) gZ.temp[qa] = 20;
P5.finalize(gZ, NOW7, 47.37, 8.54); var pZ = P5.toPayload(gZ, 'celsius', true, NOW7);
check('Zurich without offset unchanged (phone clock, ~05:3x / ~21:2x)', [pZ.sunrise_hour, pZ.sunset_hour], [5, 21]);
var owmTz = W.PROVIDERS.owm.parse({ list: [{ dt: NOW7, main: { temp: 1, feels_like: 1 }, pop: 0, clouds: { all: 0 }, visibility: 10000, weather: [{ id: 800 }] }], city: { timezone: 32400, sunrise: NOW7 - 30000, sunset: NOW7 + 20000 } }, { now: NOW7, lat: 35, lon: 139 });
check('OWM 2.5 city.timezone -> utcOffsetS', owmTz.utcOffsetS, 32400);

/* ---- 2.9.3: wind gusts per provider (km/h) + wind bitmask ---- */
var omG = JSON.parse(JSON.stringify(om));
omG.hourly.wind_gusts_10m = t72.map(function (_, i) { return i === 24 ? 55 : i === 25 ? 49.9 : 20; });
var gG = W.PROVIDERS.openmeteo.parse(omG, CTX);
check('Gust OM wind_gusts_10m (km/h)', [gG.gust[24], gG.gust[25], gG.gust[30]], [55, 49.9, 20]);
check('Gust OM URL asks wind_gusts_10m in km/h', [/wind_gusts_10m/.test(W.PROVIDERS.openmeteo.buildUrl(46.3, 7, {})), /wind_speed_unit=kmh/.test(W.PROVIDERS.openmeteo.buildUrl(46.3, 7, {}))], [true, true]);
check('Wind bits >= 50 km/h (49.9 not)', P.toPayload(gG, 'celsius', true, NOW, 50).wind_bits, [0, 0, 0, 1, 0, 0, 0, 0, 0]);
check('Wind bits off (0) / threshold 40', [P.toPayload(gG, 'celsius', true, NOW, 0).wind_bits.join(''), P.toPayload(gG, 'celsius', true, NOW, 40).wind_bits[3]], ['000000000', 3]);
check('Wind bits: no gust data never triggers', P.windBits(P.emptyGrid('meteoblue', NOW), 40), [0, 0, 0, 0, 0, 0, 0, 0, 0]);
var tsG = [{ time: iso(H0 + 13 * 3600), data: { instant: { details: { air_temperature: 5, wind_speed_of_gust: 15 } }, next_1_hours: { summary: { symbol_code: 'lightsnow' }, details: { precipitation_amount: 0.3 } } } },
	{ time: iso(H0 + 14 * 3600), data: { instant: { details: { air_temperature: 5, wind_speed_of_gust: 5 } }, next_1_hours: { summary: { symbol_code: 'snow' }, details: { precipitation_amount: 1 } } } }];
var gmG = W.PROVIDERS.metno.parse({ properties: { timeseries: tsG } }, CTX);
check('Gust MET wind_speed_of_gust m/s → km/h', [gmG.gust[13], gmG.gust[14]], [54, 18]);
var gtG = W.PROVIDERS.tomorrow.parse({ timelines: { hourly: [{ time: iso(H0 + 13 * 3600), values: { temperature: 5, windGust: 20, weatherCode: 5100 } }, { time: iso(H0 + 14 * 3600), values: { temperature: 5, windGust: 1, weatherCode: 5000 } }] } }, CTX);
check('Gust Tomorrow windGust m/s → km/h', gtG.gust[13], 72);
var owG = W.PROVIDERS.owm.parse({ list: [{ dt: H0 + 12 * 3600, main: { temp: 5, feels_like: 3 }, pop: 0, clouds: { all: 50 }, visibility: 10000, wind: { gust: 14 }, weather: [{ id: 600 }] },
	{ dt: H0 + 15 * 3600, main: { temp: 5, feels_like: 3 }, pop: 0, clouds: { all: 50 }, visibility: 10000, wind: { gust: 20 }, weather: [{ id: 601 }] }] }, CTX);
check('Gust OWM 2.5 wind.gust m/s → km/h, interpolated', [owG.gust[12], owG.gust[15], Math.round(owG.gust[13] * 10) / 10], [50.4, 72, 57.6]);
var ocG = W.PROVIDERS.owm.parse({ hourly: [{ dt: H0 + 12 * 3600, temp: 5, feels_like: 3, clouds: 50, wind_gust: 12, weather: [{ id: 800 }] }] }, CTX);
check('Gust OWM One Call wind_gust m/s → km/h', ocG.gust[12], 43.2);
var waG = W.PROVIDERS.weatherapi.parse({ forecast: { forecastday: [{ hour: [{ time_epoch: H0 + 13 * 3600, temp_c: 5, feelslike_c: 3, cloud: 50, gust_kph: 61, condition: { code: 1210 } }, { time_epoch: H0 + 14 * 3600, temp_c: 5, feelslike_c: 3, cloud: 50, gust_kph: 10, condition: { code: 1219 } }] }] } }, CTX);
check('Gust WeatherAPI gust_kph', [waG.gust[13], waG.gust[14]], [61, 10]);
var geoG = P.emptyGrid('geosphere', NOW); for (var gi = 12; gi < 40; gi++) geoG.temp[gi] = 5;
W.blend(geoG, gG);
check('Gust GeoSphere filled from Open-Meteo blend', [geoG.gust[24], geoG.gust[11]], [55, 20]);
check('Gust caps: Meteoblue none, others yes', Object.keys(W.CAPS).map(function (k) { return k + ':' + W.CAPS[k].gust; }).sort(),
	['geosphere:true', 'meteoblue:false', 'metno:true', 'openmeteo:true', 'owm:true', 'tomorrow:true', 'weatherapi:true']);
check('Meteoblue fixture has no gusts', MB.parse(mbFix(true), CTX).gust, null);
check('wxsend sends WEATHER_WIND (9 bytes)', require('../src/pkjs/wxsend.js').buildWeatherDict({ wind_bits: [1, 2], fetch_time: NOW }).WEATHER_WIND, [1, 2, 0, 0, 0, 0, 0, 0, 0]);

/* ---- 2.9.3: light/moderate snow codes + watch snow event codes ---- */
check('Light/moderate snow → WMO 71/73 (MET, Tomorrow, OWM, WeatherAPI)', [gmG.wmo_code[13], gmG.wmo_code[14], gtG.wmo_code[13], gtG.wmo_code[14], owG.wmo_code[12], owG.wmo_code[15], waG.wmo_code[13], waG.wmo_code[14]], [71, 73, 71, 73, 71, 73, 71, 73]);
var evSrc = require('fs').readFileSync(__dirname + '/../src/c/ui/event_layer.c', 'utf8');
var snowLine = (/return (code == 71[^;]*);/.exec(evSrc) || [])[1] || '';
check('Watch snow event codes (level Any) = 71, 73, 75, 77, 85, 86', (snowLine.match(/\d+/g) || []).map(Number).sort(function (a, b) { return a - b; }), [71, 73, 75, 77, 85, 86]);
check('Watch event priority tornado > thunder > snow > wind > fog', /return icon;\s*if \(wind && wind\[i\]\)[\s\S]*?if \(fog && fog\[i\]/.test(evSrc), true);

/* ---- 2.9.4: strong storms (CAPE / MET probability) + fog visibility levels ---- */
check('OM URL asks cape', /cape/.test(W.PROVIDERS.openmeteo.buildUrl(46.3, 7, {})), true);
var omC = JSON.parse(JSON.stringify(om)); omC.hourly.cape = t72.map(function (_, i) { return i === 20 ? 1800 : 100; });
var gC = W.PROVIDERS.openmeteo.parse(omC, CTX);
check('OM cape parsed', [gC.cape[20], gC.cape[21]], [1800, 100]);
var tsT = [{ time: iso(H0 + 13 * 3600), data: { instant: { details: { air_temperature: 15 } }, next_1_hours: { summary: { symbol_code: 'rainandthunder' }, details: { precipitation_amount: 2, probability_of_thunder: 70 } } } }];
var gmT = W.PROVIDERS.metno.parse({ properties: { timeseries: tsT } }, CTX);
check('MET probability_of_thunder parsed; strong bit set at 70 %', [gmT.thunder_prob[13], gmT.wmo_code[13], (P.stormBits(gmT)[1] >> 5) & 1], [70, 95, 1]);
var geoC = P.emptyGrid('geosphere', NOW); for (var gj = 12; gj < 40; gj++) geoC.temp[gj] = 5;
W.blend(geoC, gC);
check('GeoSphere cape from Open-Meteo blend', geoC.cape[20], 1800);
check('Payload carries storm_bits', P.toPayload(gC, 'celsius', true, NOW).storm_bits.length, 9);
var gF = P.emptyGrid('openmeteo', NOW); gF.visibility_m = P.nulls();
for (var fi = 0; fi < 72; fi++) { gF.temp[fi] = 5; gF.wmo_code[fi] = 3; }
gF.fog[10] = 1; gF.visibility_m[10] = 800;  /* fog under 1000 m only */
gF.fog[11] = 1; gF.visibility_m[11] = 400;  /* under 500 */
gF.fog[12] = 1; gF.visibility_m[12] = 150;  /* under 200 */
gF.fog[13] = 1; gF.wmo_code[13] = 45; gF.visibility_m[13] = 2000; /* fog code: every level */
var fb = function (v) { var b = P.toPayload(gF, 'celsius', v > 0, NOW, 0, v).fog_bits; return [10, 11, 12, 13].map(function (i) { return (b[i >> 3] >> (i & 7)) & 1; }).join(''); };
check('Fog levels: 1000 / 500 / 200 / off narrow the provider fog; codes 45/48 always', [fb(1000), fb(500), fb(200), fb(0)], ['1111', '0111', '0011', '0000']);
check('Fog default (no level arg) = 1000 m behaviour', P.toPayload(gF, 'celsius', true, NOW, 0).fog_bits.join(','), P.toPayload(gF, 'celsius', true, NOW, 0, 1000).fog_bits.join(','));
check('Caps strongStorm/fogVis', Object.keys(W.CAPS).map(function (k) { return k + ':' + (W.CAPS[k].strongStorm ? 1 : 0) + (W.CAPS[k].fogVis ? 1 : 0); }).sort(),
	['geosphere:11', 'meteoblue:01', 'metno:10', 'openmeteo:11', 'owm:11', 'tomorrow:01', 'weatherapi:11']);

function NOW5() { return Date.UTC(2026, 9, 15, 14) / 1000; }

console.log((n - fails) + '/' + n + ' passed');
process.exit(fails ? 1 : 0);
