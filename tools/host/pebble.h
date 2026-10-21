/* 3.0.3: minimal host stand-in for <pebble.h>, enough to compile
 * src/c/modules/settings.c for tools/test_settings_migration.c.
 * Persist storage is an in-memory table the test can fill and inspect. */
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define PERSIST_DATA_MAX_LENGTH 256
typedef enum { TUPLE_BYTE_ARRAY = 0, TUPLE_CSTRING = 1, TUPLE_UINT = 2, TUPLE_INT = 3 } TupleType;
typedef struct {
	uint32_t key;
	TupleType type;
	uint16_t length;
	union { uint8_t data[1]; char cstring[1]; uint8_t uint8; uint16_t uint16; uint32_t uint32;
		int8_t int8; int16_t int16; int32_t int32; } value[];
} Tuple;
typedef struct DictionaryIterator DictionaryIterator;
typedef struct AppTimer AppTimer;
typedef void (*AppTimerCallback)(void *data);

/* host persist table (tools/test_settings_migration.c) */
typedef struct { bool used; int size; uint8_t data[PERSIST_DATA_MAX_LENGTH]; } HostPersist;
extern HostPersist g_host_persist[32];

static inline bool persist_exists(uint32_t k) { return k < 32 && g_host_persist[k].used; }
static inline int persist_get_size(uint32_t k) { return persist_exists(k) ? g_host_persist[k].size : -1; }
static inline int persist_read_data(uint32_t k, void *buf, size_t n) {
	if (!persist_exists(k)) return -1;
	int c = (int)n < g_host_persist[k].size ? (int)n : g_host_persist[k].size;
	memcpy(buf, g_host_persist[k].data, c);
	return c;
}
static inline int persist_write_data(uint32_t k, const void *buf, size_t n) {
	if (k >= 32 || n > PERSIST_DATA_MAX_LENGTH) return -1;
	g_host_persist[k].used = true; g_host_persist[k].size = (int)n; memcpy(g_host_persist[k].data, buf, n);
	return (int)n;
}
static inline int32_t persist_read_int(uint32_t k) { int32_t v = 0; persist_read_data(k, &v, sizeof(v)); return v; }
static inline int persist_write_int(uint32_t k, int32_t v) { return persist_write_data(k, &v, sizeof(v)); }
static inline int persist_delete(uint32_t k) { if (k < 32) g_host_persist[k].used = false; return 0; }
static inline Tuple *dict_find(const DictionaryIterator *it, uint32_t key) { (void)it; (void)key; return NULL; }
static inline AppTimer *app_timer_register(uint32_t ms, AppTimerCallback cb, void *d) { (void)ms; (void)cb; (void)d; return NULL; }
static inline bool app_timer_reschedule(AppTimer *t, uint32_t ms) { (void)t; (void)ms; return false; }
static inline void app_timer_cancel(AppTimer *t) { (void)t; }
