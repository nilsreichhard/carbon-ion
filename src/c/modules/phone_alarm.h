/**
 * Phone alarm (3.3.0): the next alarm of the Android phone, sent by the
 * Carbon Ion Android companion app (PebbleKit Android) as PHONE_ALARM_TIME =
 * UTC epoch seconds, 0 = no alarm. Kept in persist so it survives a restart of
 * the face while the phone is away. iOS has no API for the next alarm.
 *
 * @license   GPL-3.0-or-later
 */
#pragma once
#include <pebble.h>

void phone_alarm_init(void);
/* True when the message carried PHONE_ALARM_TIME (the value may be unchanged). */
bool phone_alarm_apply_from_message(DictionaryIterator *iter);
/* The alarm when one is set and still ahead of `now`, else 0. */
time_t phone_alarm_next(time_t now);
#if defined(DEMO_SCENARIO)
void phone_alarm_set_demo(time_t t);
#endif
