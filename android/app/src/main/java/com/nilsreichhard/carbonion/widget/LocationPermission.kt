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
        a.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST)
    }

    fun openAppSettings(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
    }

    /** Permission just granted (dialog or the system page): fix + refresh now. */
    fun onGranted(ctx: Context) = LocationHelper.refreshInForeground(ctx.applicationContext, force = true)

    /** What the location note says. */
    enum class State { OK, DENIED, NO_FIX }

    /** Only when the last fetch had to use the default place (not with fixed coordinates or a phone fix). */
    fun state(ctx: Context): State = when {
        WidgetStore.placeSource(ctx) != LocationHelper.VILLARS.source -> State.OK
        !LocationHelper.hasPermission(ctx) -> State.DENIED
        else -> State.NO_FIX
    }
}
