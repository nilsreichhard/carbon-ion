## Carbon Ion 3.3.0

Changes since 3.2.1 (current store version).

**Next phone alarm on the watch (Android)**
- The date line can now show your phone's next alarm, e.g. `Thursday, May 28 · ⏰ 05:55`. If the alarm is more than 24 hours away, its weekday is shown too (`⏰ Fri 07:00`).
- The alarm time follows the watch's 12/24-hour setting and the "leading zero" option, just like the clock.
- The alarm is hidden when there is no alarm set or once its time has passed. The last value is remembered when the watchface restarts.
- If the full date and the alarm don't fit on one line, the weekday is shortened (`Wed, Jul 15 · ⏰ Fri 07:00`). On 12-hour watches the alarm is then shortened to `7:00a`. If it still doesn't fit, only the date is shown. The date itself is never shrunk or cut off.
- New setting: **Show next phone alarm (Android companion app)**, on by default.
- How it works: the Carbon Ion Android companion app (1.1.0 or later) reads the phone's next alarm and sends it to the watchface. This uses PebbleKit Android 2 in the Core Devices Pebble app. It sends again whenever the alarm changes, when the watchface opens, on each weather update, after a reboot or app update, and when the time or time zone changes.
- Requirements: Android only. iPhones don't let apps read the phone's alarms, so nothing is shown there. You need the companion app installed, and the alarm only reaches the watch while Carbon Ion is the active watchface. Without the companion app the watchface works exactly as before.

**Date**
- New date format: `Mon, Jan 15` (abbreviated weekday).
- Weekday names (`%A`, `%a`) are always in English, matching the rest of the watchface.

**Technical**
- New message keys: `PHONE_ALARM_TIME` (10095, UTC epoch seconds, 0 = none) and `SETTING_PHONE_ALARM` (10096).
- New persist key 11 for the last alarm received. Older saved settings load with the alarm shown.
- `companionApp` is declared in appinfo/package.json (Android package `com.nilsreichhard.carbonion.widget`, `required: false`). The Pebble app needs this to route PebbleKit 2 messages from the companion app. The companion app is optional.
- Watch and phone settings, weather and pkjs are unchanged. The companion's PebbleKit listener acknowledges watch messages so it doesn't conflict with pkjs.
