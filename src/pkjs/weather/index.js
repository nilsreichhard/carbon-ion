/**
 * Weather provider registry + fetch orchestration.
 *
 * deps.get(url, {headers, allow304}, cb(err, text, info{status, header(name)}))
 * deps.store: {get(key) → string|null, set(key, string)}
 * deps.log(code, detail)  — details must never contain API keys or URLs with keys.
 *
 * @license GPL-3.0-or-later
 */
var P = require('./provider');
var PROVIDERS = {
	openmeteo: require('./openmeteo'),
	metno: require('./metno'),
	tomorrow: require('./tomorrow'),
	owm: require('./owm'),
	weatherapi: require('./weatherapi'),
	geosphere: require('./geosphere'),
	meteoblue: require('./meteoblue'),
};

/* Provider capabilities: single table shared with the Clay page (caps.js). */
var CAPS = require('./caps');
var MET_CACHE = 'carbon.metno.v1';
var PREV_GRID = 'carbon.wxprev.v1';

function sameCoords(a, b, dp) {
	var f = Math.pow(10, dp);
	return a && b && Math.round(a.lat * f) === Math.round(b.lat * f) &&
		Math.round(a.lon * f) === Math.round(b.lon * f);
}

function readJson(store, key) {
	try { var s = store.get(key); return s ? JSON.parse(s) : null; } catch (e) { return null; }
}
function writeJson(store, key, v) {
	try { store.set(key, JSON.stringify(v)); } catch (e) { }
}

function missingPast(g) {
	for (var i = 0; i < P.PAST; i++) if (g.temp[i] === null) return true;
	return false;
}

function fetchRaw(prov, o, deps, cb) {
	var url = prov.buildUrl(o.lat, o.lon, o);
	if (prov.id !== 'metno') {
		deps.get(url, {}, function (err, text) { cb(err, text); });
		return;
	}
	/* MET Norway: honour Expires, conditional GET with If-Modified-Since. */
	var c = readJson(deps.store, MET_CACHE);
	var here = { lat: o.lat, lon: o.lon };
	var cached = c && sameCoords(c, here, 4) ? c : null;
	if (cached && cached.expires && o.now * 1000 < cached.expires && cached.body) {
		deps.log('wx_met', 'cache (Expires)');
		cb(null, cached.body);
		return;
	}
	var headers = { 'User-Agent': prov.userAgent };
	if (cached && cached.lastModified) headers['If-Modified-Since'] = cached.lastModified;
	deps.get(url, { headers: headers, allow304: true }, function (err, text, info) {
		/* MET Norway's terms need an identifying User-Agent; some phone JS
		 * runtimes (iOS WebView) refuse to set it. Log so 403s are explainable. */
		if (info && info.droppedHeaders && info.droppedHeaders.indexOf('User-Agent') >= 0)
			deps.log('wx_met_ua', 'User-Agent refused by phone runtime' + (err ? ' (' + String(err).slice(0, 40) + ')' : ''));
		if (err) { cb(err); return; }
		var status = info && info.status;
		if (status === 304 && cached && cached.body) {
			text = cached.body;
			deps.log('wx_met', '304 not modified');
		}
		var exp = info && info.header ? Date.parse(info.header('Expires') || '') : NaN;
		writeJson(deps.store, MET_CACHE, {
			lat: o.lat, lon: o.lon, body: text,
			expires: isNaN(exp) ? o.now * 1000 + 30 * 60000 : exp,
			lastModified: (info && info.header && info.header('Last-Modified')) || (cached && cached.lastModified) || null,
		});
		cb(null, text);
	});
}

function finish(g, o, deps, cb) {
	writeJson(deps.store, PREV_GRID, { lat: o.lat, lon: o.lon, grid: g });
	var p = P.toPayload(g, o.unit, o.fogOn, o.now);
	var pv = PROVIDERS[g.provider];
	p.attribution = pv ? (pv.attributionFor ? pv.attributionFor(o) : pv.attribution) : '';
	cb(null, p, g);
}

/* GeoSphere-style blend: keep native fields, take past hours and the fields
 * the provider lacks from an Open-Meteo best_match grid. */
