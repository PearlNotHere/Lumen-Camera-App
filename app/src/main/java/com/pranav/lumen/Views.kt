package com.pranav.lumen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Grid, level, histogram, zebra / focus peaking layer, and the tap-to-focus box. */
class OverlayView(ctx: Context) : View(ctx) {
    var grid = true
    var level = true
    var levelAngle = 0f
    var hist: IntArray? = null
    var analysis: Bitmap? = null
    var focusX = -1f
    var focusY = -1f
    var focusUntil = 0L
    var locked = false
    var sunOffset = 0f

    private val d = resources.displayMetrics.density
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF; strokeWidth = 1f * d }
    private val lvl = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d; strokeCap = Paint.Cap.ROUND }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = MainActivity.ACCENT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint().apply { isFilterBitmap = false }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }
    private val path = Path()
    private val rect = RectF()
    private val histCols = intArrayOf(0x8CFF453A.toInt(), 0x8C30D158.toInt(), 0x990A84FF.toInt(), 0x80FFFFFF.toInt())

    fun focusVisible(): Boolean = focusX >= 0 && (locked || System.currentTimeMillis() < focusUntil)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()

        analysis?.let {
            rect.set(0f, 0f, w, h)
            c.drawBitmap(it, null, rect, bmpPaint)
        }

        if (grid) {
            for (i in 1..2) {
                c.drawLine(w * i / 3, 0f, w * i / 3, h, thin)
                c.drawLine(0f, h * i / 3, w, h * i / 3, thin)
            }
        }

        if (level) {
            val flat = abs(levelAngle) < 1.2f
            lvl.color = 0x80FFFFFF.toInt()
            c.drawLine(w / 2 - 95 * d, h / 2, w / 2 - 65 * d, h / 2, lvl)
            c.drawLine(w / 2 + 65 * d, h / 2, w / 2 + 95 * d, h / 2, lvl)
            lvl.color = if (flat) MainActivity.ACCENT else 0xDDFFFFFF.toInt()
            c.save()
            c.rotate(levelAngle, w / 2, h / 2)
            c.drawLine(w / 2 - 50 * d, h / 2, w / 2 + 50 * d, h / 2, lvl)
            c.restore()
        }

        hist?.let { drawHist(c, it) }

        if (focusVisible()) {
            val s = 36 * d
            c.drawRect(focusX - s, focusY - s, focusX + s, focusY + s, box)
            fill.color = MainActivity.ACCENT
            val sy = (focusY + sunOffset).coerceIn(focusY - s, focusY + s)
            c.drawCircle(focusX + s + 14 * d, sy, 5 * d, fill)
            if (!locked) postInvalidateDelayed(200)
        }

        if (locked) {
            val label = "AE/AF LOCK"
            val tw = text.measureText(label) + 16 * d
            fill.color = MainActivity.ACCENT
            rect.set(w / 2 - tw / 2, 10 * d, w / 2 + tw / 2, 32 * d)
            c.drawRoundRect(rect, 4 * d, 4 * d, fill)
            c.drawText(label, w / 2, 25 * d, text)
        }
    }

    private fun drawHist(c: Canvas, hh: IntArray) {
        val bw = 120 * d
        val bh = 56 * d
        val x0 = 10 * d
        val y0 = 10 * d
        fill.color = 0x73000000
        rect.set(x0, y0, x0 + bw, y0 + bh)
        c.drawRoundRect(rect, 6 * d, 6 * d, fill)
        var mx = 1
        for (i in 192 until 256) mx = max(mx, hh[i])
        for (ch in 0 until 4) {
            path.reset()
            path.moveTo(x0, y0 + bh)
            for (i in 0 until 64) {
                val x = x0 + i / 63f * bw
                val y = y0 + bh - min(1f, hh[ch * 64 + i].toFloat() / mx) * (bh - 6 * d)
                path.lineTo(x, y)
            }
            path.lineTo(x0 + bw, y0 + bh)
            path.close()
            fill.color = histCols[ch]
            c.drawPath(path, fill)
        }
    }
}

/** iPhone-style shutter: white disc for photo, red for video, rounded square while recording. */
class ShutterView(ctx: Context) : View(ctx) {
    var video = false
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.WHITE
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Shutter"
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        c.drawCircle(cx, cy, r - 2 * d, ring)
        inner.color = if (video) MainActivity.REC else Color.WHITE
        if (recording) {
            val s = r * 0.42f
            c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 6 * d, 6 * d, inner)
        } else {
            c.drawCircle(cx, cy, (r - 8 * d) * (if (isPressed) 0.9f else 1f), inner)
        }
    }
}
