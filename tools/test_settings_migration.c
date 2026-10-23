/* 3.0.3: host test for settings_init() migration (older and newer blobs).
 * Built by tools/test_payload.js:
 *   cc -std=c11 -Itools/host -I<generated keys> tools/test_settings_migration.c src/c/modules/settings.c */
#include <stdio.h>
#include "pebble.h"
#include "../src/c/modules/settings.h"
#include "../src/c/modules/storage_keys.h"

HostPersist g_host_persist[32];
static int fails, n;
#define CHECK(name, cond) do { n++; if (cond) printf("PASS %s\n", name); else { fails++; printf("FAIL %s\n", name); } } while (0)

static void reset(void) { memset(g_host_persist, 0, sizeof(g_host_persist)); }
static void store_blob(const Settings *s, int size) {
	uint8_t buf[PERSIST_DATA_MAX_LENGTH];
	memset(buf, 0xA5, sizeof(buf));               /* "unknown" bytes beyond the struct */
	memcpy(buf, s, size < (int)sizeof(*s) ? size : (int)sizeof(*s));
	persist_write_data(STORAGE_KEY_SETTINGS, buf, size);
}

int main(void) {
	reset();
	settings_init();
	Settings def = *settings_get();
	CHECK("no blob: defaults (light theme, 24 h, events on, rain = probability, thickness on)",
	      def.light_theme && def.forecast_hours == DEFAULT_FORECAST_HOURS && def.event_hide == 0 &&
	      def.rain_height == RAIN_HEIGHT_PROBABILITY && def.cloud_thick == CLOUD_THICKNESS_ON);

	/* 2.9.2-sized blob: ends right before event_hide */
	Settings old = def;
	old.light_theme = false; old.forecast_hours = 36; old.fog_indicator = false; old.flick_action = FLICK_ACTION_CLOUDS;
	old.event_hide = 0x3f; old.rain_height = 1; old.cloud_thick = 1; /* not part of the old blob */
	reset(); store_blob(&old, (int)offsetof(Settings, event_hide)); settings_init();
	Settings *s = settings_get();
	CHECK("older blob (2.9.2 size): stored fields kept", !s->light_theme && s->forecast_hours == 36 && !s->fog_indicator &&
	      s->flick_action == FLICK_ACTION_CLOUDS);
	CHECK("older blob: newer fields keep their defaults (event_hide, rain_height, cloud_thick = 0)",
	      s->event_hide == 0 && s->rain_height == 0 && s->cloud_thick == 0);

	/* pre-sensitivity blob: legacy rays toggle is mapped */
	Settings pre = def; pre.sunlight_rays = true;
	reset(); store_blob(&pre, (int)offsetof(Settings, sunlight_sensitivity)); settings_init();
	CHECK("pre-sensitivity blob: rays on → sunlight_sensitivity Sensitive", settings_get()->sunlight_sensitivity == SUN_SENS_SENSITIVE);
	pre.sunlight_rays = false;
	reset(); store_blob(&pre, (int)offsetof(Settings, sunlight_sensitivity)); settings_init();
	CHECK("pre-sensitivity blob: rays off → Off", settings_get()->sunlight_sensitivity == SUN_SENS_OFF);

	/* newer blob (a later version appended fields): known fields kept, tail ignored */
	Settings cur = def; cur.light_theme = false; cur.cloud_thick = CLOUD_THICKNESS_OFF; cur.rain_height = RAIN_HEIGHT_AMOUNT; cur.event_hide = 0x05;
	reset(); store_blob(&cur, (int)sizeof(Settings) + 24); settings_init();
	s = settings_get();
	CHECK("newer blob (+24 bytes): every known field read, unknown tail ignored",
	      !s->light_theme && s->cloud_thick == CLOUD_THICKNESS_OFF && s->rain_height == RAIN_HEIGHT_AMOUNT && s->event_hide == 0x05);

	/* out-of-range values are sanitised */
	Settings bad = def; bad.flick_action = 200; bad.event_hide = 0xff;
	reset(); store_blob(&bad, (int)sizeof(Settings)); settings_init();
	CHECK("sanitised: bad flick_action → Range, event_hide masked", settings_get()->flick_action == FLICK_ACTION_RANGE &&
	      settings_get()->event_hide == EVENT_FLAGS_MASK);

	/* 3.0.3 leading_zero: a 3.0.2-size blob ends before it → Auto */
	Settings lz = def; lz.light_theme = false; lz.leading_zero = LEADING_ZERO_OFF;
	reset(); store_blob(&lz, (int)offsetof(Settings, leading_zero)); settings_init();
	CHECK("3.0.2 blob (ends before leading_zero): leading_zero = Auto, older fields kept",
	      settings_get()->leading_zero == LEADING_ZERO_AUTO && !settings_get()->light_theme);
	reset(); store_blob(&lz, (int)sizeof(Settings)); settings_init();
	CHECK("current blob: leading_zero Off kept", settings_get()->leading_zero == LEADING_ZERO_OFF);
	Settings lzb = def; lzb.leading_zero = 0xA5; /* garbage, e.g. a later version's value */
	reset(); store_blob(&lzb, (int)sizeof(Settings)); settings_init();
	CHECK("out-of-range leading_zero → Auto", settings_get()->leading_zero == LEADING_ZERO_AUTO);

	/* 3.1.3 rain_height 2 = Amount + line kept; junk → Probability */
	Settings rl = def; rl.rain_height = RAIN_HEIGHT_AMOUNT_LINE;
	reset(); store_blob(&rl, (int)sizeof(Settings)); settings_init();
	CHECK("rain_height Amount + line (2) kept", settings_get()->rain_height == RAIN_HEIGHT_AMOUNT_LINE);
	rl.rain_height = 0x77;
	reset(); store_blob(&rl, (int)sizeof(Settings)); settings_init();
	CHECK("out-of-range rain_height → Probability", settings_get()->rain_height == RAIN_HEIGHT_PROBABILITY);

	/* own keys: cloud override 1/2 accepted, others dropped */
	reset(); persist_write_int(STORAGE_KEY_CLOUD_OVERRIDE, 2); settings_init();
	CHECK("cloud override key 9 = 2 → Split/Total override honoured", settings_effective_cloud_mode() == (CloudDisplayMode)1);
	reset(); persist_write_int(STORAGE_KEY_CLOUD_OVERRIDE, 7); settings_init();
	CHECK("cloud override key 9 out of range → ignored (Clay value)", settings_effective_cloud_mode() == def.cloud_display_mode);

	printf("%d/%d passed\n", n - fails, n);
	return fails ? 1 : 0;
}
