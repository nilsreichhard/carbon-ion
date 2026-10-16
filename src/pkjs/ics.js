/**
 * ICS (RFC 5545) parsing for Carbon Ion timeline events.
 *
 * - VTIMEZONE offsets with real yearly transition dates (BYMONTH + BYDAY like
 *   -1SU / 2SU, or BYMONTHDAY), or the phone's own zone when TZID matches it.
 * - Recurrence via calendar-date arithmetic on wall time (DST-safe), jumping
 *   straight to the window (no iteration caps from the series start).
 * - DAILY / WEEKLY / MONTHLY / YEARLY, INTERVAL, COUNT, UNTIL, BYDAY (with
 *   ordinals), BYMONTHDAY, BYMONTH, EXDATE, RECURRENCE-ID, STATUS:CANCELLED.
 *
 * Wall times are "pseudo-UTC" seconds: Date.UTC() of the local components.
 *
 * @license GPL-3.0-or-later
 */

var DAY = 86400;
var MAX_ICS_BYTES = 2 * 1024 * 1024;   /* reject larger feeds before parsing */
var MAX_VEVENTS = 2000;                /* components considered per feed */
var RECUR_BUDGET = 50000;              /* recurrence steps per parse, all series */
var PROP_RE = /^([A-Za-z0-9-]+)((?:;[A-Za-z0-9-]+=(?:"[^"]*"|[^";:])*)*):(.*)$/;
var LOOSE_PROP_RE = /^([A-Za-z0-9-]+)((?:;[^:]*)?):(.*)$/;
var PARAM_RE = /;([A-Za-z0-9-]+)=((?:"[^"]*"|[^";])*)/g;
var WD = { MO: 0, TU: 1, WE: 2, TH: 3, FR: 4, SA: 5, SU: 6 }; /* Monday = 0 */

function unfold(text) {
	return String(text || '').replace(/\r\n[ \t]/g, '').replace(/\n[ \t]/g, '');
}

function parseProps(block) {
	var props = [];
	var lines = block.split(/\r?\n/);
	for (var i = 0; i < lines.length; i++) {
		/* Quote-aware: TZID="(UTC+01:00) Amsterdam" must not split at ':'. */
		var m = PROP_RE.exec(lines[i]) || LOOSE_PROP_RE.exec(lines[i]);
		if (!m) continue;
		var params = {};
		if (m[2]) {
			var pm;
			PARAM_RE.lastIndex = 0;
			while ((pm = PARAM_RE.exec(m[2])) !== null) {
				params[pm[1].toUpperCase()] = pm[2].replace(/^"|"$/g, '');
			}
		}
		props.push({ name: m[1].toUpperCase(), params: params, value: m[3].trim() });
	}
	return props;
}

function getProp(props, name) {
	for (var i = 0; i < props.length; i++) if (props[i].name === name) return props[i];
	return null;
}

function getProps(props, name) {
	return props.filter(function (p) { return p.name === name; });
}

/* Split text into top-level component bodies of a given type. */
function components(text, type, max) {
	var out = [];
	var re = new RegExp('^BEGIN:' + type + '\\s*$([\\s\\S]*?)^END:' + type + '\\s*$', 'gim');
	var m;
	while ((m = re.exec(text)) !== null) {
		out.push(m[1]);
		if (max && out.length >= max) break;
	}
	return out;
}

/* ---- calendar helpers (no timezone involved) ---- */
function dayNum(y, m, d) { return Math.floor(Date.UTC(y, m - 1, d) / 86400000); }
function ymd(n) {
	var dt = new Date(n * 86400000);
	return { y: dt.getUTCFullYear(), m: dt.getUTCMonth() + 1, d: dt.getUTCDate(),
		wd: (dt.getUTCDay() + 6) % 7 };
}
function daysInMonth(y, m) { return new Date(Date.UTC(y, m, 0)).getUTCDate(); }

function parseOffset(str) {
	var m = /^([+-])(\d{2})(\d{2})(\d{2})?$/.exec(String(str || '').trim());
	if (!m) return null;
	return (m[1] === '+' ? 1 : -1) * (parseInt(m[2], 10) * 3600 + parseInt(m[3], 10) * 60 +
		(m[4] ? parseInt(m[4], 10) : 0));
}

