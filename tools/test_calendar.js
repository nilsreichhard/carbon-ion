/* Host test: node tools/test_calendar.js (3.1.2)
 * Mocked XHR + localStorage: per-calendar cache fallback and http fallback. */
var store = {};
global.localStorage = { getItem: function (k) { return k in store ? store[k] : null; },
	setItem: function (k, v) { store[k] = String(v); }, removeItem: function (k) { delete store[k]; },
	key: function (i) { return Object.keys(store)[i] || null; }, get length() { return Object.keys(store).length; } };
var realSetTimeout = setTimeout;
global.setTimeout = function (f) { return realSetTimeout(f, 0); };
var routes = {}, hits = [];
global.XMLHttpRequest = function () {};
XMLHttpRequest.prototype.open = function (m, url) { this.url = url; };
XMLHttpRequest.prototype.setRequestHeader = function () {};
XMLHttpRequest.prototype.send = function () {
	var self = this; hits.push(self.url);
	realSetTimeout(function () {
		var r = routes[self.url];
		if (r === undefined) { self.onerror && self.onerror(); return; }
		self.status = r[0]; self.responseText = r[1]; self.onload && self.onload();
	}, 0);
};
var NOW = Math.floor(Date.now() / 1000);
function stamp(t) { return new Date(t * 1000).toISOString().replace(/[-:]/g, '').replace(/\.\d+/, ''); }
function ics(uid, off) {
	return ['BEGIN:VCALENDAR', 'BEGIN:VEVENT', 'UID:' + uid, 'DTSTART:' + stamp(NOW + off), 'DTEND:' + stamp(NOW + off + 1800),
		'END:VEVENT', 'END:VCALENDAR'].join('\r\n');
}
store['clay-settings'] = JSON.stringify({ SETTING_CALENDAR_ICS_URL: 'https://a.test/one.ics',
	SETTING_CALENDAR_ICS_URL_2: 'http://b.test/two.ics', SETTING_TIMELINE_EVENT: '2' });
var cal = require('../src/pkjs/calendar.js');
var pass = 0, fail = 0;
function check(name, got, want) {
	var ok = JSON.stringify(got) === JSON.stringify(want); ok ? pass++ : fail++;
	console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '\n   got  ' + JSON.stringify(got) + '\n   want ' + JSON.stringify(want)));
}
function run(cb) { cal.fetchCalendarEvents({}, function (p) { cb((p.timeline_events || []).map(function (e) { return e.color; }).sort()); }); }
var A = 'https://a.test/one.ics', Bs = 'https://b.test/two.ics', Bh = 'http://b.test/two.ics';
var quiet = console.log; var logs = [];
routes[A] = [200, ics('a', 3600)]; routes[Bs] = [200, ics('b', 7200)];
run(function (c1) {
	check('Both calendars fresh', c1, [1, 7]);
	routes[A] = [500, 'err'];
	run(function (c2) {
		check('Calendar 1 fails: its own cache is used, calendar 2 stays fresh', c2, [1, 7]);
		delete routes[Bs]; routes[Bh] = [200, ics('b', 7200)]; routes[A] = [200, ics('a', 3600)];
		hits = [];
		run(function (c3) {
			check('http:// link: https fails → falls back to http', [c3, hits.indexOf(Bh) >= 0], [[1, 7], true]);
			/* 3.1.2 re-check: clear removes all per-calendar caches, also of removed links */
			store[cal._calCacheKey('https://gone.test/old.ics')] = '[]';
			var before = Object.keys(store).filter(function (k) { return k.indexOf('cached_cal_events_') === 0; }).length;
			cal.clearCalEventCaches();
			var after = Object.keys(store).filter(function (k) { return k.indexOf('cached_cal_events_') === 0; }).length;
			check('Clear cache removes every cached_cal_events_* key (incl. removed link)', [before >= 3, after], [true, 0]);
			console.log(pass + '/' + (pass + fail) + ' passed');
			process.exit(fail ? 1 : 0);
		});
	});
});
