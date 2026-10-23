/**
 * Time layer
 *
 * @author    Nils Reich <https://github.com/nilsreichhard/carbon-ion>
 * @copyright 2026 Nils Reich
 * @license   GPL-3.0-or-later
 * @link      https://github.com/nilsreichhard/carbon-ion
 */

#include "time_layer.h"
#include "../modules/settings.h"
#include "../generated/icons.h"
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct TimeLayer {
	Layer *container;
	TextLayer *city_label;
	TextLayer *cond_label; // weather condition icon right of city
	Layer *time_label;     /* 3.0.1: fixed-cell digits (was a centred TextLayer) */
	GColor time_color;
	TextLayer *step_label;
	TextLayer *step_k_label; // "K" under step thousands
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
	char time_tmpl[8];     /* time_buf with every digit as '0' (layout box) */
	bool time_lead_blank;  /* 3.0.3: 12h, leading zero stripped: skip one cell */
	char step_buf[12];
	char date_buf[32];
};

/* 2.9.5: both status icons are solid rounded badges with an identical
 * 15x17 footprint, drawn from pixel maps so their size, edges and centre
 * match exactly ('#' = icon colour, '.' = background knock-out, ' ' = not
 * drawn). The "off" slash runs along the same diagonal in both. */
#define STATUS_ICON_W 15
#define STATUS_ICON_H 17
#define STATUS_ICON_X 3   /* left edge inside the status layer (3.0.1: 5 -> 4, 3.0.2: -> 3, 2 px clear of the hour digits) */
#define STATUS_ICON_GAP 6 /* vertical gap between the two badges */

static const char *const s_bt_off_bits[STATUS_ICON_H] = {
	"  ###########  ",
	" ############# ",
	"#.#####.#######",
	"##.####..######",
	"###.###.#.#####",
	"####.##.##.####",
	"#####.#.#.#####",
	"######...######",
	"#######.#######",
	"######...######",
	"#####.#.#.#####",
	"####.##.##.####",
	"#######.#.#.###",
	"#######..###.##",
	"#######.#####.#",
	" ############# ",
	"  ###########  ",
};

static const char *const s_quiet_bits[STATUS_ICON_H] = {
	"  ###########  ",
	" ############# ",
	"#.#############",
	"##.####.#######",
	"###.##...######",
	"####......#####",
	"######....#####",
	"#####.#...#####",
	"####...#...####",
	"####....#..####",
	"###......#..###",
	"##........#..##",
	"###########.###",
	"######...###.##",
	"#############.#",
	" ############# ",
	"  ###########  ",
};