/* "20261015T090000" / "20261015" → {wall, allDay, utc(bool)} */
function parseStamp(v) {
	var m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$/.exec(String(v || '').trim());
	if (!m) return null;
	var y = +m[1], mo = +m[2], d = +m[3];
	if (m[4] === undefined) return { wall: dayNum(y, mo, d) * DAY, allDay: true, utc: false };
	return { wall: Date.UTC(y, mo - 1, d, +m[4], +m[5], m[6] ? +m[6] : 0) / 1000,
		allDay: false, utc: m[7] === 'Z' };
}

function parseRrule(value) {
	var r = {};
	String(value || '').split(';').forEach(function (part) {
		var eq = part.indexOf('=');
		if (eq > 0) r[part.substring(0, eq).toUpperCase()] = part.substring(eq + 1).toUpperCase();
	});
	return r;
}

/* BYDAY → [{n:ordinal|0, wd}] */
function parseByday(s) {
	if (!s) return [];
	return s.split(',').map(function (t) {
		var m = /^([+-]?\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)$/.exec(t.trim());
		return m ? { n: m[1] ? parseInt(m[1], 10) : 0, wd: WD[m[2]] } : null;
	}).filter(function (x) { return x; });
}

function intList(s) {
	if (!s) return [];
	return s.split(',').map(function (x) { return parseInt(x, 10); })
		.filter(function (x) { return !isNaN(x) && x !== 0; });
}

/* Day numbers within month y/m matching BYDAY / BYMONTHDAY, or default day. */
function monthDays(y, m, byday, bymonthday, defDay) {
	var dim = daysInMonth(y, m);
	var first = dayNum(y, m, 1);
	var out = [];
	if (byday.length) {
		var fwd = ymd(first).wd;
		byday.forEach(function (b) {
			var all = [];
			for (var d = 1 + ((b.wd - fwd + 7) % 7); d <= dim; d += 7) all.push(d);
			if (b.n > 0 && all[b.n - 1]) out.push(all[b.n - 1]);
			else if (b.n < 0 && all[all.length + b.n]) out.push(all[all.length + b.n]);
			else if (b.n === 0) out = out.concat(all);
		});
		if (bymonthday.length) {
			out = out.filter(function (d) {
				return bymonthday.some(function (md) { return (md > 0 ? md : dim + md + 1) === d; });
			});
		}
	} else if (bymonthday.length) {
		bymonthday.forEach(function (md) {
			var d = md > 0 ? md : dim + md + 1;
			if (d >= 1 && d <= dim) out.push(d);
		});
	} else if (defDay <= dim) {
		out.push(defDay);
	}
	return out.map(function (d) { return first + d - 1; }).sort(function (a, b) { return a - b; });
}

/* ---- VTIMEZONE ---- */
function parseVtimezones(text) {
	var zones = {};
	components(text, 'VTIMEZONE').forEach(function (body) {
		var tzid = getProp(parseProps(body.replace(/BEGIN:(STANDARD|DAYLIGHT)[\s\S]*?END:\1/gi, '')), 'TZID');
		if (!tzid) return;
		var comps = [];
		['STANDARD', 'DAYLIGHT'].forEach(function (type) {
			components(body, type).forEach(function (cb) {
				var p = parseProps(cb);
				var from = parseOffset((getProp(p, 'TZOFFSETFROM') || {}).value);
				var to = parseOffset((getProp(p, 'TZOFFSETTO') || {}).value);
				var st = parseStamp((getProp(p, 'DTSTART') || {}).value);
				if (to === null) return;
				var rr = getProp(p, 'RRULE');
				comps.push({ from: from === null ? to : from, to: to,
					start: st ? st.wall : 0, rule: rr ? parseRrule(rr.value) : null });
			});
		});
		if (comps.length) zones[tzid.value] = comps;
	});
	return zones;
}

