/**
 * Precipitation layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "precip_layer.h"
#include "../modules/settings.h"
#include "../modules/precip_map.h"
#include "graph_common.h"
#include <stddef.h>
#include <stdlib.h>

struct PrecipLayer {
	Layer *layer;
	uint8_t prob[MAX_GRAPH_HOURS];
	uint8_t hourly_code[MAX_GRAPH_HOURS];
	uint8_t mm[MAX_GRAPH_HOURS]; /* 0.1 mm/h steps (2.9.6) */
	bool have_mm;
	uint8_t current_hour;
};

#if defined(PBL_COLOR)
/* 2.9.6 palette: hue = type, lightness = intensity. Dark theme: heavier is
 * brighter; light theme: heavier is darker. */
static GColor prv_bar_color(uint8_t kind, bool heavy, bool light) {
	switch (kind) {
	case PRECIP_KIND_SNOW:
		return heavy ? (light ? GColorDarkGray : GColorWhite) : GColorLightGray;
	case PRECIP_KIND_MIX:
		return light ? GColorTiffanyBlue : GColorElectricBlue;
	case PRECIP_KIND_HAIL:
		return GColorBabyBlueEyes; /* light theme adds a Liberty outline */
	default:
		return heavy ? (light ? GColorCobaltBlue : GColorPictonBlue)
		             : (light ? GColorVividCerulean : GColorCobaltBlue); /* 3.0.1: light rain on light Picton -> VividCerulean */
	}
}
#endif

/* Amount mode: shade 2 solid, 1 checker, 0 sparse dots; the tip row is solid. */
static void prv_fill_shaded(GContext *ctx, int x0, int w, int h, uint8_t shade) {
	if (shade >= 2) {
		graphics_fill_rect(ctx, GRect(x0, 0, w, h), 0, GCornerNone);
		return;
	}
	for (int y = 0; y < h - 1; y++)
		for (int x = x0; x < x0 + w; x++)
			if (shade == 1 ? (((x + y) & 1) == 0) : ((x & 1) == 0 && (y & 1) == 0))
				graphics_draw_pixel(ctx, GPoint(x, y));
	graphics_fill_rect(ctx, GRect(x0, h - 1, w, 1), 0, GCornerNone);
}

static int prv_precip_min_prob(PrecipSensitivity sens) {
	switch (sens) {
	case PRECIP_SENS_VERY: return 5;
	case PRECIP_SENS_BALANCED: return 30;
	case PRECIP_SENS_INSENSITIVE: return 50;
	case PRECIP_SENS_OFF: return 101; /* never */
	case PRECIP_SENS_SENSITIVE:
	default: return 15;
	}
}

static void prv_update_proc(Layer *layer, GContext *ctx) {
	PrecipLayer *pl = *(PrecipLayer **)layer_get_data(layer);
	GRect bounds = layer_get_bounds(layer);
	int graph_x = GRAPH_OFFSET_X;
	int graph_w = bounds.size.w - graph_x;
	int layer_h = bounds.size.h;

	/* Bars hang down from the cloud deck, one per hour, proportional x.
	 * Probability mode (default): height = chance above the sensitivity floor.
	 * Amount mode (2.9.6): height = mm/h (sqrt scale, full at 8 mm/h) and the
	 * chance is shading (solid >= 70 %, checker >= 40 %, dots below).
	 * Colour (precip_map.h): rain blue, snow grey, mixed/freezing teal, hail
	 * pale lavender; light vs heavy is lightness. Storms use their rain or
	 * hail colour; the thunder event marks them. */
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;

	int min_prob = prv_precip_min_prob(settings_get()->precip_sensitivity);
	bool amount = pl->have_mm && settings_get()->rain_height == RAIN_HEIGHT_AMOUNT;
#if defined(PBL_COLOR)
	bool light = settings_get()->light_theme;
#endif

	for (int i = 0; i < total_hours; i++) {
		int x0 = graph_x + (long)i * graph_w / total_hours;
		int x1 = graph_x + (long)(i + 1) * graph_w / total_hours;
		int bar_w = x1 - x0 - 1;
		if (bar_w < 1)
			bar_w = 1;

		int prob = (int)pl->prob[i];
		if (prob < min_prob)
			continue;
		int bar_h;
		if (amount) {
			bar_h = precip_amount_px(pl->mm[i], layer_h - 2);
			if (bar_h < 1)
				continue;
		} else {
			/* Scale height from the sensitivity floor so bars still fill usefully. */
			int span = 100 - min_prob;
			if (span < 1) span = 1;
			int scaled = ((prob - min_prob) * 100) / span;
			if (scaled < 1) scaled = 1;
			bar_h = (scaled * (layer_h - 2)) / 100;
		}

#if defined(PBL_COLOR)
		uint8_t code = pl->hourly_code[i];
		uint8_t kind = precip_kind(code);
		GColor col = prv_bar_color(kind, precip_is_heavy(code, pl->have_mm, pl->mm[i]), light);
#else
		GColor col = GColorWhite;
#endif
		graphics_context_set_fill_color(ctx, col);
		graphics_context_set_stroke_color(ctx, col);
		if (amount)
			prv_fill_shaded(ctx, x0, bar_w, bar_h, precip_prob_shade((uint8_t)prob));
		else
			graphics_fill_rect(ctx, GRect(x0, 0, bar_w, bar_h), 0, GCornerNone);
#if defined(PBL_COLOR)
		if (kind == PRECIP_KIND_HAIL && light) {
			graphics_context_set_stroke_color(ctx, GColorLiberty);
			graphics_draw_rect(ctx, GRect(x0, 0, bar_w, bar_h));
		}
#endif
	}
}

PrecipLayer *precip_layer_create(GRect frame) {
	PrecipLayer *pl = malloc(sizeof(PrecipLayer));
	if (!pl)
		return NULL;
	memset(pl->prob, 0, sizeof(pl->prob));
	memset(pl->hourly_code, 0, sizeof(pl->hourly_code));
	memset(pl->mm, 0, sizeof(pl->mm));
	pl->have_mm = false;
	pl->current_hour = 0;

	pl->layer = layer_create_with_data(frame, sizeof(PrecipLayer *));
	*(PrecipLayer **)layer_get_data(pl->layer) = pl;
	layer_set_update_proc(pl->layer, prv_update_proc);
	return pl;
}

void precip_layer_destroy(PrecipLayer *layer) {
	if (!layer)
		return;
	layer_destroy(layer->layer);
	free(layer);
}

Layer *precip_layer_get_layer(PrecipLayer *layer) {
	return layer ? layer->layer : NULL;
}

void precip_layer_set_data(PrecipLayer *layer, const uint8_t *prob,
                           const uint8_t *hourly_code, const uint8_t *mm,
                           bool have_mm, uint8_t current_hour) {
	if (!layer)
		return;
	int total_hours = GRAPH_HOURS;
	if (total_hours > MAX_GRAPH_HOURS) total_hours = MAX_GRAPH_HOURS;
	memcpy(layer->prob, prob, total_hours);
	memcpy(layer->hourly_code, hourly_code, total_hours);
	memcpy(layer->mm, mm, total_hours);
	layer->have_mm = have_mm;
	layer->current_hour = current_hour;
	layer_mark_dirty(layer->layer);
}
