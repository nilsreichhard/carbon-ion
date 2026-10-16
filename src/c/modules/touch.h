/**
 * Watchface touch input (double tap). No-op unless CARBON_TOUCH_ENABLED.
 *
 * @license GPL-3.0-or-later
 */
#pragma once
#include <pebble.h>
#include "settings.h" /* defines CARBON_TOUCH_ENABLED when built with touch */

#if defined(CARBON_TOUCH_ENABLED)
void touch_input_apply(Window *window, bool want, void (*on_double_tap)(void));
void touch_input_stop(void);
#else
static inline void touch_input_stop(void) {}
#endif