/* UTC instants of a component's transitions in year y. */
function transitionsInYear(c, y) {
	var tod = ((c.start % DAY) + DAY) % DAY;
	var out = [];
	if (!c.rule || c.rule.FREQ !== 'YEARLY') {
		if (ymd(Math.floor(c.start / DAY)).y === y) out.push(c.start - c.from);
		return out;
	}
	var sd = ymd(Math.floor(c.start / DAY));
	var months = intList(c.rule.BYMONTH);
	if (!months.length) months = [sd.m];
	var until = c.rule.UNTIL ? parseStamp(c.rule.UNTIL) : null;
	months.forEach(function (m) {
		var days = monthDays(y, m, parseByday(c.rule.BYDAY), intList(c.rule.BYMONTHDAY), sd.d);
		if (!days.length) return;
		var wall = days[0] * DAY + tod;
		if (wall < c.start) return;
		var utc = wall - c.from;
		if (until && (until.utc ? utc : wall) > until.wall) return;
		out.push(utc);
	});
	return out;
}

function zoneOffsetAt(comps, utc) {
	var y = new Date(utc * 1000).getUTCFullYear();
	var best = null, bestOff = null, earliest = null;
	comps.forEach(function (c) {
		[y - 1, y].forEach(function (yy) {
			transitionsInYear(c, yy).forEach(function (t) {
				if (t <= utc && (best === null || t > best)) { best = t; bestOff = c.to; }
			});
		});
		if (earliest === null || c.start < earliest.start) earliest = c;
	});
	if (bestOff !== null) return bestOff;
	/* Before any known transition in range: fall back to latest rule older
	 * than 2 years (e.g. fixed-offset zone) or the earliest component. */
	return earliest.rule ? earliest.from : earliest.to;
}

function deviceTz() {
	try { return Intl.DateTimeFormat().resolvedOptions().timeZone || null; } catch (e) { return null; }
}

function localToUtc(wall) {
	var d = new Date(wall * 1000);
	return Math.floor(new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(),
		d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds()).getTime() / 1000);
}

/* IANA TZID without a VTIMEZONE block: offsets from Intl (null if unsupported). */
var s_intlCache = {};
function intlZone(tzid) {
	if (Object.prototype.hasOwnProperty.call(s_intlCache, tzid)) return s_intlCache[tzid];
	var z = null;
	try {
		var fmt = new Intl.DateTimeFormat('en-US', { timeZone: tzid, hourCycle: 'h23',
			year: 'numeric', month: '2-digit', day: '2-digit',
			hour: '2-digit', minute: '2-digit', second: '2-digit' });
		if (typeof fmt.formatToParts !== 'function') throw new Error('no formatToParts');
		var memo = {};
		z = function (utc) { /* offset (s) at utc instant, memoised per hour */
			var hk = Math.floor(utc / 3600);
			if (memo[hk] !== undefined) return memo[hk];
			var o = {};
			fmt.formatToParts(new Date(hk * 3600000)).forEach(function (p) { o[p.type] = p.value; });
			var w = Date.UTC(+o.year, +o.month - 1, +o.day, (+o.hour) % 24, +o.minute, +o.second) / 1000;
			var off = w - hk * 3600;
			if (isNaN(off)) throw new Error('bad Intl output');
			memo[hk] = off;
			return off;
		};
		z(Math.floor(Date.now() / 1000));
	} catch (e) {
		z = null;
	}
	s_intlCache[tzid] = z;
	return z;
}

/* ctx: {kind:'utc'|'local'|'zone'|'intl', comps|off} */
function toUtc(wall, ctx) {
	if (ctx.kind === 'utc') return wall;
	if (ctx.kind === 'intl') {
		var g = wall - ctx.off(wall);
		return wall - ctx.off(g);
	}
	if (ctx.kind === 'zone') {
		var guess = wall - zoneOffsetAt(ctx.comps, wall);
		return wall - zoneOffsetAt(ctx.comps, guess);
	}
	return localToUtc(wall);
}

function ctxFor(prop, stamp, zones, devTz) {
	if (stamp.utc) return { kind: 'utc' };
	if (stamp.allDay) return { kind: 'local' };
	var tzid = prop && prop.params.TZID;
	if (tzid) {
		if (devTz && tzid === devTz) return { kind: 'local' };
		if (zones[tzid]) return { kind: 'zone', comps: zones[tzid] };
		if (/^(Etc\/)?(UTC|GMT|Zulu)$/i.test(tzid)) return { kind: 'utc' };
		if (/^[A-Za-z]+(\/[A-Za-z0-9_+-]+)+$/.test(tzid)) {
			var off = intlZone(tzid);
			if (off) return { kind: 'intl', off: off };
		}
	}
	return { kind: 'local' };
}

