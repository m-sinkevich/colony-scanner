package com.paradise.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Анимированный "экран сканера": кольца, вращающийся луч, отметки, кольцо прогресса. */
class RadarView(ctx: Context) : View(ctx) {

    enum class State { IDLE, SCANNING, LOCKED, FAIL, OFFLINE }

    var color: Int = Pal.DEFAULT_ACCENT
        set(v) {
            field = v; shader = null; invalidate()
        }

    var state: State = State.IDLE
        set(v) {
            field = v; shader = null; stateSince = SystemClock.uptimeMillis(); invalidate()
        }

    /** 0..1 — заполнение внешнего кольца во время сканирования. */
    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f); invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
    }
    private var shader: SweepGradient? = null
    private val sweepMatrix = Matrix()
    private val rect = RectF()

    private var angle = 0f
    private var last = 0L
    private var stateSince = SystemClock.uptimeMillis()

    // случайные "отражения" на экране
    private class Blip(val a: Float, val r: Float, var life: Float)
    private val blips = ArrayList<Blip>()

    private fun current(): Int = when (state) {
        State.FAIL -> Pal.ERROR
        State.OFFLINE -> Pal.DIM
        else -> color
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last).coerceAtMost(100)) / 1000f
        last = now

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - context.dpf(14f)
        if (r <= 0f) return
        val c = current()
        val sw = context.dpf(1f)

        // фон-диск
        fill.color = Pal.withAlpha(c, 14)
        canvas.drawCircle(cx, cy, r, fill)

        // кольца
        ring.strokeWidth = sw
        for (i in 1..4) {
            ring.color = Pal.withAlpha(c, if (i == 4) 150 else 55)
            canvas.drawCircle(cx, cy, r * i / 4f, ring)
        }
        // перекрестие
        ring.color = Pal.withAlpha(c, 45)
        canvas.drawLine(cx - r, cy, cx + r, cy, ring)
        canvas.drawLine(cx, cy - r, cx, cy + r, ring)

        // деления по краю
        ring.color = Pal.withAlpha(c, 120)
        for (i in 0 until 72) {
            val a = Math.toRadians(i * 5.0)
            val len = if (i % 6 == 0) context.dpf(9f) else context.dpf(4f)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(cx + ca * (r + context.dpf(3f)), cy + sa * (r + context.dpf(3f)),
                cx + ca * (r + context.dpf(3f) + len), cy + sa * (r + context.dpf(3f) + len), ring)
        }

        val speed = when (state) {
            State.IDLE -> 70f
            State.SCANNING -> 330f
            State.LOCKED -> 0f
            State.FAIL -> 40f
            State.OFFLINE -> 0f
        }
        angle = (angle + speed * dt) % 360f

        // вращающийся луч
        if (state != State.OFFLINE && state != State.LOCKED) {
            val sh = shader ?: SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Pal.withAlpha(c, 40), Pal.withAlpha(c, 170)),
                floatArrayOf(0f, 0.70f, 0.93f, 1f)
            ).also { shader = it }
            sweepMatrix.reset()
            sweepMatrix.postRotate(angle, cx, cy)
            sh.setLocalMatrix(sweepMatrix)
            sweep.shader = sh
            canvas.drawCircle(cx, cy, r, sweep)
            ring.color = Pal.withAlpha(c, 220)
            ring.strokeWidth = sw * 2
            val ra = Math.toRadians(angle.toDouble())
            canvas.drawLine(cx, cy, cx + cos(ra).toFloat() * r, cy + sin(ra).toFloat() * r, ring)
            ring.strokeWidth = sw
        }

        // отметки
        val spawnChance = if (state == State.SCANNING) 0.25f else 0.03f
        if (state != State.OFFLINE && state != State.LOCKED && Random.nextFloat() < spawnChance) {
            blips.add(Blip(angle, Random.nextFloat() * 0.85f + 0.1f, 1f))
        }
        val iter = blips.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            b.life -= dt * 0.8f
            if (b.life <= 0f) {
                iter.remove(); continue
            }
            val a = Math.toRadians(b.a.toDouble())
            fill.color = Pal.withAlpha(c, (230 * b.life).toInt())
            canvas.drawCircle(cx + cos(a).toFloat() * r * b.r, cy + sin(a).toFloat() * r * b.r, context.dpf(3.5f), fill)
        }

        // кольцо прогресса
        if (state == State.SCANNING || state == State.LOCKED) {
            arc.color = c
            arc.strokeWidth = context.dpf(5f)
            val o = r + context.dpf(3f)
            rect.set(cx - o, cy - o, cx + o, cy + o)
            canvas.drawArc(rect, -90f, 360f * (if (state == State.LOCKED) 1f else progress), false, arc)
        }

        // захват цели
        if (state == State.LOCKED) {
            val t = (now - stateSince) / 1000f
            val pulse = 0.5f + 0.5f * sin(t * 10f)
            ring.color = c
            ring.strokeWidth = context.dpf(3f)
            canvas.drawCircle(cx, cy, r * (0.22f + 0.04f * pulse), ring)
            fill.color = Pal.withAlpha(c, 200)
            canvas.drawCircle(cx, cy, r * 0.06f, fill)
            ring.strokeWidth = sw
        }

        // центр
        fill.color = c
        canvas.drawCircle(cx, cy, context.dpf(3f), fill)

        if (state != State.OFFLINE) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        last = 0L
    }
}
