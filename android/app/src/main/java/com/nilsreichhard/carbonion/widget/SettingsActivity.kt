package com.nilsreichhard.carbonion.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Widget settings = the watch's Clay page, built natively from the same
 * src/pkjs/config.js items (names, order, defaults, descriptions), with the
 * same provider-dependent visibility from src/pkjs/config/custom.js, both
 * run headless in QuickJS (tools/widget-entry.js). Saving stores the Clay
 * settings like the watch's phone JS does; widget-only options (background,
 * phone location, place search) are added in their own rows.
 *
 * Opens when the widget is added (configure activity), from the launcher
 * icon, and from a tap on the widget's city name.
 */
class SettingsActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var js: JsRuntime
    private lateinit var list: LinearLayout
    private val rows = ArrayList<Row>()
    private var updating = false

    private var bg = WidgetStore.Background()
    private var ui = UiOptions()
    private lateinit var bgColorRows: List<View>
    private lateinit var locStatus: TextView
    private lateinit var locAllow: Button
    private lateinit var placeRow: View

    private class Row(val type: String, val key: String?, val container: View, val input: View?, val label: TextView?, val desc: TextView?) {
        var optionValues: List<String> = emptyList()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /** Theme colours (Material 3 DayNight), so the page reads in light and dark. */
    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }
    private val primaryText by lazy { attrColor(android.R.attr.textColorPrimary) }
    private val secondaryText by lazy { attrColor(android.R.attr.textColorSecondary) }
    private val headingColor by lazy { attrColor(androidx.appcompat.R.attr.colorPrimary) }
    private val fields by lazy { FieldStyle.forTheme(this) }

    /** 1.1.2: dropdowns as tinted, outlined fields with an accent chevron and their own popup surface. */
    private fun styleSpinner(sp: Spinner) {
        fields.applyTo(sp)
    }

    /** Same field look for text inputs (outline turns accent and 2 dp on focus). */
    private fun styleInput(et: EditText): EditText = et.also { fields.applyTo(it) }

    private fun fieldAdapter(sp: Spinner, names: List<String>, typefaces: List<Typeface?>? = null) =
        FieldStyle.Adapter(this, sp, names, fields, typefaces)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light/dark like the expanded screen: the Theme setting (Follow system by default).
        AppCompatDelegate.setDefaultNightMode(WidgetStore.nightMode(WidgetStore.settings(this)))
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        title = getString(R.string.settings_title)
        bg = WidgetStore.background(this)
        ui = WidgetStore.ui(this)

        js = JsRuntime(this)
        val userData = JSONObject()
        WidgetStore.phoneLocation(this)?.let { userData.put("lastKnownLat", "%.4f".format(java.util.Locale.US, it.first).toDouble()).put("lastKnownLon", "%.4f".format(java.util.Locale.US, it.second).toDouble()) }
        val state = JSONArray(js.call("carbonSettingsInit", userData.toString()))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), dp(24f)) }
        for (i in 0 until state.length()) {
            val it = state.getJSONObject(i)
            if (it.optString("type") == "submit") addWidgetSection()
            rows.add(buildRow(it))
            // Location note + inline Allow button right under the Fixed coordinates switch.
            if (it.optString("key") == "SETTING_USE_STATIC_LOCATION") addLocationRows()
        }
        applyState(state)
        val scroll = ScrollView(this).apply { addView(list); clipToPadding = false }
        // Edge-to-edge (Android 15+): keep the last rows and the Save button above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, bars.bottom); insets
        }
        setContentView(scroll)
    }

    override fun onDestroy() {
        js.close()
        super.onDestroy()
    }

    // --------------------------------------------------------------- Clay rows
    private fun text(sizeSp: Float, bold: Boolean = false, color: Int? = null) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color ?: primaryText)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun html(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)

    private fun block(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8f), 0, dp(8f))
        list.addView(this)
    }

    private fun buildRow(o: JSONObject): Row {
        val type = o.optString("type")
        val key = o.optString("key").takeIf { o.has("key") && !o.isNull("key") }
        val label = o.optString("label")
        val description = o.optString("description")
        val box = block()
        var input: View? = null
        var labelView: TextView? = null
        when (type) {
            "heading" -> box.addView(text(if (rows.isEmpty()) 24f else 18f, true, headingColor).apply {
                this.text = html(o.optString("value")); setPadding(0, dp(12f), 0, 0)
            })
            "text" -> { labelView = text(14f, color = secondaryText).apply { movementMethod = LinkMovementMethod.getInstance() }; box.addView(labelView) }
            "select" -> {
                labelView = text(16f).apply { this.text = label }; box.addView(labelView)
                val sp = Spinner(this).also { styleSpinner(it) }
                box.addView(sp, fields.params())
                input = sp
            }
            "toggle" -> {
                val sw = Switch(this).apply { this.text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
                box.addView(sw); input = sw
            }
            "input" -> {
                labelView = text(16f).apply { this.text = label }; box.addView(labelView)
                val attrs = o.optJSONObject("attributes") ?: JSONObject()
                val et = EditText(this).apply {
                    hint = attrs.optString("placeholder")
                    isSingleLine = true
                    inputType = when (attrs.optString("type")) {
                        "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                }
                styleInput(et)
                box.addView(et, fields.params()); input = et
            }
            // Save is in the action bar (onCreateOptionsMenu); the page's own submit row stays hidden.
            "submit" -> box.visibility = View.GONE
        }
        val desc = if (description.isNotEmpty()) text(13f, color = secondaryText).apply { this.text = html(description) }.also { box.addView(it) } else null
        val row = Row(type, key, box, input, labelView, desc)
        wire(row)
        return row
    }

    private fun wire(row: Row) {
        val key = row.key ?: return
        when (val v = row.input) {
            is Spinner -> v.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (!updating && pos in row.optionValues.indices) change(key, JSONObject.quote(row.optionValues[pos]))
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            is Switch -> v.setOnCheckedChangeListener { _, checked -> if (!updating) change(key, checked.toString()) }
            is EditText -> v.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!updating) change(key, JSONObject.quote(s.toString())) }
            })
        }
    }

    private fun change(key: String, valueJson: String) {
        if (isDestroyed) return // a late dialog callback after onDestroy closed the JS runtime
        applyState(JSONArray(js.call("carbonSettingsChange", key, valueJson)))
    }

    /** Pushes the JS page model (values, hidden, disabled, allowed options) into the views. */
    private fun applyState(state: JSONArray) {
        updating = true
        try {
            for (i in 0 until minOf(state.length(), rows.size)) {
                val o = state.getJSONObject(i)
                val row = rows[i]
                if (row.type == "submit") continue
                row.container.visibility = if (o.optBoolean("hidden")) View.GONE else View.VISIBLE
                val enabled = !o.optBoolean("disabled")
                row.container.alpha = if (enabled) 1f else 0.4f
                row.input?.isEnabled = enabled
                when (val v = row.input) {
                    is Spinner -> {
                        val opts = o.optJSONArray("options") ?: JSONArray()
                        val values = (0 until opts.length()).map { opts.getJSONObject(it).optString("value") }
                        if (values != row.optionValues) {
                            row.optionValues = values
                            v.adapter = fieldAdapter(v, (0 until opts.length()).map { optionLabel(row.key, values[it], opts.getJSONObject(it).optString("label")) })
                        }
                        val idx = values.indexOf(o.optString("value"))
                        if (idx >= 0 && v.selectedItemPosition != idx) v.setSelection(idx, false)
                    }
                    is Switch -> v.isChecked = o.optBoolean("value")
                    is EditText -> { val s = o.optString("value"); if (v.text.toString() != s && !v.hasFocus()) v.setText(s) }
                }
                if (row.type == "text") row.label?.text = html(o.optString("value"))
                if (row.key == "SETTING_USE_STATIC_LOCATION") updateLocationRows(o.optBoolean("value"))
            }
        } finally { updating = false }
    }

    /** Date formats preview today's date in the phone's language (the short-weekday one in its locale order). */
    private fun optionLabel(key: String?, value: String, label: String): String =
        if (key == "SETTING_DATE_FORMAT" && value.isNotEmpty())
            runCatching { android.text.format.DateFormat.format(WidgetUpdater.localDatePattern(value), System.currentTimeMillis()).toString() }.getOrDefault(label)
        else label

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        // Accent-coloured so the only Save action reads as a button in light and dark.
        val title = android.text.SpannableString(getString(R.string.settings_save)).apply {
            setSpan(android.text.style.ForegroundColorSpan(headingColor), 0, length, 0)
        }
        menu.add(0, MENU_SAVE, 0, title).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SAVE) { save(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------- widget-only options
    private fun addLocationRows() {
        val box = block()
        // The note and its Allow button side by side, so the fix is right where the problem is stated.
        val note = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        locStatus = text(13f, color = secondaryText)
        locAllow = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = getString(R.string.location_allow)
            setOnClickListener { LocationPermission.ask(this@SettingsActivity) }
        }
        note.addView(locStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        note.addView(locAllow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
        box.addView(note)
        val place = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val q = styleInput(EditText(this).apply { hint = getString(R.string.place_hint); isSingleLine = true })
        place.addView(q, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        place.addView(Button(this).apply { text = getString(R.string.place_find); setOnClickListener { findPlace(q.text.toString()) } })
        box.addView(place)
        placeRow = place
    }

    private fun updateLocationRows(fixed: Boolean) {
        if (!::locStatus.isInitialized) return
        val granted = LocationHelper.hasPermission(this)
        val noFix = WidgetStore.placeSource(this) == LocationHelper.VILLARS.source
        locStatus.text = when {
            fixed -> getString(R.string.location_fixed)
            granted && noFix -> getString(R.string.location_no_fix)
            granted -> getString(R.string.location_phone)
            else -> getString(R.string.location_denied)
        }
        locStatus.setTextColor(if (!fixed && (!granted || noFix)) attrColor(androidx.appcompat.R.attr.colorError) else secondaryText)
        locAllow.visibility = if (!fixed && !granted) View.VISIBLE else View.GONE
        placeRow.visibility = if (fixed) View.VISIBLE else View.GONE
    }

    private var hadPermission: Boolean? = null

    override fun onResume() {
        super.onResume()
        // Back from the app's settings page: the permission may have changed there (granted → fix + refresh).
        val granted = LocationHelper.hasPermission(this)
        if (hadPermission == false && granted) LocationPermission.onGranted(this)
        hadPermission = granted
        rows.firstOrNull { it.key == "SETTING_USE_STATIC_LOCATION" }?.let { updateLocationRows((it.input as? Switch)?.isChecked ?: false) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        hadPermission = LocationHelper.hasPermission(this)
        if (requestCode == LocationPermission.REQUEST && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) LocationPermission.onGranted(this)
        updateLocationRows(rows.firstOrNull { it.key == "SETTING_USE_STATIC_LOCATION" }?.let { (it.input as? Switch)?.isChecked } ?: false)
    }

    /** Manual place: Open-Meteo geocoding → fills Latitude / Longitude. */
    private fun findPlace(query: String) {
        if (query.isBlank()) return
        Thread {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&name=" + URLEncoder.encode(query.trim(), "UTF-8") +
                "&language=" + java.util.Locale.getDefault().language
            val r = runCatching { JsRuntime.http.get(url, emptyMap()) }.getOrElse { JsRuntime.HttpResult(0, error = it.javaClass.simpleName) }
            val results = runCatching { JSONObject(r.body).optJSONArray("results") }.getOrNull() ?: JSONArray()
            val places = (0 until results.length()).map { results.getJSONObject(it) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.status != 200) { MaterialAlertDialogBuilder(this).setMessage(R.string.place_error).setPositiveButton(android.R.string.ok, null).show(); return@runOnUiThread }
                if (places.isEmpty()) { MaterialAlertDialogBuilder(this).setMessage(R.string.place_none).setPositiveButton(android.R.string.ok, null).show(); return@runOnUiThread }
                val labels = places.map { p -> listOf(p.optString("name"), p.optString("admin1"), p.optString("country")).filter { it.isNotEmpty() }.distinct().joinToString(", ") }
                MaterialAlertDialogBuilder(this).setTitle(R.string.place_pick).setItems(labels.toTypedArray()) { _, i ->
                    val p = places[i]
                    change("SETTING_STATIC_LAT", JSONObject.quote("%.4f".format(java.util.Locale.US, p.getDouble("latitude"))))
                    change("SETTING_STATIC_LON", JSONObject.quote("%.4f".format(java.util.Locale.US, p.getDouble("longitude"))))
                }.show()
            }
        }.start()
    }

    private fun addWidgetSection() {
        block().addView(text(18f, true, headingColor).apply { text = getString(R.string.widget_section); setPadding(0, dp(12f), 0, 0) })
        addLookRows()
        val modeBox = block()
        modeBox.addView(text(16f).apply { text = getString(R.string.bg_label) })
        val modes = listOf(WidgetStore.BG_TRANSPARENT, WidgetStore.BG_COLOR)
        val sp = Spinner(this).apply {
            styleSpinner(this)
            adapter = fieldAdapter(this, listOf(getString(R.string.bg_transparent), getString(R.string.bg_color)))
            setSelection(modes.indexOf(bg.mode).coerceAtLeast(0), false)
        }
        modeBox.addView(sp, fields.params())
        modeBox.addView(text(13f, color = secondaryText).apply { text = getString(R.string.bg_desc) })

        val colorBox = block()
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hex = styleInput(EditText(this).apply { hint = "#RRGGBB"; isSingleLine = true; setText("#%06X".format(bg.color and 0xFFFFFF)) })
        val preview = View(this).apply { contentDescription = getString(R.string.bg_preview) }
        fun setColor(c: Int, fromHex: Boolean) {
            bg = bg.copy(color = c and 0xFFFFFF)
            preview.background = GradientDrawable().apply { setColor(c or 0xFF000000.toInt()); cornerRadius = dp(6f).toFloat(); setStroke(1, Color.GRAY) }
            if (!fromHex) hex.setText("#%06X".format(c and 0xFFFFFF))
        }
        // 40 dp swatches with a 48 dp touch height, named for TalkBack.
        for (c in PRESETS) swatches.addView(View(this).apply {
            background = android.graphics.drawable.InsetDrawable(GradientDrawable().apply { setColor(c); cornerRadius = dp(6f).toFloat(); setStroke(dp(1f), Color.GRAY) }, 0, dp(4f), 0, dp(4f))
            contentDescription = getString(R.string.bg_swatch, "#%06X".format(c and 0xFFFFFF))
            isFocusable = true
            setOnClickListener { setColor(c, false) }
        }, LinearLayout.LayoutParams(dp(40f), dp(48f)).apply { marginEnd = dp(8f) })
        colorBox.addView(text(16f).apply { text = getString(R.string.bg_color_label) })
        colorBox.addView(swatches)
        val hexRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hexRow.addView(hex, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hexRow.addView(preview, LinearLayout.LayoutParams(dp(40f), dp(32f)))
        colorBox.addView(hexRow)
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching { Color.parseColor(s.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull()?.let { setColor(it, true) }
            }
        })
        val opLabel = text(16f)
        val seek = SeekBar(this).apply { max = 100; progress = bg.opacity }
        fun opText() { opLabel.text = getString(R.string.bg_opacity, bg.opacity) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { bg = bg.copy(opacity = p); opText() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        opText()
        colorBox.addView(opLabel); colorBox.addView(seek)
        setColor(bg.color, false)
        bgColorRows = listOf(colorBox)
        fun showColor() { bgColorRows.forEach { it.visibility = if (bg.mode == WidgetStore.BG_COLOR) View.VISIBLE else View.GONE } }
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { bg = bg.copy(mode = modes[pos]); showColor() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        showColor()
    }

    /** Style, fonts (each shown in its own typeface), clock size, scrim and wallpaper accent. */
    private fun addLookRows() {
        fun <T> choice(label: String, desc: String?, items: List<T>, names: List<String>, current: T,
                       typefaces: List<Typeface?>? = null, onPick: (T) -> Unit) {
            val box = block()
            box.addView(text(16f).apply { text = label })
            val sp = Spinner(this).apply {
                styleSpinner(this)
                adapter = fieldAdapter(this, names, typefaces)
                setSelection(items.indexOf(current).coerceAtLeast(0), false)
            }
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(items[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            box.addView(sp, fields.params())
            if (desc != null) box.addView(text(13f, color = secondaryText).apply { text = desc })
        }
        val styles = UiOptions.Style.entries
        choice(getString(R.string.style_label), getString(R.string.style_desc), styles,
            listOf(getString(R.string.style_modern), getString(R.string.style_classic)), ui.style) { ui = ui.copy(style = it) }
        val fonts = FontOption.entries
        val clockFonts = fonts.filter { it.clockCapable }
        choice(getString(R.string.clock_font_label), getString(R.string.clock_font_desc), clockFonts, clockFonts.map { it.label }, ui.clockFont,
            clockFonts.map { if (it == FontOption.AUTO) null else it.typeface(this, clock = true) }) { ui = ui.copy(clockFont = it) }
        val sizes = UiOptions.ClockSize.entries
        choice(getString(R.string.clock_size_label), null, sizes,
            listOf(getString(R.string.clock_size_small), getString(R.string.clock_size_normal), getString(R.string.clock_size_large)),
            ui.clockSize) { ui = ui.copy(clockSize = it) }
        choice(getString(R.string.text_font_label), getString(R.string.text_font_desc), fonts, fonts.map { it.label }, ui.textFont,
            fonts.map { if (it == FontOption.AUTO) null else it.typeface(this, clock = false) }) { ui = ui.copy(textFont = it) }
        val scrimBox = block()
        scrimBox.addView(Switch(this).apply {
            text = getString(R.string.scrim_label); textSize = 16f; isChecked = ui.scrim
            setOnCheckedChangeListener { _, on -> ui = ui.copy(scrim = on) }
        })
        scrimBox.addView(text(13f, color = secondaryText).apply { text = getString(R.string.scrim_desc) })
        val accentBox = block()
        accentBox.addView(Switch(this).apply {
            text = getString(R.string.accent_label); textSize = 16f
            isEnabled = FontOption.dynamicColorAvailable
            isChecked = ui.wallpaperAccent && isEnabled
            setOnCheckedChangeListener { _, on -> ui = ui.copy(wallpaperAccent = on) }
        })
        accentBox.addView(text(13f, color = secondaryText).apply {
            text = getString(if (FontOption.dynamicColorAvailable) R.string.accent_desc else R.string.accent_unavailable)
        })
    }

    // -------------------------------------------------------------------- save
    private fun save() {
        val dict = js.call("carbonSettingsSave")
        WidgetStore.markThemeChosen(this)
        WidgetStore.saveDict(this, dict)
        WidgetStore.saveBackground(this, bg)
        WidgetStore.saveUi(this, ui)
        WidgetUpdater.updateAll(this)
        val app = applicationContext
        Thread {
            // 1.1.6: the fetch takes its own fix (LocationHelper.resolve: recent only, real fix time).
            WeatherWorker.schedule(app, replace = true)
            WeatherWorker.runNow(app)
        }.start()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    companion object {
        private const val MENU_SAVE = 1
        /** Background presets: white, black, Pixel dark grey, slate, navy, teal, warm sand. */
        val PRESETS = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF202124.toInt(), 0xFF37474F.toInt(),
            0xFF1A237E.toInt(), 0xFF00695C.toInt(), 0xFFF3E5C8.toInt())
    }
}