/* Draws one pixel map as horizontal runs (crisp, no anti-aliasing). */
static void prv_draw_status_icon(GContext *ctx, const char *const *bits, int ox, int oy,
                                 GColor color, GColor bg) {
	for (int y = 0; y < STATUS_ICON_H; y++) {
		const char *row = bits[y];
		int x = 0;
		while (x < STATUS_ICON_W) {
			char c = row[x];
			int x2 = x;
			while (x2 < STATUS_ICON_W && row[x2] == c)
				x2++;
			if (c != ' ') {
				graphics_context_set_fill_color(ctx, c == '#' ? color : bg);
				graphics_fill_rect(ctx, GRect(ox + x, oy + y, x2 - x, 1), 0, GCornerNone);
			}
			x = x2;
		}
	}
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
	GColor bg = tl->light_theme ? GColorWhite : GColorBlack;
#if defined(PBL_COLOR)
	GColor bt_col = tl->light_theme ? GColorRed : GColorSunsetOrange;
	GColor quiet_col = tl->light_theme ? GColorCobaltBlue : GColorPictonBlue;
#else
	GColor bt_col = tl->light_theme ? GColorBlack : GColorWhite;
	GColor quiet_col = bt_col;
#endif

	/* One badge, or two stacked with a fixed gap: the group is centred on
	 * the time digits' ink (which starts 1 px below the layer top), so a lone
	 * icon sits exactly where the pair's centre is. */
	int n = (show_bt ? 1 : 0) + (show_quiet ? 1 : 0);
	int group_h = n * STATUS_ICON_H + (n - 1) * STATUS_ICON_GAP;
	int y = (bounds.size.h - group_h) / 2 + 1;
	if (show_bt) {
		prv_draw_status_icon(ctx, s_bt_off_bits, STATUS_ICON_X, y, bt_col, bg);
		y += STATUS_ICON_H + STATUS_ICON_GAP;
	}
	if (show_quiet)
		prv_draw_status_icon(ctx, s_quiet_bits, STATUS_ICON_X, y, quiet_col, bg);
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


/* 3.0.1: the time is laid out on a fixed box: the width of the same string
 * with every digit '0' (LECO digits share one advance; '1' is narrower), and
 * each digit is centred in a '0'-wide cell. So 11:11 sits exactly where
 * 22:22 does and nothing moves when thin digits come up. */
static void prv_time_template(const char *src, char *dst, int n) {
	int i = 0;
	for (; src[i] && i < n - 1; i++)
		dst[i] = (src[i] >= '0' && src[i] <= '9') ? '0' : src[i];
	dst[i] = '\0';
}

static int prv_text_w(const char *str, GFont f) {
	return graphics_text_layout_get_content_size(str, f, GRect(0, 0, 400, TL_TIME_H),
	                                             GTextOverflowModeWordWrap, GTextAlignmentLeft).w;
}

/* 3.0.3: time metrics, cached; they only change with the font (the face
 * redraws every minute, on gabbro every frame of the arc warp). */
static GFont s_tf = NULL;
static int16_t s_w0, s_adv0, s_dw[10], s_sep_adv;
static char s_sep = 0;
static void prv_time_cache(GFont f) {
	if (f == s_tf)
		return;
	s_tf = f;
	s_w0 = prv_text_w("0", f);
	s_adv0 = prv_text_w("00", f) - s_w0; /* digit advance */
	for (int d = 0; d < 10; d++) {
		char c1[2] = {(char)('0' + d), '\0'};
		s_dw[d] = prv_text_w(c1, f);
	}
	s_sep = 0;
}
static int prv_char_adv(char c, GFont f) {
	if (c >= '0' && c <= '9')
		return s_adv0;
	if (c != s_sep) { /* separator advance: "0:0" minus "00" */
		char t3[4] = {'0', c, '0', '\0'};
		s_sep = c;
		s_sep_adv = prv_text_w(t3, f) - (s_w0 + s_adv0);
	}
	return s_sep_adv;
}
/* Width from the first cell's left edge to the last cell's right edge (the
 * last cell counts its own width, not its advance). 3.0.3: the old centring
 * used the text layout width of the string, which includes the trailing
 * advance/side bearing of the last glyph, so the digits sat a few px left
 * of centre (emery 24 h: x = 22-167 on a 200 px screen). */
static int prv_cells_span(const char *str, GFont f) {
	int span = 0, n = (int)strlen(str);
	for (int i = 0; i < n; i++)
		span += (i == n - 1) ? ((str[i] >= '0' && str[i] <= '9') ? s_w0 : prv_char_adv(str[i], f))
		                     : prv_char_adv(str[i], f);
	return span;
}

#ifndef TIME_12H_ALIGN
#define TIME_12H_ALIGN 2 /* 12 h single-digit hour: 0 stable box, 1 centred real text, 2 shift adv0/2 */
#endif

/* x of the first drawn cell. Also gives the visible right end of the fixed
 * box for the step layout (via *box_right). */
static int prv_time_origin(TimeLayer *tl, GFont f, int w, int *box_right) {
	prv_time_cache(f);
	int box_span = prv_cells_span(tl->time_tmpl, f);
	int x_box = (w - box_span) / 2;
	if (box_right)
		*box_right = x_box + box_span;
	if (!tl->time_lead_blank)
		return x_box;
#if TIME_12H_ALIGN == 0
	return x_box + s_adv0;
#elif TIME_12H_ALIGN == 1
	{ /* centre the real visible glyphs (first/last digit inked width) */
		const char *b = tl->time_buf;
		int n = (int)strlen(b), span = prv_cells_span(b, f);
		int l = (b[0] >= '0' && b[0] <= '9') ? (s_w0 - s_dw[b[0] - '0']) / 2 : 0;
		int r = (b[n - 1] >= '0' && b[n - 1] <= '9') ? (s_w0 - s_dw[b[n - 1] - '0']) / 2 : 0;
		return (w - (span - l - r)) / 2 - l;
	}
#else
	return x_box + s_adv0 / 2;
#endif
}

static void prv_time_update_proc(Layer *layer, GContext *ctx) {
	TimeLayer *tl = *(TimeLayer **)layer_get_data(layer);
	if (!tl->time_buf[0])
		return;
	GFont f = fonts_get_system_font(TL_TIME_FONT_KEY);
	int w = layer_get_bounds(layer).size.w;
	int x = prv_time_origin(tl, f, w, NULL);
	graphics_context_set_text_color(ctx, tl->time_color);
	for (int i = 0; tl->time_buf[i]; i++) {
		char c[2] = {tl->time_buf[i], '\0'};
		bool digit = c[0] >= '0' && c[0] <= '9';
		int ox = digit ? (s_w0 - s_dw[c[0] - '0']) / 2 : 0;
		graphics_draw_text(ctx, c, f, GRect(x + ox, 0, w - (x + ox) > 0 ? w - (x + ox) : 1, TL_TIME_H),
		                   GTextOverflowModeWordWrap, GTextAlignmentLeft, NULL);
		x += prv_char_adv(c[0], f);
	}
}

static void prv_step_metrics(StepSize size, GFont *num_font, GFont *k_font,
                             int *num_h, int *k_h) {
	if (size == STEP_SIZE_LARGE) {
		/* Bold: number and K both Gothic 18 Bold (no 14 Bold on Pebble) */
		*num_font = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
		*k_font = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
		*num_h = 18;
		*k_h = 18;
	} else {
		*num_font = fonts_get_system_font(FONT_KEY_GOTHIC_18);
		*k_font = fonts_get_system_font(FONT_KEY_GOTHIC_14);
		*num_h = 18;
		*k_h = 14;
	}
}

/* Center the step stack in the flank between time digits and the right edge.
 * Same math for Normal and Bold — do not widen/shift the slot by weight. */
#define TL_STEP_INK_DY (-2) /* 3.1.1 */
#define TL_STEP_2D_DX 2      /* 3.1.2 */
#define TL_STEP_INK_SLACK 2  /* 3.1.2: blank right of the number's ink */

static void prv_layout_step_label(TimeLayer *tl) {
	if (!tl || !tl->container || !tl->step_label || !tl->step_k_label)
		return;
	GRect frame = layer_get_frame(tl->container);
	int w = frame.size.w;
	int time_y = TL_SMALL_H - TL_TIME_PAD - 1;
	GFont num_font, k_font;
	int num_h, k_h;
	prv_step_metrics(settings_get()->step_size, &num_font, &k_font, &num_h, &k_h);
	text_layer_set_font(tl->step_label, num_font);
	text_layer_set_font(tl->step_k_label, k_font);
	/* 3.1.1: both weights show 3 px between the number and the K (Bold's
	 * K box is 18 px, Normal's 14 px). */
	int num_k_gap = settings_get()->step_size == STEP_SIZE_LARGE ? -4 : -2;
	int stack_h = num_h + num_k_gap + k_h;
	int band_h = TL_TIME_H - TL_TIME_PAD;
	int step_y = time_y + TL_TIME_PAD + (band_h - stack_h) / 2;
	/* 3.1.1: the font boxes carry more blank below the ink than above, so the
	 * box-centred stack sat 2.5 px low; centre on the visible ink of the time
	 * digits (measured: within 0.5 px on emery and gabbro, both weights). */
	step_y += TL_STEP_INK_DY;
	if (step_y < time_y)
		step_y = time_y;

	GFont time_font = fonts_get_system_font(TL_TIME_FONT_KEY);
	/* 3.0.3: right end of the fixed time box as drawn (cells, not the text
	 * layout width with its trailing slack). */
	int box_right;
	if (!tl->time_tmpl[0])
		strncpy(tl->time_tmpl, "00:00", sizeof(tl->time_tmpl));
	prv_time_origin(tl, time_font, w, &box_right);

	/* 3.0.2: the stack is right-aligned to a fixed margin from the visible
	 * edge (on round screens the bezel curve at the stack's rows is added),
	 * and never comes closer than 4 px to the fixed time box. */
	int gap = 4;
#if defined(PBL_ROUND)
	int edge = 4;
	{
		int r = w / 2, dy_max = 0;
		int abs_y0 = frame.origin.y + step_y, abs_y1 = abs_y0 + stack_h;
		int cy = r; /* gabbro: container spans the full width; centre row = r */
		int d0 = abs_y0 - cy, d1 = abs_y1 - cy;
		if (d0 < 0) d0 = -d0;
		if (d1 < 0) d1 = -d1;
		dy_max = d0 > d1 ? d0 : d1;
		int half = 0; /* integer sqrt(r^2 - dy^2) */
		int rem = r * r - dy_max * dy_max;
		while ((half + 1) * (half + 1) <= rem)
			half++;
		edge += r - half;
	}
#else
	int edge = 4;
#endif
	int flank_left = box_right + gap;
	if (flank_left < w / 2)
		flank_left = w / 2;
	int flank_right = w - edge;

	/* Never ellipsize, never shrink the font (Nils, 3.0.3): 1K-99K all use
	 * the same face and flank_right stays at w - edge. If the count does not
	 * fit, the gap to the time gives way, down to 2 px of visible blank. */
	int text_pad = 2; /* TextLayer needs a little slack or it ellipsizes */
	/* 3.1.1: the stack box is sized for "00" in this weight and both lines
	 * are centred in it, so the K never moves between 1 and 2 digits. */
	GSize num_size = graphics_text_layout_get_content_size(
	    "00", num_font, GRect(0, 0, 100, num_h),
	    GTextOverflowModeWordWrap, GTextAlignmentLeft);
	/* The last '0' cell has 3 px of blank right of its ink (emery: ink ends
	 * at x=177, box at 180), so 2 px into the box still leaves >= 2 px visible. */
	int min_left = box_right - 2;
	if (min_left > flank_left)
		min_left = flank_left;
	GSize k_size = graphics_text_layout_get_content_size(
	    "K", k_font, GRect(0, 0, 100, k_h),
	    GTextOverflowModeWordWrap, GTextAlignmentLeft);
	int content_w = (num_size.w > k_size.w ? num_size.w : k_size.w) + text_pad;
	int slot_left = flank_right - content_w;
	if (slot_left < min_left) {
		slot_left = min_left;
		content_w = flank_right - min_left;
	}

	/* 3.1.2: two-digit counts (10K-99K) nudge only the number right, away
	 * from the minutes; the K box stays where it is. */
	int num_dx = 0;
	if (tl->step_buf[0] && tl->step_buf[1]) {
		/* Fit check incl. the nudge: the shifted number's right edge (its real
		 * width, centred in the box) must stay inside flank_right, which
		 * already holds the edge/bezel margin. Shrink the nudge if not. */
		GSize real = graphics_text_layout_get_content_size(
		    tl->step_buf, num_font, GRect(0, 0, 100, num_h),
		    GTextOverflowModeWordWrap, GTextAlignmentLeft);
		int num_right = slot_left + (content_w + real.w) / 2;
		/* The measured box ends 2 px right of the last digit's ink
		 * (side bearing), so +2 still leaves >= 3 px visible on emery
		 * (edge 4) and >= 5 px to the gabbro rim (measured). */
		num_dx = flank_right + TL_STEP_INK_SLACK - num_right;
		if (num_dx > TL_STEP_2D_DX)
			num_dx = TL_STEP_2D_DX;
		if (num_dx < 0)
			num_dx = 0;
	}
	layer_set_frame(text_layer_get_layer(tl->step_label),
	                GRect(slot_left + num_dx, step_y, content_w, num_h));
	text_layer_set_text_alignment(tl->step_label, GTextAlignmentCenter);
	layer_set_frame(text_layer_get_layer(tl->step_k_label),
	                GRect(slot_left, step_y + num_h + num_k_gap, content_w, k_h));
	text_layer_set_text_alignment(tl->step_k_label, GTextAlignmentCenter);
}

TimeLayer *time_layer_create(GRect frame) {
	TimeLayer *tl = malloc(sizeof(TimeLayer));
	if (!tl)
		return NULL;

	tl->city_buf[0] = '\0';
	tl->cond_glyph[0] = '\0';
	tl->time_buf[0] = '\0';
	tl->time_tmpl[0] = '\0';
	tl->time_lead_blank = false;
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
	int time_y = TL_SMALL_H - TL_TIME_PAD - 1;
	(void)time_font;
	tl->time_color = GColorWhite;
	tl->time_label = layer_create_with_data(GRect(0, time_y, w, TL_TIME_H), sizeof(TimeLayer *));
	*(TimeLayer **)layer_get_data(tl->time_label) = tl;
	layer_set_update_proc(tl->time_label, prv_time_update_proc);
	layer_add_child(tl->container, tl->time_label);

	// Step count — number with "K" stacked under it in the right flank
	int tz_ampm_y = time_y + TL_TIME_PAD + (TL_TIME_H - TL_TIME_PAD - 18) / 2;
	GFont step_font = fonts_get_system_font(FONT_KEY_GOTHIC_18);
	GFont step_k_font = fonts_get_system_font(FONT_KEY_GOTHIC_14);
	tl->step_label = text_layer_create(GRect(w / 2, tz_ampm_y, w / 2 - 2, 18));
	text_layer_set_background_color(tl->step_label, GColorClear);
	text_layer_set_text_color(tl->step_label, GColorLightGray);
	text_layer_set_font(tl->step_label, step_font);
	text_layer_set_text_alignment(tl->step_label, GTextAlignmentCenter);
	text_layer_set_text(tl->step_label, tl->step_buf);
	layer_set_hidden(text_layer_get_layer(tl->step_label), true);
	layer_add_child(tl->container, text_layer_get_layer(tl->step_label));

	tl->step_k_label = text_layer_create(GRect(w / 2, tz_ampm_y + 14, w / 2 - 2, 14));
	text_layer_set_background_color(tl->step_k_label, GColorClear);
	text_layer_set_text_color(tl->step_k_label, GColorLightGray);
	text_layer_set_font(tl->step_k_label, step_k_font);
	text_layer_set_text_alignment(tl->step_k_label, GTextAlignmentCenter);
	text_layer_set_text(tl->step_k_label, "K");
	layer_set_hidden(text_layer_get_layer(tl->step_k_label), true);
	layer_add_child(tl->container, text_layer_get_layer(tl->step_k_label));
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
	int date_y = time_y + TL_TIME_H - 1;
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
	text_layer_destroy(layer->step_k_label);
	text_layer_destroy(layer->step_label);
	layer_destroy(layer->time_label);
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
	GColor sub_color = settings->light_theme ? (g_high_contrast ? GColorBlack : GColorDarkGray)
	                                          : (g_high_contrast ? GColorWhite : GColorLightGray);
	layer->light_theme = settings->light_theme;
	layer->show_bt_alert = settings->show_bt_alert;
	layer->show_silent_mode = settings->show_silent_mode;
	layer_mark_dirty(layer->status_layer);
	prv_update_location_row(layer);
	layer->time_color = text_color;
	text_layer_set_text_color(layer->date_label, text_color);
	text_layer_set_text_color(layer->step_label, sub_color);
	text_layer_set_text_color(layer->step_k_label, sub_color);

	bool is_24h = clock_is_24h_style();

	// Time string
	/* 3.0.3: Leading zero setting. Auto keeps the old behaviour (24 h shows
	 * it, 12 h strips it). The box is always sized for "00:00"; without the
	 * zero the time is shifted by half a digit (TIME_12H_ALIGN) to stay
	 * centred. */
	bool strip = settings->leading_zero == LEADING_ZERO_OFF ||
	             (settings->leading_zero == LEADING_ZERO_AUTO && !is_24h);
	char tmp[8];
	strftime(tmp, sizeof(tmp), is_24h ? "%H:%M" : "%I:%M", tick_time);
	const char *src = (strip && tmp[0] == '0') ? tmp + 1 : tmp;
	strncpy(layer->time_buf, src, sizeof(layer->time_buf) - 1);
	layer->time_buf[sizeof(layer->time_buf) - 1] = '\0';
	prv_time_template("00:00", layer->time_tmpl, sizeof(layer->time_tmpl));
	layer->time_lead_blank = strlen(layer->time_buf) < strlen(layer->time_tmpl);
	layer_mark_dirty(layer->time_label);
	prv_layout_step_label(layer);
	bool hide_steps = !settings->show_step_count || layer->step_buf[0] == '\0';
	layer_set_hidden(text_layer_get_layer(layer->step_label), hide_steps);
	layer_set_hidden(text_layer_get_layer(layer->step_k_label), hide_steps);

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
	/* 2.8.5: health events fire often; only touch the layers (and so redraw
	 * the face, incl. the gabbro arc warp) when what is shown changes. */
	static int s_last_k = -1;
	static int8_t s_last_show = -1, s_last_size = -1;
	int8_t size = (int8_t)settings_get()->step_size;
	if (thousands == s_last_k && (int8_t)show == s_last_show && size == s_last_size)
		return;
	s_last_k = thousands;
	s_last_show = (int8_t)show;
	s_last_size = size;
	if (show) {
		/* 3.0.3: capped at 99K (two digits always fit the flank) */
		snprintf(layer->step_buf, sizeof(layer->step_buf), "%d", thousands > 99 ? 99 : thousands);
	} else {
		layer->step_buf[0] = '\0';
	}
	text_layer_set_text(layer->step_label, layer->step_buf);
	prv_layout_step_label(layer);
	layer_set_hidden(text_layer_get_layer(layer->step_label), !show);
	layer_set_hidden(text_layer_get_layer(layer->step_k_label), !show);
}
