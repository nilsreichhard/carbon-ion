/**
 * Carbon Ion — calendar (ICS fetch, alarm rows, event window and cache)
 * (split out of index.js in 2.8.2; behaviour unchanged)
 *
 * @license GPL-3.0-or-later
 */
var C = require('./constants');
var eventLog = require('./eventlog');
var net = require('./net');
var settings = require('./settings');
var ics = require('./ics');
var MAX_TIMELINE_EVENTS = C.MAX_TIMELINE_EVENTS;
var getStringSetting = settings.getStringSetting, getBoolSetting = settings.getBoolSetting;
var readClaySettings = settings.readClaySettings, getForecastHoursFromSettings = settings.getForecastHoursFromSettings;
var maskUrl = net.maskUrl, retryXhr = net.retryXhr;

/* http:// and webcal:// are fetched over https (phones block plain http);
 * log it once per URL so a failing http-only server is explainable (2.8.5). */
var s_httpsLogged = {};
function normalizeCalendarUrl(url) {
	var out = ics.normalizeUrl(url);
	if (/^http:\/\//i.test(String(url || '').trim()) && !s_httpsLogged[out]) {
		s_httpsLogged[out] = true;
		eventLog.log('cal_https', 'http calendar URL fetched over https');
	}
	return out;
}

/** Timed events nearest/overlapping now first, then all-day; start order. */
function pickNearestEvents(events, cap) { return ics.pickNearest(events, cap); }

function parseIcsEvents(icsText, maxEvents, windowStart, windowEnd) {
	return pickNearestEvents(ics.parseEvents(icsText, windowStart, windowEnd), maxEvents);
}

/**
 * Read the configured calendar ICS URL from Clay settings or cache.
 *
 * @param   {Object} settings
 * @returns {string}
 */
function sanitizeCalendarUrlSetting(settings, key, cacheKey) {
	var raw = getStringSetting(settings, key, '');
	var url = normalizeCalendarUrl(raw);
	if (url && url !== '[object Object]') return url;

	var rawSetting = settings ? settings[key] : null;
	var corrupt = (raw === '[object Object]') ||
		(rawSetting !== null && rawSetting !== undefined &&
			typeof rawSetting === 'object');
	if (corrupt) {
		try {
			var cached = localStorage.getItem(cacheKey);
			if (cached && cached !== '[object Object]') {
				url = normalizeCalendarUrl(cached);
			}
		} catch (e) { }
	}
	return (url && url !== '[object Object]') ? url : '';
}

function getCalendarColorSetting(settings, key, fallback) {
	var n = parseInt(getStringSetting(settings, key, String(fallback)), 10);
	if (isNaN(n) || n < 0 || n > 15) return fallback;
	return n;
}

/** 0=on timeline, 1=above, 2=below */
function getCalendarPlaceSetting(settings, key, fallback) {
	var n = parseInt(getStringSetting(settings, key, String(fallback)), 10);
	if (isNaN(n) || n < 0 || n > 2) return fallback;
	return n;
}

/** @returns {{url:string,color:number,place:number,cacheKey:string}[]} */
function getConfiguredCalendars(settings) {
	return [
		{
			url: sanitizeCalendarUrlSetting(
				settings, 'SETTING_CALENDAR_ICS_URL', 'cached_calendar_url'
			),
			color: getCalendarColorSetting(settings, 'SETTING_CALENDAR_COLOR_1', 1),
			place: getCalendarPlaceSetting(settings, 'SETTING_CALENDAR_PLACE_1', 0),
			cacheKey: 'cached_calendar_url',
		},
		{
			url: sanitizeCalendarUrlSetting(
				settings, 'SETTING_CALENDAR_ICS_URL_2', 'cached_calendar_url_2'
			),
			color: getCalendarColorSetting(settings, 'SETTING_CALENDAR_COLOR_2', 7),
			place: getCalendarPlaceSetting(settings, 'SETTING_CALENDAR_PLACE_2', 0),
			cacheKey: 'cached_calendar_url_2',
		},
		{
			url: sanitizeCalendarUrlSetting(
				settings, 'SETTING_CALENDAR_ICS_URL_3', 'cached_calendar_url_3'
			),
			color: getCalendarColorSetting(settings, 'SETTING_CALENDAR_COLOR_3', 2),
			place: getCalendarPlaceSetting(settings, 'SETTING_CALENDAR_PLACE_3', 0),
			cacheKey: 'cached_calendar_url_3',
		},
	].filter(function (c, i) {
		return !!c.url && !isAlarmRow(settings, i);
	});
}

