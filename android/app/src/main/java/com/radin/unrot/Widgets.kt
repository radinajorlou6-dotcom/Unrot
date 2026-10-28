package com.radin.unrot

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** A rounded progress bar. */
class ProgressBarView(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Col.VIOLET
        set(v) { field = v; invalidate() }
    var track = Col.CARD2

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        p.color = track
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (fraction <= 0f) return
        r.set(0f, 0f, max(h, width * fraction), h)
        p.color = color
        c.drawRoundRect(r, h / 2, h / 2, p)
        // A lighter stripe on top so it looks a bit "juicy".
        p.color = Col.alpha(0xFFFFFF, 0x2A)
        r.set(h / 3, h * 0.18f, max(h / 3, width * fraction - h / 3), h * 0.42f)
        if (r.width() > 0) c.drawRoundRect(r, h / 4, h / 4, p)
    }
}

/** A circular progress ring (the balance and lock-in timers). Size comes from layout params. */
class RingView(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Col.GOLD
        set(v) { field = v; invalidate() }
    var track = Col.CARD2
    var strokeDp = 14

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val sw = context.dp(strokeDp).toFloat()
        val size = min(width, height).toFloat()
        val left = (width - size) / 2 + sw / 2
        val top = (height - size) / 2 + sw / 2
        r.set(left, top, left + size - sw, top + size - sw)
        p.strokeWidth = sw
        p.color = track
        c.drawArc(r, 0f, 360f, false, p)
        if (fraction > 0f) {
            // Soft glow underneath, then the arc.
            p.color = Col.alpha(color, 0x40)
            p.strokeWidth = sw * 1.7f
            c.drawArc(r, -90f, 360f * fraction, false, p)
            p.color = color
            p.strokeWidth = sw
            c.drawArc(r, -90f, 360f * fraction, false, p)
        }
    }
}

/** Seven bars with day labels and an optional dashed target line. */
class BarChartView(ctx: Context) : View(ctx) {
    var values: List<Float> = emptyList()
    var labels: List<String> = emptyList()
    var target: Float? = null
    var colorFor: (Int, Float) -> Int = { _, _ -> Col.VIOLET }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Col.MUTED
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Col.alpha(Col.TEXT, 0x80)
    }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        txt.textSize = context.dp(12).toFloat()
        txt.typeface = Fonts.semi(context)
        dash.strokeWidth = context.dp(1).toFloat()
        dash.pathEffect = DashPathEffect(floatArrayOf(context.dp(5).toFloat(), context.dp(4).toFloat()), 0f)
        val labelH = context.dp(22).toFloat()
        val chartH = height - labelH
        val maxV = max(values.maxOrNull() ?: 0f, (target ?: 0f) * 1.15f).coerceAtLeast(1f)
        val n = values.size
        val slot = width.toFloat() / n
        val barW = slot * 0.52f
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            r.set(cx - barW / 2, 0f, cx + barW / 2, chartH)
            p.color = Col.CARD2
            c.drawRoundRect(r, barW / 2.6f, barW / 2.6f, p)
            val v = values[i]
            if (v > 0f) {
                val h = max(barW * 0.6f, chartH * (v / maxV))
                r.set(cx - barW / 2, chartH - h, cx + barW / 2, chartH)
                p.color = colorFor(i, v)
                c.drawRoundRect(r, barW / 2.6f, barW / 2.6f, p)
            }
            if (i < labels.size) c.drawText(labels[i], cx, height - context.dp(5).toFloat(), txt)
        }
        target?.let { t ->
            val y = chartH - chartH * (t / maxV)
            c.drawLine(0f, y, width.toFloat(), y, dash)
        }
    }
}
