package com.exclusivo.moon

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class WaveView(c: Context, a: AttributeSet?) : View(c, a) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8AB4FF.toInt(); strokeCap = Paint.Cap.ROUND; strokeWidth = 8f }
    private var t = 0f
    private val an = ValueAnimator.ofFloat(0f, 6.2832f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); an.start() }
    override fun onDetachedFromWindow() { an.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(cv: Canvas) {
        val n = 19; val gap = width / n.toFloat()
        for (i in 0 until n) {
            val h = height * (0.2f + 0.7f * abs(sin(i * 0.7f + t)))
            val x = gap * (i + 0.5f)
            cv.drawLine(x, (height - h) / 2, x, (height + h) / 2, p)
        }
    }
}

class MoonView(c: Context, a: AttributeSet?) : View(c, a) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDBE7FF.toInt() }
    private val g = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val m = min(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f; val r = m * 0.72f
        g.shader = RadialGradient(cx, cy, m, 0x668AB4FF, 0x008AB4FF, Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, m, g)
        val a = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val b = Path().apply { addCircle(cx + r * 0.45f, cy - r * 0.25f, r * 0.9f, Path.Direction.CW) }
        a.op(b, Path.Op.DIFFERENCE)
        cv.drawPath(a, p)
    }
}

class GlowView(c: Context, a: AttributeSet?) : View(c, a) {
    private val p = Paint()
    private var t = 0f
    private val an = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); an.start() }
    override fun onDetachedFromWindow() { an.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val a = 0.55f + 0.25f * sin(t * 6.2832f)
        p.shader = RadialGradient(w / 2, h, w * 0.9f, intArrayOf(((a * 255).toInt() shl 24) or 0x8AB4FF, 0x008AB4FF), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * (0.1f + 0.8f * abs(2 * t - 1f)), h, w * 0.5f, intArrayOf(0x66B79CFF, 0x00B79CFF), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p)
    }
}
