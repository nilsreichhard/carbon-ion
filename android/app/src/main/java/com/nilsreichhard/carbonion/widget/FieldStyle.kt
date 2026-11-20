package com.nilsreichhard.carbonion.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * 1.1.2: settings fields that read as controls in light and dark (Material 3
 * theme colours). Dropdowns: a rounded field with a subtle accent-tinted fill,
 * a 1 dp accent outline and an accent chevron; the open list has its own
 * raised surface (rounded, outlined, shadow) with the selected item tinted,
 * bold and checked. Text inputs get the same field without the chevron.
 */
class FieldStyle(
    private val ctx: Context,
    val accent: Int,
    val page: Int,
    val text: Int,
    val hint: Int,
    val fill: Int,
    val popup: Int,
    val popupOutline: Int,
    val selected: Int,
) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()

    private fun shape(color: Int, strokeDp: Float, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp); setStroke(dpi(strokeDp).coerceAtLeast(1), stroke)
    }

    /** Field: 1 dp accent outline, 2 dp when focused or pressed. */
    private fun field(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(fill, 2f, accent, RADIUS_DP))
        addState(intArrayOf(android.R.attr.state_pressed), shape(fill, 2f, accent, RADIUS_DP))
        addState(intArrayOf(), shape(fill, 1f, accent, RADIUS_DP))
    }

    private fun tinted(res: Int): Drawable = ctx.getDrawable(res)!!.mutate().apply { setTint(accent) }

    fun popupBackground(): Drawable = shape(popup, 1f, popupOutline, RADIUS_DP)

    fun applyTo(sp: Spinner) {
        val bg = LayerDrawable(arrayOf(field(), tinted(R.drawable.ic_field_chevron)))
        bg.setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
        bg.setLayerSize(1, dpi(24f), dpi(24f))
        bg.setLayerInsetEnd(1, dpi(12f))
        sp.background = bg
        sp.setPaddingRelative(dpi(16f), dpi(4f), dpi(44f), dpi(4f))
        sp.minimumHeight = dpi(FIELD_MIN_DP)
        sp.setPopupBackgroundDrawable(popupBackground())
        sp.dropDownVerticalOffset = dpi(4f)
        sp.tag = TAG
    }

    fun applyTo(et: EditText) {
        et.background = field()
        et.setPaddingRelative(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
        et.minimumHeight = dpi(FIELD_MIN_DP)
        et.setTextColor(text)
        et.setHintTextColor(hint)
        et.tag = TAG
    }

    fun params() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpi(6f); bottomMargin = dpi(4f)
    }

    /** Closed value in the normal text colour; list rows ≥ 48 dp with the selected one highlighted. */
    class Adapter(ctx: Context, private val sp: Spinner, names: List<String>, private val st: FieldStyle,
                  private val typefaces: List<Typeface?>? = null) : ArrayAdapter<String>(ctx, 0, names) {
        private fun tv(convertView: View?) = (convertView as? TextView) ?: TextView(context).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        }
        private fun bind(v: TextView, pos: Int) {
            v.text = getItem(pos)
            v.setTextColor(st.text)
            val tf = typefaces?.getOrNull(pos)
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (typefaces != null) 18f else 16f)
            v.typeface = tf ?: Typeface.DEFAULT
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = tv(convertView).also {
            bind(it, position)
            it.background = null
            it.setPadding(0, 0, 0, 0)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = tv(convertView).also {
            bind(it, position)
            val sel = position == sp.selectedItemPosition
            it.minHeight = st.dpi(48f)
            it.setPaddingRelative(st.dpi(16f), st.dpi(12f), st.dpi(12f), st.dpi(12f))
            it.compoundDrawablePadding = st.dpi(12f)
            if (sel) {
                if (typefaces?.getOrNull(position) == null) it.setTypeface(Typeface.DEFAULT_BOLD)
                it.background = InsetDrawable(st.shape(st.selected, 0f, st.selected, 8f), st.dpi(6f), st.dpi(2f), st.dpi(6f), st.dpi(2f))
                it.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, st.tinted(R.drawable.ic_field_check), null)
            } else {
                it.background = null
                it.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            }
            it.isSelected = sel
        }
    }

    companion object {
        const val TAG = "carbon-field"
        const val FIELD_MIN_DP = 52f
        const val RADIUS_DP = 12f

        private fun attr(ctx: Context, a: Int, fallback: Int): Int {
            val tv = TypedValue()
            if (!ctx.theme.resolveAttribute(a, tv, true)) return fallback
            return if (tv.resourceId != 0) runCatching { ctx.getColor(tv.resourceId) }.getOrDefault(fallback) else tv.data
        }

        fun forTheme(ctx: Context): FieldStyle {
            val accent = attr(ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
            val page = attr(ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE)
            val text = attr(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            val hint = attr(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
            val container = attr(ctx, com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt())
            val popup = attr(ctx, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFFE6E0E9.toInt())
            val outlineV = attr(ctx, com.google.android.material.R.attr.colorOutline, Color.GRAY)
            return FieldStyle(ctx, accent, page, text, hint,
                fill = ColorUtils.blendARGB(page, container, 0.7f),
                popup = popup,
                popupOutline = outlineV,
                selected = ColorUtils.blendARGB(popup, accent, 0.18f))
        }

        fun contrast(fg: Int, bg: Int): Double = ColorUtils.calculateContrast(fg or 0xFF000000.toInt(), bg or 0xFF000000.toInt())
    }
}