/** Row i (0-based) set to "Next alarm" in Clay. */
function isAlarmRow(settings, i) {
	return parseInt(getStringSetting(settings, 'SETTING_CALENDAR_SOURCE_' + (i + 1), '0'), 10) === 1;
}

/** Pack per-row alarm info for the watch: byte i = 0x80 | place<<4 | color. */
function packAlarmRows(settings) {
	var defs = [1, 7, 2]; /* Clay defaults */
	var v = 0;
	for (var i = 0; i < 3; i++) {
		if (!isAlarmRow(settings, i)) continue;
		var c = getCalendarColorSetting(settings, 'SETTING_CALENDAR_COLOR_' + (i + 1), defs[i]);
		var p = getCalendarPlaceSetting(settings, 'SETTING_CALENDAR_PLACE_' + (i + 1), 0);
		v |= ((0x80 | (p << 4) | c) << (8 * i));
	}
	return v | 0;
}


function clearCachedTimelineEvents() {
	try { localStorage.removeItem('cached_timeline_events'); } catch (e) { }
}

function getTimelineEventWindow() {
	var nowSec = Math.floor(Date.now() / 1000);
	var forecastHours = getForecastHoursFromSettings();
	var pastHours = Math.floor(forecastHours / 4);
	/* Wrist-flick cycling can set any range up to 48h (+12h past) on the
	 * watch; send events covering the widest one. */
	try {
		var cs = JSON.parse(localStorage.getItem('clay-settings') || '{}');
		var flickOn = getBoolSetting(cs, 'SETTING_WRIST_FLICK', false);
		var flickRange = getStringSetting(cs, 'SETTING_FLICK_ACTION', '0') !== '1';
		if (flickOn && flickRange) {
			forecastHours = 48;
			pastHours = 12;
		}
	} catch (e) { }
	return {
		windowStart: nowSec - pastHours * 3600,
		windowEnd: nowSec + forecastHours * 3600,
	};
}

function readCachedTimelineEvents() {
	try {
		var cached = localStorage.getItem('cached_timeline_events');
		if (cached) return JSON.parse(cached);
	} catch (e) { }
	return null;
}

function filterTimelineEventsInWindow(events, windowStart, windowEnd) {
	var filtered = [];
	for (var i = 0; i < events.length && filtered.length < MAX_TIMELINE_EVENTS; i++) {
		var ev = events[i];
		if (ev.start < windowEnd && ev.end > windowStart) {
			filtered.push(ev);
		}
	}
	return filtered;
}

/**
 * Resolve timeline events for the watch.
 *
 * @param {number} timelineEvent  Clay mode (0=off, 1=bar, 2=span)
 * @param {Array|null} parsedEvents  Parsed ICS events, [] on success empty,
 *                                   null when no successful parse this cycle
 * @param {number} windowStart
 * @param {number} windowEnd
 * @param {{hasUrl:boolean, fetchFailed:boolean}=} opts
 * @returns {{start:number,end:number}[]}
 */
