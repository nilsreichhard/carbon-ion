package com.nilsreichhard.carbonion.widget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The inline "Allow location" buttons (app screen card, settings location note): ask for coarse
 * location; once Android stops showing its dialog ("Don't allow" twice), open the app's system
 * settings page instead. After a grant: a fresh fix and an immediate refresh.
 */
object LocationPermission {
    const val REQUEST = 41
    private const val ASKED = "loc_asked"

    fun ask(a: Activity) {
        val prefs = a.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            openAppSettings(a); return
        }
        prefs.edit().putBoolean(ASKED, true).apply()
        // 1.1.4: both, so Android offers Precise / Approximate (precise matches the watch's fix).
        a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST)
    }

    /** Approximate only: ask for precise (Android shows its "Use precise location" dialog), else the system page. */
    fun askPrecise(a: Activity) {
        val prefs = a.getSharedPreferences(WidgetStore.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_PRECISE, false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            openAppSettings(a); return
        }
        prefs.edit().putBoolean(ASKED_PRECISE, true).apply()
        a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST)
    }
    private const val ASKED_PRECISE = "loc_asked_precise"

    fun openAppSettings(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
    }

    /** Permission just granted (dialog or the system page): fix + refresh now. */
    fun onGranted(ctx: Context) = LocationHelper.refreshInForeground(ctx.applicationContext, force = true)

    /** What the location note says. */
    enum class State { OK, DENIED, NO_FIX, APPROXIMATE }

    /** Only when the last fetch had to use the default place (not with fixed coordinates or a phone fix). */
    /**
     * 1.1.6: permission first. Revoked while a saved phone fix is in use is DENIED (1.1.5 called it
     * APPROXIMATE and offered "Use precise location"), and approximate needs an approximate grant.
     */
    fun state(ctx: Context): State {
        val src = WidgetStore.placeSource(ctx)
        return when {
            src == LocationHelper.SOURCE_FIXED -> State.OK
            !LocationHelper.hasPermission(ctx) -> State.DENIED
            src?.startsWith(LocationHelper.SOURCE_PHONE) == true && !LocationHelper.hasPrecise(ctx) -> State.APPROXIMATE
            src == LocationHelper.VILLARS.source -> State.NO_FIX
            else -> State.OK
        }
    }
}
