/* Host test (2.9.6): cc -std=c99 -Wall -Wextra -o /tmp/t tools/test_precip_map.c && /tmp/t
 * Rain bar classification in src/c/modules/precip_map.h. Run from test_payload.js. */
#include <stdio.h>
#include "../src/c/modules/precip_map.h"
static int n, fails;
static void check(const char *name, int got, int want) {
	n++;
	if (got != want) fails++;
	printf("%s %s", got == want ? "PASS" : "FAIL", name);
	if (got != want) printf(" (got %d want %d)", got, want);
	printf("\n");
}
#define R PRECIP_KIND_RAIN
#define S PRECIP_KIND_SNOW
#define M PRECIP_KIND_MIX
#define H PRECIP_KIND_HAIL
int main(void) {
	/* kind per code */
	static const struct { int code, kind; const char *what; } K[] = {
	    {0, R, "0 clear (chance only) -> rain"}, {2, R, "2 cloud -> rain"}, {45, R, "45 fog -> rain"},
	    {51, R, "51 drizzle"}, {55, R, "55 dense drizzle"}, {56, M, "56 freezing drizzle"}, {57, M, "57 freezing drizzle"},
	    {61, R, "61 light rain"}, {63, R, "63 rain"}, {65, R, "65 heavy rain"}, {66, M, "66 freezing rain"}, {67, M, "67"},
	    {68, M, "68 rain+snow mixed"}, {69, M, "69 rain+snow mixed heavy"}, {71, S, "71 light snow"}, {73, S, "73"}, {75, S, "75"},
	    {77, S, "77 snow grains = snow"}, {79, M, "79 ice pellets = mixed"}, {80, R, "80 showers"}, {81, R, "81"}, {82, R, "82"},
	    {83, M, "83 rain-snow showers"}, {84, M, "84"}, {85, S, "85 light snow showers"}, {86, S, "86 heavy snow showers"},
	    {87, H, "87 small hail"}, {89, H, "89 hail showers"}, {95, R, "95 storm -> rain"}, {96, H, "96 storm + hail"},
	    {97, R, "97 heavy storm -> rain"}, {99, H, "99 storm + heavy hail"},
	};
	for (unsigned i = 0; i < sizeof K / sizeof K[0]; i++)
		check(K[i].what, precip_kind((uint8_t)K[i].code), K[i].kind);
	/* intensity from code only (no mm) */
	check("61 light, no mm", precip_is_heavy(61, false, 0), 0);
	check("80 light, no mm", precip_is_heavy(80, false, 0), 0);
	check("63 moderate -> heavy shade", precip_is_heavy(63, false, 0), 1);
	check("81 moderate -> heavy shade", precip_is_heavy(81, false, 0), 1);
	check("65 heavy", precip_is_heavy(65, false, 0), 1);
	check("82 violent", precip_is_heavy(82, false, 0), 1);
	check("85 light snow showers light", precip_is_heavy(85, false, 0), 0);
	check("71 light snow light", precip_is_heavy(71, false, 0), 0);
	check("75 heavy snow heavy", precip_is_heavy(75, false, 0), 1);
	check("95 storm light without mm", precip_is_heavy(95, false, 0), 0);
	check("97 heavy storm heavy without mm", precip_is_heavy(97, false, 0), 1);
	/* intensity from mm wins */
	check("65 with 0.8 mm -> light", precip_is_heavy(65, true, 8), 0);
	check("61 with 3.0 mm -> heavy", precip_is_heavy(61, true, 30), 1);
	check("rain 2.4 mm light / 2.5 heavy", precip_is_heavy(61, true, 24) * 10 + precip_is_heavy(61, true, 25), 1);
	check("snow 0.9 mm light / 1.0 heavy", precip_is_heavy(71, true, 9) * 10 + precip_is_heavy(71, true, 10), 1);
	check("95 storm with 6 mm -> heavy rain", precip_is_heavy(95, true, 60), 1);
	check("mix single shade", precip_is_heavy(68, true, 200), 0);
	check("hail single shade", precip_is_heavy(99, true, 200), 0);
	/* amount scale (h = 25 px) */
	check("amount 0 mm -> 0 px", precip_amount_px(0, 25), 0);
	check("amount 0.1 mm -> 2 px", precip_amount_px(1, 25), 2);
	check("amount 1 mm -> 8 px", precip_amount_px(10, 25), 8);
	check("amount 2.5 mm -> 13 px", precip_amount_px(25, 25), 13);
	check("amount 8 mm -> full", precip_amount_px(80, 25), 25);
	check("amount 25.5 mm -> full", precip_amount_px(255, 25), 25);
	check("amount tiny on 1 px row -> 1", precip_amount_px(1, 1), 1);
	check("shade 70 solid / 40 checker / 39 dots", precip_prob_shade(70) * 100 + precip_prob_shade(40) * 10 + precip_prob_shade(39), 210);
	/* clouds: heavy hours (2.9.6) */
	check("cloud 95 storm heavy", cloud_hour_heavy(95, true, 0), 1);
	check("cloud 99 hail storm heavy", cloud_hour_heavy(99, false, 0), 1);
	check("cloud 61 + 3 mm heavy", cloud_hour_heavy(61, true, 30), 1);
	check("cloud 65 + 0.8 mm not heavy", cloud_hour_heavy(65, true, 8), 0);
	check("cloud 65 without mm heavy (code)", cloud_hour_heavy(65, false, 0), 1);
	check("cloud 75 heavy snow (code)", cloud_hour_heavy(75, false, 0), 1);
	check("cloud 71 + 1.2 mm snow heavy", cloud_hour_heavy(71, true, 12), 1);
	check("cloud 68 mixed never heavy", cloud_hour_heavy(68, true, 100), 0);
	check("cloud 3 overcast dry not heavy", cloud_hour_heavy(3, true, 0), 0);
	check("cloud 45 fog not heavy", cloud_hour_heavy(45, false, 0), 0);
	/* split per-layer mask */
	check("split: not heavy -> 0", cloud_split_heavy_mask(63, false, 7), 0);
	check("split: storm -> every drawn layer", cloud_split_heavy_mask(95, true, CLOUD_LAYER_LOW | CLOUD_LAYER_HIGH), CLOUD_LAYER_LOW | CLOUD_LAYER_HIGH);
	check("split: heavy rain -> low only", cloud_split_heavy_mask(65, true, 7), CLOUD_LAYER_LOW);
	check("split: heavy rain, no low -> mid", cloud_split_heavy_mask(65, true, CLOUD_LAYER_MID | CLOUD_LAYER_HIGH), CLOUD_LAYER_MID);
	check("split: heavy rain, only high -> none", cloud_split_heavy_mask(65, true, CLOUD_LAYER_HIGH), 0);
	/* cloud thickness per layer (3.0.0) */
	check("thick: none -> normal", cloud_thick_for_layer(CLOUD_THICK_NONE, 0, 0), CLOUD_THICK_NORMAL);
	check("thick: junk 7 -> normal", cloud_thick_for_layer(7, CLOUD_LAYER_LOW, 7), CLOUD_THICK_NORMAL);
	check("thick: total keeps light", cloud_thick_for_layer(CLOUD_THICK_LIGHT, 0, 0), CLOUD_THICK_LIGHT);
	check("thick: total keeps thick", cloud_thick_for_layer(CLOUD_THICK_THICK, 0, 0), CLOUD_THICK_THICK);
	check("thick: split light -> every layer", cloud_thick_for_layer(CLOUD_THICK_LIGHT, CLOUD_LAYER_HIGH, 7), CLOUD_THICK_LIGHT);
	check("thick: split thick -> low when drawn", cloud_thick_for_layer(CLOUD_THICK_THICK, CLOUD_LAYER_LOW, 7), CLOUD_THICK_THICK);
	check("thick: split thick -> mid normal when low drawn", cloud_thick_for_layer(CLOUD_THICK_THICK, CLOUD_LAYER_MID, 7), CLOUD_THICK_NORMAL);
	check("thick: split thick, no low -> mid", cloud_thick_for_layer(CLOUD_THICK_THICK, CLOUD_LAYER_MID, CLOUD_LAYER_MID | CLOUD_LAYER_HIGH), CLOUD_THICK_THICK);
	check("thick: split thick, high only -> high", cloud_thick_for_layer(CLOUD_THICK_THICK, CLOUD_LAYER_HIGH, CLOUD_LAYER_HIGH), CLOUD_THICK_THICK);
	{
		const uint8_t b[18] = {0x39, 0x01};
		check("thick unpack h0", cloud_thick_unpack(b, 0), 1);
		check("thick unpack h1", cloud_thick_unpack(b, 1), 2);
		check("thick unpack h2", cloud_thick_unpack(b, 2), 3);
		check("thick unpack h3", cloud_thick_unpack(b, 3), 0);
		check("thick unpack h4", cloud_thick_unpack(b, 4), 1);
		check("thick unpack h71", cloud_thick_unpack(b, 71), 0);
	}
	printf("%d/%d passed\n", n - fails, n);
	return fails ? 1 : 0;
}
