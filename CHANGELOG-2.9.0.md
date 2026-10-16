# Carbon Ion 2.9.0

Everything new since 2.6.4.

## Weather
- New providers: Open-Meteo (free, no key), MET Norway (free), GeoSphere Austria (free), plus Tomorrow.io, OpenWeatherMap (2.5 or One Call 3.0), WeatherAPI and Meteoblue with your own API key.
- Open-Meteo model choice: Best match, MeteoSwiss ICON-CH1/CH2, DWD ICON-D2, GeoSphere AROME, KNMI, DMI, Météo-France AROME HD, ECMWF IFS.
- Fog is shown as a weather event with a small wavy icon. The thunder icon is now a solid bolt.
- The settings page adapts to the chosen provider: it hides options the provider can't support, enforces a minimum refresh interval, and shows attribution, last update and the last error.
- Changing a non-weather setting no longer triggers a new weather fetch, which saves API credits.
- High and low now cover the next 24 hours.
- Offline handling: temperature and condition follow the forecast for the current hour. The offline icon appears after 6 hours, and "--" is shown once the forecast runs out or the data is older than 24 hours.
- Day and night switch at the exact sunrise and sunset minute, using the location's time zone. Polar day and night are drawn correctly.

## Layouts
- Pebble Round 2 (gabbro): the timeline, clouds, rain and temperature follow the curve of the display as concentric arcs. Icons rotate with the arc at their normal size, and labels read min, current (bold) and max.
- Pebble 2 Duo (flint): black-and-white layout.

## Calendar
- New "Next alarm" source for calendar rows, drawn with a diamond marker.
- Better recurring events: BYSETPOS, yearly rules, excluded dates, moved and cancelled occurrences.
- Correct time zones and DST, including quoted TZIDs.
- Shows the 12 nearest events, with timed events before all-day ones. Size and event limits protect the phone.

## Battery
- New markers at 20 / 10 / 4 / 0 %, with a None option. The drain estimate updates once per discharge cycle.

## Controls
- Wrist flick (off by default) either cycles the range (12/18/24/36/48h) or toggles 1 vs 3 cloud layers. It also turns on the backlight.
- Optional high-contrast colours while the backlight is on.

## Fixes and reliability
- Each setting is sent to the watch once, so settings messages are smaller.
- Weather refresh limits are respected even when the face relaunches often.
- Calendar URLs and API keys are masked in logs, the calendar proxy has been removed, and "Clear cache" also clears the logs.
- Fixes for hourly data alignment, half-hour time zones, stale high/low values, gabbro drawing artifacts and memory use.