function parseDuration(v) {
	var m = /^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(String(v || '').trim());
	if (!m) return null;
	var s = (+(m[2] || 0)) * 7 * DAY + (+(m[3] || 0)) * DAY + (+(m[4] || 0)) * 3600 +
		(+(m[5] || 0)) * 60 + (+(m[6] || 0));
	return m[1] === '-' ? -s : s;
}

/* Key identifying an instance (EXDATE / RECURRENCE-ID matching). */
function instanceKey(wall, allDay, ctx) {
	return allDay ? 'D' + Math.floor(wall / DAY) : 'T' + toUtc(wall, ctx);
}

function dateKeys(propList, zones, devTz) {
	var keys = {};
	propList.forEach(function (p) {
		p.value.split(',').forEach(function (v) {
			var st = parseStamp(v);
			if (!st) return;
			var c = ctxFor(p, st, zones, devTz);
			keys[instanceKey(st.wall, st.allDay, c)] = true;
		});
	});
	return keys;
}

/*
 * Wall-time occurrence starts of a recurring event, in order, from the first
 * period that can reach windowStartWall (or from the start when COUNT is set).
 * cb(wall) returns false to stop.
 */
/* Rule parts this expander cannot honour: emit DTSTART only (never guess). */
function unsupportedRule(rule) {
	if (!{ DAILY: 1, WEEKLY: 1, MONTHLY: 1, YEARLY: 1 }[rule.FREQ]) return true;
	return !!(rule.BYWEEKNO || rule.BYYEARDAY || rule.BYHOUR || rule.BYMINUTE || rule.BYSECOND);
}

function applySetpos(days, setpos) {
	if (!setpos.length || !days.length) return days;
	var out = [];
	setpos.forEach(function (sp) {
		var d = sp > 0 ? days[sp - 1] : days[days.length + sp];
		if (d !== undefined && out.indexOf(d) < 0) out.push(d);
	});
	return out.sort(function (a, b) { return a - b; });
}

/* All days of year y on the BYDAY weekdays, ordinals counted within the year. */
function yearDays(y, byday) {
	var first = dayNum(y, 1, 1), last = dayNum(y, 12, 31);
	var fwd = ymd(first).wd, out = [];
	byday.forEach(function (b) {
		var all = [];
		for (var d = first + ((b.wd - fwd + 7) % 7); d <= last; d += 7) all.push(d);
		if (b.n > 0 && all[b.n - 1] !== undefined) out.push(all[b.n - 1]);
		else if (b.n < 0 && all[all.length + b.n] !== undefined) out.push(all[all.length + b.n]);
		else if (b.n === 0) out = out.concat(all);
	});
	return out;
}

