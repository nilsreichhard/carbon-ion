# Carbon Ion Android widget

A small Kotlin app whose job is a resizable home-screen widget that looks
like the Carbon Ion watchface (emery layout): the daylight strip with noon,
moon, now and phone-battery markers, clouds (Total or Split 3-layer, with
thickness) and sun rays, rain bars (Probability / Amount + dots / Amount +
line), the weather-event row (thunder, wind, fog, snow, tornado, missing
data), the temperature meteogram, and in the middle the city with the
condition icon, a live `TextClock`, the date and the phone's next alarm. It
adapts to the space it gets (see *Adaptive layout*); the default size is
4x3 cells.

Two styles (Settings > Widget > Style):

- **Modern (default)** — `ModernRenderer`: same layout, rows, data, event
  logic and settings as the watch, drawn anti-aliased at the device's real
  pixel density in full colour: soft billowy cloud bands (Split: three tones;
  opacity by cover, darker when thick, slate when heavy), soft sun rays, rain
  bars with rounded ends and a light→heavy colour ramp (snow, mix, hail have
  their own), probability as opacity (Amount + dots) or a smooth orange line
  with dots (Amount + line), vector event icons in the watch's colours, a
  monotone-cubic temperature curve with a cold-blue→warm-red gradient fill,
  crisp now line and dot, min/current/max in the chosen text font, a
  day/night gradient daylight bar with sunrise/sunset ticks, a sun dot, a
  moon-phase glyph and small battery pips. Corners follow the launcher's
  widget radius. Light/dark palettes work over wallpapers (soft text shadow,
  optional radial scrim) and on solid colours; optional Material You accent
  (Android 12+) for the now line and the night track.
- **Classic (watch)** — `WidgetRenderer`: the Pebble's pixel look. In dark mode the
  heavier cloud layers are drawn brighter than on the watch so they stay visible on black.

It lives in the watchface repo on purpose. The weather logic and the settings
page are **not copied**: the widget runs the same `src/pkjs/` code as the
watch's phone JS (providers, settings dict, geocoding, Clay config and
`config/custom.js`), so a fix there applies to both.

## Build

Requirements: JDK 17, the Android SDK (platform 36, build-tools 36) and Node
(used only to bundle the JS).

```sh
cd android
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # → PNG previews in app/build/outputs/preview/
```

