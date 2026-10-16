/**
 * Glyph transform (2.8.4): small markers/icons are drawn relative to an
 * anchor through a rotation, so gabbro can draw them at native (emery) pixel
 * size after the arc warp, only turned to the arc tangent. On rect platforms
 * the transform is the identity and every helper calls the plain SDK
 * primitive with the same coordinates (pixel-identical output).
 *
 * @license   GPL-3.0-or-later
 */
#pragma once
#include <pebble.h>

typedef struct {
	int16_t x, y;   /* anchor in screen/layer coordinates */
	int32_t cs, sn; /* cos/sin of the rotation (TRIG_MAX_RATIO scale) */
} GlyphXf;

static inline GlyphXf gx_identity(int x, int y) {
	GlyphXf t = {(int16_t)x, (int16_t)y, TRIG_MAX_RATIO, 0};
	return t;
}

static inline bool gx_is_identity(const GlyphXf *t) {
	return t->sn == 0 && t->cs == TRIG_MAX_RATIO;
}

static inline int gx_rdiv(int32_t v) {
	return v >= 0 ? (int)((v + TRIG_MAX_RATIO / 2) / TRIG_MAX_RATIO)
	              : -(int)((-v + TRIG_MAX_RATIO / 2) / TRIG_MAX_RATIO);
}

/* Offset (dx, dy) from the anchor, rotated. */
static inline GPoint gx_pt(const GlyphXf *t, int dx, int dy) {
	if (gx_is_identity(t))
		return GPoint(t->x + dx, t->y + dy);
	return GPoint(t->x + gx_rdiv((int32_t)dx * t->cs - (int32_t)dy * t->sn),
	              t->y + gx_rdiv((int32_t)dx * t->sn + (int32_t)dy * t->cs));
}

/* Bit mask glyph: rows[y] bit x set = pixel at local (ox + x, oy + y). */
void gx_mask(GContext *ctx, const GlyphXf *t, const uint16_t *rows, int w, int h, int ox, int oy,
             GColor c);
void gx_fill_rect(GContext *ctx, const GlyphXf *t, GRect r, GColor c);
/* 1 px outline with the 4 corner pixels cut (graphics_draw_round_rect r=1). */
void gx_round_rect1(GContext *ctx, const GlyphXf *t, GRect r, GColor c);

static inline void gx_fill_circle(GContext *ctx, const GlyphXf *t, int dx, int dy, int r, GColor c) {
	graphics_context_set_fill_color(ctx, c);
	graphics_fill_circle(ctx, gx_pt(t, dx, dy), r);
}

static inline void gx_draw_circle(GContext *ctx, const GlyphXf *t, int dx, int dy, int r, GColor c) {
	graphics_context_set_stroke_color(ctx, c);
	graphics_context_set_stroke_width(ctx, 1);
	graphics_draw_circle(ctx, gx_pt(t, dx, dy), r);
}

static inline void gx_line(GContext *ctx, const GlyphXf *t, int x0, int y0, int x1, int y1, GColor c) {
	graphics_context_set_stroke_color(ctx, c);
	graphics_context_set_stroke_width(ctx, 1);
	graphics_draw_line(ctx, gx_pt(t, x0, y0), gx_pt(t, x1, y1));
}