function resolveTimelineEvents(timelineEvent, parsedEvents, windowStart, windowEnd, opts) {
	opts = opts || {};
	var hasUrl = !!opts.hasUrl;
	var fetchFailed = !!opts.fetchFailed;

	if (timelineEvent === 0) {
		clearCachedTimelineEvents();
		eventLog.log('cal_off', 'clear');
		return [];
	}

	if (parsedEvents && parsedEvents.length > 0) {
		try {
			localStorage.setItem(
				'cached_timeline_events', JSON.stringify(parsedEvents)
			);
		} catch (e) { }
		eventLog.log('cal_ok', 'n=' + parsedEvents.length);
		return parsedEvents;
	}

	// Successful fetch/parse with zero events in window: show nothing.
	if (parsedEvents && parsedEvents.length === 0 && hasUrl && !fetchFailed) {
		eventLog.log('cal_empty', 'ok');
		return [];
	}

	// No URL configured: never seed fake meetings for normal users.
	if (!hasUrl) {
		clearCachedTimelineEvents();
		eventLog.log('cal_nourl', 'clear');
		return [];
	}

	// Transient network/parse failure: keep last good cache to avoid flicker.
	if (fetchFailed) {
		var cachedFail = readCachedTimelineEvents();
		if (cachedFail && cachedFail.length > 0) {
			var kept = filterTimelineEventsInWindow(
				cachedFail, windowStart, windowEnd
			);
			if (kept.length > 0) {
				eventLog.log('cal_cache', 'n=' + kept.length);
				return kept;
			}
		}
		eventLog.log('cal_fail_keep', 'empty');
		return [];
	}

	var cached = readCachedTimelineEvents();
	if (cached && cached.length > 0) {
		var inWindow = filterTimelineEventsInWindow(
			cached, windowStart, windowEnd
		);
		if (inWindow.length > 0) {
			eventLog.log('cal_cache', 'n=' + inWindow.length);
			return inWindow;
		}
	}
	return [];
}

function fetchCalendarEvents(payload, callback) {
	var settings = readClaySettings();
	var calendars = getConfiguredCalendars(settings);
	var timelineEvent = parseInt(
		getStringSetting(settings, 'SETTING_TIMELINE_EVENT', '1'), 10
	);
	var win = getTimelineEventWindow();
	var hasUrl = calendars.length > 0;
	var resolveOpts = { hasUrl: hasUrl, fetchFailed: false };

	if (timelineEvent === 0) {
		payload.timeline_events = resolveTimelineEvents(
			timelineEvent, [], win.windowStart, win.windowEnd, resolveOpts
		);
		callback(payload);
		return;
	}

	if (!hasUrl) {
		payload.timeline_events = resolveTimelineEvents(
			timelineEvent, null, win.windowStart, win.windowEnd, resolveOpts
		);
		callback(payload);
		return;
	}

	var pending = calendars.length;
	var merged = [];
	var anyOk = false;
	var anyFail = false;

	function finishOne() {
		pending--;
		if (pending > 0) return;
		var parsed = anyOk ? pickNearestEvents(merged, MAX_TIMELINE_EVENTS) : null;
		payload.timeline_events = resolveTimelineEvents(
			timelineEvent, parsed, win.windowStart, win.windowEnd,
			{ hasUrl: true, fetchFailed: !anyOk && anyFail }
		);
		callback(payload);
	}

	function fetchOne(cal) {
		function accept(text) {
			if (text.length > ics.MAX_ICS_BYTES) {
				eventLog.log('cal_fail', 'too large ' + Math.round(text.length / 1024) + 'KB ' + maskUrl(cal.url));
				anyFail = true;
				finishOne();
				return;
			}
			var parsed = parseIcsEvents(
				text, MAX_TIMELINE_EVENTS, win.windowStart, win.windowEnd
			);
			for (var i = 0; i < parsed.length; i++) {
				merged.push({
					start: parsed[i].start,
					end: parsed[i].end,
					allDay: !!parsed[i].allDay,
					color: cal.color,
					place: cal.place,
				});
			}
			anyOk = true;
			finishOne();
		}
		/* Direct fetch only (no third-party proxies: the ICS URL is a secret).
		 * One retry; on failure the last good cache is used. */
		retryXhr(cal.url, 2, 2000, 'calendar fetch', function (err, responseText) {
			if (!err && responseText && responseText.indexOf('BEGIN:VCALENDAR') >= 0) {
				accept(responseText);
				return;
			}
			eventLog.log('cal_fail', err || 'not ics');
			anyFail = true;
			finishOne();
		});
	}

	for (var ci = 0; ci < calendars.length; ci++) {
		fetchOne(calendars[ci]);
	}
}

module.exports = {
	fetchCalendarEvents: fetchCalendarEvents, packAlarmRows: packAlarmRows,
	getConfiguredCalendars: getConfiguredCalendars, resolveTimelineEvents: resolveTimelineEvents,
	getTimelineEventWindow: getTimelineEventWindow,
};
