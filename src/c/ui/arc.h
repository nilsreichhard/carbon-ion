/**
 * Round (gabbro) arc warp: the timeline/weather strips render straight into a
 * scratch band of the frame buffer, then are re-projected onto arcs that are
 * concentric with the bezel. Time maps linearly to angle (same for every
 * strip, so the now-needle is radial). Rect platforms don't use this.
 *
 * @license   GPL-3.0-or-later
 */
#pragma once
#include <pebble.h>
#include "glyph.h"

/* Point-mapping callback type (GPoint is a function-like macro). */
typedef GPoint ArcPoint;
typedef ArcPoint (*ArcMapFn)(int x, int y);

#if defined(PBL_ROUND)
#define ARC_SRC_X 30      /* source strips: x 30..229 (fits every source row) */
#define ARC_SRC_W 200
#define ARC_SRC_Y 78      /* first source row (top stack, then meteogram) */
#define ARC_SWEEP_DEG 140 /* total angular sweep of each band (2.8.3: was 120) */
#define ARC_R_OUTER 128   /* outer radius of the bottom band (2.8.3: was 124) */
#define ARC_R_TOP 129     /* outer radius of the top band (2.8.6: was 128) */
#define ARC_MAX_ROWS 64   /* scratch buffer rows (max band depth) */

/* Allocate / free the reused warp buffer (window load / unload). */
bool arc_init(void);
void arc_deinit(void);
/* Tangent angle (TRIG units, clockwise positive) of a screen point on the
 * top/bottom arc, for tilting glyphs along the curve. */
int arc_angle_at(GPoint p, bool top);
/* Rotate the (2r+1)^2 box around c in the frame buffer (r <= ARC_BOX_R).
 * Take arc_snapshot_box() before drawing the glyph and pass it as `before`
 * to move only the glyph pixels (NULL: rotate the whole box). */
#define ARC_BOX_R 9
#define ARC_BOX_W (2 * ARC_BOX_R + 1)
void arc_snapshot_box(GContext *ctx, GPoint c, int r, GColor bg, uint8_t *out);
void arc_rotate_box(GContext *ctx, GPoint c, int r, int32_t angle, GColor bg,
                    const uint8_t *before);

void arc_set_geometry(int top_h, int bottom_h);
/* Screen point for a source pixel in the top stack / meteogram source rows. */
GPoint arc_map_top(int src_x, int src_y);
/* Glyph transform at a source point of the top band: mapped anchor, rotated
 * to the arc tangent (2.8.4: markers drawn at native size after the warp). */
GlyphXf arc_glyph_xf_top(int src_x, int src_y);
GPoint arc_map_bottom(int src_x, int src_y);
/* Warp both bands in place (frame buffer); false if the buffer is missing
 * (flat strips are blanked; skip curved overlays). */
bool arc_warp(GContext *ctx, GColor bg);
#endif
