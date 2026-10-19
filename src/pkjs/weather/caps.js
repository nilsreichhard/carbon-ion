/**
 * Weather provider capabilities: single source of truth for the JS fetch
 * logic (weather/index.js, settings.js) AND the Clay page (custom.js gets this
 * table via clay.meta.userData.wxCaps; Clay's custom function is serialised
 * and cannot require modules).
 *
 *   label, layers (3 cloud layers), horizon (h ahead), key (needs API key),
 *   minInterval (min refresh, minutes), src (attribution), url/hint (key),
 *   notes (short limitation notes), gust (hourly wind gusts → wind event, 2.9.3),
 *   tornado (reports WMO 19 / 99 → tornado event, 2.9.3),
 *   strongStorm (can tell strong storms: hail codes, CAPE or thunder probability, 2.9.4),
 *   fogVis (hourly visibility → stricter fog levels, 2.9.4)
 *
 * @license GPL-3.0-or-later
 */
module.exports = {
	openmeteo: { strongStorm: true, fogVis: true, tornado: true, gust: true, label: 'Open-Meteo', layers: true, horizon: 60, key: false, minInterval: 15,
		src: 'Open-Meteo.com (CC BY 4.0)' },
	metno: { strongStorm: true, fogVis: false, tornado: false, gust: true, label: 'MET Norway (yr.no)', layers: true, horizon: 60, key: false, minInterval: 30,
		src: 'MET Norway (CC BY 4.0)',
		notes: 'Rain chance is estimated from the amount. Updates about every 30 min.' },
	geosphere: { strongStorm: true, fogVis: true, tornado: true, gust: true, label: 'GeoSphere Austria', layers: false, horizon: 57, key: false, minInterval: 30,
		src: 'GeoSphere Austria (CC BY 4.0), gaps from Open-Meteo.com',
		notes: 'Alps region only (43–52° N, 5.5–22° E); elsewhere Open-Meteo is used. Rain chance, events, fog, gusts and past hours come from Open-Meteo.' },
	meteoblue: { strongStorm: false, fogVis: true, tornado: false, gust: false, label: 'Meteoblue', layers: true, horizon: 60, key: true, minInterval: 60,
		src: 'meteoblue (licence of your API plan)',
		url: 'https://www.meteoblue.com/en/weather-api',
		hint: 'Trial key: meteoblue.com → Weather API. Needs basic-1h (clouds-1h for 3 layers). Credits are limited, so refresh is at most hourly.' },
	tomorrow: { strongStorm: false, fogVis: true, tornado: false, gust: true, label: 'Tomorrow.io', layers: false, horizon: 60, key: true, minInterval: 30,
		src: 'Tomorrow.io (terms of service)',
		url: 'https://app.tomorrow.io/development/keys',
		hint: 'Free key: app.tomorrow.io → Development → API keys (500 calls/day).' },
	owm: { strongStorm: true, fogVis: true, tornado: true, gust: true, label: 'OpenWeatherMap', layers: false, horizon: 54, key: true, minInterval: 30,
		src: 'OpenWeather (terms of service)',
		url: 'https://home.openweathermap.org/api_keys',
		hint: 'Any free key works (5-day / 3-hour forecast, interpolated to hourly). Turn on One Call 3.0 only if you subscribed to it.',
		notes: 'Free plan: 3-hour steps interpolated to hourly. Fog from codes 741 / 701 + visibility.',
		onecallHorizon: 47 },
	weatherapi: { strongStorm: true, fogVis: true, tornado: false, gust: true, label: 'WeatherAPI.com', layers: false, horizon: 54, key: true, minInterval: 15,
		src: 'WeatherAPI.com (terms of service)',
		url: 'https://www.weatherapi.com/my/',
		hint: 'Free key: weatherapi.com → sign up → My account. Free plan covers 3 days.',
		notes: 'Rain chance = max(chance of rain, chance of snow).' },
};
