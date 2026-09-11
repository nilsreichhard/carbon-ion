/**
 * Clay configuration for Carbon Ion
 *
 * @author    Nils Reich
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

module.exports = [
	{
		'type': 'heading',
		'defaultValue': 'Carbon Ion',
	},
	{
		'type': 'text',
		'defaultValue': 'By Nils Reich',
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
				'label': 'Forecast Horizon',
				'description': 'Upcoming weather horizon displayed on timeline.',
				'defaultValue': 24,
				'options': [
					{ 'label': '12h forecast (+3h past)', 'value': 12 },
					{ 'label': '18h forecast (+4.5h past)', 'value': 18 },
					{ 'label': '24h forecast (+6h past)', 'value': 24 },
					{ 'label': '36h forecast (+9h past)', 'value': 36 },
					{ 'label': '48h forecast (+12h past)', 'value': 48 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_INFILL_MODE',
				'label': 'Thermal Infill',
				'description': 'Colored infill area under temperature curve.',
				'defaultValue': 0,
				'options': [
					{ 'label': 'Future forecast only', 'value': 0 },
					{ 'label': 'Past hours only', 'value': 1 },
					{ 'label': 'Full timeline', 'value': 2 },
					{ 'label': 'None (lines only)', 'value': 3 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_NEEDLE_MODE',
				'label': 'Current Time Needle',
				'description': 'Red vertical needle marking current hour.',
				'defaultValue': 0,
				'options': [
					{ 'label': 'Top track & meteogram', 'value': 0 },
					{ 'label': 'Top track only', 'value': 1 },
					{ 'label': 'Bottom meteogram only', 'value': 2 },
					{ 'label': 'Hidden', 'value': 3 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_TIMELINE_BATTERY',
				'label': 'Timeline Battery Markers',
				'description': 'Battery depletion milestones projected onto timeline.',
				'defaultValue': 1,
				'options': [
					{ 'label': '20% yellow, 10% orange, 0% red', 'value': 0 },
					{ 'label': '10% orange, 0% red', 'value': 1 },
					{ 'label': '0% red', 'value': 2 },
					{ 'label': 'None', 'value': 3 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_TIMELINE_EVENT',
				'label': 'Calendar Events',
				'description': 'Show ICS events on the daylight timeline. Add up to 3 calendar URLs below and pick a color for each.',
				'defaultValue': 2,
				'options': [
					{ 'label': 'Bar at start time', 'value': 1 },
					{ 'label': 'Duration span', 'value': 2 },
					{ 'label': 'Off', 'value': 0 },
				],
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_CALENDAR_ICS_URL',
				'label': 'Calendar 1 ICS URL',
				'description': 'Paste a private ICS/iCal subscription URL, then set Calendar Events to Bar or Duration.\n\nHow to get a URL:\n• Google Calendar: Settings → your calendar → Integrate calendar → Secret address in iCal format\n• Outlook / Office 365: Calendar → Share → Publish calendar → ICS link\n• Apple Calendar (iCloud): Calendar sharing → Public Calendar → copy the webcal/ICS link (webcal:// is fine)\n\nLeave all URLs blank and/or set Calendar Events to Off to hide events.',
				'defaultValue': '',
				'attributes': {
					'placeholder': 'https://calendar.google.com/calendar/ical/.../basic.ics',
				},
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_CALENDAR_COLOR_1',
				'label': 'Calendar 1 color',
				'defaultValue': 1,
				'options': [
					{ 'label': 'Cyan', 'value': 0 },
					{ 'label': 'Blue', 'value': 1 },
					{ 'label': 'Green', 'value': 2 },
					{ 'label': 'Red', 'value': 3 },
					{ 'label': 'Orange', 'value': 4 },
					{ 'label': 'Purple', 'value': 5 },
					{ 'label': 'Yellow', 'value': 6 },
					{ 'label': 'Magenta', 'value': 7 },
				],
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_CALENDAR_ICS_URL_2',
				'label': 'Calendar 2 ICS URL (optional)',
				'defaultValue': '',
				'attributes': {
					'placeholder': 'https://…/basic.ics',
				},
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_CALENDAR_COLOR_2',
				'label': 'Calendar 2 color',
				'defaultValue': 7,
				'options': [
					{ 'label': 'Cyan', 'value': 0 },
					{ 'label': 'Blue', 'value': 1 },
					{ 'label': 'Green', 'value': 2 },
					{ 'label': 'Red', 'value': 3 },
					{ 'label': 'Orange', 'value': 4 },
					{ 'label': 'Purple', 'value': 5 },
					{ 'label': 'Yellow', 'value': 6 },
					{ 'label': 'Magenta', 'value': 7 },
				],
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_CALENDAR_ICS_URL_3',
				'label': 'Calendar 3 ICS URL (optional)',
				'defaultValue': '',
				'attributes': {
					'placeholder': 'https://…/basic.ics',
				},
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_CALENDAR_COLOR_3',
				'label': 'Calendar 3 color',
				'defaultValue': 2,
				'options': [
					{ 'label': 'Cyan', 'value': 0 },
					{ 'label': 'Blue', 'value': 1 },
					{ 'label': 'Green', 'value': 2 },
					{ 'label': 'Red', 'value': 3 },
					{ 'label': 'Orange', 'value': 4 },
					{ 'label': 'Purple', 'value': 5 },
					{ 'label': 'Yellow', 'value': 6 },
					{ 'label': 'Magenta', 'value': 7 },
				],
			},
		],
	},
	{
		'type': 'section',
		'items': [
			{
				'type': 'heading',
				'defaultValue': 'Display & Indicators',
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_LIGHT_THEME',
				'label': 'Color Theme',
				'description': 'Background appearance.',
				'defaultValue': 1,
				'options': [
					{ 'label': 'Dark', 'value': 0 },
					{ 'label': 'Light', 'value': 1 },
				],
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_SHOW_BT_ALERT',
				'label': 'Bluetooth Disconnect Alert',
				'description': 'Show red alert icon left of the time when disconnected.',
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
				'type': 'toggle',
				'messageKey': 'SETTING_SHOW_STEP_COUNT',
				'label': 'Show Step Counter',
				'description': 'Display daily step count to the right of the time.',
				'defaultValue': true,
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_DATE_FORMAT',
				'label': 'Date Format',
				'defaultValue': '%A, %b %d',
				'options': [
					{ 'label': 'Monday, 1/15', 'value': '%A, %m/%d' },
					{ 'label': 'Monday, 15/1', 'value': '%A, %d/%m' },
					{ 'label': 'Monday, Jan 15', 'value': '%A, %b %d' },
					{ 'label': '1/15/2026', 'value': '%m/%d/%Y' },
					{ 'label': '15/1/2026', 'value': '%d/%m/%Y' },
					{ 'label': '15 Jan 2026', 'value': '%d %b %Y' },
					{ 'label': '2026-01-15', 'value': '%Y-%m-%d' },
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
				'description': 'Auto detects unit from your phone locale.',
				'defaultValue': 0,
				'options': [
					{ 'label': 'Auto (Locale)', 'value': -1 },
					{ 'label': 'Celsius (°C)', 'value': 0 },
					{ 'label': 'Fahrenheit (°F)', 'value': 1 },
				],
			},
			{
				'type': 'select',
				'messageKey': 'SETTING_FETCH_INTERVAL',
				'label': 'Refresh Interval',
				'defaultValue': 15,
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
				'label': 'Detect City Name',
				'description': 'Look up city name from GPS. When off, custom text below is shown.',
				'defaultValue': true,
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_LOCATION_OVERRIDE',
				'label': 'Custom City Text',
				'description': 'Shown when city detection is off. Leave blank to hide.',
				'defaultValue': '',
				'attributes': {
					'placeholder': 'e.g. Home',
					'maxlength': 23,
				},
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_USE_STATIC_LOCATION',
				'label': 'Use Fixed Coordinates',
				'description': 'Skip GPS and use fixed coordinates for weather and city name.',
				'defaultValue': false,
			},
			{
				'type': 'input',
				'messageKey': 'SETTING_STATIC_LAT',
				'label': 'Latitude',
				'description': 'Used only when fixed coordinates are enabled.',
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
				'label': 'Longitude',
				'description': 'Used only when fixed coordinates are enabled.',
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
				'label': 'Show Advanced Options',
				'description': 'Diagnostics and troubleshooting.',
				'defaultValue': false,
			},
			{
				'type': 'toggle',
				'messageKey': 'SETTING_CLEAR_CACHE',
				'label': 'Clear Cached Weather on Save',
				'description': 'Wipes cached weather and refetches immediately.',
				'defaultValue': false,
			},
		],
	},
	{
		'type': 'debug-info',
	},
];
