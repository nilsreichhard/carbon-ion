/* 3.3.1: ctypes wrapper so tools/sim_batt_accuracy.py drives the real
 * modules/batt_eta.c (one model instance, host persist shim). */
#include "pebble.h"
#include "../src/c/modules/batt_eta.h"
#include "../src/c/modules/storage_keys.h"

HostPersist g_host_persist[32];
static BattEta s_b;

void sim_reset(long learned) {
	memset(g_host_persist, 0, sizeof(g_host_persist));
	if (learned > 0)
		persist_write_int(STORAGE_KEY_BATT_LEARNED, (int32_t)learned);
	batt_eta_init(&s_b);
}
void sim_set(long now, int pct, int charging, int plugged) {
	batt_eta_set(&s_b, (time_t)now, (uint8_t)pct, charging != 0, plugged != 0);
}
/* Face re-created after another app: reload from persist, like daylight_layer_create. */
void sim_cold_start(long now, int pct) {
	batt_eta_init(&s_b);
	batt_eta_set(&s_b, (time_t)now, (uint8_t)pct, false, false);
}
long sim_eta(long now, int m) {
	time_t t = 0;
	batt_eta_time(&s_b, (time_t)now, m, (time_t)now + 400L * 86400, &t);
	return (long)t;
}
int sim_profile_ready(void) { return batt_eta_profile_ready(&s_b); }
