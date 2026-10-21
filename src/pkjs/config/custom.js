/**
 * Clay custom function.
 */

module.exports = function () {
	var clayConfig = this;

	function normalizeBool(value) {
		if (value === 'false' || value === '0' || value === 0) return false;
		if (value === 'true' || value === '1' || value === 1) return true;
		return !!value;
	}

	function isBlank(value) {
		return value === null || value === undefined || String(value).trim() === '';
	}

	/**
	 * Controls visibility of static coordinate inputs and pre-fills them from
	 * userData when static mode is enabled and fields are blank.
	 */
	function applyStaticLocationVisibility() {
		var useStaticItem = clayConfig.getItemByMessageKey('SETTING_USE_STATIC_LOCATION');
		var latItem = clayConfig.getItemByMessageKey('SETTING_STATIC_LAT');
		var lonItem = clayConfig.getItemByMessageKey('SETTING_STATIC_LON');
		if (!useStaticItem || !latItem || !lonItem) return;

		var useStatic = normalizeBool(useStaticItem.get());
		if (!useStatic) {
			latItem.hide();
			lonItem.hide();
			return;
		}

		latItem.show();
		lonItem.show();

		var userData = clayConfig.meta.userData || {};
		if (isBlank(latItem.get()) && userData.lastKnownLat !== undefined && userData.lastKnownLat !== null) {
			latItem.set(String(userData.lastKnownLat));
		}
		if (isBlank(lonItem.get()) && userData.lastKnownLon !== undefined && userData.lastKnownLon !== null) {
			lonItem.set(String(userData.lastKnownLon));
		}
	}

	/**
	 * Controls visibility of location name override.
	 */
	function applyGeocodeVisibility() {
		var geocodeEnabledItem = clayConfig.getItemByMessageKey('SETTING_GEOCODE_ENABLED');
		var locationOverrideItem = clayConfig.getItemByMessageKey('SETTING_LOCATION_OVERRIDE');
		if (!geocodeEnabledItem || !locationOverrideItem) return;

		var geocodeEnabled = normalizeBool(geocodeEnabledItem.get());
		if (geocodeEnabled) {
			locationOverrideItem.hide();
			return;
		}

		locationOverrideItem.show();
	}

	/**
	 * Controls visibility of developer-oriented advanced options.
	 */
	function applyAdvancedVisibility() {
		var showAdvancedItem = clayConfig.getItemByMessageKey('SETTING_SHOW_ADVANCED_OPTIONS');
		var clearCacheItem = clayConfig.getItemByMessageKey('SETTING_CLEAR_CACHE');
		if (!showAdvancedItem || !clearCacheItem) return;

		if (normalizeBool(showAdvancedItem.get())) {
			clearCacheItem.show();
			return;
		}

		clearCacheItem.hide();
	}

	/* Provider capabilities come from weather/caps.js through userData
	 * (single source of truth; Clay's custom function can't require modules).
	 * The fallback only keeps the page usable if userData is missing. */
	var ud = (clayConfig.meta && clayConfig.meta.userData) || {};
	var CAPS = ud.wxCaps || { openmeteo: { label: 'Open-Meteo', layers: true, horizon: 60, key: false, minInterval: 15, src: 'Open-Meteo.com (CC BY 4.0)' } };
	var MODEL_SRC = { meteoswiss_icon_ch1: 'MeteoSwiss', meteoswiss_icon_ch2: 'MeteoSwiss', icon_d2: 'DWD',
		geosphere_arome_austria: 'GeoSphere Austria', knmi_harmonie_arome_europe: 'KNMI',
		dmi_harmonie_arome_europe: 'DMI', meteofrance_arome_france_hd: 'Météo-France', ecmwf_ifs: 'ECMWF' };

	function esc(t) { return String(t).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
	function pad2(n) { return (n < 10 ? '0' : '') + n; }

	/* Hide/disable <option>s of a Clay select; keeps the value valid. */
	function limitOptions(item, allow) {
		if (!item || !item.$manipulatorTarget) return;
		var sel = item.$manipulatorTarget[0];
		if (!sel || !sel.options) return;
		var best = null, first = null;
		for (var i = 0; i < sel.options.length; i++) {
			var o = sel.options[i], ok = allow(o.value);
			o.disabled = !ok; o.hidden = !ok; o.style.display = ok ? '' : 'none';
			if (ok) { best = o.value; if (first === null) first = o.value; }
		}
		if (!allow(String(item.get())) && best !== null) item.set(best);
	}
	/* For minimum-type limits (refresh): bump to the smallest allowed. */
	function limitMin(item, min) {
		if (!item || !item.$manipulatorTarget) return;
		var sel = item.$manipulatorTarget[0];
		if (!sel || !sel.options) return;
		var lowest = null;
		for (var i = 0; i < sel.options.length; i++) {
			var o = sel.options[i], ok = Number(o.value) >= min;
			o.disabled = !ok; o.hidden = !ok; o.style.display = ok ? '' : 'none';
			if (ok && (lowest === null || Number(o.value) < Number(lowest))) lowest = o.value;
		}
		if (Number(item.get()) < min && lowest !== null) item.set(lowest);
	}

	/** Adapt every weather-dependent setting to the provider's real limits. */
	function applyProviderVisibility() {
		var prov = clayConfig.getItemByMessageKey('SETTING_WEATHER_PROVIDER');
		if (!prov) return;
		var v = String(prov.get());
		var c = CAPS[v] || CAPS.openmeteo;
		var key = clayConfig.getItemByMessageKey('SETTING_WEATHER_API_KEY');
		var model = clayConfig.getItemByMessageKey('SETTING_OM_MODEL');
		var onecall = clayConfig.getItemByMessageKey('SETTING_OWM_ONECALL');
		var hint = clayConfig.getItemById('wx_keyhint');
		var notes = clayConfig.getItemById('wx_notes');
		var attr = clayConfig.getItemById('wx_attribution');
		if (key) { if (c.key) key.show(); else key.hide(); }
		if (hint) {
			if (c.key && c.url) { hint.set('<small>Get a key: <a href="' + c.url + '">' + c.url.replace('https://', '') + '</a>. ' + esc(c.hint || '') + '</small>'); hint.show(); }
			else hint.hide();
		}
		if (model) { if (v === 'openmeteo') model.show(); else model.hide(); }
		var useOnecall = false;
		if (onecall) {
			if (v === 'owm') { onecall.show(); useOnecall = normalizeBool(onecall.get()); } else onecall.hide();
		}
		var horizon = (useOnecall && c.onecallHorizon) ? c.onecallHorizon : c.horizon;

		var n = [];
		if (!c.layers) n.push('No cloud layers: clouds stay 1 row.');
		if (c.gust === false) n.push('No wind gust data: the wind event is off.');
		if (c.strongStorm === false) n.push('Thunder: no storm strength data, so only Any storm or Off.');
		if (c.fogVis === false) n.push('Fog: no visibility data, so stricter fog levels are hidden.');
		if (horizon < 48) n.push('About ' + horizon + ' h ahead: longer ranges are hidden.');
		if ((c.minInterval || 15) > 15) n.push('Refreshes at most every ' + c.minInterval + ' min.');
		if (c.notes && !(useOnecall && v === 'owm')) n.push(esc(c.notes));
		if (notes) { if (n.length) { notes.set('<small>' + n.join('<br>') + '</small>'); notes.show(); } else notes.hide(); }

		var src = c.src;
		if (v === 'openmeteo' && model && MODEL_SRC[String(model.get())]) src += ', model ' + MODEL_SRC[String(model.get())];
		var lf = ud.lastFetch, last = '';
		if (lf && lf.t) {
			var d = new Date(lf.t);
			var used = (CAPS[lf.provider] && CAPS[lf.provider].label) || lf.provider;
			last = '<br>Last update ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ' (' + d.toLocaleDateString() + ') from ' + esc(used) +
				(lf.requested && lf.requested !== lf.provider ? ' (fallback)' : '') + '.';
		}
		var le = ud.lastWxError;
		if (le && le.t && le.msg) {
			var de = new Date(le.t);
			last += '<br>Last problem ' + pad2(de.getHours()) + ':' + pad2(de.getMinutes()) + ': ' + esc(le.msg) + '.';
		}
		if (attr) attr.set('<small>Weather data: ' + esc(src) + '.' + last + '</small>');

		limitOptions(clayConfig.getItemByMessageKey('SETTING_FORECAST_HOURS'), function (h) { return Number(h) <= horizon; });
		limitOptions(clayConfig.getItemByMessageKey('SETTING_CLOUD_DISPLAY_MODE'), function (m) { return c.layers || Number(m) === 0; });
		limitOptions(clayConfig.getItemByMessageKey('SETTING_FLICK_ACTION'), function (a) { return c.layers || Number(a) === 0; });
		limitMin(clayConfig.getItemByMessageKey('SETTING_FETCH_INTERVAL'), c.minInterval || 15);
		/* 2.9.3: wind event needs hourly gusts from the provider. */
		var gustItem = clayConfig.getItemByMessageKey('SETTING_WIND_GUST');
		if (gustItem) { if (c.gust === false) gustItem.hide(); else gustItem.show(); }
		/* 2.9.6: Amount bar height needs hourly mm; Probability stays. */
		var rhItem = clayConfig.getItemByMessageKey('SETTING_RAIN_HEIGHT');
		if (rhItem) {
			if (c.mm === false) { if (Number(rhItem.get()) !== 0) rhItem.set(0); rhItem.hide(); } else rhItem.show();
		}
		/* 2.9.3: tornado only from providers that report funnel clouds / WMO 99. */
		var tornItem = clayConfig.getItemByMessageKey('SETTING_EVENT_TORNADO');
		if (tornItem) { if (c.tornado === false) tornItem.hide(); else tornItem.show(); }
		/* 2.9.4: levels the provider can't grade are hidden (Off and the default stay). */
		/* An unsupported level falls back to the default (0), never to Off. */
		function limitLevel(key, allow) {
			var it = clayConfig.getItemByMessageKey(key);
			if (it && !allow(String(it.get()))) it.set('0');
			limitOptions(it, allow);
		}
		limitLevel('SETTING_THUNDER_LEVEL', function (l) { return c.strongStorm !== false || Number(l) !== 1; });
		limitLevel('SETTING_FOG_LEVEL', function (l) { return c.fogVis !== false || Number(l) === 0 || Number(l) === 3; });
	}

	/** Flick action only when wrist flick is on. */
	function applyFlickVisibility() {
		var flick = clayConfig.getItemByMessageKey('SETTING_WRIST_FLICK');
		var act = clayConfig.getItemByMessageKey('SETTING_FLICK_ACTION');
		if (!flick || !act) return;
		if (normalizeBool(flick.get())) act.show(); else act.hide();
	}

	clayConfig.on(clayConfig.EVENTS.AFTER_BUILD, function () {
		var provItem = clayConfig.getItemByMessageKey('SETTING_WEATHER_PROVIDER');
		if (provItem) { applyProviderVisibility(); provItem.on('change', applyProviderVisibility); }
		var modelItem = clayConfig.getItemByMessageKey('SETTING_OM_MODEL');
		if (modelItem) modelItem.on('change', applyProviderVisibility);
		var ocItem = clayConfig.getItemByMessageKey('SETTING_OWM_ONECALL');
		if (ocItem) ocItem.on('change', applyProviderVisibility);
		var flickItem = clayConfig.getItemByMessageKey('SETTING_WRIST_FLICK');
		if (flickItem) { applyFlickVisibility(); flickItem.on('change', applyFlickVisibility); }

		var geocodeEnabledItem = clayConfig.getItemByMessageKey('SETTING_GEOCODE_ENABLED');
		var useStaticItem = clayConfig.getItemByMessageKey('SETTING_USE_STATIC_LOCATION');
		var showAdvancedItem = clayConfig.getItemByMessageKey('SETTING_SHOW_ADVANCED_OPTIONS');
		if (!geocodeEnabledItem || !useStaticItem || !showAdvancedItem) return;

		applyGeocodeVisibility();
		geocodeEnabledItem.on('change', applyGeocodeVisibility);

		applyStaticLocationVisibility();
		useStaticItem.on('change', applyStaticLocationVisibility);

		applyAdvancedVisibility();
		showAdvancedItem.on('change', applyAdvancedVisibility);
	});
};
