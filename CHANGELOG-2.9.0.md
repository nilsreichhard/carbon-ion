# Carbon Ion 2.9.0

Everything new since 2.6.4.

## Weather
- New providers: Open-Meteo (free, no key), MET Norway (free), GeoSphere Austria (free), plus Tomorrow.io, OpenWeatherMap (2.5 or One Call 3.0), WeatherAPI and Meteoblue with your own API key.
- Open-Meteo model choice: Best match, MeteoSwiss ICON-CH1/CH2, DWD ICON-D2, GeoSphere AROME, KNMI, DMI, Météo-France AROME HD, ECMWF IFS.
- New wind event: hours with strong gusts get a violet wind icon. The threshold is adjustable (Off, 40, 50, 60 or 75 km/h; default 50 km/h, Beaufort 7). Works with every provider except Meteoblue, which has no gust data.
- Fog is shown as a weather event with a small dark-grey icon of two wavy lines. The thunder icon is now a solid bolt.
- Snow events now also show for light and moderate snow, not only heavy snow and snow showers.
- Thunder, snow and fog have sensitivity levels in settings, each with an Off option:
  - Thunder: Any storm (default) or Strong only. Strong means hail, high atmospheric instability (CAPE over 1500 J/kg) or a thunder probability of 50 % or more. Strong only isn't offered for Meteoblue and Tomorrow.io, which don't report storm strength.
  - Snow: Any (default), Moderate and heavy, or Heavy only.
  - Fog: visibility under 1000 m (default), 500 m or 200 m. Fog codes count at every level. MET Norway has no visibility data, so it only offers the default and Off.
  - Thunder and snow levels apply on the watch without a new weather fetch. Previous on/off choices carry over.
- Tornado and wind keep their own settings. The tornado toggle only appears for providers that report tornadoes.
- The settings page adapts to the chosen provider: it hides options the provider can't support, enforces a minimum refresh interval, and shows attribution, last update and the last error.
- Changing a non-weather setting no longer triggers a new weather fetch, which saves API credits.
- High and low now cover the next 24 hours.
- Offline handling: temperature and condition follow the forecast for the current hour. The offline icon appears after 6 hours, and "--" is shown once the forecast runs out or the data is older than 24 hours.
- Day and night switch at the exact sunrise and sunset minute, using the location's time zone. Polar day and night are drawn correctly.

## Layouts
- Pebble Round 2 (gabbro): the timeline, clouds, rain and temperature follow the curve of the display as concentric arcs. Icons rotate with the arc at their normal size, and labels read min, current (bold) and max.

## Calendar
- New "Next alarm" source for calendar rows, drawn with a diamond marker.
- Better recurring events: BYSETPOS, yearly rules, excluded dates, moved and cancelled occurrences.
- Correct time zones and DST, including quoted TZIDs.
- Shows the 12 nearest events, with timed events before all-day ones. Size and event limits protect the phone.

## Battery
- New markers at 20 / 10 / 4 / 0 %, with a None option. The drain estimate updates once per discharge cycle.

## Status icons
- The Quiet Time and Bluetooth-disconnected icons are now solid badges in their colour, with the bell or Bluetooth rune cut out. Both badges have exactly the same size and shape, are aligned on the same left edge with the slash on the same diagonal, and are centred together next to the time.

## Controls
- Wrist flick (off by default) either cycles the range (12/18/24/36/48h) or toggles 1 vs 3 cloud layers. It also turns on the backlight.
- Optional high-contrast colours while the backlight is on.

## Fixes and reliability
- Each setting is sent to the watch once, so settings messages are smaller.
- Weather refresh limits are respected even when the face relaunches often.
- Calendar URLs and API keys are masked in logs, the calendar proxy has been removed, and "Clear cache" also clears the logs.
- Fixes for hourly data alignment, half-hour time zones, stale high/low values, gabbro drawing artifacts and memory use.
