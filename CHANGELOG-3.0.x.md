# Carbon Ion 3.0.3
- Fixes weather not updating since 2.9.5. A bug on the phone side stopped every weather fetch, so the watch kept showing old data. Weather now updates again with every provider.
- If a weather fetch still fails for any reason, the watch keeps the last weather that is still recent, and the next update tries again normally instead of getting stuck.
- If the watch can't reach the phone for a weather update, it tries once more about 30 seconds later.
- MET Norway: rain and snow amounts, rain chance and thunder now line up with the right hour, the same way as Open-Meteo, and the current hour shows its rain too.
- OpenWeatherMap and WeatherAPI: thin cloud cover is drawn as normal clouds. These providers have no data to tell how thick clouds are.
- Split clouds: a cloud layer can never show more cloud than the total cover, so Split and Total agree.
- 12-hour clock: single-digit hours (1:00 to 9:59) are now centred on the screen, and the digits keep their places while the time changes.
- Step count: two-digit counts (10K and up) use the same size as 1K–9K, and the count stops at 99K instead of being cut off.
- Small speed-ups when the time is redrawn.
- (Released as 3.1.0 together with the new Leading zero setting; see CHANGELOG-3.1.0.md.)

# Carbon Ion 3.0.2
- The Quiet Time and Bluetooth icons moved another pixel left, so there is a clear 2 px gap to the hour digits.
- The step count is right-aligned with a fixed margin from the screen edge (on Pebble Round 2 it follows the curve of the bezel), so counts like 12K no longer sit on the edge, and it keeps clear of the time.

# Carbon Ion 3.0.1
- The fog icon and its range line are a little darker on light screens and a little brighter on dark screens, so fog is easier to see.
- Light rain on light screens is a stronger blue (still clearly lighter than heavy rain).
- The Quiet Time and Bluetooth icons moved 1 px left so they no longer touch wide hour digits.
- The time sits in a fixed-width box: wide times like 22:22 no longer run into the icons or the step count, and thin digits like 11:11 no longer shift the clock sideways.

# Carbon Ion 3.0.0

## Clouds
- Clouds now show how thick they are: **light** (pale, checkered), **normal**, or **thick** (darker, checkered). By day this comes from how much sunshine gets through compared with a clear sky. At night, and for providers without sunshine data, it comes from rain, the cloud layers, cloud cover and (Tomorrow.io) the cloud base.
- New setting "Cloud thickness": On (default) or Off.
- Heavy hours stay solid: a thunderstorm, or heavy rain or snow (from 2.5 mm/h rain or 1 mm/h snow) makes a cloud dark grey on light screens and light grey on dark screens.
- Split clouds: thin cloud thins every layer; thick cloud and heavy rain mark the lowest layer, a thunderstorm marks every layer.
- Fog hours are no longer drawn dotted: the fog event marks them. The light-blue snow cloud is gone.
- Clouds and sun rays sit 2 px closer to the timeline.

## Rain chart
- New colours: blue for rain, grey for snow, teal for mixed and freezing rain, pale lavender for hail. Heavier rain is brighter on dark screens and deeper on light screens; heavy snow is white on dark screens and dark grey on light ones.
- Light and heavy now come from the forecast amount (heavy rain from 2.5 mm/h, heavy snow from 1 mm/h).
- New setting "Rain bar height": Probability (default) or Amount. With Amount a taller bar means more rain (full height at 8 mm/h) and the chance shows as shading: solid from 70 %, checkered from 40 %, dotted below.
- Thunderstorm bars show their rain or hail colour; the thunder event marks the storm.
- The rain chart is taller: 5 px on Pebble Time 2 and 6 px deeper on Pebble Round 2. The time, date and temperature graph don't move.

## Fixes
- The forecast is kept when the watchface updates, instead of starting empty until the next fetch.
- Heavy thunderstorms from MET Norway, OpenWeatherMap and WeatherAPI are no longer shown as hail.
- Light snow showers no longer count as heavy snow; rain and snow mixed counts as mixed; snow grains count as snow.
- Lower memory use while drawing the weather.

## 2.9 highlights (recap)
- Pebble Round 2: timeline, clouds, rain and temperature follow the round display as arcs.
- Weather events for thunder, snow, strong wind gusts, tornado and fog, each with its own sensitivity.
- Seven weather providers, three of them without an API key; the settings adapt to each provider.
