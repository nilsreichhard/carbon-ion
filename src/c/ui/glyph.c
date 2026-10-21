/**
 * Glyph transform — see glyph.h.
 *
 * @license   GPL-3.0-or-later
 */
#include "glyph.h"
#include <stdlib.h>

/* Inverse-sample the rotated rect over its screen bounding box: crisp, no
 * holes. `inside` tests the local pixel (lx, ly) in TRIG_MAX_RATIO units. */
static void prv_sample(GContext *ctx, const GlyphXf *t, GRect r, GColor c, bool outline) {
	graphics_context_set_stroke_color(ctx, c);
	int ext = (r.size.w > r.size.h ? r.size.w : r.size.h) + 2 +
	          (abs(r.origin.x) > abs(r.origin.y) ? abs(r.origin.x) : abs(r.origin.y));
	const int32_t H = TRIG_MAX_RATIO / 2;
	for (int Y = -ext; Y <= ext; Y++) {
		for (int X = -ext; X <= ext; X++) {
			int32_t lx = (int32_t)X * t->cs + (int32_t)Y * t->sn;
			int32_t ly = -(int32_t)X * t->sn + (int32_t)Y * t->cs;
			/* local pixel index = round(l) */
			int px = (lx >= -H) ? (int)((lx + H) / TRIG_MAX_RATIO) : -(int)((-lx - H + TRIG_MAX_RATIO - 1) / TRIG_MAX_RATIO);
			int py = (ly >= -H) ? (int)((ly + H) / TRIG_MAX_RATIO) : -(int)((-ly - H + TRIG_MAX_RATIO - 1) / TRIG_MAX_RATIO);
			int x0 = r.origin.x, y0 = r.origin.y, x1 = x0 + r.size.w - 1, y1 = y0 + r.size.h - 1;
			if (px < x0 || px > x1 || py < y0 || py > y1)
				continue;
			if (outline) {
				bool edge = (px == x0 || px == x1 || py == y0 || py == y1);
				bool corner = (px == x0 || px == x1) && (py == y0 || py == y1);
				if (!edge || corner)
					continue;
			}
			graphics_draw_pixel(ctx, GPoint(t->x + X, t->y + Y));
		}
	}
}

void gx_fill_rect(GContext *ctx, const GlyphXf *t, GRect r, GColor c) {
	if (gx_is_identity(t)) {
		graphics_context_set_fill_color(ctx, c);
		graphics_fill_rect(ctx, GRect(t->x + r.origin.x, t->y + r.origin.y, r.size.w, r.size.h), 0, GCornerNone);
		return;
	}
	prv_sample(ctx, t, r, c, false);
}

void gx_round_rect1(GContext *ctx, const GlyphXf *t, GRect r, GColor c) {
	if (gx_is_identity(t)) {
		graphics_context_set_stroke_color(ctx, c);
		graphics_context_set_stroke_width(ctx, 1);
		graphics_draw_round_rect(ctx, GRect(t->x + r.origin.x, t->y + r.origin.y, r.size.w, r.size.h), 1);
		return;
	}
	prv_sample(ctx, t, r, c, true);
}

void gx_mask(GContext *ctx, const GlyphXf *t, const uint16_t *rows, int w, int h, int ox, int oy,
             GColor c) {
	gx_mask2(ctx, t, rows, w, h, ox, oy, c, c);
}

/* 3.0.1: as gx_mask, with c2 on every other screen pixel (checker), so a
 * 1 px glyph reads as a mid tone between c and c2 without gaps. */
void gx_mask2(GContext *ctx, const GlyphXf *t, const uint16_t *rows, int w, int h, int ox, int oy,
              GColor c, GColor c2) {
	bool two = !gcolor_equal(c, c2);
	graphics_context_set_stroke_color(ctx, c);
	int ext = (w > h ? w : h) + 2;
	const int32_t H = TRIG_MAX_RATIO / 2;
	for (int Y = -ext; Y <= ext; Y++) {
		for (int X = -ext; X <= ext; X++) {
			int32_t lx = (int32_t)X * t->cs + (int32_t)Y * t->sn + H;
			int32_t ly = -(int32_t)X * t->sn + (int32_t)Y * t->cs + H;
			int px = lx >= 0 ? (int)(lx / TRIG_MAX_RATIO) : -(int)((-lx + TRIG_MAX_RATIO - 1) / TRIG_MAX_RATIO);
			int py = ly >= 0 ? (int)(ly / TRIG_MAX_RATIO) : -(int)((-ly + TRIG_MAX_RATIO - 1) / TRIG_MAX_RATIO);
			px -= ox;
			py -= oy;
			if (px < 0 || px >= w || py < 0 || py >= h || !(rows[py] & (1u << px)))
				continue;
			if (two)
				graphics_context_set_stroke_color(ctx, (((t->x + X) + (t->y + Y)) & 1) ? c2 : c);
			graphics_draw_pixel(ctx, GPoint(t->x + X, t->y + Y));
		}
	}
}
