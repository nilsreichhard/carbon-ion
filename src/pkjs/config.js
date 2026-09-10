/**
 * Clay configuration for Carbon Ion
 *
 * @author    Nils Reich
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

// Build metadata inlined for robust offline/CloudPebble builds
var version = '2.0.0';
var hash = 'custom';

module.exports = [
	{
		'type': 'heading',
		'defaultValue': 'Carbon Ion',
	},
	{
		'type': 'text',
		'defaultValue': `v${version} (Emery & Gabbro Edition)\nBy Nils Reich — based on Carbon by Cory Hughart`,
	},
	{
		'type': 'section',
		'items': [
			{
				'type': 'heading',
				'defaultValue': 'Timeline & Horizon',
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_FORECAST_HOURS',
				'label': 'Forecast Window',
				'description': 'Select timeline horizon ahead with matching 1/5 past ratio.',
				'defaultValue': 24,
				'options': [
					{ 'label': '12hr future (+3h past = 15h)', 'value': 12 },
					{ 'label': '18hr future (+4.5h past = 22.5h)', 'value': 18 },
					{ 'label': '24hr future (+6h past = 30h)', 'value': 24 },
					{ 'label': '36hr future (+9h past = 45h)', 'value': 36 },
					{ 'label': '48hr future (+12h past = 60h)', 'value': 48 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_INFILL_MODE',
				'label': 'Thermal Infill Shading',
				'description': 'Shading area under temperature curve on meteogram.',
				'defaultValue': 0,
				'options': [
					{ 'label': 'Forecast Only (After Now)', 'value': 0 },
					{ 'label': 'Past Only (Before Now)', 'value': 1 },
					{ 'label': 'All (Full Horizon)', 'value': 2 },
					{ 'label': 'None (Curves Only)', 'value': 3 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_NEEDLE_MODE',
				'label': 'Current Time Indicator Bar',
				'description': 'Position of the vertical needle marking current hour.',
				'defaultValue': 0,
				'options': [
					{ 'label': 'Both (Top Track & Meteogram)', 'value': 0 },
					{ 'label': 'Top Track Only (Daylight & Sky)', 'value': 1 },
					{ 'label': 'Bottom Only (Meteogram)', 'value': 2 },
					{ 'label': 'None (Hide Needle)', 'value': 3 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_TIMELINE_BATTERY',
				'label': 'Timeline Battery Markers',
				'description': 'Battery depletion milestones projected onto the timeline track.',
				'defaultValue': 1,
				'options': [
					{ 'label': '20% yellow, 10% orange, 0% red', 'value': 0 },
					{ 'label': '10% orange, 0% red (default)', 'value': 1 },
					{ 'label': '0% red', 'value': 2 },
					{ 'label': 'None', 'value': 3 },
				],
			},
		],
	},
	{
		'type': 'section',
		'items': [
			{
				'type': 'heading',
				'defaultValue': 'Theme & Display',
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_LIGHT_THEME',
				'label': 'Color Theme',
				'description': 'Background and contrast appearance.',
				'defaultValue': 0,
				'options': [
					{ 'label': 'Dark Theme (Black)', 'value': 0 },
					{ 'label': 'Light Theme (White)', 'value': 1 },
				],
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_SHOW_BT_ALERT',
				'label': 'Bluetooth Disconnect Alert',
				'description': 'Show red alert icon left of the time when disconnected from phone.',
				'defaultValue': true,
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_SHOW_SILENT_MODE',
				'label': 'Silent Mode Indicator',
				'description': 'Show muted bell icon left of the time when Quiet Time is active.',
				'defaultValue': true,
			},
			{
				'type': 'text',
				'defaultValue': 'Time format (12h/24h) is determined by the watch system settings.',
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_DATE_FORMAT',
				'label': 'Date Format',
				'defaultValue': '%A, %m/%d',
				'options': [
					{ 'label': 'Monday, 1/15', 'value': '%A, %m/%d' },
					{ 'label': 'Monday, 15/1', 'value': '%A, %d/%m' },
					{ 'label': 'Monday, Jan 15', 'value': '%A, %b %d' },
					{ 'label': '1/15/2026', 'value': '%m/%d/%Y' },
					{ 'label': '15/1/2026', 'value': '%d/%m/%Y' },
					{ 'label': '15 Jan 2026', 'value': '%d %b %Y' },
					{ 'label': '2026-01-15', 'value': '%Y-0%m-0%d' },
				],
			},
		],
	},
	{
		'type': 'section',
		'items': [
			{
				'type': 'heading',
				'defaultValue': 'Weather',
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_TEMP_UNIT',
				'label': 'Temperature Unit',
				'description': '"Auto" detects your locale (US = °F, everywhere else = °C).',
				'defaultValue': -1,
				'options': [
					{ 'label': 'Auto (locale)', 'value': -1 },
					{ 'label': 'Celsius (°C)', 'value': 0 },
					{ 'label': 'Fahrenheit (°F)', 'value': 1 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_FETCH_INTERVAL',
				'label': 'Weather Refresh Interval',
				'defaultValue': 30,
				'options': [
					{ 'label': 'Every 15 minutes', 'value': 15 },
					{ 'label': 'Every 30 minutes', 'value': 30 },
					{ 'label': 'Every 60 minutes', 'value': 60 },
				],
			},
		],
	},
	{
		'type': 'section',
		'items': [
			{
				'type': 'heading',
				'defaultValue': 'Location',
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_GEOCODE_ENABLED',
				'label': 'Detect Location Name',
				'description': 'Look up city name from GPS (reverse geocoding). When off, custom text below is shown.',
				'defaultValue': true,
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_LOCATION_OVERRIDE',
				'label': 'Custom Location Text',
				'description': 'Shown when location detection is off. Leave blank to hide.',
				'defaultValue': '',
				'attributes': {
					'placeholder': 'e.g. Home',
					'maxlength': 23,
				},
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_USE_STATIC_LOCATION',
				'label': 'Use Static Location',
				'description': 'Skip GPS and use fixed coordinates for weather and location name.',
				'defaultValue': false,
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_STATIC_LAT',
				'label': 'Static Latitude',
				'description': 'Used only when static location is enabled.',
				'defaultValue': '',
				'attributes': {
					'type': 'number',
					'step': 'any',
					'placeholder': '41.8338',
				},
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_STATIC_LON',
				'label': 'Static Longitude',
				'description': 'Used only when static location is enabled.',
				'defaultValue': '',
				'attributes': {
					'type': 'number',
					'step': 'any',
					'placeholder': '-87.8966',
				},
			},
		],
	},
	{
		'type': 'submit',
		'defaultValue': 'Save Settings',
	},
	{
		'type': 'section',
		'items': [
			{
				'type': 'heading',
				'defaultValue': 'Advanced',
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_SHOW_ADVANCED_OPTIONS',
				'label': 'Show advanced options',
				'description': 'Advanced options for diagnostics and troubleshooting.',
				'defaultValue': false,
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_CLEAR_CACHE',
				'label': 'Clear cached data on save',
				'description': 'Wipes cached weather and refetches immediately.',
				'defaultValue': false,
			},
		],
	},
	{
		'type': 'debug-info',
	},
];
