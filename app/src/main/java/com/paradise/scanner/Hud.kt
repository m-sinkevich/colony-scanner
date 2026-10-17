package com.paradise.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.sin
import kotlin.random.Random

/** Панель со скошенными углами и "уголками-скобами" — основной элемент космического HUD. */
class HudDrawable(
    ctx: Context,
    private val stroke: Int,
    private val fillColor: Int,
    chamferDp: Float = 14f,
    strokeDp: Float = 1.2f,
    private val brackets: Boolean = true,
) : Drawable() {
    private val chamfer = ctx.dpf(chamferDp)
    private val sw = ctx.dpf(strokeDp)
    private val bracketLen = ctx.dpf(16f)
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = Pal.withAlpha(stroke, 170) }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw * 2.2f; color = stroke }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val l = bounds.left + sw
        val t = bounds.top + sw
        val r = bounds.right - sw
        val b = bounds.bottom - sw
        val c = chamfer.coerceAtMost((b - t) / 2f)
        path.reset()
        path.moveTo(l + c, t)
        path.lineTo(r, t)
        path.lineTo(r, b - c)
        path.lineTo(r - c, b)
        path.lineTo(l, b)
        path.lineTo(l, t + c)
        path.close()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
        if (brackets) {
            val bd = bounds
            val l = bd.left + sw
            val r = bd.right - sw
            val t = bd.top + sw
            val b = bd.bottom - sw
            val len = bracketLen.coerceAtMost((r - l) / 4f)
            // правый верхний и левый нижний углы — прямые, подчёркиваем их скобами
            canvas.drawLine(r - len, t, r, t, bracket)
            canvas.drawLine(r, t, r, t + len.coerceAtMost((b - t) / 2f), bracket)
            canvas.drawLine(l, b, l + len, b, bracket)
            canvas.drawLine(l, b, l, b - len.coerceAtMost((b - t) / 2f), bracket)
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha; line.alpha = alpha; bracket.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter; line.colorFilter = colorFilter; bracket.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Анимированный фон: мерцающие звёзды с параллаксом, туманности, сетка и линии развёртки. */
class StarfieldView(ctx: Context) : View(ctx) {

    var accent: Int = Pal.DEFAULT_ACCENT
        set(v) {
            field = v; nebula = null; invalidate()
        }

    private class Star(var x: Float, var y: Float, val z: Float, val phase: Float, val speed: Float)

    private val stars = ArrayList<Star>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { strokeWidth = 1f }
    private val scan = Paint()
    private var nebula: Array<Paint>? = null
    private var last = 0L

    init {
        repeat(170) {
            val z = Random.nextFloat()
            stars.add(Star(Random.nextFloat(), Random.nextFloat(), z, Random.nextFloat() * 6.28f, 0.6f + Random.nextFloat() * 2.5f))
        }
    }

    private fun nebulaPaints(w: Float, h: Float): Array<Paint> {
        val a = Paint().apply {
            shader = RadialGradient(w * 0.85f, h * 0.18f, w * 0.9f,
                intArrayOf(Pal.withAlpha(accent, 46), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        val b = Paint().apply {
            shader = RadialGradient(w * 0.1f, h * 0.85f, w * 1.0f,
                intArrayOf(Color.argb(52, 120, 60, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        val c = Paint().apply {
            shader = RadialGradient(w * 0.5f, h * 0.5f, h * 0.75f,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        }
        return arrayOf(a, b, c)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        nebula = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last).coerceAtMost(100)) / 1000f
        last = now
        val t = now / 1000f

        canvas.drawColor(Pal.BG)
        val neb = nebula ?: nebulaPaints(w, h).also { nebula = it }
        canvas.drawRect(0f, 0f, w, h, neb[0])
        canvas.drawRect(0f, 0f, w, h, neb[1])

        // перспективная сетка внизу экрана — "палуба"
        grid.color = Pal.withAlpha(accent, 22)
        val horizon = h * 0.78f
        val vx = w / 2f
        for (i in -8..8) {
            canvas.drawLine(vx + i * w * 0.02f, horizon, vx + i * w * 0.22f, h, grid)
        }
        var y = 0f
        var k = 0
        while (horizon + y < h && k < 30) {
            canvas.drawLine(0f, horizon + y, w, horizon + y, grid)
            k++
            y += 4f + k * k * 0.9f
        }

        // звёзды: дальние — мелкие и медленные, ближние — крупные
        for (s in stars) {
            s.y += dt * (0.004f + s.z * 0.02f)
            if (s.y > 1f) {
                s.y -= 1f; s.x = Random.nextFloat()
            }
            val tw = 0.55f + 0.45f * sin(t * s.speed + s.phase)
            val r = context.dpf(0.5f + s.z * 1.6f)
            val alpha = (tw * (90 + s.z * 165)).toInt().coerceIn(0, 255)
            paint.color = if (s.z > 0.93f) Pal.withAlpha(accent, alpha) else Color.argb(alpha, 220, 235, 255)
            canvas.drawCircle(s.x * w, s.y * h, r, paint)
        }

        // медленная горизонтальная линия развёртки
        val sy = ((t * 0.12f) % 1f) * h
        scan.color = Pal.withAlpha(accent, 18)
        canvas.drawRect(0f, sy, w, sy + context.dpf(2f), scan)

        canvas.drawRect(0f, 0f, w, h, neb[2])
        postInvalidateOnAnimation()
    }
}

/** Оборачивает экран: звёздный фон под содержимым. */
fun Context.spaceScreen(content: View, accent: Int): Pair<FrameLayout, StarfieldView> {
    val frame = FrameLayout(this)
    val stars = StarfieldView(this).apply { this.accent = accent }
    frame.addView(stars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    return frame to stars
}
