/**
 * Phone alarm (3.3.0), see phone_alarm.h.
 *
 * @license   GPL-3.0-or-later
 */
#include "phone_alarm.h"
#include "settings.h"
#include "storage_keys.h"

static int32_t s_alarm; /* UTC epoch seconds, 0 = none */

void phone_alarm_init(void) {
	s_alarm = persist_exists(STORAGE_KEY_PHONE_ALARM) ? persist_read_int(STORAGE_KEY_PHONE_ALARM) : 0;
	if (s_alarm < 0)
		s_alarm = 0;
}

bool phone_alarm_apply_from_message(DictionaryIterator *iter) {
	Tuple *t = dict_find(iter, MESSAGE_KEY_PHONE_ALARM_TIME);
	if (!t)
		return false;
	int32_t v = settings_tuple_int(t);
	if (v < 0)
		v = 0;
	if (v != s_alarm) {
		s_alarm = v;
		if (v)
			persist_write_int(STORAGE_KEY_PHONE_ALARM, v);
		else if (persist_exists(STORAGE_KEY_PHONE_ALARM))
			persist_delete(STORAGE_KEY_PHONE_ALARM);
	}
	return true;
}

time_t phone_alarm_next(time_t now) {
	return (s_alarm > 0 && (time_t)s_alarm > now) ? (time_t)s_alarm : 0;
}

#if defined(DEMO_SCENARIO)
void phone_alarm_set_demo(time_t t) {
	s_alarm = (int32_t)t;
}
#endif
