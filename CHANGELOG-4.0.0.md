## Carbon Ion 4.0.0

Changes since 3.3.0 (current store version). Watchface 4.0.0 and Android companion app 4.0.0 (versionCode 13).

## Watchface

**Hide the feels-like line (new setting)**
- New setting **Show feels-like line** under Timeline, between "Temperature fill" and "Now line". It is on by default, so nothing changes unless you turn it off.
- Off: the dashed feels-like line is no longer drawn on the temperature chart, on Pebble Time 2 and Pebble Round 2. The chart is then scaled to the real temperature only, so the curve uses the full chart height.
- Saved settings from older versions keep the line on.

**More accurate battery markers**
- Fixed: markers often showed about 4 hours too late, after another app had been open while the battery dropped a percent. The watchface wrongly treated that as a coarse 10 %-style battery reading. Pebble Time 2 and Pebble Round 2 report every 1 %, so the estimate now counts from the exact moment each percent is reached.
- New: Carbon Ion learns your daily pattern, for example more drain during the day and less at night. After about 3 days it uses that pattern for the marker times, so markers that land in the evening or overnight are placed more accurately. Until then it uses your average drain, as before.
- A small 1–2 % rise in the battery reading (normal fuel-gauge wobble) no longer moves the markers.
- After you return from another app, the estimate continues from when the battery most likely changed, not from the moment you came back.
- In simulations of a typical 19-day battery, the typical marker error dropped from about 5 hours to about 2 hours.

**Battery markers at 20, 10, 5 and 0 %**
- The markers now sit at fixed levels on both watches: 20 % (yellow), 10 % (orange), 5 % (red) and 0 % (deep red). The 5 % marker replaces the old 4 % one, so Pebble Time 2 and Pebble Round 2 behave the same.
- Choose from: 20 / 10 / 5 / 0 % (default), 10 / 5 / 0 %, 5 / 0 %, 10 / 0 %, 0 % and None. The 10 / 0 % and 0 % options are new.
- Your saved choice carries over: 20 / 10 / 4 / 0 % becomes 20 / 10 / 5 / 0 %, 10 / 4 / 0 % and 10 / 4 % become 10 / 5 / 0 %, 4 / 0 % becomes 5 / 0 %, and None stays None.

**Solid fills on Pebble Round 2**
- Fixed: on the round face, filled areas (temperature/daylight fill, rain bars, cloud band, battery bar) looked granular, with scattered background-coloured dots. The curved bands left about 5–6 % of their pixels unpainted. Every pixel is now filled, so fills are solid in light and dark themes. Pebble Time 2 is unchanged.

**Technical**
- Battery logic moved to `src/c/modules/batt_eta.c`, with host tests. New persist key 12 stores the hour-of-day profile (52 bytes). Existing battery history and learned rate are kept.
- `SETTING_TIMELINE_BATTERY` values are now the same on the watch and the Android app: 0 = 20/10/5/0, 1 = 10/5/0, 2 = 5/0, 7 = 10/0, 5 = 0, 3 = None. Old values are mapped on the phone and on the watch: 4 → 1, and the old Android values 6 → 0 and 8 → 3.
- Round arc warp (`src/c/ui/arc.c`): 672 angular samples instead of 336, rounded to the nearest pixel (+1.7 KB RAM on Pebble Round 2).
- New message key `SETTING_FEELS_LIKE` (10097, 1 = show, default). Watch-side only, so changing it does not refetch the weather. Stored as a new last byte in the saved settings, so older saved settings load with the line shown.

## Android companion app

Changes since 1.1.8. The app version jumps to 4.0.0 to match the watchface.

**Hide the feels-like line (new setting)**
- The same **Show feels-like line** toggle is in the app's settings, between "Temperature fill" and "Now line", on by default. It uses the same setting as the watch.
- Off: the dashed feels-like line is hidden on the widget in both styles (Modern and Classic), in the app's widget view, and the chart is scaled to the real temperature only. The hour details in the app still show the feels-like value.

**Battery markers: same choices as the watch**
- The phone battery markers use the watch's list: 20 / 10 / 5 / 0 % (default), 10 / 5 / 0 %, 5 / 0 %, 10 / 0 %, 0 % and None. The 20 / 0 % option has been removed. "0 % only" is now called "0 %", and "Off" is now "None".
- Your saved choice carries over: 20 / 0 % becomes 20 / 10 / 5 / 0 %, Off becomes None, and the rest stay as they were. The colours are unchanged: 20 % yellow, 10 % orange, 5 % red, 0 % dark red.

**Quieter "saved location" note**
- When the phone location could not be refreshed, the widget's city line now shows only the city while the saved location is less than 3 hours old. From 3 hours on it shows a short note with the age, for example `Ollon · 5 h` (before, `Ollon · saved …` appeared after just 30 minutes). TalkBack reads it as "location saved 5 h ago".
