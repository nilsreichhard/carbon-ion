package com.nilsreichhard.carbonion.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout

/**
 * Hour columns of a widget render as fractions of its width (the chart bitmap fills the widget,
 * fitXY), plus the payload slot of the first column: the app maps a touch on the mirror to an hour.
 * [edges] has total + 1 entries, left edge of column 0 to right edge of the last one.
 */
class MirrorColumns(val edges: FloatArray, val startSlot: Int) {
    val total: Int get() = edges.size - 1

    /** Column under [xFrac] (0..1 of the width); null in the side insets / outside unless [clamp]. */
    fun columnAt(xFrac: Float, clamp: Boolean = false): Int? {
        if (total <= 0) return null
        if (xFrac < edges[0]) return if (clamp) 0 else null
        if (xFrac >= edges[total]) return if (clamp) total - 1 else null
        // Edges are increasing (Classic's are whole rows, so not evenly spaced): last edge <= x.
        var lo = 0; var hi = total
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (edges[m] <= xFrac) lo = m else hi = m }
        return lo
    }

    fun centerFrac(col: Int): Float = (edges[col] + edges[col + 1]) / 2f
    fun slotOf(col: Int): Int = startSlot + col

    companion object {
        /** Even columns from an inset (Modern: ModernRenderer.columns). */
        fun even(leftPx: Float, colPx: Float, total: Int, widthPx: Float, startSlot: Int) =
            MirrorColumns(FloatArray(total + 1) { (leftPx + it * colPx) / widthPx }, startSlot)

        /** Classic: column i starts at row i * w / total (integer rows, WidgetRenderer.colX). */
        fun rows(widthRows: Int, total: Int, startSlot: Int) =
            MirrorColumns(FloatArray(total + 1) { (it * widthRows / total).toFloat() / widthRows }, startSlot)
    }
}

/**
 * The widget mirror in the app, made touchable: touching or dragging on the chart parts (anything but
 * the clock block) selects the hour under the finger, draws a scrub line over the mirror and reports
 * the payload slot. The selection stays after the finger lifts (like the old chart); a tap in the side
 * insets clears it. Taps never reach the RemoteViews' own click intents. TalkBack sees one slider-like
 * node: swipe up / down (scroll actions) steps through the hours.
 */
class MirrorFrame(context: Context) : FrameLayout(context) {
    var columns: MirrorColumns? = null
        set(v) { field = v; selected = selected?.takeIf { v != null && it < v.total }; invalidate() }
    /** Selected column in [columns], or null (the mirror then looks exactly like the widget). */
    var selected: Int? = null
        private set
    var onSelect: ((slot: Int?) -> Unit)? = null
    /** Spoken for the selected hour (stateDescription). */
    var describeSlot: ((Int) -> String)? = null
    var lineColor: Int = 0xFF3F5F90.toInt(); set(v) { field = v; lineP.color = v }

    private val dp = resources.displayMetrics.density
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND; color = lineColor }
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f * dp; strokeCap = Paint.Cap.ROUND; color = 0x55000000 }

    init { setWillNotDraw(false); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true }

    /** Select [col] (null clears); [notify] false restores a selection without reporting it. */
    fun select(col: Int?, notify: Boolean = true) {
        val c = columns
        val v = if (c == null) null else col?.coerceIn(0, c.total - 1)
        if (v == selected) return
        selected = v
        invalidate()
        if (notify) onSelect?.invoke(v?.let { c!!.slotOf(it) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stateDescription = v?.let { describeSlot?.invoke(c!!.slotOf(it)) }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    /** The clock block (time_block of the widget layouts), where touches don't scrub. */
    private fun clockBand(): Pair<Int, Int>? {
        val tb = findViewById<View>(R.id.time_block) ?: return null
        var top = tb.top; var v: View = tb
        while (v.parent is View && v.parent !== this) { v = v.parent as View; top += v.top }
        return top to top + tb.height
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val c = columns ?: return false
        if (width <= 0) return false
        val xf = e.x / width
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val band = clockBand()
                if (band != null && e.y >= band.first && e.y < band.second) return false
                val col = c.columnAt(xf)
                if (col == null) { select(null); return false }
                parent?.requestDisallowInterceptTouchEvent(true)
                select(col)
                return true
            }
            MotionEvent.ACTION_MOVE -> { select(c.columnAt(xf, clamp = true)); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return false
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val c = columns ?: return
        val col = selected ?: return
        val x = c.centerFrac(col) * width
        val band = clockBand()
        val segs = if (band == null) listOf(0f to height.toFloat())
            else listOf(0f to band.first.toFloat(), band.second.toFloat() to height.toFloat())
        for ((a, b) in segs) if (b - a > 2 * dp) {
            canvas.drawLine(x, a + dp, x, b - dp, haloP)
            canvas.drawLine(x, a + dp, x, b - dp, lineP)
        }
    }

    // ---- accessibility: one slider-like node

    override fun getAccessibilityClassName(): CharSequence = android.widget.SeekBar::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val c = columns ?: return
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, (c.total - 1).toFloat(), (selected ?: -1).toFloat().coerceAtLeast(0f))
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val c = columns ?: return super.performAccessibilityAction(action, arguments)
        val cur = selected
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { select(if (cur == null) columnOfNow() else cur + 1); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { select(if (cur == null) columnOfNow() else cur - 1); return true }
        }
        if (action == android.R.id.accessibilityActionSetProgress) {
            select(arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.toInt()); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    /** The now column, where stepping starts. */
    var nowColumn: Int = 0
    private fun columnOfNow() = nowColumn
}
