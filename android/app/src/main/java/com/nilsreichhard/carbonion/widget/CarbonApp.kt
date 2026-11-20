package com.nilsreichhard.carbonion.widget

import android.app.Application
import android.content.res.Configuration

/**
 * Redraws the widgets when the phone switches dark mode while the app process is alive, for
 * "Follow system" on Android 8–11 (12+ carries both looks in the RemoteViews, see
 * WidgetUpdater.dualTheme) and when a solid background renders one look for both.
 * Without a live process the 30-minute RedrawWorker catches up.
 */
class CarbonApp : Application() {
    private var night = 0

    override fun onCreate() {
        super.onCreate()
        night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val n = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (n == night) return
        night = n
        val ctx = applicationContext
        if (WidgetStore.settings(ctx).themeMode == WidgetSettings.THEME_SYSTEM) Thread { WidgetUpdater.updateAll(ctx) }.start()
    }
}
