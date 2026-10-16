/* Host test: TZ=Europe/Zurich node tools/test_ics.js (also run with TZ=America/New_York) */
var ics = require('../src/pkjs/ics.js');
var VTZ = ['BEGIN:VTIMEZONE','TZID:Europe/Zurich',
'BEGIN:DAYLIGHT','TZOFFSETFROM:+0100','TZOFFSETTO:+0200','TZNAME:CEST','DTSTART:19700329T020000','RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU','END:DAYLIGHT',
'BEGIN:STANDARD','TZOFFSETFROM:+0200','TZOFFSETTO:+0100','TZNAME:CET','DTSTART:19701025T030000','RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU','END:STANDARD',
'END:VTIMEZONE'];
function cal(evs){ return ['BEGIN:VCALENDAR'].concat(VTZ, evs, ['END:VCALENDAR']).join('\r\n'); }
function ev(lines){ return ['BEGIN:VEVENT'].concat(lines, ['END:VEVENT']); }
function U(s){ return Math.floor(Date.parse(s)/1000); }
var fails=0, n=0;
function check(name, got, want){ n++; var ok=JSON.stringify(got)===JSON.stringify(want);
 if(!ok) fails++; console.log((ok?'PASS ':'FAIL ')+name+(ok?'':'\n   got  '+JSON.stringify(got)+'\n   want '+JSON.stringify(want))); }
