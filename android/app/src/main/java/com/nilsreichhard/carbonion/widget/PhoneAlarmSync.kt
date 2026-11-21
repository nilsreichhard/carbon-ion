package com.nilsreichhard.carbonion.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Sends the phone's next alarm to the Carbon Ion watch face (3.3.0 / app 1.1.0).
 *
 * Transport: PebbleKit Android 2 (io.rebble.pebblekit2), which the Core Devices Pebble app serves.
 * The watch face lists this package under `companionApp.android` in its package.json, so the Pebble
 * app accepts its AppMessages next to the face's own PebbleKit JS (`required: false`: the face
 * works without this app). Only the running face gets messages, so the alarm is sent again whenever
 * the face opens ([CarbonPebbleListener.onAppOpened]) and on every watch weather request.
 * A classic PebbleKit intent goes out as well, for phone apps that still serve PebbleKit 1
 * (Gadgetbridge, the original Pebble app); the Core app ignores it for a PebbleKit 2 face.
 *
 * Message: PHONE_ALARM_TIME (10095) = alarm time, UTC epoch seconds; 0 = no alarm.
 */
object PhoneAlarmSync {
    val WATCH_UUID: UUID = UUID.fromString("b3e351bb-53f8-4af7-8b98-9b44da3bad50")
    const val KEY_PHONE_ALARM_TIME = 10095
    /** The watch's weather request: the face is open, a good moment to resend. */
    const val KEY_WEATHER_REQUEST = 10000
    /** The same keys as dictionary keys (UInt literals: converting the Int consts trips the Kotlin 2 compiler). */
    val KEY_PHONE_ALARM_TIME_U: UInt = 10095u
    val KEY_WEATHER_REQUEST_U: UInt = 10000u
    private const val TAG = "CarbonAlarmSync"
    private const val SEND_TIMEOUT_MS = 9_000L
    private const val PREF_LAST = "pebble_alarm_last"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Alarm time in UTC epoch seconds, 0 when the phone has no alarm. */
    fun alarmSeconds(ctx: Context): Int = NextAlarm.info(ctx)?.let { (it.triggerMillis / 1000L).toInt() } ?: 0

    fun dictionary(seconds: Int): PebbleDictionary =
        mapOf(KEY_PHONE_ALARM_TIME_U to PebbleDictionaryItem.Int32(seconds))

    /** Classic PebbleKit (1.x) AppMessage intent with the same dictionary. */
    fun classicIntent(seconds: Int, transactionId: Int = 0): Intent = Intent("com.getpebble.action.app.SEND")
        .putExtra("uuid", WATCH_UUID)
        .putExtra("transaction_id", transactionId)
        .putExtra("msg_data", """[{"key":$KEY_PHONE_ALARM_TIME,"type":"int","length":4,"value":$seconds}]""")

    /** Fire and forget (activities, the listener service). */
    fun send(ctx: Context, delayMs: Long = 0L) {
        val app = ctx.applicationContext
        scope.launch { if (delayMs > 0) delay(delayMs); sendNow(app) }
    }

    /** From a broadcast receiver: keeps the process alive until the send is done. */
    fun sendFromReceiver(receiver: BroadcastReceiver, ctx: Context) {
        val pending = receiver.goAsync()
        val app = ctx.applicationContext
        scope.launch { try { sendNow(app) } finally { pending.finish() } }
    }

    /** From a worker thread. */
    fun sendBlocking(ctx: Context) {
        runBlocking { sendNow(ctx.applicationContext) }
    }

    /** Sends and returns a short result for the log ("ok", "no watch", "face not open", …). */
    suspend fun sendNow(ctx: Context): String {
        // "Send next alarm to watch" off: send "no alarm" so the face drops one it already has.
        val seconds = if (WidgetStore.settings(ctx).alarmToWatch) alarmSeconds(ctx) else 0
        runCatching { ctx.sendBroadcast(classicIntent(seconds)) }
        val summary = try {
            val sender = DefaultPebbleSender(ctx)
            try {
                val res = withTimeoutOrNull(SEND_TIMEOUT_MS) { sender.sendDataToPebble(WATCH_UUID, dictionary(seconds)) }
                describe(res)
            } finally {
                // PebbleKit 2 throws "Service not registered" here when no Pebble app could be bound
                runCatching { sender.close() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PebbleKit 2 send failed", e)
            "error ${e.javaClass.simpleName}"
        }
        ctx.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_LAST, "${System.currentTimeMillis()} $seconds $summary").apply()
        Log.i(TAG, "next alarm $seconds -> $summary")
        return summary
    }

    fun describe(res: Map<WatchIdentifier, TransmissionResult>?): String =
        describeNames(res?.values?.map { it.javaClass.simpleName })

    /** By result class name, so it is testable without loading PebbleKit classes (Java 21 bytecode) on the JVM. */
    fun describeNames(names: List<String>?): String = when {
        names == null -> "no Pebble app"
        names.isEmpty() -> "no watch connected"
        names.any { it == "Success" } -> "ok"
        else -> names.joinToString()
    }
}

/** Alarm changed, clock changed, boot or app update: resend the next alarm. */
class PhoneAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = PhoneAlarmSync.sendFromReceiver(this, ctx)
}

/**
 * Bound by the Pebble app while the watch face runs (PebbleKit Android 2). Messages from the face
 * go to its PebbleKit JS as well, so they are acknowledged here and otherwise ignored; a weather
 * request is also a cue to resend the alarm.
 */
class CarbonPebbleListener : BasePebbleListenerService() {
    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID == PhoneAlarmSync.WATCH_UUID && data.containsKey(PhoneAlarmSync.KEY_WEATHER_REQUEST_U))
            PhoneAlarmSync.send(this, delayMs = 2_000L)
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        // Short delay: the Pebble app finishes setting up the session after this callback.
        if (watchappUUID == PhoneAlarmSync.WATCH_UUID) PhoneAlarmSync.send(this, delayMs = 1_500L)
    }
}
