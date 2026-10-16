/**
 * Clay configuration for Carbon Ion (2.8.2: regrouped into Weather /
 * Timeline / Calendar / Battery / Display / Advanced, short sentence-case
 * copy). Message keys are unchanged, so saved settings carry over.
 * Provider-dependent visibility lives in config/custom.js.
 *
 * Double-tap toggle stays hidden until PebbleOS delivers touch to watchfaces
 * (see src/c/modules/touch.c).
 *
 * @author    Nils Reich
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

module.exports = [
	{
		"type": "heading",
		"defaultValue": "Carbon Ion"
	},
	{
		"type": "text",
		"defaultValue": "By Nils Reich"
	},
	{
		"type": "section",
		"items": [
			{
				"type": "heading",
				"defaultValue": "Weather"
			},
			{
				"type": "select",
				"messageKey": "SETTING_WEATHER_PROVIDER",
				"label": "Provider",
				"defaultValue": "openmeteo",
				"options": [
					{
						"label": "Open-Meteo (default)",
						"value": "openmeteo"
					},
					{
						"label": "MET Norway (yr.no)",
						"value": "metno"
					},
					{
						"label": "GeoSphere Austria",
						"value": "geosphere"
					},
					{
						"label": "Meteoblue (key)",
						"value": "meteoblue"
					},
					{
						"label": "Tomorrow.io (key)",
						"value": "tomorrow"
					},
					{
						"label": "OpenWeatherMap (key)",
						"value": "owm"
					},
					{
						"label": "WeatherAPI.com (key)",
						"value": "weatherapi"
					}
				],
				"description": "Keyed providers fall back to Open-Meteo if the key fails. Options below follow what the provider offers."
			},
			{
				"type": "select",
				"messageKey": "SETTING_OM_MODEL",
				"label": "Model",
				"defaultValue": "best_match",
				"options": [
					{
						"label": "Best match (default)",
						"value": "best_match"
					},
					{
						"label": "MeteoSwiss ICON-CH1 · 1 km",
						"value": "meteoswiss_icon_ch1"
					},
					{
						"label": "MeteoSwiss ICON-CH2 · 2 km",
						"value": "meteoswiss_icon_ch2"
					},
					{
						"label": "DWD ICON-D2 · 2 km",
						"value": "icon_d2"
					},
					{
						"label": "GeoSphere AROME · 2.5 km",
						"value": "geosphere_arome_austria"
					},
					{
						"label": "KNMI HARMONIE",
						"value": "knmi_harmonie_arome_europe"
					},
					{
						"label": "DMI HARMONIE",
						"value": "dmi_harmonie_arome_europe"
					},
					{
						"label": "Météo-France AROME HD · 1.5 km",
						"value": "meteofrance_arome_france_hd"
					},
					{
						"label": "ECMWF IFS · global",
						"value": "ecmwf_ifs"
					}
				],
				"description": "Regional models are sharper but shorter; missing hours come from Best match."
			},
			{
				"type": "input",
				"messageKey": "SETTING_WEATHER_API_KEY",
				"label": "API key",
				"defaultValue": "",
				"attributes": {
					"type": "password",
					"autocomplete": "off",
					"placeholder": "API key"
				},
				"description": "Stays on your phone; masked in diagnostics."
			},
			{
				"type": "text",
				"id": "wx_keyhint",
				"defaultValue": ""
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_OWM_ONECALL",
				"label": "Use One Call 3.0",
				"defaultValue": false,
				"description": "Hourly data, 48 h. Needs the separate One Call subscription."
			},
			{
				"type": "text",
				"id": "wx_notes",
				"defaultValue": ""
			},
			{
				"type": "select",
				"messageKey": "SETTING_TEMP_UNIT",
				"label": "Temperature unit",
				"defaultValue": 0,
				"options": [
					{
						"label": "Auto (locale)",
						"value": -1
					},
					{
						"label": "Celsius (°C)",
						"value": 0
					},
					{
						"label": "Fahrenheit (°F)",
						"value": 1
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_FETCH_INTERVAL",
				"label": "Refresh",
				"defaultValue": 15,
				"options": [
					{
						"label": "Every 15 min",
						"value": 15
					},
					{
						"label": "Every 30 min",
						"value": 30
					},
					{
						"label": "Every 60 min",
						"value": 60
					}
				]
			},
			{
				"type": "text",
				"id": "wx_attribution",
				"defaultValue": "<small>Weather data: Open-Meteo.com (CC BY 4.0).</small>"
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_GEOCODE_ENABLED",
				"label": "Detect city name",
				"defaultValue": true,
				"description": "Off shows your own text instead."
			},
			{
				"type": "input",
				"messageKey": "SETTING_LOCATION_OVERRIDE",
				"label": "City text",
				"defaultValue": "",
				"attributes": {
					"placeholder": "e.g. Home",
					"maxlength": 23
				},
				"description": "Leave blank to hide."
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_USE_STATIC_LOCATION",
				"label": "Fixed coordinates",
				"defaultValue": false,
				"description": "Skip GPS for weather and city name."
			},
			{
				"type": "input",
				"messageKey": "SETTING_STATIC_LAT",
				"label": "Latitude",
				"defaultValue": "",
				"attributes": {
					"type": "number",
					"step": "any",
					"placeholder": "47.3769"
				}
			},
			{
				"type": "input",
				"messageKey": "SETTING_STATIC_LON",
				"label": "Longitude",
				"defaultValue": "",
				"attributes": {
					"type": "number",
					"step": "any",
					"placeholder": "8.5417"
				}
			}
		]
	},
	{
		"type": "section",
		"items": [
			{
				"type": "heading",
				"defaultValue": "Timeline"
			},
			{
				"type": "select",
				"messageKey": "SETTING_FORECAST_HOURS",
				"label": "Range",
				"defaultValue": 24,
				"options": [
					{
						"label": "12 h",
						"value": 12
					},
					{
						"label": "18 h",
						"value": 18
					},
					{
						"label": "24 h",
						"value": 24
					},
					{
						"label": "36 h",
						"value": 36
					},
					{
						"label": "48 h",
						"value": 48
					}
				],
				"description": "Hours ahead. A quarter of that is shown as past (24 h → 6 h past)."
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_WRIST_FLICK",
				"label": "Wrist flick",
				"defaultValue": false,
				"description": "Flick runs the action below (max. every 2 s). Also lights the screen."
			},
			{
				"type": "select",
				"messageKey": "SETTING_FLICK_ACTION",
				"label": "Flick action",
				"defaultValue": 0,
				"options": [
					{
						"label": "Cycle range",
						"value": 0
					},
					{
						"label": "Toggle cloud layers",
						"value": 1
					}
				],
				"description": "Range: 12 → 18 → 24 → 36 → 48 h. Layers: 1 ↔ 3 cloud rows."
			},
			{
				"type": "select",
				"messageKey": "SETTING_CLOUD_DISPLAY_MODE",
				"label": "Clouds",
				"defaultValue": 0,
				"options": [
					{
						"label": "Total (1 row)",
						"value": 0
					},
					{
						"label": "Split (3 rows)",
						"value": 1
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_CLOUD_SENSITIVITY",
				"label": "Cloud sensitivity",
				"defaultValue": 1,
				"options": [
					{
						"label": "Very sensitive",
						"value": 0
					},
					{
						"label": "Sensitive",
						"value": 1
					},
					{
						"label": "Balanced",
						"value": 2
					},
					{
						"label": "Insensitive",
						"value": 3
					},
					{
						"label": "Off",
						"value": 4
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_SUNLIGHT_SENSITIVITY",
				"label": "Sunlight bars",
				"defaultValue": 4,
				"options": [
					{
						"label": "Very sensitive",
						"value": 0
					},
					{
						"label": "Sensitive",
						"value": 1
					},
					{
						"label": "Balanced",
						"value": 2
					},
					{
						"label": "Insensitive",
						"value": 3
					},
					{
						"label": "Off",
						"value": 4
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_PRECIP_SENSITIVITY",
				"label": "Rain sensitivity",
				"defaultValue": 1,
				"options": [
					{
						"label": "Very sensitive",
						"value": 0
					},
					{
						"label": "Sensitive",
						"value": 1
					},
					{
						"label": "Balanced",
						"value": 2
					},
					{
						"label": "Insensitive",
						"value": 3
					},
					{
						"label": "Off",
						"value": 4
					}
				],
				"description": "Minimum rain chance for a bar."
			},
			{
				"type": "select",
				"messageKey": "SETTING_WEATHER_EVENT_DISPLAY",
				"label": "Weather events",
				"defaultValue": 0,
				"options": [
					{
						"label": "Range + icon",
						"value": 0
					},
					{
						"label": "Icon only",
						"value": 1
					},
					{
						"label": "None",
						"value": 2
					}
				],
				"description": "Thunder, snow, tornado and fog markers."
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_FOG_INDICATOR",
				"label": "Fog",
				"defaultValue": true,
				"description": "Fog codes or visibility under 1 km, shown as wavy lines."
			},
			{
				"type": "select",
				"messageKey": "SETTING_INFILL_MODE",
				"label": "Temperature fill",
				"defaultValue": 0,
				"options": [
					{
						"label": "Future only",
						"value": 0
					},
					{
						"label": "Past only",
						"value": 1
					},
					{
						"label": "Full",
						"value": 2
					},
					{
						"label": "None",
						"value": 3
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_NEEDLE_MODE",
				"label": "Now line",
				"defaultValue": 0,
				"options": [
					{
						"label": "Top and bottom",
						"value": 0
					},
					{
						"label": "Top only",
						"value": 1
					},
					{
						"label": "Bottom only",
						"value": 2
					},
					{
						"label": "Hidden",
						"value": 3
					}
				]
			}
		]
	},
	{
		"type": "section",
		"items": [
			{
				"type": "heading",
				"defaultValue": "Calendar"
			},
			{
				"type": "select",
				"messageKey": "SETTING_TIMELINE_EVENT",
				"label": "Calendar events",
				"defaultValue": 2,
				"options": [
					{
						"label": "Duration span",
						"value": 2
					},
					{
						"label": "Bar at start",
						"value": 1
					},
					{
						"label": "Off",
						"value": 0
					}
				],
				"description": "Up to 3 rows: an ICS link or the next alarm."
			},
			{
				"type": "text",
				"defaultValue": "<details><summary>How to get an ICS link</summary><small>• Google: Settings → your calendar → Integrate → Secret address in iCal format<br>• Outlook: Calendar → Share → Publish → ICS link<br>• iCloud: Calendar sharing → Public calendar → copy link (webcal:// works)</small></details>"
			},
			{
				"type": "text",
				"defaultValue": "<strong>Row 1</strong>"
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_SOURCE_1",
				"label": "Source",
				"defaultValue": "0",
				"options": [
					{
						"label": "Calendar link",
						"value": "0"
					},
					{
						"label": "Next alarm",
						"value": "1"
					}
				],
				"description": "Next alarm draws a diamond in this row's colour."
			},
			{
				"type": "input",
				"messageKey": "SETTING_CALENDAR_ICS_URL",
				"label": "ICS link",
				"defaultValue": "",
				"attributes": {
					"placeholder": "https://…/basic.ics",
					"autocomplete": "off"
				}
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_COLOR_1",
				"label": "Colour",
				"defaultValue": 1,
				"options": [
					{
						"label": "Cyan",
						"value": 0
					},
					{
						"label": "Blue",
						"value": 1
					},
					{
						"label": "Green",
						"value": 2
					},
					{
						"label": "Red",
						"value": 3
					},
					{
						"label": "Orange",
						"value": 4
					},
					{
						"label": "Purple",
						"value": 5
					},
					{
						"label": "Yellow",
						"value": 6
					},
					{
						"label": "Magenta",
						"value": 7
					},
					{
						"label": "Teal",
						"value": 8
					},
					{
						"label": "Pink",
						"value": 9
					},
					{
						"label": "Lime",
						"value": 10
					},
					{
						"label": "Deep red",
						"value": 11
					},
					{
						"label": "Sky",
						"value": 12
					},
					{
						"label": "Mint",
						"value": 13
					},
					{
						"label": "Indigo",
						"value": 14
					},
					{
						"label": "Gold",
						"value": 15
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_PLACE_1",
				"label": "Position",
				"defaultValue": 0,
				"options": [
					{
						"label": "On timeline",
						"value": 0
					},
					{
						"label": "Above timeline",
						"value": 1
					},
					{
						"label": "Below timeline",
						"value": 2
					}
				]
			},
			{
				"type": "text",
				"defaultValue": "<strong>Row 2</strong>"
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_SOURCE_2",
				"label": "Source",
				"defaultValue": "0",
				"options": [
					{
						"label": "Calendar link",
						"value": "0"
					},
					{
						"label": "Next alarm",
						"value": "1"
					}
				]
			},
			{
				"type": "input",
				"messageKey": "SETTING_CALENDAR_ICS_URL_2",
				"label": "ICS link",
				"defaultValue": "",
				"attributes": {
					"placeholder": "https://…/basic.ics",
					"autocomplete": "off"
				}
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_COLOR_2",
				"label": "Colour",
				"defaultValue": 7,
				"options": [
					{
						"label": "Cyan",
						"value": 0
					},
					{
						"label": "Blue",
						"value": 1
					},
					{
						"label": "Green",
						"value": 2
					},
					{
						"label": "Red",
						"value": 3
					},
					{
						"label": "Orange",
						"value": 4
					},
					{
						"label": "Purple",
						"value": 5
					},
					{
						"label": "Yellow",
						"value": 6
					},
					{
						"label": "Magenta",
						"value": 7
					},
					{
						"label": "Teal",
						"value": 8
					},
					{
						"label": "Pink",
						"value": 9
					},
					{
						"label": "Lime",
						"value": 10
					},
					{
						"label": "Deep red",
						"value": 11
					},
					{
						"label": "Sky",
						"value": 12
					},
					{
						"label": "Mint",
						"value": 13
					},
					{
						"label": "Indigo",
						"value": 14
					},
					{
						"label": "Gold",
						"value": 15
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_PLACE_2",
				"label": "Position",
				"defaultValue": 0,
				"options": [
					{
						"label": "On timeline",
						"value": 0
					},
					{
						"label": "Above timeline",
						"value": 1
					},
					{
						"label": "Below timeline",
						"value": 2
					}
				]
			},
			{
				"type": "text",
				"defaultValue": "<strong>Row 3</strong>"
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_SOURCE_3",
				"label": "Source",
				"defaultValue": "0",
				"options": [
					{
						"label": "Calendar link",
						"value": "0"
					},
					{
						"label": "Next alarm",
						"value": "1"
					}
				]
			},
			{
				"type": "input",
				"messageKey": "SETTING_CALENDAR_ICS_URL_3",
				"label": "ICS link",
				"defaultValue": "",
				"attributes": {
					"placeholder": "https://…/basic.ics",
					"autocomplete": "off"
				}
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_COLOR_3",
				"label": "Colour",
				"defaultValue": 2,
				"options": [
					{
						"label": "Cyan",
						"value": 0
					},
					{
						"label": "Blue",
						"value": 1
					},
					{
						"label": "Green",
						"value": 2
					},
					{
						"label": "Red",
						"value": 3
					},
					{
						"label": "Orange",
						"value": 4
					},
					{
						"label": "Purple",
						"value": 5
					},
					{
						"label": "Yellow",
						"value": 6
					},
					{
						"label": "Magenta",
						"value": 7
					},
					{
						"label": "Teal",
						"value": 8
					},
					{
						"label": "Pink",
						"value": 9
					},
					{
						"label": "Lime",
						"value": 10
					},
					{
						"label": "Deep red",
						"value": 11
					},
					{
						"label": "Sky",
						"value": 12
					},
					{
						"label": "Mint",
						"value": 13
					},
					{
						"label": "Indigo",
						"value": 14
					},
					{
						"label": "Gold",
						"value": 15
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_CALENDAR_PLACE_3",
				"label": "Position",
				"defaultValue": 0,
				"options": [
					{
						"label": "On timeline",
						"value": 0
					},
					{
						"label": "Above timeline",
						"value": 1
					},
					{
						"label": "Below timeline",
						"value": 2
					}
				]
			}
		]
	},
	{
		"type": "section",
		"items": [
			{
				"type": "heading",
				"defaultValue": "Battery"
			},
			{
				"type": "select",
				"messageKey": "SETTING_TIMELINE_BATTERY",
				"label": "Battery markers",
				"defaultValue": 0,
				"options": [
					{
						"label": "20 / 10 / 4 / 0 %",
						"value": 0
					},
					{
						"label": "10 / 4 / 0 %",
						"value": 1
					},
					{
						"label": "4 / 0 %",
						"value": 2
					},
					{
						"label": "10 / 4 %",
						"value": 4
					},
					{
						"label": "None",
						"value": 3
					}
				],
				"description": "When the battery is expected to reach each level. 4 % = battery saver."
			}
		]
	},
	{
		"type": "section",
		"items": [
			{
				"type": "heading",
				"defaultValue": "Display"
			},
			{
				"type": "select",
				"messageKey": "SETTING_LIGHT_THEME",
				"label": "Theme",
				"defaultValue": 1,
				"options": [
					{
						"label": "Dark",
						"value": 0
					},
					{
						"label": "Light",
						"value": 1
					}
				]
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_BACKLIGHT_COLORS",
				"label": "Backlight colours",
				"defaultValue": false,
				"description": "Stronger ticks and labels while the light is on."
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_SHOW_BT_ALERT",
				"label": "Disconnect alert",
				"defaultValue": true
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_SHOW_SILENT_MODE",
				"label": "Quiet Time icon",
				"defaultValue": true
			},
			{
				"type": "select",
				"messageKey": "SETTING_STEP_DISPLAY",
				"label": "Steps",
				"defaultValue": 1,
				"options": [
					{
						"label": "Off",
						"value": 0
					},
					{
						"label": "Normal",
						"value": 1
					},
					{
						"label": "Bold",
						"value": 2
					}
				]
			},
			{
				"type": "select",
				"messageKey": "SETTING_DATE_FORMAT",
				"label": "Date format",
				"defaultValue": "%A, %b %d",
				"options": [
					{
						"label": "Monday, 1/15",
						"value": "%A, %m/%d"
					},
					{
						"label": "Monday, 15/1",
						"value": "%A, %d/%m"
					},
					{
						"label": "Monday, Jan 15",
						"value": "%A, %b %d"
					},
					{
						"label": "1/15/2026",
						"value": "%m/%d/%Y"
					},
					{
						"label": "15/1/2026",
						"value": "%d/%m/%Y"
					},
					{
						"label": "15 Jan 2026",
						"value": "%d %b %Y"
					},
					{
						"label": "2026-01-15",
						"value": "%Y-%m-%d"
					}
				]
			}
		]
	},
	{
		"type": "submit",
		"defaultValue": "Save"
	},
	{
		"type": "section",
		"items": [
			{
				"type": "heading",
				"defaultValue": "Advanced"
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_SHOW_ADVANCED_OPTIONS",
				"label": "Show advanced",
				"defaultValue": false,
				"description": "Diagnostics and troubleshooting."
			},
			{
				"type": "toggle",
				"messageKey": "SETTING_CLEAR_CACHE",
				"label": "Clear cache on save",
				"defaultValue": false,
				"description": "Wipes cached weather, links and the log, then refetches."
			}
		]
	},
	{
		"type": "debug-info"
	}
];
