package com.paradise.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import kotlin.random.Random

/**
 * Технический фон: чертёжная сетка с узлами, линейки по краям, "печатная плата"
 * с бегущими по дорожкам импульсами, мерцающие блоки шестнадцатеричных данных и линия развёртки.
 */
class TechBackgroundView(ctx: Context) : View(ctx) {

    var accent: Int = Pal.DEFAULT_ACCENT
        set(v) {
            field = v; shaders = null; invalidate()
        }

    private class Trace(val path: Path, val measure: PathMeasure, val length: Float, var t: Float, val speed: Float)
    private class HexCell(val x: Float, val y: Float, var text: String, var nextChange: Long, val alpha: Int)

    private val traces = ArrayList<Trace>()
    private val hexCells = ArrayList<HexCell>()
    private val minor = Paint().apply { strokeWidth = 1f }
    private val major = Paint().apply { strokeWidth = 1f }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = MONO }
    private val fill = Paint()
    private var shaders: Array<Shader>? = null
    private val seg = Path()
    private var last = 0L

    private val step = ctx.dpf(20f)      // мелкая сетка
    private val majorEvery = 4           // крупная — каждые 4 клетки

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaders = null
        buildTraces(w.toFloat(), h.toFloat())
        buildHex(w.toFloat(), h.toFloat())
    }

    private fun buildTraces(w: Float, h: Float) {
        traces.clear()
        if (w <= 0f || h <= 0f) return
        val cell = step * 2
        val cols = (w / cell).toInt()
        val rows = (h / cell).toInt()
        if (cols < 2 || rows < 2) return
        repeat(14) {
            var cx = Random.nextInt(cols + 1)
            var cy = Random.nextInt(rows + 1)
            val p = Path()
            p.moveTo(cx * cell, cy * cell)
            var horizontal = Random.nextBoolean()
            repeat(3 + Random.nextInt(5)) {
                val len = 1 + Random.nextInt(4)
                val dir = if (Random.nextBoolean()) 1 else -1
                if (horizontal) cx = (cx + dir * len).coerceIn(0, cols) else cy = (cy + dir * len).coerceIn(0, rows)
                p.lineTo(cx * cell, cy * cell)
                horizontal = !horizontal
            }
            val m = PathMeasure(p, false)
            if (m.length > cell) traces.add(Trace(p, m, m.length, Random.nextFloat(), 0.08f + Random.nextFloat() * 0.18f))
        }
    }

    private fun buildHex(w: Float, h: Float) {
        hexCells.clear()
        if (w <= 0f || h <= 0f) return
        repeat(9) {
            hexCells.add(HexCell(
                Random.nextFloat() * (w - context.dpf(90f)) + context.dpf(26f),
                Random.nextFloat() * (h - context.dpf(60f)) + context.dpf(40f),
                hex(), 0L, 30 + Random.nextInt(40)))
        }
    }

    private fun hex(): String = buildString {
        repeat(2) { line ->
            if (line > 0) append('\n')
            repeat(4) { append(String.format("%02X", Random.nextInt(256))).append(' ') }
        }
    }

    private fun shaderSet(w: Float, h: Float): Array<Shader> = arrayOf(
        LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.rgb(4, 10, 14), Color.rgb(6, 16, 20), Color.rgb(3, 7, 10)), null, Shader.TileMode.CLAMP),
        RadialGradient(w * 0.5f, h * 0.42f, w * 0.85f,
            intArrayOf(Pal.withAlpha(accent, 34), Color.TRANSPARENT), null, Shader.TileMode.CLAMP),
        RadialGradient(w * 0.5f, h * 0.5f, h * 0.72f,
            intArrayOf(Color.TRANSPARENT, Color.argb(170, 0, 0, 0)), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP),
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last).coerceAtMost(100)) / 1000f
        last = now
        val t = now / 1000f
        val sh = shaders ?: shaderSet(w, h).also { shaders = it }

        fill.shader = sh[0]; canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = sh[1]; canvas.drawRect(0f, 0f, w, h, fill)

        // сетка
        minor.color = Pal.withAlpha(accent, 14)
        major.color = Pal.withAlpha(accent, 34)
        var i = 0
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, if (i % majorEvery == 0) major else minor)
            x += step; i++
        }
        i = 0
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, if (i % majorEvery == 0) major else minor)
            y += step; i++
        }
        // крестики в узлах крупной сетки
        node.color = Pal.withAlpha(accent, 70)
        node.strokeWidth = context.dpf(1f)
        val big = step * majorEvery
        val cr = context.dpf(3f)
        x = big
        while (x < w) {
            y = big
            while (y < h) {
                canvas.drawLine(x - cr, y, x + cr, y, node)
                canvas.drawLine(x, y - cr, x, y + cr, node)
                y += big
            }
            x += big
        }

        // дорожки платы и импульсы
        trace.color = Pal.withAlpha(accent, 40)
        trace.strokeWidth = context.dpf(1.4f)
        pulse.color = Pal.withAlpha(accent, 200)
        pulse.strokeWidth = context.dpf(2.2f)
        node.style = Paint.Style.FILL
        for (tr in traces) {
            canvas.drawPath(tr.path, trace)
            tr.t = (tr.t + dt * tr.speed) % 1.25f
            if (tr.t <= 1f) {
                val end = tr.t * tr.length
                val start = (end - context.dpf(26f)).coerceAtLeast(0f)
                seg.reset()
                if (tr.measure.getSegment(start, end, seg, true)) canvas.drawPath(seg, pulse)
            }
        }
        node.style = Paint.Style.STROKE

        // линейки по краям
        text.textSize = context.dpf(7f)
        text.color = Pal.withAlpha(accent, 90)
        node.color = Pal.withAlpha(accent, 90)
        y = 0f
        i = 0
        while (y <= h) {
            val len = if (i % 10 == 0) context.dpf(10f) else if (i % 5 == 0) context.dpf(6f) else context.dpf(3f)
            canvas.drawLine(0f, y, len, y, node)
            canvas.drawLine(w - len, y, w, y, node)
            if (i % 10 == 0 && i > 0) canvas.drawText(String.format("%03d", i), context.dpf(12f), y + context.dpf(2.5f), text)
            y += context.dpf(8f); i++
        }

        // блоки данных
        text.textSize = context.dpf(8f)
        for (c in hexCells) {
            if (now > c.nextChange) {
                c.text = hex(); c.nextChange = now + 600 + Random.nextLong(2400)
            }
            text.color = Pal.withAlpha(accent, c.alpha)
            var ly = c.y
            for (line in c.text.split('\n')) {
                canvas.drawText(line, c.x, ly, text); ly += context.dpf(10f)
            }
        }

        // линия развёртки
        val sy = ((t * 0.15f) % 1f) * h
        fill.shader = null
        fill.color = Pal.withAlpha(accent, 22)
        canvas.drawRect(0f, sy, w, sy + context.dpf(2f), fill)
        fill.color = Pal.withAlpha(accent, 8)
        canvas.drawRect(0f, sy - context.dpf(24f), w, sy, fill)

        fill.shader = sh[2]; canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        postInvalidateOnAnimation()
    }
}

/**
 * Оборачивает экран: технический фон под содержимым, а содержимое — внутри безопасной зоны,
 * чтобы кнопки не уходили под нижнюю панель навигации, вырез камеры или клавиатуру.
 */
fun Context.spaceScreen(content: View, accent: Int): Pair<FrameLayout, TechBackgroundView> {
    // одинаково на всех версиях Android: рисуем под системными панелями, а отступы считаем сами
    (this as? android.app.Activity)?.window?.let { win ->
        win.statusBarColor = Color.TRANSPARENT
        win.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            win.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            win.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
    val frame = FrameLayout(this)
    val bg = TechBackgroundView(this).apply { this.accent = accent }
    frame.addView(bg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val safe = FrameLayout(this)
    safe.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.addView(safe, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    frame.setOnApplyWindowInsetsListener { _, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            safe.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        } else {
            @Suppress("DEPRECATION")
            safe.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
    frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.requestApplyInsets()
        override fun onViewDetachedFromWindow(v: View) {}
    })
    return frame to bg
}
