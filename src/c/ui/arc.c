/**
 * Round arc warp — see arc.h.
 *
 * @license   GPL-3.0-or-later
 */
#include "arc.h"

#if defined(PBL_ROUND)
#include <stdlib.h>
#include <string.h>

static int prv_cx(void) { return PBL_DISPLAY_WIDTH / 2; }
static int prv_cy(void) { return PBL_DISPLAY_HEIGHT / 2; }
#define prv_cx_pub prv_cx
#define prv_cy_pub prv_cy

/* Angular samples per radius row: >= outer arc length (124 * 2.09 ≈ 260)
 * so the outermost row has no tangential holes. */
#define ARC_SAMPLES 336 /* 2.8.3: 128 * 2.44 rad ≈ 313 */

static int16_t s_sin[ARC_SAMPLES]; /* r * s_sin >> 12 */
static int16_t s_cos[ARC_SAMPLES];
static uint8_t s_col[ARC_SAMPLES]; /* source column 0..ARC_SRC_W-1 */
static bool s_ready;
static int s_top_h = 60, s_bot_h = 48;
/* One scratch buffer, allocated once at window load and reused by both
 * bands every frame (2.8.2: no per-frame 11 KB malloc). */
static uint8_t *s_buf;

bool arc_init(void) {
	if (!s_buf)
		s_buf = malloc(ARC_MAX_ROWS * ARC_SRC_W);
	return s_buf != NULL;
}

void arc_deinit(void) {
	free(s_buf);
	s_buf = NULL;
}

static void prv_init(void) {
	if (s_ready)
		return;
	for (int k = 0; k < ARC_SAMPLES; k++) {
		/* θ from -sweep/2 .. +sweep/2, sampled at column centres. */
		int32_t a = DEG_TO_TRIGANGLE(ARC_SWEEP_DEG) * (2 * k + 1) / (2 * ARC_SAMPLES) -
		            DEG_TO_TRIGANGLE(ARC_SWEEP_DEG) / 2;
		s_sin[k] = (int16_t)(sin_lookup(a) >> 4); /* 65536 -> 4096 */
		s_cos[k] = (int16_t)(cos_lookup(a) >> 4);
		s_col[k] = (uint8_t)((2 * k + 1) * ARC_SRC_W / (2 * ARC_SAMPLES));
	}
	s_ready = true;
}

void arc_set_geometry(int top_h, int bottom_h) {
	s_top_h = top_h > ARC_MAX_ROWS ? ARC_MAX_ROWS : top_h;
	s_bot_h = bottom_h > ARC_MAX_ROWS ? ARC_MAX_ROWS : bottom_h;
}

int arc_angle_at(GPoint p, bool top) {
	int dx = p.x - prv_cx_pub();
	int dy = top ? prv_cy_pub() - p.y : p.y - prv_cy_pub();
	/* Angle from vertical, clockwise positive (top) / mirrored (bottom). */
	int32_t a = atan2_lookup(dx, dy);
	if (a > TRIG_MAX_ANGLE / 2)
		a -= TRIG_MAX_ANGLE;
	return top ? (int)a : -(int)a;
}

/* Copy the (2r+1)^2 box around c (r <= ARC_BOX_R) from the frame buffer;
 * pixels off screen read as bg. */
static void prv_read_box(GBitmap *fb, GPoint c, int r, uint8_t bg, uint8_t *box) {
	int w = 2 * r + 1;
	for (int y = 0; y < w; y++) {
		int sy = c.y - r + y;
		bool row_ok = sy >= 0 && sy < PBL_DISPLAY_HEIGHT;
		GBitmapDataRowInfo ri = gbitmap_get_data_row_info(fb, row_ok ? sy : 0);
		for (int x = 0; x < w; x++) {
			int sx = c.x - r + x;
			box[y * w + x] = (row_ok && sx >= ri.min_x && sx <= ri.max_x) ? ri.data[sx] : bg;
		}
	}
}

void arc_snapshot_box(GContext *ctx, GPoint c, int r, GColor bg, uint8_t *out) {
	if (r > ARC_BOX_R)
		r = ARC_BOX_R;
	GBitmap *fb = graphics_capture_frame_buffer(ctx);
	if (!fb)
		return;
	/* 2.8.7: off-screen pixels read as bg, exactly like arc_rotate_box,
	 * so they never count as glyph pixels (stray dots at the bezel). */
	prv_read_box(fb, c, r, bg.argb, out);
	graphics_release_frame_buffer(ctx, fb);
}

/* Tilt an upright glyph onto the arc tangent (nearest neighbour). With
 * `before` (a snapshot taken just before the glyph was drawn) only the
 * glyph's own pixels move: the box is restored to `before` and the rotated
 * glyph pixels are written on top, so nothing around it is erased (2.8.5).
 * Without it the whole box is rotated and the corners become bg. */
