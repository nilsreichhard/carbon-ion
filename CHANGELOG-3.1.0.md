# Carbon Ion 3.1.0
Everything since 2.9.5.

## Fixes
- **Weather updates again.** Since 2.9.5 a bug on the phone side stopped every weather fetch, so the watch kept showing old data. It works again with every provider.
- If a fetch still fails, the watch keeps the last weather that is still recent and tries again normally at the next update instead of getting stuck. If the watch can't reach the phone, it asks once more about 30 seconds later.
- The forecast is kept when the watchface updates, instead of starting empty until the next fetch.
- MET Norway: rain amounts, rain chance and thunder now sit on the right hour (as with Open-Meteo), and the current hour shows its rain.
- Heavy thunderstorms from MET Norway, OpenWeatherMap and WeatherAPI are no longer shown as hail. Light snow showers no longer count as heavy snow, rain and snow together count as mixed, and snow grains count as snow.
- Lower memory use while drawing the weather.

## Rain
- New colours: blue for rain, grey for snow, teal for mixed and freezing rain, pale lavender for hail. Heavier rain is brighter on dark screens and deeper on light screens. Light rain on light screens is a stronger blue.
- Light and heavy come from the forecast amount (heavy rain from 2.5 mm/h, heavy snow from 1 mm/h).
- New setting "Rain bar height": Probability (default) or Amount. With Amount, a taller bar means more rain (full at 8 mm/h), and the chance shows as shading: solid from 70 %, checkered from 40 %, dotted below.
- Thunderstorm bars show their rain or hail colour, and the thunder event marks the storm.
- The rain chart is taller: 5 px on Pebble Time 2 and 6 px on Pebble Round 2.

## Clouds
- Clouds show how thick they are: light (pale, checkered), normal, or thick (darker, checkered). By day this comes from how much sunshine gets through. At night, and for providers without sunshine data, it comes from rain, the cloud layers and cloud cover. OpenWeatherMap and WeatherAPI show normal clouds, since they have no data for thickness.
- New setting "Cloud thickness": On (default) or Off.
- A thunderstorm, or heavy rain or snow, makes a cloud solid dark (light grey on dark screens).
- Split clouds: a layer never shows more cloud than the total cover. Thin cloud thins every layer, thick cloud and heavy rain mark the lowest layer, and a thunderstorm marks every layer.
- Fog is marked by the fog event instead of dotted clouds, and its icon and line are easier to see. The light-blue snow cloud is gone. Clouds and sun rays sit 2 px closer to the timeline.

## Clock & layout
- The time sits in a fixed-width box: wide times like 22:22 no longer run into the icons or the step count, and thin digits like 11:11 don't shift the clock.
- Without a leading zero (e.g. 9:59), the time stays centred and doesn't jump around as the minutes change.
- The Quiet Time and Bluetooth icons keep a clear gap to the hour digits.
- The step count is right-aligned with a fixed margin from the edge (following the bezel on Pebble Round 2). 10K and up use the same size as 1K–9K, and the count stops at 99K instead of being cut off.

## Settings
- New "Leading zero" setting: Auto (default: 24-hour shows 09:41, 12-hour shows 9:41), On or Off. It works with both clock formats and applies right away.
