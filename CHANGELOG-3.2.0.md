## Carbon Ion 3.2.0

Changes since 3.1.2 (current store version).

**Display**
- New high-wind icon: two gust lines, the upper with a flat hook and the lower with an open curl (adapted from the Lucide wind icon), now green (dark green on light, bright green on dark) so it stands apart from rain, snow and storm colours.
- Rain chart setting (was "Rain bar height") now has three options: Probability, Amount + checked dots probability (the previous Amount look), and the new Amount + line probability: solid bars for the amount and a thin orange line for the chance, drawn down from the top like the bars (a lower line means a higher chance). Each option is explained under the setting; your current choice is kept.

**Weather**
- No more snow when it is too warm: where the provider gives humidity, snow is checked against the wet-bulb temperature (2 °C or more: rain; 0.5 °C or less: snow; in between: rain and snow mix). Without humidity: below 3 °C unchanged; 3–7 °C rain, or mix when snowfall is forecast; 7 °C or more always rain. Applies to the timeline, the snow event and the forecast's current icon; observed current conditions (OpenWeatherMap, WeatherAPI) and sleet/freezing rain are not changed.
- The current hour on the timeline follows the live 15-minute reading (Open-Meteo, plus OpenWeatherMap and WeatherAPI current conditions). If nothing is falling now, the hour shows no amount and its rain chance is capped at 10 %; if the reading says rain or drizzle, the hour always shows at least a small bar.
- Past hours with MET Norway, OpenWeatherMap, Tomorrow.io, WeatherAPI and Meteoblue now come from Open-Meteo's latest run instead of the old forecast saved at the previous refresh (the saved forecast is only a fallback).
- Fixed: a current temperature of exactly 0 °C or a clear-sky current reading from Open-Meteo was ignored and replaced by the hourly value.

**Settings (provider & model)**
- API keys are remembered per provider: switching between OpenWeatherMap, Tomorrow.io, WeatherAPI and Meteoblue fills in that provider's own key (or leaves it empty) instead of sending the previous provider's key, which failed and silently fell back to Open-Meteo.
- Changing provider also drops the previous provider's saved past hours.
- Refresh interval: a provider that needs a longer minimum (Meteoblue 60 min, MET/GeoSphere/OWM/Tomorrow.io 30 min) still raises it, but switching back restores the interval you had chosen.
- Cloud thickness is greyed out (still visible) for providers without the data for it (OpenWeatherMap, WeatherAPI) and comes back when you switch to one that has it.
- Clearer warning on the settings page when the chosen provider failed and Open-Meteo data is shown instead: it names the provider and the reason (rejected key or quota, missing key, location not covered, not reachable) and asks you to check the key when that is the cause.

**GeoSphere Austria**
- Moved to GeoSphere's new 1 km model (nwp-v2-1h-1km; the old 2.5 km dataset is no longer used). GeoSphere now also supplies humidity, snowfall, wind gusts and storm energy itself, and its area is 43–51.5° N, 5–22.5° E. Rain chance, weather codes, fog and past hours still come from Open-Meteo.
