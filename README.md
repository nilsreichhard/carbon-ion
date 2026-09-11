[![GitHub Release](https://img.shields.io/github/v/release/nilsreichhard/carbon-ion?style=flat&label=latest)](https://github.com/nilsreichhard/carbon-ion/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL_3.0-blue.svg)](LICENSE)

# Carbon Ion — Pebble Weather Watchface

**Carbon Ion** is a high-precision, weather-focused watchface engineered **exclusively for Pebble Time 2 / Emery** (`emery`, 200×228 color display). Other platforms are not build targets. Live meteorological and astronomical telemetry is powered by the free [Open-Meteo](https://open-meteo.com) API with zero API keys required.

Developed by **Nils Reich** ([@nilsreichhard](https://github.com/nilsreichhard)), based on the original open-source [Carbon watchface](https://github.com/cr0ybot/carbon) by **Cory Hughart** ([@cr0ybot](https://github.com/cr0ybot)).

## Features

- **Flexible Rolling Timeline (12h–48h)**: Configurable forecast horizons (12h, 18h, 24h, 36h, 48h) with a fixed 1/5 past ratio anchoring the current time needle at 20% width.
- **Timeline Battery Milestones**: Projects remaining battery life onto the top timeline track (20% yellow, 10% orange, 0% red death projection).
- **Dual Temperature Curves & Synchronized Infill**: Actual temperature spline matching infill colors across thermal bands (Freezing, Cold, Cool, Mild, Warm, Hot, Burning), paired with parallel dashed feels-like temperature curve.
- **Dynamic High/Low Labels**: Left-side numeric readouts calculate peak and valley temperatures across the active timeline window.
- **Continuous Astronomical Daylight Track**: Full-width 3px baseline track, multi-day daylight spans with sunrise/sunset delimiter brackets, and per-midnight astronomical lunar phases.
- **Hourly Timeline Axis Scale**: Bottom ticks marking hourly intervals (3px) and solar noon/midnight milestones (5px).
- **Dead-Center Time & Zero-Overlap Status Icons**: Large bold digital clock with date below, city and condition icon above, and dedicated left margin for Bluetooth alert and Quiet Time bell runes.
- **Daily Step Counter**: Clean, compact step count in thousands (`1`, `2`, `10`) right-aligned on the right side of the clock row.
- **Timeline Calendar Events**: Projects approaching events onto the **daylight** timeline track as a short vertical blue bar (start-only) or a solid blue duration block, powered by iCal/ICS calendar feeds. (WMO weather icons live on `event_layer`, not calendar events.)
- **Precipitation & Cloud Density Tracks**: Inverted rain histogram and vector cloud lobes.
- **Theme Parity**: Full Dark Theme and Light Theme support.

## Settings

Configurable on your phone via Pebble app settings (Clay):
- **Forecast Horizon**: 12h forecast (+3h past), 18h forecast (+4.5h past), 24h forecast (+6h past), 36h forecast (+9h past), 48h forecast (+12h past).
- **Thermal Infill**: Future forecast only, Past hours only, Full timeline, or None (lines only).
- **Current Time Needle**: Top track & meteogram, Top track only, Bottom meteogram only, or Hidden.
- **Timeline Battery Markers**: 20% yellow / 10% orange / 0% red, 10% orange / 0% red, 0% red, or None.
- **Calendar Events**: Blue bar at start time, Duration span, or Off.
- **Calendar ICS URL**: Paste a private iCal/ICS link from Google Calendar, Apple iCloud, or Outlook (see settings page how-to). Leave blank / set Calendar Events Off to clear.
- **Color Theme**: Dark (Black) or Light (White).
- **Bluetooth Disconnect Alert**: Show red alert icon when disconnected.
- **Silent Mode Indicator**: Show muted bell icon when Quiet Time is active.
- **Show Step Counter**: Display daily step count to the right of the time.
- **Date Format**: Multiple localization presets.
- **Temperature Unit**: Auto (locale), Celsius, or Fahrenheit.
- **Location & Geocoding**: Automatic GPS reverse geocoding or custom fixed coordinates.

## To do

- [x] Settings page for customizations
- [x] Customize date format
- [ ] Custom date format string
- [x] Customize battery indicator (depletion markers on timeline)
- [x] Customize temperature unit
- [x] Customize color scheme (Dark & Light themes)
- [x] Localization (system locale)
- [ ] Custom locale support
- [x] Bluetooth disconnect vibration
- [x] Quiet time indicator
- [x] Daily step counter
- [x] Calendar event indicators on timeline
- [ ] Support round watches (e.g. Pebble Round 2)

---

## Reporting Issues

You may choose to report an issue either through the "contact developer" link in the Pebble app store or by opening a new issue on the project's GitHub repository.

If you're experiencing unexpected behavior, the **Debug** section at the bottom of the settings page can help identify the cause and gives us a snapshot of everything the watchface knows at that moment. Ideally, bug reports should include this debug information to assist in troubleshooting.

To access it:
1. Open the Pebble app and tap the gear icon next to Carbon to open settings.
2. Scroll to the bottom of the settings page and expand the **Debug** section.
3. Review the data inline, or tap **Copy debug info to clipboard** to grab it all as JSON.

The clipboard JSON contains everything displayed in the **Debug** section, including:
- `activeWatchInfo` — watch hardware, platform, and firmware version
- `buildInfo` — build metadata including version, git commit hash, branch, dirty flag, and build date
- `cache` — the full weather payload including fetch time, expiry, and all hourly data
- `settings` — current Clay settings stored on the phone
- `eventLog` — log of recent communication and fetch events

> **Before sharing debug info in a GitHub issue, obfuscate the `lat` and `lon` values** inside `cache.payload` and anything else you deem sensitive to protect your location privacy.

---


## Watchface layer stack (Emery)

Top → bottom on the root window:

1. `daylight_layer` — astronomical daylight track, battery milestones, **calendar ICS event bars/blocks**
2. `cloud_layer` — cloud cover lobes
3. `precip_layer` — precipitation histogram
4. `event_layer` — WMO weather condition icons (not calendar events)
5. `time_layer` — clock, date, city, BT/quiet icons, step counter
6. `temp_layer` — temperature curves + high/current/low labels (pinned bottom)

Icon glyph map: `resources/fonts/icons.icomoon.json` (IcoMoon TTF under `resources/fonts/`).

## Development

### Prerequisites

- [Pebble SDK](https://developer.repebble.com/sdk/) (includes the `pebble` CLI tool)
- [Node.js](https://nodejs.org) (for PKJS dependencies)

### Code completion

For code completion and linting, you can use [clangd](https://clangd.llvm.org/) with the `compile_commands.json` generated by the Waf build system. To get the most out of it, you'll want to have clangd 22+ for better docblock support. I had to install llvm via Homebrew and add it to my PATH to get a new enough version of clangd on MacOS.

### Build & run in emulator

Build the watchface using the Pebble CLI:

```sh
pebble build
```

Then install it on the emulator of your choice:

```sh
# Pebble Time 2 (rectangular, 200×228)
pebble install --emulator emery --logs

# Pebble 2 Duo (rectangular, 144×168)
pebble install --emulator flint --logs
```

Note that adding/removing `messageKeys` in package.json will require a `pebble clean` before the next build to avoid stale generated code.

#### Emulator config page

To test the config page with the emulator:

```sh
pebble emu-app-config
```

### Install on your device

If you want to be able to run the watchface on your device, you'll also want to log in with GitHub after installing the Pebble SDK:

```sh
pebble login
```

This will enable the `--cloudpebble` option:

```sh
pebble install --cloudpebble
```

### Demo Build & Screenshots

Demo builds with different weather conditions can be created with the `DEMO` environment variable. See [src/c/modules/demo.c](./src/c/modules/demo.c) for the available demo scenarios.

```sh
DEMO=1 pebble build
```

To take screenshots of a particular demo scenario you can use the Pebble CLI's screenshot command, which saves to `./screenshots`:

```sh
DEMO=1 pebble build && pebble screenshot --all-platforms
```

Note: you may need to run `pebble wipe` if the emulator stalls and try again.

### Project Structure

```
resources/      # Static assets (e.g. icon font)
scripts/        # Utility scripts (e.g. icon generation)
src/
  c/            # C code
    generated/  # Generated C code (e.g. from generated icons)
    modules/    # C modules (settings, weather, etc.)
    ui/         # Custom UI widget implementations (e.g. graph, event layer)
    main.c      # C entrypoint
  pkjs/
    index.js    # Phone-side weather & location data fetching
```

### Debug Info

The settings page includes a **Debug** section (collapsed by default) powered by the `debug-info` custom Clay component in `src/pkjs/config/debug.js`. It reads the weather cache and current Clay settings from `localStorage` at the moment the settings page is opened, merges in the active watch info from the Clay runtime, and renders each key as a collapsible `<details>` block.

Build metadata is written to `.buildinfo.json` (gitignored) at the start of every `pebble build` and bundled into the JS. It contains:

```json
{
  "version": "1.4.0",
  "hash": "abc1234",
  "branch": "main",
  "dirty": false,
  "buildDate": "2026-07-03T15:00:00+00:00"
}
```

To add new fields to the debug output, add keys to the object returned by `formatDebugInfo()` in `src/pkjs/index.js`. The component renders any key it receives without needing changes.

### Icons

This watchfaces uses icons from the [Carbon](https://carbondesignsystem.com/elements/icons/library/) icon set, which has the most exhaustive set of weather icons I could find. The name is a coincidence, I named the watchface Carbon before I found the icon set.

Icons are included as a custom font generated from [IcoMoon](https://icomoon.io/). The `src/embeddedjs/assets/icons.icomoon.json` file can be imported into IcoMoon to edit the icon set. When icons are added, removed, or rearranged, the font must be re-exported from IcoMoon (with font family set to "IcoMoon"), and both the TTF and the JSON selection file must be replaced.

Move the downloaded TTF font file to `resources/fonts/IcoMoon-Regular.ttf` (the `-Regular` suffix is important!) and the JSON selection file to `resources/fonts/icons.icomoon.json`, then regenerate the reference table:

```sh
npm run gen-icons
```

This will update `src/c/generated/icons.h` with the icon names and codepoints, which can be used in C code as `ICON_<NAME>` (e.g. `ICON_SUN`).

---

## Credits & Acknowledgements

- **Original Project**: **Carbon Ion** is a specialized fork and overhaul developed by **Nils Reich** ([@nilsreichhard](https://github.com/nilsreichhard)), built upon the original open-source **[Carbon watchface](https://github.com/cr0ybot/carbon)** created by **Cory Hughart** ([@cr0ybot](https://github.com/cr0ybot) / [coryhughart.com](https://coryhughart.com)).
- **Original Author**: **Cory Hughart** — creator of the foundational Carbon watchface architecture, embedded WMO code translation, and IcoMoon Carbon icon set integration.
- **Carbon Ion Enhancements**:
  - Full high-resolution support and layout geometry for **Pebble Time 2** (`emery`, 200×228) and **Pebble Round 2** (`gabbro`/`chalk`, 260×260).
  - 12h–48h configurable timeline windows with locked 1/5 past ratio.
  - Continuous multi-day astronomical daylight & night tracking with per-midnight lunar phase algorithm.
  - Dual temperature spline curves (bold actual curve vs parallel dashed apparent curve) with non-overflowing solid thermal infill shading and dynamic window min/max scale readouts.
  - Configurable timeline battery milestones and hourly axis ticks.
  - Standalone Rebble CloudPebble vendoring and dynamic AppMessage payloads.

## License

Released under [GPL-3.0-or-later](LICENSE) in accordance with the original Carbon license.

## Attribution

- Weather data from [Open-Meteo.com](https://open-meteo.com/)
- Reverse geocoding from [BigDataCloud](https://www.bigdatacloud.com/free-api/free-reverse-geocode-to-city-api)
- Icons from the [Carbon Design System](https://carbondesignsystem.com/elements/icons/library/) icon set by IBM
- Icons assembled with [IcoMoon](https://icomoon.io/)