Release build (R8, not debuggable): `./gradlew assembleRelease` →
`app/build/outputs/apk/release/app-release.apk`. It is signed when
`~/.carbon-ion-keys/keystore.properties` exists (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`; override with `-Pcarbon.signing=/path/to/file`),
otherwise it is left unsigned. Keystores never go in the repo.

`local.properties` (git-ignored) points at the SDK (`sdk.dir=…`). If Gradle
can't find Node, pass `-Pcarbon.node=/path/to/node`.

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then
add **Carbon Ion weather** from the widget picker. The settings open when the
widget is added (optional on Android 12+). Tap the clock or the next alarm to
open the phone's alarms; tap anywhere else to open the Carbon Ion app screen
(also the launcher icon). The app screen shows the widget itself (the same
RemoteViews, at the placed widget's shape); touch or drag on its timeline,
clouds, rain or temperature chart to see that hour's details right below it
(time, temperature and feels-like, rain, cloud layers, wind, sun). The
selection stays when you lift your finger; a tap in the side margin clears it.
TalkBack reads it as a slider: swipe up or down to step through the hours.
The range is the widget's own (Settings, forecast hours). Below: now,
where the data is for, the next days and the source; Refresh and Settings.

## Settings (same as the watch)

`SettingsActivity` builds its page from `src/pkjs/config.js`: same labels,
order, defaults, descriptions and options. `config/custom.js` runs headless
against a small fake Clay API, so provider-dependent behaviour is the watch's
own: model list per provider, API key rows, options a provider can't supply
removed, **Cloud thickness greyed out** without the data, Amount rain modes
hidden without mm, fetch interval minimum, and so on. Saving does what
`index.js` does on `webviewclosed` (flatten, remember per-provider keys,
clear the cache when the provider changes) and stores `clay-settings` in the
JS localStorage (`SharedPreferences` `carbon_js_store`). The fetch then gets
the same options as the watch, and `settings.buildSettingsDict` gives the
same integer dict the watch receives, which the renderer reads
(`WidgetSettings`).

Wired into the drawing: theme (light/dark), range, cloud mode Total/Split,
cloud sensitivity, cloud thickness, sunlight bars level, rain sensitivity,
rain chart (Probability / Amount + checked dots / Amount + line with the
orange chance line), weather events (Range + icon / Icon only / None; thunder
level, tornado, snow level, fog level, wind gusts), battery markers (20/10/4/0
and the other levels), temperature unit, temperature fill, now line, leading
zero (clock and alarm) and date format.

**Not shown on the phone (watch-only):** wrist flick and flick action, the
whole Calendar section (timeline events, ICS feeds, colours, places),
backlight colours, disconnect alert, Quiet Time icon, steps, debug
info. They keep their stored values, so nothing is lost if the same storage is
ever shared with the watch.

**Widget-only rows:**

- *Location*: with "Fixed coordinates" off, the widget uses the phone's coarse
  location (`ACCESS_COARSE_LOCATION`, platform `LocationManager`, no Play
  services). Without permission or a fix it uses the last saved phone
  location, and Villars-sur-Ollon (46.30 N, 7.05 E) before that. With "Fixed
  coordinates" on, a **Find** box (Open-Meteo geocoding) fills Latitude and
  Longitude. The city name comes from `src/pkjs/geo.js` exactly like the
  watch (BigDataCloud reverse geocoding, cached). For Villars' coordinates
  that gives "Ollon", as on the watch.
- *Background*: Transparent (default) or a colour (presets or `#RRGGBB`)
  with opacity. On a transparent background, pick the Dark theme for dark
  wallpapers. Text has a soft shadow and the chart's background pixels are
  cleared, so the wallpaper shows through.

## Adaptive layout

Every layout keeps the watch's row grid (emery rows) and is scaled to the
widget height; `WidgetLayout` picks which strips are shown from the height the
launcher reports (`OPTION_APPWIDGET_SIZES` on Android 12+, one RemoteViews per
reported size, so portrait and landscape each get their own; min width × max
height before 12):

| Layout  | Height        | Rows (top / time / gap / meteogram) | Cells on Pixel Launcher |
|---------|---------------|-------------------------------------|-------------------------|
| SHORT   | under 280 dp  | 73 / 90 / 6 / – = 169               | 1–2 rows (and 3 rows in landscape) |
| DEFAULT | 280–399 dp    | 73 / 90 / 9 / 56 = 228 (emery)      | 3 rows (the default, 4x3) |
| TALL    | 400 dp and up | 85 (rain +12) / 90 / 9 / 86 = 270   | 4+ rows |

The thresholds are calibrated on Pixel Launcher (Android 15, 1080×2400 @
420 dpi), which reports ≈ 90 dp per column and ≈ 120 dp per row: 2x2 =
172×224, 4x2 = 360×224, 4x3 = 360×344, 4x4 = 360×464, 5x3 on the 5x5 grid
= 369×341, 4x3 landscape = 625×208 dp.

Width never squashes the chart. The bitmap keeps the widget's aspect at an
integer number of pixels per row (crisp at the native density), and when an
hour column would be narrower than 5 rows the range steps down (48 → 36 → 24
→ 18 → 12 h): a 2x2 shows 18 h. The clock is a `TextClock` with the watch's
leading-zero rule (Auto: 24 h "09:41", 12 h "9:41"), following the phone's
12/24 h setting; its size is the watch's 62 rows, capped so it fits narrow
widgets. City and date are 16 rows. `widget_carbon.xml`, `widget_short.xml`
and `widget_tall.xml` carry the row weights. The widget is resizable both
ways (min 110×80 dp); `targetCellHeight` 3 and `minHeight` 180 dp make 4x3
the default. The picker shows `res/drawable-nodpi/widget_preview.png` (light)
and `res/drawable-night-nodpi/widget_preview.png` (dark), through
`layout/widget_preview_layout.xml` on Android 12+. Both are rendered by the
real widget code in `WidgetPreviewTest` (Modern, sample Villars data, alarm,
battery markers), which fails when the committed PNGs no longer match the
renderer. Regenerate after a visual change:
`./gradlew :app:testDebugUnitTest --tests '*WidgetPreviewTest*' -Pcarbon.updatePreview=true`.

## Events, markers, icons (ported from the C)

- **Events** (`event_layer.c`): priority tornado > thunder > snow > wind >
  fog, runs merged into spans, a red "missing data" icon past the data. Range
  + icon draws the span line with end ticks (fog with the checker line), Icon
  only draws the icons, None drops the row and lets the rain chart grow as on
  the watch. Bolt / wind / snow / tornado are the watch's bitmasks, fog the
  wave. Colours per theme as on the watch (WindsorTan/Yellow, IslamicGreen/
  ScreaminGreen, CobaltBlue/Cyan, Orange, DarkGray). The data are the
  payload's `fog_bits`, `wind_bits` and `storm_bits`.
- **Daylight strip** (`daylight_layer.c`): sunrise/sunset brackets
  (DST-aware), noon marker and moon phase at midnight (integer moon
  algorithm), the red now needle.
- **Battery markers** mapped to the **phone** battery (`BatteryTracker`): the
  watch's marks at 20 / 10 / 4 / 0 % placed where the charge is expected to
  reach them. Drain rate measured from the last two days of samples (≥ 1 h and
  ≥ 3 %), else ~24 h per full charge (the watch assumes ~20 days); anchored
  at the last percent change so they don't slide; hidden while charging. The
  *Battery markers* setting picks the levels as on the watch.
- **Condition icon** right of the city (`time_layer.c`): the watch's
  weather-code → IcoMoon glyph mapping, day/night variants.

## Fonts

Settings > Widget has **Clock font**, **Clock size** (Small / Normal /
Large) and **Text font** (city, date, temperatures, alarm). "Style default"
is Light clock + System text for Modern, Condensed bold for Classic.

RemoteViews can't call `setTypeface`, so each option is a small layout with
a fixed `android:fontFamily` (`res/layout/clock_*.xml`, `text_*.xml`),
added into the time block with `RemoteViews.addView`; the clock stays a live
`TextClock`. Verified on the Pixel Launcher (API 35):

| Option | Clock (live TextClock) | Text |
|---|---|---|
| System (Roboto / device sans), Thin, Light, Medium, Condensed bold, Condensed light, Serif, Monospace | yes | yes (RemoteViews) |
| Rounded (Varela Round), Outfit, Inter — bundled, SIL OFL 1.1 | **no** | yes, drawn into the chart bitmap |

Bundled `@font` references inside RemoteViews are ignored by the launcher (it
falls back to Roboto), so the bundled fonts are offered for text only: the
city row and the date are drawn into the bitmap (the RemoteViews text stays
invisible as the tap target) and a Doze-tolerant alarm redraws the widget
after midnight (also on time / time-zone changes). Font files are in
`app/src/main/res/font/`, licences in `app/src/main/assets/licenses/OFL-fonts.txt`
(Varela Round is the unmodified upstream file because of its Reserved Font
Name; Outfit and Inter are Google Fonts' Latin builds). The Pebble fonts
(Gothic, Bitham, LECO) only exist in the firmware resource pack, so they are
not used. Icons use the repo's `resources/fonts/IcoMoon-Regular.ttf`, copied
into the generated assets by Gradle.

## Next alarm

`AlarmManager.getNextAlarmClock()` is shown as an alarm icon plus
"Fri 06:30" (12 h: "Fri 6:30 AM"). It refreshes on
`ACTION_NEXT_ALARM_CLOCK_CHANGED`. **Placement:** in the clock's right flank,
vertically centred on the digits (the watch's step-count slot, which the
phone doesn't use), so the row stack stays identical to the watch. One line
when it fits, else day over time; the clock gives up to a quarter of its
size to make room, and the alarm is hidden on very narrow widgets (2x2) and
when no alarm is set.

## How the JS reuse works

1. `tools/bundle-pkjs.js` is a ~70-line CommonJS bundler with no npm
   dependencies. On every build, the Gradle task `bundlePkjs` runs it. It
   starts at `tools/widget-entry.js` and follows the relative `require()`s
   into `../src/pkjs/` (weather, settings, geo, net, config.js,
   config/custom.js). It writes one generated asset, `carbon-weather.js`,
   into `app/build/generated/`.
2. `tools/widget-entry.js` shims `localStorage`, `navigator`, `setTimeout`
   and `XMLHttpRequest` on four host globals: `__carbonHttpGet`,
   `__carbonStoreGet/Set` and `__carbonLog`. It exposes `carbonFetchWeather`
   (index.js options, retries, error and last-fetch records, city lookup),
   `carbonLocation` and the settings-page functions.
3. `JsRuntime.kt` evaluates the asset in QuickJS
   (`wang.harlon.quickjs:wrapper-android`), with `HttpURLConnection`
   (synchronous, worker thread only) and SharedPreferences behind the
   globals.
4. `tools/run-node.js` runs the same bundle on a Mac, with curl as HTTP:
   `node tools/run-node.js [lat lon] [--json out.json] [--record dir]`.
   The test fixtures came from it.

The drawing is the only part that exists twice: C on the watch, and
`WidgetRenderer.kt` here. `CarbonStyle.kt` names the C source of every
colour, threshold and offset, and `PrecipMap.kt` ports `precip_map.h`.

## Tests (JVM, Robolectric native graphics)

- `RenderVariantsTest`: light, dark, Split, Amount + dots, Amount + line,
  alarm shown/hidden, plus alarm and pattern formatting.
- `WidgetCompositeTest`: the real `RemoteViews` applied and drawn over sample
  wallpapers, and the picker preview.
- `WidgetLayoutTest`: breakpoints and range fitting.
- `ModernRendererTest`: device-pixel size and anti-aliasing, Split/Total and
  rain modes, coloured vector event icons, dark theme and accent, monotone
  curve, style switch (Modern vs Classic bitmap), clock fonts as live
  `TextClock`s, bundled text fonts drawn into the bitmap.
- `UiOptionsTest`: defaults, encode/decode and persistence, bundled fonts
  text-only, every font's layouts and typefaces, clock size.
- `EventIconsTest`: event priorities and flags, spans and the missing-data
  span, icons drawn / icon only / off / hidden, moon phase, Split rows.
- `BatteryTrackerTest`: drain rate and anchoring.
- `SampleFixturesTest`: crafted Open-Meteo responses (sunny, snow,
  thunderstorm + gusts, fog, plus the recorded Villars one), run through the
  watch JS; writes `app/build/outputs/samples/payloads/*.json` for the
  emulator.
- `JsRuntimeTest`, `SettingsScreenTest` and `SampleFixturesTest` run the
  bundled watch JS in a desktop build of the same QuickJS JNI library,
  against recorded responses (`src/test/resources/fixtures`). Since 1.1.6
  they fail (with these instructions) when that library is missing, instead
  of skipping silently; `-Pcarbon.skipQuickJs=true` skips them on purpose.
  To build it on macOS (Homebrew `cmake` and `ninja`):

  ```sh
  mkdir -p ~/Library/Caches/carbon-quickjs && cd ~/Library/Caches/carbon-quickjs
  git clone --depth 1 -b 3.2.3 https://github.com/HarlonWang/quickjs-wrapper qjs-3.2.3
  cmake -G Ninja -S qjs-3.2.3/wrapper-java/src/main -B build && ninja -C build
  cp build/libquickjs-java-wrapper.dylib .
  ```

  On Linux the same `cmake`/`ninja` build gives
  `build/libquickjs-java-wrapper.so` (needs a JDK for the JNI headers); pass
  it with `-Pcarbon.quickjs=/path/to/libquickjs-java-wrapper.so`. Any other
  build location works the same way.

## Samples on the emulator

The screenshots come from a headless emulator (`sdkmanager "emulator"
"system-images;android-35;google_apis_playstore;arm64-v8a"`, an AVD with a
1080×2400 / 420 dpi Pixel profile, `emulator -avd carbon_pixel -no-window
-gpu swiftshader_indirect`) with the widget on the real Pixel Launcher,
resized with `adb shell input motionevent` and captured with `adb exec-out
screencap -p`. The debug build adds `DebugSamplesReceiver` (not in release):

```sh
adb push app/build/outputs/samples/payloads/*.json /data/local/tmp/cs/
adb shell run-as com.nilsreichhard.carbonion.widget sh -c 'mkdir -p files/samples; cp /data/local/tmp/cs/* files/samples/'
adb shell am broadcast -n com.nilsreichhard.carbonion.widget/.DebugSamplesReceiver \
    -a com.nilsreichhard.carbonion.widget.DEBUG_LOAD --es scenario thunder \
    --es set SETTING_LIGHT_THEME=0,SETTING_WEATHER_EVENT_DISPLAY=1 --es bg 1A237E --ei opacity 85
```

`--es ui style=modern,clock=thin,text=outfit,size=large,scrim=1,accent=wallpaper`
(or `reset,…`) sets the look. `DEBUG_WALLPAPER --es file wall.png` sets a wallpaper from the same folder.
Battery: `adb emu power ac off; adb emu power capacity 35`. Alarm: the Clock
app's `SET_ALARM` intent.

## Not done yet

- Calendar rows, the timeline alarm diamond (drawn on the watch only with
  calendar rows), steps, Bluetooth and Quiet Time icons: watch-only.
- Pebble fonts, and bundled fonts on the live clock (see *Fonts*).
- Background refreshes use the last known location. A fresh fix is taken only
  when settings are saved, because Android limits background location.
- WorkManager's minimum period is 15 min, so "Refresh" maps to 15 / 30 / 60.
- Settings are per app, not per widget: two widgets show the same settings.
- Tested on the JVM, with Node, and on the Android 15 emulator (Pixel
  Launcher), not on a physical phone yet.
