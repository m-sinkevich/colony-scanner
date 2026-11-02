package com.paradise.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import kotlin.random.Random

/**
 * Фон прибора: неподвижная фактура, своя у каждого типа сканера
 * (медицинский — сетка крестиков, технический — чертёжная сетка со штриховкой и заклёпками,
 * научный — соты), подсветка цветом сканера и затемнение по краям. Без анимации.
 */
class TechBackgroundView(ctx: Context) : View(ctx) {

    var accent: Int = Pal.DEFAULT_ACCENT
        set(v) {
            if (field != v) { field = v; cache = null; invalidate() }
        }

    private var cache: Bitmap? = null
    private var cacheKind: Kind? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        if (cache == null || cacheKind != Look.kind) {
            cache?.recycle()
            cacheKind = Look.kind
            cache = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { render(Canvas(it), w.toFloat(), h.toFloat(), Look.kind) } } catch (e: Throwable) { null }
        }
        val b = cache
        if (b != null) canvas.drawBitmap(b, 0f, 0f, null) else render(canvas, w.toFloat(), h.toFloat(), Look.kind)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cache?.recycle(); cache = null
    }

    private fun render(c: Canvas, w: Float, h: Float, kind: Kind) {
        val base = when (kind) {
            Kind.MED -> intArrayOf(Color.rgb(10, 16, 20), Color.rgb(13, 20, 25), Color.rgb(6, 10, 13))
            Kind.TECH -> intArrayOf(Color.rgb(4, 10, 16), Color.rgb(6, 14, 22), Color.rgb(3, 7, 11))
            Kind.SCI -> intArrayOf(Color.rgb(4, 12, 8), Color.rgb(6, 17, 12), Color.rgb(3, 8, 6))
        }
        p.shader = LinearGradient(0f, 0f, 0f, h, base, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * 0.5f, h * 0.4f, w * 0.9f, intArrayOf(Pal.withAlpha(accent, if (kind == Kind.MED) 22 else 30), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val d = context.dpf(1f)
        when (kind) {
            Kind.MED -> {
                // аккуратная сетка крестиков и тонкие горизонтальные полосы, как на медицинской плёнке
                p.style = Paint.Style.FILL
                p.color = Pal.withAlpha(accent, 6)
                var y = 0f
                while (y < h) { c.drawRect(0f, y, w, y + d * 14, p); y += d * 28 }
                p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.1f
                val s = d * 26; val k = d * 3.2f
                var row = 0
                y = s / 2
                while (y < h) {
                    var x = if (row % 2 == 0) s / 2 else s
                    while (x < w) {
                        p.color = Pal.withAlpha(accent, if ((row + (x / s).toInt()) % 5 == 0) 40 else 18)
                        c.drawLine(x - k, y, x + k, y, p); c.drawLine(x, y - k, x, y + k, p)
                        x += s
                    }
                    y += s; row++
                }
            }
            Kind.TECH -> {
                // мелкая диагональная штриховка + чертёжная сетка + заклёпки в узлах
                p.style = Paint.Style.STROKE
                p.strokeWidth = d * 0.8f
                p.color = Pal.withAlpha(accent, 9)
                val hs = d * 7
                var x = -h
                while (x < w) { c.drawLine(x, h, x + h, 0f, p); x += hs }
                val g = d * 48
                p.strokeWidth = d
                p.color = Pal.withAlpha(accent, 22)
                x = 0f
                while (x <= w) { c.drawLine(x, 0f, x, h, p); x += g }
                var y = 0f
                while (y <= h) { c.drawLine(0f, y, w, y, p); y += g }
                p.style = Paint.Style.FILL
                x = g
                while (x < w) {
                    y = g
                    while (y < h) {
                        p.color = Pal.withAlpha(accent, 45); c.drawCircle(x, y, d * 2.2f, p)
                        p.color = Color.argb(120, 0, 0, 0); c.drawCircle(x + d * 0.7f, y + d * 0.7f, d * 1.1f, p)
                        y += g
                    }
                    x += g
                }
            }
            Kind.SCI -> {
                // соты: часть ячеек чуть залита
                val r = d * 16
                val hw = r * kotlin.math.sqrt(3f)
                val path = Path()
                val rnd = Random(7)
                var row = 0
                var cy = 0f
                while (cy < h + r) {
                    var cx = if (row % 2 == 0) 0f else hw / 2
                    while (cx < w + hw) {
                        path.reset()
                        for (i in 0..6) {
                            val a = Math.toRadians(60.0 * i - 30)
                            val px = cx + (r * kotlin.math.cos(a)).toFloat(); val py = cy + (r * kotlin.math.sin(a)).toFloat()
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        val f = rnd.nextFloat()
                        if (f < 0.22f) { p.style = Paint.Style.FILL; p.color = Pal.withAlpha(accent, (6 + f * 60).toInt()); c.drawPath(path, p) }
                        p.style = Paint.Style.STROKE; p.strokeWidth = d * 0.9f; p.color = Pal.withAlpha(accent, 20)
                        c.drawPath(path, p)
                        cx += hw
                    }
                    cy += r * 1.5f; row++
                }
            }
        }
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(w * 0.5f, h * 0.5f, h * 0.75f, intArrayOf(Color.TRANSPARENT, Color.argb(170, 0, 0, 0)), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
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
