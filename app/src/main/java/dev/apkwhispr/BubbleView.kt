package dev.apkwhispr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The floating pill. Idle = mic glyph, Rec = live waveform, Busy = pulsing dots. */
@SuppressLint("ViewConstructor")
class BubbleView(ctx: Context) : View(ctx) {
    enum class State { IDLE, REC, BUSY }

    private val d = resources.displayMetrics.density
    fun dp(v: Float) = (v * d + 0.5f).toInt()

    val h = dp(40f)
    fun widthFor(s: State) = when (s) {
        State.IDLE -> dp(40f)
        State.REC -> dp(116f)
        State.BUSY -> dp(72f)
    }

    var state = State.IDLE
        set(v) { field = v; levels.fill(0f); invalidate() }

    private val levels = FloatArray(11)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EE141414") }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = d; color = Color.parseColor("#33FFFFFF")
    }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.6f * d
    }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF453A") }
    private val rect = RectF()

    /** amp is MediaRecorder.getMaxAmplitude() (0..32767). */
    fun pushLevel(amp: Int) {
        val v = min(1f, kotlin.math.sqrt(amp / 32767f) * 1.6f)
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = max(v, levels[levels.size - 2] * 0.6f)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val hh = height.toFloat()
        rect.set(d, d, w - d, hh - d)
        c.drawRoundRect(rect, hh / 2, hh / 2, bg)
        c.drawRoundRect(rect, hh / 2, hh / 2, border)
        val cx = w / 2; val cy = hh / 2
        when (state) {
            State.IDLE -> drawMic(c, cx, cy)
            State.REC -> {
                c.drawCircle(dp(15f).toFloat(), cy, 3.5f * d, red)
                val n = levels.size
                val gap = 6.5f * d
                val startX = dp(29f).toFloat()
                for (i in 0 until n) {
                    val bh = 3f * d + levels[i] * (hh * 0.55f)
                    val x = startX + i * gap
                    if (x > w - dp(12f)) break
                    c.drawLine(x, cy - bh / 2, x, cy + bh / 2, fg)
                }
            }
            State.BUSY -> {
                val t = SystemClock.uptimeMillis() / 1000.0
                for (i in -1..1) {
                    val a = (0.35 + 0.65 * (0.5 + 0.5 * sin(t * 2 * PI * 1.4 - i * 0.9))).toFloat()
                    fg.alpha = (a * 255).toInt()
                    c.drawCircle(cx + i * 11f * d, cy, 3.2f * d, fg)
                }
                fg.alpha = 255
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawMic(c: Canvas, cx: Float, cy: Float) {
        fg.style = Paint.Style.STROKE
        val s = fg.strokeWidth
        fg.strokeWidth = 2f * d
        rect.set(cx - 3.5f * d, cy - 9f * d, cx + 3.5f * d, cy + 3f * d)
        c.drawRoundRect(rect, 3.5f * d, 3.5f * d, fg)
        rect.set(cx - 7f * d, cy - 6f * d, cx + 7f * d, cy + 6.5f * d)
        c.drawArc(rect, 20f, 140f, false, fg)
        c.drawLine(cx, cy + 6.5f * d, cx, cy + 10f * d, fg)
        fg.strokeWidth = s
        fg.style = Paint.Style.FILL
    }
}
