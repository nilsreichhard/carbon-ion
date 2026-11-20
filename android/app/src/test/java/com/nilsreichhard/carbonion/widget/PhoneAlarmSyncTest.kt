package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** 1.1.0: the next phone alarm for the watch face (PebbleKit Android 2 + classic intent). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneAlarmSyncTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The watch face's package.json: same UUID, message key and companion package as this app. */
    @Test
    fun matchesWatchPackageJson() {
        val f = listOf(File("../../package.json"), File("../package.json")).first { it.exists() }
        val pebble = JSONObject(f.readText()).getJSONObject("pebble")
        assertEquals(PhoneAlarmSync.WATCH_UUID, UUID.fromString(pebble.getString("uuid")))
        assertEquals(PhoneAlarmSync.KEY_PHONE_ALARM_TIME, pebble.getJSONObject("messageKeys").getInt("PHONE_ALARM_TIME"))
        assertEquals(PhoneAlarmSync.KEY_WEATHER_REQUEST, pebble.getJSONObject("messageKeys").getInt("WEATHER_REQUEST"))
        assertEquals(PhoneAlarmSync.KEY_PHONE_ALARM_TIME.toLong(), PhoneAlarmSync.KEY_PHONE_ALARM_TIME_U.toLong())
        assertEquals(PhoneAlarmSync.KEY_WEATHER_REQUEST.toLong(), PhoneAlarmSync.KEY_WEATHER_REQUEST_U.toLong())
        val android = pebble.getJSONObject("companionApp").getJSONObject("android")
        assertEquals("the face must work without this app", false, android.getBoolean("required"))
        val apps = android.getJSONArray("apps")
        assertTrue((0 until apps.length()).any { apps.getJSONObject(it).getString("package") == ctx.packageName })
    }

    @Test
    fun classicIntent() {
        val i = PhoneAlarmSync.classicIntent(0, 7)
        assertEquals("com.getpebble.action.app.SEND", i.action)
        @Suppress("DEPRECATION")
        assertEquals(PhoneAlarmSync.WATCH_UUID, i.getSerializableExtra("uuid"))
        assertEquals(7, i.getIntExtra("transaction_id", -1))
        val tuple = JSONArray(i.getStringExtra("msg_data")).getJSONObject(0)
        assertEquals(10095, tuple.getInt("key")); assertEquals("int", tuple.getString("type"))
        assertEquals(4, tuple.getInt("length")); assertEquals(0, tuple.getInt("value"))
    }

    @Test
    fun alarmSecondsFromNextAlarm() {
        val prefs = ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(NextAlarm.DEBUG_PREF, 1_795_000_123_456L).commit()
        assertEquals(1_795_000_123, PhoneAlarmSync.alarmSeconds(ctx))
        prefs.edit().putLong(NextAlarm.DEBUG_PREF, NextAlarm.DEBUG_NONE).commit()
        assertEquals(0, PhoneAlarmSync.alarmSeconds(ctx))
        prefs.edit().remove(NextAlarm.DEBUG_PREF).commit()
    }

    /** PebbleKit 2 types are Java 21 bytecode, which this JVM (17) cannot load: the summary is tested by name;
     *  the real send path is exercised on the emulator (logcat "CarbonAlarmSync"). */
    @Test
    fun describeResults() {
        assertEquals("no Pebble app", PhoneAlarmSync.describeNames(null))
        assertEquals("no watch connected", PhoneAlarmSync.describeNames(emptyList()))
        assertEquals("ok", PhoneAlarmSync.describeNames(listOf("FailedTimeout", "Success")))
        assertEquals("FailedDifferentAppOpen", PhoneAlarmSync.describeNames(listOf("FailedDifferentAppOpen")))
    }

    /** The receiver and the PebbleKit 2 listener are declared for the right actions. */
    @Test
    fun manifestDeclaresReceiverAndListener() {
        val pm = ctx.packageManager
        val alarm = pm.queryBroadcastReceivers(Intent("android.app.action.NEXT_ALARM_CLOCK_CHANGED").setPackage(ctx.packageName), 0)
        assertTrue(alarm.any { it.activityInfo.name.endsWith(".PhoneAlarmReceiver") })
        val svc = pm.queryIntentServices(Intent("io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH").setPackage(ctx.packageName), 0)
        assertTrue(svc.any { it.serviceInfo.name.endsWith(".CarbonPebbleListener") && it.serviceInfo.exported })
    }
}