function iterateRule(rule, startWall, startDayWd, spanDays, winStartWall, winEndWall, cb, budget) {
	var freq = rule.FREQ;
	var I = Math.max(1, parseInt(rule.INTERVAL, 10) || 1);
	var count = rule.COUNT ? parseInt(rule.COUNT, 10) : null;
	var tod = ((startWall % DAY) + DAY) % DAY;
	var sDay = Math.floor(startWall / DAY);
	var sd = ymd(sDay);
	var byday = parseByday(rule.BYDAY);
	var bymd = intList(rule.BYMONTHDAY);
	var bymonth = intList(rule.BYMONTH);
	var setpos = intList(rule.BYSETPOS);
	var maxLen = { DAILY: 1, WEEKLY: 7, MONTHLY: 31, YEARLY: 366 }[freq];
	if (!maxLen) return;

	function periodDays(p) {
		var days = [];
		if (freq === 'DAILY') {
			days = [sDay + p * I];
		} else if (freq === 'WEEKLY') {
			var wk = sDay - sd.wd + p * I * 7;
			var wds = byday.length ? byday.map(function (b) { return b.wd; }) : [sd.wd];
			days = wds.map(function (w) { return wk + w; });
		} else if (freq === 'MONTHLY') {
			var mi = (sd.y * 12 + sd.m - 1) + p * I;
			days = monthDays(Math.floor(mi / 12), (mi % 12) + 1, byday, bymd, sd.d);
		} else {
			var y = sd.y + p * I;
			if (byday.length && !bymonth.length && !bymd.length) {
				days = yearDays(y, byday);
			} else {
				/* BYMONTHDAY without BYMONTH applies to every month (RFC 5545). */
				var ms = bymonth.length ? bymonth : (bymd.length ? [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12] : [sd.m]);
				ms.forEach(function (m) {
					days = days.concat(monthDays(y, m, byday, bymd, sd.d));
				});
			}
		}
		if (freq !== 'YEARLY' && bymonth.length) {
			days = days.filter(function (d) { return bymonth.indexOf(ymd(d).m) >= 0; });
		}
		if (freq === 'DAILY' && byday.length) {
			days = days.filter(function (d) {
				return byday.some(function (b) { return b.wd === ymd(d).wd; });
			});
		}
		days = days.sort(function (a, b) { return a - b; })
			.filter(function (d, i, a) { return i === 0 || a[i - 1] !== d; });
		return applySetpos(days, setpos);
	}

	var p = 0;
	var emitted = 0;
	var lead = Math.floor((winStartWall - startWall) / DAY) - spanDays - 2;
	if (count === null) {
		if (lead > 0) p = Math.floor(lead / (maxLen * I));
	} else if (lead > 0 && !setpos.length && !bymonth.length &&
		(freq === 'WEEKLY' || (freq === 'DAILY' && !byday.length))) {
		/* 2.8.5: COUNT series with a fixed number of instances per period skip
		 * ahead too (counting the skipped instances) instead of walking from
		 * DTSTART through the shared budget. */
		var pSkip = Math.floor(lead / (maxLen * I));
		if (pSkip > 1) {
			var per = periodDays(1).length;
			var first = periodDays(0).filter(function (d) { return d * DAY + tod > startWall; }).length;
			var before = 1 + first + (pSkip - 1) * per; /* 1 = DTSTART */
			if (before >= count) return; /* series ended before the window */
			emitted = before;
			p = pSkip;
		}
	}
	var winEndDay = Math.floor(winEndWall / DAY) + 2;
	/* DTSTART is always the first instance (RFC 5545). */
	if (p === 0) {
		emitted++;
		if (cb(startWall) === false) return;
		if (count !== null && emitted >= count) return;
	}
	for (var guard = 0; guard < 200000; guard++, p++) {
		if (budget && --budget.left < 0) return;
		var days = periodDays(p);
		var minPossible = freq === 'DAILY' ? sDay + p * I : null;
		if (days.length === 0) {
			if (minPossible !== null && minPossible > winEndDay && count === null) return;
			if (freq !== 'DAILY' && count === null) {
				/* rough period start check */
				var approx = sDay + p * I * (maxLen === 366 ? 365 : maxLen === 31 ? 28 : maxLen);
				if (approx > winEndDay) return;
			}
			continue;
		}
		if (days[0] > winEndDay && count === null) return;
		for (var k = 0; k < days.length; k++) {
			var wall = days[k] * DAY + tod;
			if (wall <= startWall) continue;
			emitted++;
			if (cb(wall) === false) return;
			if (count !== null && emitted >= count) return;
		}
		if (count !== null && days[0] > winEndDay) return;
	}
}

/**
 * Parse ICS text into occurrences overlapping [windowStart, windowEnd] (UTC s).
 * @returns {{start:number,end:number,allDay:boolean}[]}
 */