function starts(r){ return r.map(function(e){return new Date(e.start*1000).toISOString();}); }
/* devtz-match case only valid when the host really is in Zurich */
(Intl.DateTimeFormat().resolvedOptions().timeZone==='Europe/Zurich'?[null,'Europe/Zurich']:[null]).forEach(function(dev){
 var o={deviceTz: dev===null? 'Etc/None': dev}; var tag=dev?' [devtz match]':' [VTIMEZONE]';
 var t1=ics.parseEvents(cal(ev(['UID:a','DTSTART;TZID=Europe/Zurich:20261015T090000','DTEND;TZID=Europe/Zurich:20261015T100000'])),U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'),o);
 check('Oct 15 09:00 CEST = 07:00Z'+tag, starts(t1), ['2026-10-15T07:00:00.000Z']);
 var t2=ics.parseEvents(cal(ev(['UID:b','DTSTART;TZID=Europe/Zurich:20261026T090000','DTEND;TZID=Europe/Zurich:20261026T100000'])),U('2026-10-25T00:00Z'),U('2026-10-28T00:00Z'),o);
 check('Oct 26 09:00 CET = 08:00Z'+tag, starts(t2), ['2026-10-26T08:00:00.000Z']);
 var t3=ics.parseEvents(cal(ev(['UID:c','DTSTART;TZID=Europe/Zurich:20230301T073000','DTEND;TZID=Europe/Zurich:20230301T080000','RRULE:FREQ=DAILY'])),U('2026-10-24T12:00Z'),U('2026-10-27T00:00Z'),o);
 check('Daily since 2023 across DST end'+tag, starts(t3), ['2026-10-25T06:30:00.000Z','2026-10-26T06:30:00.000Z']);
 var t4=ics.parseEvents(cal(ev(['UID:d','DTSTART;TZID=Europe/Zurich:20240910T100000','DTEND;TZID=Europe/Zurich:20240910T110000','RRULE:FREQ=WEEKLY;BYDAY=TU,TH','EXDATE;TZID=Europe/Zurich:20261015T100000'])),U('2026-10-12T00:00Z'),U('2026-10-28T00:00Z'),o);
 check('Weekly TU/TH with EXDATE Oct 15'+tag, starts(t4), ['2026-10-13T08:00:00.000Z','2026-10-20T08:00:00.000Z','2026-10-22T08:00:00.000Z','2026-10-27T09:00:00.000Z']);
 var t5=ics.parseEvents(cal([].concat(
   ev(['UID:e','DTSTART;TZID=Europe/Zurich:20260105T140000','DTEND;TZID=Europe/Zurich:20260105T150000','RRULE:FREQ=WEEKLY']),
   ev(['UID:e','RECURRENCE-ID;TZID=Europe/Zurich:20261019T140000','DTSTART;TZID=Europe/Zurich:20261020T160000','DTEND;TZID=Europe/Zurich:20261020T170000']),
   ev(['UID:e','RECURRENCE-ID;TZID=Europe/Zurich:20261026T140000','DTSTART;TZID=Europe/Zurich:20261026T140000','DTEND;TZID=Europe/Zurich:20261026T150000','STATUS:CANCELLED']))),
   U('2026-10-18T00:00Z'),U('2026-10-28T00:00Z'),o);
 check('RECURRENCE-ID moved + cancelled instance'+tag, starts(t5), ['2026-10-20T14:00:00.000Z']);
});
var t6=ics.parseEvents(cal(ev(['UID:f','DTSTART;VALUE=DATE:19900316','DTEND;VALUE=DATE:19900317','RRULE:FREQ=YEARLY'])),U('2027-03-15T00:00Z'),U('2027-03-17T23:00Z'));
check('Yearly birthday (all-day, local)', t6.map(function(e){return [new Date(e.start*1000).toString().slice(0,15), e.allDay, (e.end-e.start)/3600];}), [['Tue Mar 16 2027',true,24]]);
var t7=ics.parseEvents(cal(ev(['UID:g','DTSTART:20261015T120000Z','DTEND:20261015T130000Z','STATUS:CANCELLED'])),U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'));
check('Cancelled single event dropped', t7.length, 0);
var t8=ics.parseEvents(cal(ev(['UID:h','DTSTART;TZID=Europe/Zurich:20260101T090000','DURATION:PT30M','RRULE:FREQ=MONTHLY;BYDAY=-1FR;COUNT=12'])),U('2026-10-01T00:00Z'),U('2026-11-30T00:00Z'),{deviceTz:'x'});
check('Monthly last Friday COUNT=12 (+DURATION)', starts(t8), ['2026-10-30T08:00:00.000Z','2026-11-27T08:00:00.000Z']);
var t9=ics.parseEvents(cal(ev(['UID:i','DTSTART;TZID=Europe/Zurich:20260101T090000','DTEND;TZID=Europe/Zurich:20260101T100000','RRULE:FREQ=DAILY;UNTIL=20261015T235959Z'])),U('2026-10-14T00:00Z'),U('2026-10-18T00:00Z'),{deviceTz:'x'});
check('UNTIL stops series', starts(t9), ['2026-10-14T07:00:00.000Z','2026-10-15T07:00:00.000Z']);
var now=U('2026-10-15T10:00Z');
var pk=ics.pickNearest([{start:now-86400,end:now+86400,allDay:true},{start:now+3600,end:now+7200,allDay:false},{start:now+30*3600,end:now+31*3600,allDay:false}],2,now);
check('pickNearest: timed before all-day', pk.map(function(e){return e.allDay;}), [false,false]);
/* ---- 2.7.6 ---- */
var OUT='(UTC+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna';
var VTZO=VTZ.join('\r\n').replace('TZID:Europe/Zurich','TZID:'+OUT).split('\r\n');
var t10=ics.parseEvents(['BEGIN:VCALENDAR'].concat(VTZO, ev(['UID:o','DTSTART;TZID="'+OUT+'":20261015T090000','DTEND;TZID="'+OUT+'":20261015T100000']),['END:VCALENDAR']).join('\r\n'),U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'),{deviceTz:'x'});
check('Outlook quoted TZID with colon', starts(t10), ['2026-10-15T07:00:00.000Z']);
var t11=ics.parseEvents('BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:ny\r\nDTSTART;TZID=America/New_York:20261015T090000\r\nDTEND;TZID=America/New_York:20261015T100000\r\nEND:VEVENT\r\nEND:VCALENDAR',U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'),{deviceTz:'Europe/Zurich'});
check('NY TZID without VTIMEZONE on Zurich phone', t11.map(function(e){return [new Date(e.start*1000).toISOString(),(e.end-e.start)/60];}), [['2026-10-15T13:00:00.000Z',60]]);
var t11b=ics.parseEvents('BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:sy\r\nDTSTART;TZID=Australia/Sydney:20261005T090000\r\nRRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT\r\nEND:VCALENDAR',U('2026-10-01T00:00Z'),U('2026-10-10T00:00Z'),{deviceTz:'Europe/Zurich'});
check('Sydney TZID (Intl) across its DST start Oct 4', starts(t11b), ['2026-10-04T22:00:00.000Z','2026-10-05T22:00:00.000Z','2026-10-06T22:00:00.000Z']);
var t12=ics.parseEvents(cal(ev(['UID:sp','DTSTART;TZID=Europe/Zurich:20260130T170000','RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1'])),U('2026-10-01T00:00Z'),U('2027-01-01T00:00Z'),{deviceTz:'x'});
check('BYSETPOS=-1 last weekday of month', starts(t12), ['2026-10-30T16:00:00.000Z','2026-11-30T16:00:00.000Z','2026-12-31T16:00:00.000Z']);
var t13=ics.parseEvents(cal(ev(['UID:yb','DTSTART:20260105T090000Z','RRULE:FREQ=YEARLY;BYDAY=MO'])),U('2026-10-01T00:00Z'),U('2026-10-20T00:00Z'));
check('YEARLY BYDAY=MO (no BYMONTH) = every Monday', starts(t13), ['2026-10-05T09:00:00.000Z','2026-10-12T09:00:00.000Z','2026-10-19T09:00:00.000Z']);
var t13b=ics.parseEvents(cal(ev(['UID:y20','DTSTART:20260518T090000Z','RRULE:FREQ=YEARLY;BYDAY=20MO'])),U('2027-01-01T00:00Z'),U('2028-01-01T00:00Z'));
check('YEARLY BYDAY=20MO = 20th Monday of year', starts(t13b), ['2027-05-17T09:00:00.000Z']);
var t13c=ics.parseEvents(cal(ev(['UID:wk','DTSTART:20260511T090000Z','RRULE:FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO'])),U('2026-01-01T00:00Z'),U('2028-01-01T00:00Z'));
check('BYWEEKNO unsupported: DTSTART only', starts(t13c), ['2026-05-11T09:00:00.000Z']);
var big='BEGIN:VCALENDAR\r\n'+new Array(ics.MAX_ICS_BYTES+10).join('X')+'\r\nEND:VCALENDAR';
var t14t=Date.now(), t14=ics.parseEvents(big,U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'));
check('ICS > 2 MB rejected quickly', [t14.length, !!t14.tooLarge, Date.now()-t14t<200], [0,true,true]);
var many=[]; for(var k=0;k<2500;k++) many=many.concat(ev(['UID:m'+k,'DTSTART:20261015T120000Z']));
check('VEVENT cap 2000', ics.parseEvents(cal(many),U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z')).length, 2000);
var t15t=Date.now(), t15=ics.parseEvents(cal([].concat(ev(['UID:b1','DTSTART:19000101T090000Z','RRULE:FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR,SA,SU;COUNT=99999999']),ev(['UID:b2','DTSTART:20261015T120000Z']))),U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'),{recurBudget:1000});
check('Recurrence budget stops runaway series, singles survive', [t15.length, Date.now()-t15t<500], [1,true]);
/* 2.8.5: COUNT series skip ahead (counting skipped instances) */
var t16=ics.parseEvents(cal(ev(['UID:c1','DTSTART:19000101T090000Z','RRULE:FREQ=DAILY;COUNT=99999999'])),U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'),{recurBudget:1000});
check('Old DAILY COUNT series reaches today within a small budget', t16.length, 3);
var t17=ics.parseEvents(cal(ev(['UID:c2','DTSTART:20260101T090000Z','RRULE:FREQ=DAILY;COUNT=288'])),U('2026-10-14T00:00Z'),U('2026-10-17T00:00Z'));
check('DAILY COUNT=288 from Jan 1 ends on Oct 15 (skip-ahead keeps exact count)', t17.map(function(e){return new Date(e.start*1000).toISOString().slice(0,10);}), ['2026-10-14','2026-10-15']);
var t18=ics.parseEvents(cal(ev(['UID:c3','DTSTART:20200106T090000Z','RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=708'])),U('2026-10-12T00:00Z'),U('2026-10-20T00:00Z'));
check('WEEKLY BYDAY=MO,WE COUNT ends correctly after skip-ahead', t18.map(function(e){return new Date(e.start*1000).toISOString().slice(0,10);}), ['2026-10-12','2026-10-14']);
var t19=ics.parseEvents(cal(ev(['UID:c4','DTSTART:20200106T090000Z','RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10'])),U('2026-10-12T00:00Z'),U('2026-10-20T00:00Z'));
check('Finished COUNT series emits nothing in a later window', t19.length, 0);

console.log((n-fails)+'/'+n+' passed');
process.exit(fails?1:0);
