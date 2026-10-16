/**
 * Watchface touch (double-tap range cycle) — all touch code in one place
 * (2.8.2). Compiled only with CARBON_TOUCH_ENABLED (see settings.h): stock
 * PebbleOS does not deliver touch to watchfaces yet. Re-enable by building
 * with -DCARBON_WATCHFACE_TOUCH=1 and restoring the Clay toggle
 * (SETTING_TAP_CYCLE 10068).
 *
 * @license GPL-3.0-or-later
 */
#include "touch.h"

#if defined(CARBON_TOUCH_ENABLED)
static void (*s_on_double_tap)(void);
static bool s_touch_on;

static uint32_t prv_now_ms(void) {
	time_t sec;
	uint16_t ms;
	time_ms(&sec, &ms);
	return (uint32_t)sec * 1000u + ms;
}

/* Double-tap state machine. Stock PebbleOS (4.33) does not deliver touch to
 * watchfaces yet (developer guide: "touch is intentionally restricted to
 * watchapps"), so this only fires on firmware that allows it. Tolerances are
 * sized for real fingers; position comes from Touchdown/PositionUpdate (not
 * Liftoff), and if a Liftoff never arrives the next Touchdown still counts. */
#define TAP_MAX_MS 500         /* touch-down → lift-off for one tap */
#define TAP_MAX_MOVE_SQ 400    /* moved < 20px */
#define DOUBLE_TAP_GAP_MS 600  /* first tap down → second tap down */
static int16_t s_touch_x, s_touch_y;
static bool s_touch_moved, s_touch_down;
static uint32_t s_touch_ms, s_first_tap_ms;
static void prv_touch_handler(const TouchEvent *ev, void *ctx) {
	(void)ctx;
	uint32_t now = prv_now_ms();
	switch (ev->type) {
	case TouchEvent_Touchdown:
		if (s_first_tap_ms != 0 && now - s_first_tap_ms <= DOUBLE_TAP_GAP_MS) {
			s_first_tap_ms = 0;
			s_touch_down = false;
#if defined(CARBON_TOUCH_DEBUG)
			APP_LOG(APP_LOG_LEVEL_DEBUG, "double tap -> cycle range");
#endif
			if (s_on_double_tap)
				s_on_double_tap();
			return;
		}
		s_touch_x = ev->x;
		s_touch_y = ev->y;
		s_touch_ms = now;
		s_touch_moved = false;
		s_touch_down = true;
		/* Provisional first tap: a missing Liftoff must not break the gesture. */
		s_first_tap_ms = now;
		break;
	case TouchEvent_PositionUpdate:
		if (s_touch_down) {
			int dx = ev->x - s_touch_x, dy = ev->y - s_touch_y;
			if (dx * dx + dy * dy > TAP_MAX_MOVE_SQ)
				s_touch_moved = true;
		}
		break;
	case TouchEvent_Liftoff:
		if (s_touch_down && (s_touch_moved || now - s_touch_ms > TAP_MAX_MS))
			s_first_tap_ms = 0; /* swipe / long press is not a tap */
		s_touch_down = false;
		break;
	default:
		break;
	}
}

void touch_input_apply(Window *window, bool want, void (*on_double_tap)(void)) {
	s_on_double_tap = on_double_tap;
	/* Only power the sensor when the OS will actually deliver touch. */
	if (want && !s_touch_on && touch_service_is_enabled()) {
		window_set_touch_bridge_disabled(window, true);
		touch_service_subscribe(prv_touch_handler, NULL);
		s_touch_on = true;
	} else if (!want && s_touch_on) {
		touch_service_unsubscribe();
		s_touch_on = false;
	}
}

void touch_input_stop(void) {
	if (s_touch_on)
		touch_service_unsubscribe();
	s_touch_on = false;
}
#endif
