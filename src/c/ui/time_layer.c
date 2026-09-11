/**
 * Time layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "time_layer.h"
#include "../generated/icons.h"
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct TimeLayer {
	Layer *container;
	TextLayer *city_label;
	TextLayer *cond_label; // weather condition icon right of city
	TextLayer *time_label;
	TextLayer *step_label;
	TextLayer *date_label;
	Layer *status_layer;   // BT disconnected & quiet mode indicators left of time
	GFont icon_font;
	bool bt_connected;
	bool quiet_mode;
	bool light_theme;
	bool show_bt_alert;
	bool show_silent_mode;
	char city_buf[24];
	char cond_glyph[8];
	char time_buf[8];
	char step_buf[12];
	char date_buf[32];
};

static void prv_draw_silent_bell(GContext *ctx, GPoint origin, GColor color) {
	graphics_context_set_stroke_color(ctx, color);
	graphics_context_set_stroke_width(ctx, 1);
	int ox = origin.x;
	int oy = origin.y;

	// Bell top finial / loop
	graphics_draw_line(ctx, GPoint(ox + 6, oy + 1), GPoint(ox + 7, oy + 1));

	// Bell dome & flared body
	graphics_draw_line(ctx, GPoint(ox + 5, oy + 3), GPoint(ox + 8, oy + 3));
	graphics_draw_line(ctx, GPoint(ox + 5, oy + 3), GPoint(ox + 3, oy + 8));
	graphics_draw_line(ctx, GPoint(ox + 8, oy + 3), GPoint(ox + 10, oy + 8));

	// Bell rim / lip
	graphics_draw_line(ctx, GPoint(ox + 2, oy + 9), GPoint(ox + 11, oy + 9));

	// Bell clapper
	graphics_draw_line(ctx, GPoint(ox + 6, oy + 10), GPoint(ox + 7, oy + 10));

	// Diagonal slash across the bell (silent mode indicator)
	graphics_context_set_stroke_width(ctx, 1);
	graphics_draw_line(ctx, GPoint(ox + 1, oy + 1), GPoint(ox + 12, oy + 12));
}

static void prv_status_update_proc(Layer *layer, GContext *ctx) {
	TimeLayer *tl = *(TimeLayer **)layer_get_data(layer);
	if (!tl)
		return;

	bool show_bt = tl->show_bt_alert && !tl->bt_connected;
	bool show_quiet = tl->show_silent_mode && tl->quiet_mode;

	if (!show_bt && !show_quiet)
		return;

	GRect bounds = layer_get_bounds(layer);
	int icon_size = 18;
#if PBL_DISPLAY_HEIGHT >= 228
	icon_size = 18;
#else
	icon_size = 14;
#endif

	int ox = 3;

	if (show_bt && show_quiet) {
		int half_h = bounds.size.h / 2;
		int y_bt = (half_h - icon_size) / 2;
		int y_qm = half_h + (half_h - 14) / 2;

#if defined(PBL_COLOR)
		graphics_context_set_text_color(ctx, tl->light_theme ? GColorRed : GColorSunsetOrange);
#else
		graphics_context_set_text_color(ctx, tl->light_theme ? GColorBlack : GColorWhite);
#endif
		graphics_draw_text(ctx, ICON_BLUETOOTH__OFF, tl->icon_font,
		                   GRect(ox, y_bt, icon_size + 4, icon_size),
		                   GTextOverflowModeTrailingEllipsis,
		                   GTextAlignmentLeft, NULL);

#if defined(PBL_COLOR)
		GColor quiet_col = tl->light_theme ? GColorCobaltBlue : GColorPictonBlue;
#else
		GColor quiet_col = tl->light_theme ? GColorBlack : GColorWhite;
#endif
		prv_draw_silent_bell(ctx, GPoint(ox, y_qm), quiet_col);
	} else {
		if (show_bt) {
			int y = (bounds.size.h - icon_size) / 2;
#if defined(PBL_COLOR)
			graphics_context_set_text_color(ctx, tl->light_theme ? GColorRed : GColorSunsetOrange);
#else
			graphics_context_set_text_color(ctx, tl->light_theme ? GColorBlack : GColorWhite);
#endif
			graphics_draw_text(ctx, ICON_BLUETOOTH__OFF, tl->icon_font,
			                   GRect(ox, y, icon_size + 4, icon_size),
			                   GTextOverflowModeTrailingEllipsis,
			                   GTextAlignmentLeft, NULL);
		} else {
			int y = (bounds.size.h - 14) / 2;
#if defined(PBL_COLOR)
			GColor quiet_col = tl->light_theme ? GColorCobaltBlue : GColorPictonBlue;
#else
			GColor quiet_col = tl->light_theme ? GColorBlack : GColorWhite;
#endif
			prv_draw_silent_bell(ctx, GPoint(ox, y), quiet_col);
		}
	}
}

static void prv_remove_leading_zero(char *buf, size_t len) {
	bool prev_nondigit = true;
	size_t i = 0;
	while (buf[i]) {
		if (buf[i] == '0' && prev_nondigit) {
			memmove(&buf[i], &buf[i + 1], len - i - 1);
		} else {
			prev_nondigit = !(buf[i] >= '0' && buf[i] <= '9');
			i++;
		}
	}
}

static const char *const s_day_names[] = {
	"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
};

static void prv_format_date(char *out, size_t out_len, const char *fmt, const struct tm *t) {
	char custom_fmt[64];
	const char *a_pos = strstr(fmt, "%A");
	if (a_pos) {
		int day_idx = (t->tm_wday >= 0 && t->tm_wday < 7) ? t->tm_wday : 0;
		const char *day_name = s_day_names[day_idx];
		snprintf(custom_fmt, sizeof(custom_fmt), "%.*s%s%s",
		         (int)(a_pos - fmt), fmt, day_name, a_pos + 2);
		fmt = custom_fmt;
	}
	strftime(out, out_len, fmt, t);
	prv_remove_leading_zero(out, out_len);
}

static void prv_update_location_row(TimeLayer *tl) {
	if (!tl || !tl->container)
		return;
	GRect frame = layer_get_frame(tl->container);
	int w = frame.size.w;
	GFont city_font = fonts_get_system_font(TL_SMALL_FONT_KEY);
	GColor col = tl->light_theme ? GColorBlack : GColorWhite;
	text_layer_set_text_color(tl->city_label, col);
	text_layer_set_text_color(tl->cond_label, col);

	if (tl->cond_glyph[0] == '\0') {
		layer_set_frame(text_layer_get_layer(tl->city_label), GRect(0, 0, w, TL_SMALL_H));
		text_layer_set_text_alignment(tl->city_label, GTextAlignmentCenter);
		layer_set_hidden(text_layer_get_layer(tl->cond_label), true);
		return;
	}

	// Calculate city text content width
	GSize city_size = graphics_text_layout_get_content_size(
	    tl->city_buf, city_font, GRect(0, 0, w - 28, TL_SMALL_H),
	    GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft);

	int icon_w = 18;
	int gap = 5;
	int total_w = city_size.w + gap + icon_w;
	if (total_w > w)
		total_w = w;
	int start_x = (w - total_w) / 2;

	layer_set_frame(text_layer_get_layer(tl->city_label),
	                GRect(start_x, 0, city_size.w, TL_SMALL_H));
	text_layer_set_text_alignment(tl->city_label, GTextAlignmentLeft);

	layer_set_frame(text_layer_get_layer(tl->cond_label),
	                GRect(start_x + city_size.w + gap, 1, icon_w, TL_SMALL_H));
	text_layer_set_text(tl->cond_label, tl->cond_glyph);
	layer_set_hidden(text_layer_get_layer(tl->cond_label), false);
}

// Place the step label in the right flank between the centered time digits and
// the watch edge, then center the number inside that flank.
static void prv_layout_step_label(TimeLayer *tl) {
	if (!tl || !tl->container || !tl->step_label)
		return;
	GRect frame = layer_get_frame(tl->container);
	int w = frame.size.w;
	int time_y = TL_SMALL_H - TL_TIME_PAD;
	int step_h = 18;
	int step_y = time_y + TL_TIME_PAD + (TL_TIME_H - TL_TIME_PAD - step_h) / 2;

	GFont time_font = fonts_get_system_font(TL_TIME_FONT_KEY);
	const char *time_str = tl->time_buf[0] ? tl->time_buf : "00:00";
	GSize time_size = graphics_text_layout_get_content_size(
	    time_str, time_font, GRect(0, 0, w, TL_TIME_H),
	    GTextOverflowModeWordWrap, GTextAlignmentCenter);

	int gap = 4;
	int edge = 2;
	int slot_left = (w + time_size.w) / 2 + gap;
	if (slot_left < w / 2)
		slot_left = w / 2;
	int slot_right = w - edge;
	int slot_w = slot_right - slot_left;
	if (slot_w < 18) {
		slot_w = 18;
		slot_left = w - edge - slot_w;
	}

	layer_set_frame(text_layer_get_layer(tl->step_label),
	                GRect(slot_left, step_y, slot_w, step_h));
	text_layer_set_text_alignment(tl->step_label, GTextAlignmentCenter);
}

TimeLayer *time_layer_create(GRect frame) {
	TimeLayer *tl = malloc(sizeof(TimeLayer));
	if (!tl)
		return NULL;

	tl->city_buf[0] = '\0';
	tl->cond_glyph[0] = '\0';
	tl->time_buf[0] = '\0';
	tl->step_buf[0] = '\0';
	tl->date_buf[0] = '\0';
	tl->bt_connected = true;
	tl->quiet_mode = false;
	tl->light_theme = false;
	tl->show_bt_alert = true;
	tl->show_silent_mode = true;

#if PBL_DISPLAY_HEIGHT >= 228
	tl->icon_font = fonts_load_custom_font(
	    resource_get_handle(RESOURCE_ID_CARBON_ICONS_18));
#else
	tl->icon_font = fonts_load_custom_font(
	    resource_get_handle(RESOURCE_ID_CARBON_ICONS_14));
#endif

	tl->container = layer_create(frame);
	int w = frame.size.w;

	// City name — top, small font, full width centered
	GFont city_font = fonts_get_system_font(TL_SMALL_FONT_KEY);
	tl->city_label = text_layer_create(GRect(0, 0, w, TL_SMALL_H));
	text_layer_set_background_color(tl->city_label, GColorClear);
	text_layer_set_text_color(tl->city_label, GColorWhite);
	text_layer_set_font(tl->city_label, city_font);
	text_layer_set_text_alignment(tl->city_label, GTextAlignmentCenter);
	text_layer_set_text(tl->city_label, tl->city_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->city_label));

	// Weather condition glyph right next to city
	tl->cond_label = text_layer_create(GRect(0, 0, 18, TL_SMALL_H));
	text_layer_set_background_color(tl->cond_label, GColorClear);
	text_layer_set_text_color(tl->cond_label, GColorWhite);
	text_layer_set_font(tl->cond_label, tl->icon_font);
	text_layer_set_text_alignment(tl->cond_label, GTextAlignmentCenter);
	text_layer_set_text(tl->cond_label, tl->cond_glyph);
	layer_set_hidden(text_layer_get_layer(tl->cond_label), true);
	layer_add_child(tl->container, text_layer_get_layer(tl->cond_label));

	// Time — large centered. LECO_60 on emery (>=228px); LECO_36_BOLD
	// everywhere else. TL_TIME_PAD is the internal top gap measured from each
	// font's line metrics.
	GFont time_font = fonts_get_system_font(TL_TIME_FONT_KEY);
	// Shift the time label up by TL_TIME_PAD so visible digits start flush with
	// city text
	int time_y = TL_SMALL_H - TL_TIME_PAD;
	tl->time_label = text_layer_create(GRect(0, time_y, w, TL_TIME_H));
	text_layer_set_background_color(tl->time_label, GColorClear);
	text_layer_set_text_color(tl->time_label, GColorWhite);
	text_layer_set_font(tl->time_label, time_font);
	text_layer_set_text_alignment(tl->time_label, GTextAlignmentCenter);
	text_layer_set_text(tl->time_label, tl->time_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->time_label));

	// Step count — right flank of the time row; recentered after time is known
	int tz_ampm_y = time_y + TL_TIME_PAD + (TL_TIME_H - TL_TIME_PAD - 18) / 2;
	GFont step_font = fonts_get_system_font(FONT_KEY_GOTHIC_18);
	tl->step_label = text_layer_create(GRect(w / 2, tz_ampm_y, w / 2 - 2, 18));
	text_layer_set_background_color(tl->step_label, GColorClear);
	text_layer_set_text_color(tl->step_label, GColorLightGray);
	text_layer_set_font(tl->step_label, step_font);
	text_layer_set_text_alignment(tl->step_label, GTextAlignmentCenter);
	text_layer_set_text(tl->step_label, tl->step_buf);
	layer_set_hidden(text_layer_get_layer(tl->step_label), true);
	layer_add_child(tl->container, text_layer_get_layer(tl->step_label));
	prv_layout_step_label(tl);

	// Status indicators (BT disconnected & Quiet mode) left of time
#if PBL_PLATFORM_GABBRO || defined(PBL_ROUND)
	int status_x = 16;
#else
	int status_x = 2;
#endif
	int status_h = TL_TIME_H - TL_TIME_PAD;
	tl->status_layer = layer_create_with_data(GRect(status_x, time_y + TL_TIME_PAD, 34, status_h), sizeof(TimeLayer *));
	*(TimeLayer **)layer_get_data(tl->status_layer) = tl;
	layer_set_update_proc(tl->status_layer, prv_status_update_proc);
	layer_add_child(tl->container, tl->status_layer);

	// Date — below time
	int date_y = time_y + TL_TIME_H;
	GFont date_font = fonts_get_system_font(TL_SMALL_FONT_KEY);
	tl->date_label = text_layer_create(GRect(0, date_y, w, TL_SMALL_H));
	text_layer_set_background_color(tl->date_label, GColorClear);
	text_layer_set_text_color(tl->date_label, GColorWhite);
	text_layer_set_font(tl->date_label, date_font);
	text_layer_set_text_alignment(tl->date_label, GTextAlignmentCenter);
	text_layer_set_text(tl->date_label, tl->date_buf);
	layer_add_child(tl->container, text_layer_get_layer(tl->date_label));

	return tl;
}

void time_layer_destroy(TimeLayer *layer) {
	if (!layer)
		return;
	layer_destroy(layer->status_layer);
	fonts_unload_custom_font(layer->icon_font);
	text_layer_destroy(layer->date_label);
	text_layer_destroy(layer->step_label);
	text_layer_destroy(layer->time_label);
	text_layer_destroy(layer->city_label);
	text_layer_destroy(layer->cond_label);
	layer_destroy(layer->container);
	free(layer);
}

Layer *time_layer_get_layer(TimeLayer *layer) {
	return layer ? layer->container : NULL;
}

void time_layer_set_status(TimeLayer *layer, bool bt_connected, bool quiet_time) {
	if (!layer)
		return;
	if (layer->bt_connected != bt_connected || layer->quiet_mode != quiet_time) {
		layer->bt_connected = bt_connected;
		layer->quiet_mode = quiet_time;
		layer_mark_dirty(layer->status_layer);
	}
}

void time_layer_set_city(TimeLayer *layer, const char *city) {
	if (!layer || !city)
		return;
	strncpy(layer->city_buf, city, sizeof(layer->city_buf) - 1);
	layer->city_buf[sizeof(layer->city_buf) - 1] = '\0';
	prv_update_location_row(layer);
}

void time_layer_set_condition(TimeLayer *layer, WeatherCondition cond, bool is_day) {
	if (!layer)
		return;
	if (cond == WEATHER_CONDITION_UNKNOWN) {
		layer->cond_glyph[0] = '\0';
	} else {
		const char *icon = weather_condition_to_icon(cond, is_day);
		if (icon) {
			strncpy(layer->cond_glyph, icon, sizeof(layer->cond_glyph) - 1);
			layer->cond_glyph[sizeof(layer->cond_glyph) - 1] = '\0';
		} else {
			layer->cond_glyph[0] = '\0';
		}
	}
	prv_update_location_row(layer);
}

void time_layer_update(TimeLayer *layer, struct tm *tick_time,
                       const Settings *settings) {
	if (!layer || !tick_time || !settings)
		return;

	GColor text_color = settings->light_theme ? GColorBlack : GColorWhite;
	GColor sub_color = settings->light_theme ? GColorDarkGray : GColorLightGray;
	layer->light_theme = settings->light_theme;
	layer->show_bt_alert = settings->show_bt_alert;
	layer->show_silent_mode = settings->show_silent_mode;
	layer_mark_dirty(layer->status_layer);
	prv_update_location_row(layer);
	text_layer_set_text_color(layer->time_label, text_color);
	text_layer_set_text_color(layer->date_label, text_color);
	text_layer_set_text_color(layer->step_label, sub_color);

	bool is_24h = clock_is_24h_style();

	// Time string
	if (is_24h) {
		strftime(layer->time_buf, sizeof(layer->time_buf), "%H:%M", tick_time);
	} else {
		// 12h: format and strip leading zero
		char tmp[8];
		strftime(tmp, sizeof(tmp), "%I:%M", tick_time);
		const char *src = (tmp[0] == '0') ? tmp + 1 : tmp;
		strncpy(layer->time_buf, src, sizeof(layer->time_buf) - 1);
		layer->time_buf[sizeof(layer->time_buf) - 1] = '\0';
	}
	text_layer_set_text(layer->time_label, layer->time_buf);
	prv_layout_step_label(layer);
	layer_set_hidden(text_layer_get_layer(layer->step_label),
	                 !settings->show_step_count || layer->step_buf[0] == '\0');

	// Date — format string stored in settings; leading zeros stripped
	// automatically. Full weekday name expanded if %A is used.
	prv_format_date(layer->date_buf, sizeof(layer->date_buf), settings->date_format,
	                tick_time);
	text_layer_set_text(layer->date_label, layer->date_buf);
}

void time_layer_set_steps(TimeLayer *layer, int steps) {
	if (!layer)
		return;
	if (steps < 0)
		steps = 0;
	int thousands = steps / 1000;
	bool show = settings_get()->show_step_count && thousands >= 1;
	if (show) {
		snprintf(layer->step_buf, sizeof(layer->step_buf), "%d", thousands);
	} else {
		layer->step_buf[0] = '\0';
	}
	text_layer_set_text(layer->step_label, layer->step_buf);
	prv_layout_step_label(layer);
	layer_set_hidden(text_layer_get_layer(layer->step_label), !show);
}