void arc_rotate_box(GContext *ctx, GPoint c, int r, int32_t angle, GColor bg,
                    const uint8_t *before) {
	if (r > ARC_BOX_R)
		r = ARC_BOX_R;
	uint8_t box[ARC_BOX_W * ARC_BOX_W];
	int w = 2 * r + 1;
	GBitmap *fb = graphics_capture_frame_buffer(ctx);
	if (!fb)
		return;
	prv_read_box(fb, c, r, bg.argb, box);
	int32_t cs = cos_lookup(angle), sn = sin_lookup(angle);
	for (int y = -r; y <= r; y++) {
		int dy = c.y + y;
		if (dy < 0 || dy >= PBL_DISPLAY_HEIGHT)
			continue;
		GBitmapDataRowInfo ri = gbitmap_get_data_row_info(fb, dy);
		for (int x = -r; x <= r; x++) {
			int dx = c.x + x;
			if (dx < ri.min_x || dx > ri.max_x)
				continue;
			/* inverse of a clockwise rotation (screen y down) */
			int sx = (int)((x * cs + y * sn) / TRIG_MAX_RATIO);
			int sy = (int)((-x * sn + y * cs) / TRIG_MAX_RATIO);
			bool in = sx >= -r && sx <= r && sy >= -r && sy <= r;
			int si = (sy + r) * w + sx + r;
			if (before) {
				bool glyph = in && box[si] != before[si];
				ri.data[dx] = glyph ? box[si] : before[(y + r) * w + x + r];
			} else {
				ri.data[dx] = in ? box[si] : bg.argb;
			}
		}
	}
	graphics_release_frame_buffer(ctx, fb);
}

/* Exact (non-sampled) mapping for overlays. */
static GPoint prv_map(int src_x, int r, bool top) {
	int32_t a = DEG_TO_TRIGANGLE(ARC_SWEEP_DEG) * (2 * (src_x - ARC_SRC_X) + 1) / (2 * ARC_SRC_W) -
	            DEG_TO_TRIGANGLE(ARC_SWEEP_DEG) / 2;
	int x = prv_cx() + (int)((int32_t)r * sin_lookup(a) / TRIG_MAX_RATIO);
	int dy = (int)((int32_t)r * cos_lookup(a) / TRIG_MAX_RATIO);
	return GPoint(x, top ? prv_cy() - dy : prv_cy() + dy);
}

GPoint arc_map_top(int src_x, int src_y) {
	return prv_map(src_x, ARC_R_TOP - (src_y - ARC_SRC_Y), true);
}

GlyphXf arc_glyph_xf_top(int src_x, int src_y) {
	GPoint s = arc_map_top(src_x, src_y);
	int32_t a = arc_angle_at(s, true);
	GlyphXf t = {(int16_t)s.x, (int16_t)s.y, cos_lookup(a), sin_lookup(a)};
	return t;
}

GPoint arc_map_bottom(int src_x, int src_y) {
	/* Meteogram: top row innermost, baseline/ticks at the bezel. */
	int y0 = ARC_SRC_Y + s_top_h;
	return prv_map(src_x, ARC_R_OUTER - s_bot_h + 1 + (src_y - y0), false);
}

static bool prv_band(GBitmap *fb, int src_y0, int rows, bool top, uint8_t bg) {
	uint8_t *buf = s_buf;
	if (!buf) {
		/* No scratch buffer: blank the flat strips so nothing garbled shows
		 * (caller then skips the curved overlays). */
		for (int r = 0; r < rows; r++) {
			GBitmapDataRowInfo ri = gbitmap_get_data_row_info(fb, src_y0 + r);
			for (int x = ri.min_x; x <= ri.max_x; x++)
				ri.data[x] = bg;
		}
		return false;
	}
	for (int r = 0; r < rows; r++) {
		GBitmapDataRowInfo ri = gbitmap_get_data_row_info(fb, src_y0 + r);
		for (int c = 0; c < ARC_SRC_W; c++) {
			int x = ARC_SRC_X + c;
			buf[r * ARC_SRC_W + c] = (x >= ri.min_x && x <= ri.max_x) ? ri.data[x] : bg;
		}
		/* Clear the scratch row (the time block repaints on top later). */
		for (int x = ri.min_x; x <= ri.max_x; x++)
			ri.data[x] = bg;
	}
	int cx = prv_cx(), cy = prv_cy();
	/* Consecutive samples mostly stay on one screen row: reuse its row info
	 * instead of looking it up per pixel (2.8.5). */
	int ri_y = -1;
	GBitmapDataRowInfo ri = {0};
	for (int r = 0; r < rows; r++) {
		int rad = top ? ARC_R_TOP - r : ARC_R_OUTER - rows + 1 + r;
		const uint8_t *src = &buf[r * ARC_SRC_W];
		for (int k = 0; k < ARC_SAMPLES; k++) {
			int x = cx + ((rad * s_sin[k]) >> 12);
			int dy = (rad * s_cos[k]) >> 12;
			int y = top ? cy - dy : cy + dy;
			if (y < 0 || y >= PBL_DISPLAY_HEIGHT)
				continue;
			if (y != ri_y) {
				ri = gbitmap_get_data_row_info(fb, y);
				ri_y = y;
			}
			if (x < ri.min_x || x > ri.max_x)
				continue;
			ri.data[x] = src[s_col[k]];
		}
	}
	return true;
}

bool arc_warp(GContext *ctx, GColor bg) {
	prv_init();
	GBitmap *fb = graphics_capture_frame_buffer(ctx);
	if (!fb)
		return false;
	bool ok = prv_band(fb, ARC_SRC_Y, s_top_h, true, bg.argb);
	ok = prv_band(fb, ARC_SRC_Y + s_top_h, s_bot_h, false, bg.argb) && ok;
	graphics_release_frame_buffer(ctx, fb);
	return ok;
}
#endif