function blend(g, om) {
	P.fillFrom(g, om, P.PAST);
	for (var i = 0; i < P.HOURS; i++) {
		var j = P.slot(om, g.time[i]);
		if (j < 0 || g.temp[i] === null || om.temp[j] === null) continue;
		['pop', 'wmo_code', 'fog'].forEach(function (f) { if (g[f][i] === null) g[f][i] = om[f][j]; });
		if (g.feels[i] === null) g.feels[i] = om.feels[j] === null ? null : om.feels[j] + (g.temp[i] - om.temp[j]);
		if (g.precip[i] === null) g.precip[i] = om.precip[j];
		if (om.visibility_m) { if (!g.visibility_m) g.visibility_m = P.nulls(); g.visibility_m[i] = om.visibility_m[j]; }
	}
	['sunrise', 'sunset', 'sunriseLocal', 'sunsetLocal', 'utcOffsetS', 'current_code'].forEach(function (k) { if (g[k] === null || g[k] === undefined) g[k] = om[k]; });
	return g;
}

function run(prov, o, deps, cb) {
	if (prov.inDomain && !prov.inDomain(o.lat, o.lon)) { cb('outside ' + prov.label + ' domain'); return; }
	fetchRaw(prov, o, deps, function (err, text) {
		if (err) { cb(err); return; }
		var g;
		try { g = prov.parse(JSON.parse(text), o); } catch (e) { cb('parse: ' + e.message); return; }
		if (prov.blendOpenMeteo) {
			var om0 = PROVIDERS.openmeteo;
			deps.get(om0.buildUrl(o.lat, o.lon, {}), {}, function (e3, t3) {
				if (!e3) {
					try { blend(g, om0.parse(JSON.parse(t3), o)); } catch (e) { e3 = e.message; }
				}
				if (e3) deps.log('wx_blend', 'open-meteo gap fill failed');
				P.finalize(g, o.now, o.lat, o.lon);
				finish(g, o, deps, cb);
			});
			return;
		}
		if (!prov.needsPastFill || !missingPast(g)) { finish(g, o, deps, cb); return; }
		var prev = readJson(deps.store, PREV_GRID);
		if (prev && prev.grid && sameCoords(prev, o, 2)) P.fillFrom(g, prev.grid, P.PAST);
		if (!missingPast(g)) { P.finalize(g, o.now, o.lat, o.lon); finish(g, o, deps, cb); return; }
		var om = PROVIDERS.openmeteo;
		deps.get(om.buildUrl(o.lat, o.lon, { pastOnly: true }), {}, function (e2, t2) {
			if (!e2) {
				try { P.fillFrom(g, om.parse(JSON.parse(t2), o), P.PAST); } catch (e) { }
			}
			if (e2) deps.log('wx_past', 'open-meteo past fill failed');
			g.valid_hours = 0;
			P.finalize(g, o.now, o.lat, o.lon);
			finish(g, o, deps, cb);
		});
	});
}

/**
 * @param {Object} o {provider, apiKey, model, lat, lon, now, unit, fogOn}
 * @param {Object} deps
 * @param {Function} cb (err, payload, grid)
 */
function fetchWeather(o, deps, cb) {
	var prov = PROVIDERS[o.provider] || PROVIDERS.openmeteo;
	if (prov.needsKey && !String(o.apiKey || '').trim()) {
		deps.log('wx_fallback', prov.id + ': no API key → open-meteo');
		prov = PROVIDERS.openmeteo;
	}
	run(prov, o, deps, function (err, payload, g) {
		if (!err || prov.id === 'openmeteo') { cb(err, payload, g); return; }
		o = Object.assign ? Object.assign({}, o, { model: 'best_match', meteoswiss: false }) : o;
		var why = P.KEYED_FAIL_RE.test(String(err)) ? 'key/quota rejected' : 'failed';
		deps.log('wx_fallback', prov.id + ' ' + why + ' (' + String(err).replace(/[?&](apikey|appid|key)=[^&\s]*/gi, '').slice(0, 80) + ') → open-meteo');
		run(PROVIDERS.openmeteo, o, deps, cb);
	});
}

/** Effective refresh interval (minutes) for a provider: never below its minimum. */
function effectiveInterval(provider, chosenMin) {
	var c = CAPS[provider] || CAPS.openmeteo;
	var v = Number(chosenMin) || 30;
	return Math.max(v, c.minInterval || 15);
}

module.exports = { PROVIDERS: PROVIDERS, CAPS: CAPS, fetchWeather: fetchWeather, blend: blend, effectiveInterval: effectiveInterval };
