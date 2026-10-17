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