function parseEvents(icsText, windowStart, windowEnd, opts) {
	opts = opts || {};
	var out = [];
	if (!icsText) return out;
	if (icsText.length > MAX_ICS_BYTES) { out.tooLarge = true; return out; }
	var text = unfold(icsText);
	var zones = parseVtimezones(text);
	var devTz = opts.deviceTz !== undefined ? opts.deviceTz : deviceTz();
	var events = components(text, 'VEVENT', MAX_VEVENTS).map(parseProps);
	var budget = { left: opts.recurBudget || RECUR_BUDGET };

	/* Overrides by UID → instance key → override props */
	var overrides = {};
	events.forEach(function (p) {
		var rid = getProp(p, 'RECURRENCE-ID');
		var uid = getProp(p, 'UID');
		if (!rid || !uid) return;
		var st = parseStamp(rid.value);
		if (!st) return;
		var c = ctxFor(rid, st, zones, devTz);
		(overrides[uid.value] = overrides[uid.value] || {})[instanceKey(st.wall, st.allDay, c)] = true;
	});

	function push(s, e, allDay) {
		if (e < s) e = s;
		if (e >= windowStart && s <= windowEnd) out.push({ start: s, end: e, allDay: !!allDay });
	}

	events.forEach(function (p) {
		var status = getProp(p, 'STATUS');
		if (status && status.value.toUpperCase() === 'CANCELLED') return;
		var dtsP = getProp(p, 'DTSTART');
		var dts = dtsP && parseStamp(dtsP.value);
		if (!dts) return;
		if (dtsP.params.VALUE && dtsP.params.VALUE.toUpperCase() === 'DATE') dts.allDay = true;
		var ctx = ctxFor(dtsP, dts, zones, devTz);
		var dteP = getProp(p, 'DTEND');
		var dte = dteP && parseStamp(dteP.value);
		var durWall;
		var durUtc;
		var startUtc = toUtc(dts.wall, ctx);
		if (dte) {
			var ectx = ctxFor(dteP, dte, zones, devTz);
			durWall = dte.wall - dts.wall;
			durUtc = toUtc(dte.wall, ectx) - startUtc;
		} else {
			var dur = getProp(p, 'DURATION');
			durWall = dur ? parseDuration(dur.value) : null;
			if (durWall === null) durWall = dts.allDay ? DAY : 0;
			durUtc = durWall;
		}
		if (durWall < 0) durWall = 0;
		if (durUtc < 0) durUtc = 0;

		function emit(wall) {
			var s = toUtc(wall, ctx);
			var e = dts.allDay ? toUtc(wall + durWall, ctx) : s + durUtc;
			push(s, e, dts.allDay);
		}

		var rid = getProp(p, 'RECURRENCE-ID');
		var rrP = getProp(p, 'RRULE');
		if (rid || !rrP) { emit(dts.wall); return; }

		var rule = parseRrule(rrP.value);
		if (!rule.FREQ || unsupportedRule(rule)) { emit(dts.wall); return; }
		var until = rule.UNTIL ? parseStamp(rule.UNTIL) : null;
		var ex = dateKeys(getProps(p, 'EXDATE'), zones, devTz);
		var uid = getProp(p, 'UID');
		var ov = (uid && overrides[uid.value]) || {};
		/* Window in wall time with a day of slack for zone offsets. */
		var winStartWall = windowStart - DAY;
		var winEndWall = windowEnd + DAY;
		var spanDays = Math.ceil(durWall / DAY);
		iterateRule(rule, dts.wall, null, spanDays, winStartWall, winEndWall, function (wall) {
			if (until) {
				if (until.allDay ? Math.floor(wall / DAY) > Math.floor(until.wall / DAY)
					: (until.utc ? toUtc(wall, ctx) > until.wall : wall > until.wall)) return false;
			}
			if (wall > winEndWall) return false;
			var key = instanceKey(wall, dts.allDay, ctx);
			if (ex[key] || ov[key]) return true;
			emit(wall);
			return budget.left > 0;
		}, budget);
	});
	return out;
}

/**
 * Keep `cap` events: timed events overlapping/nearest now first, then all-day
 * ones fill remaining slots. Returned in start order.
 */
function pickNearest(events, cap, nowSec) {
	nowSec = nowSec || Math.floor(Date.now() / 1000);
	function gap(e) {
		if (e.start <= nowSec && e.end >= nowSec) return 0;
		return e.start > nowSec ? e.start - nowSec : nowSec - e.end;
	}
	function byGap(a, b) { return gap(a) - gap(b); }
	var timed = events.filter(function (e) { return !e.allDay; }).sort(byGap);
	var allDay = events.filter(function (e) { return e.allDay; }).sort(byGap);
	var picked = timed.concat(allDay).slice(0, cap);
	picked.sort(function (a, b) { return a.start - b.start; });
	return picked;
}

function normalizeUrl(url) {
	url = String(url || '').trim();
	if (/^webcal:\/\//i.test(url)) return 'https://' + url.substring(9);
	if (/^http:\/\//i.test(url)) return 'https://' + url.substring(7);
	return url;
}

module.exports = {
	parseEvents: parseEvents,
	pickNearest: pickNearest,
	normalizeUrl: normalizeUrl,
	MAX_ICS_BYTES: MAX_ICS_BYTES,
	_zoneOffsetAt: zoneOffsetAt,
	_parseVtimezones: parseVtimezones,
};
