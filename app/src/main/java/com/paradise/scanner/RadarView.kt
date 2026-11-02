package com.paradise.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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

    enum class State { IDLE, SCANNING, ANALYZING, LOCKED, FAIL, OFFLINE }

    var color: Int = Pal.DEFAULT_ACCENT
        set(v) {
            field = v; shader = null; invalidate()
        }

    var kind: Kind = Kind.TECH
        set(v) { field = v; invalidate() }

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
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var dashAngle = 0f
    private var waveT = 0f
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
        val r = min(w, h) / 2f - context.dpf(34f)
        if (r <= 0f) return
        val c = current()
        val sw = context.dpf(1f)

        when (kind) {
            Kind.TECH -> drawTech(canvas, cx, cy, r, c, sw, dt)
            Kind.MED -> drawMed(canvas, cx, cy, r, c, sw, dt)
            Kind.SCI -> drawSci(canvas, cx, cy, r, c, sw, dt)
        }

        // кольцо прогресса
        if (state == State.SCANNING || state == State.ANALYZING || state == State.LOCKED) {
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

    /** Технический: радар с вращающимся лучом и отметками. */
    private fun drawTech(canvas: Canvas, cx: Float, cy: Float, r: Float, c: Int, sw: Float, dt: Float) {
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

        // внешние вращающиеся пунктирные кольца (в разные стороны)
        val busy = state == State.SCANNING || state == State.ANALYZING
        dashAngle = (dashAngle + dt * (if (busy) 60f else 14f)) % 360f
        dash.color = Pal.withAlpha(c, 150)
        dash.strokeWidth = context.dpf(2f)
        dash.pathEffect = android.graphics.DashPathEffect(floatArrayOf(context.dpf(14f), context.dpf(8f)), 0f)
        val r1 = r + context.dpf(20f)
        rect.set(cx - r1, cy - r1, cx + r1, cy + r1)
        canvas.save(); canvas.rotate(dashAngle, cx, cy)
        canvas.drawArc(rect, 0f, 300f, false, dash)
        canvas.restore()
        dash.color = Pal.withAlpha(c, 80)
        dash.strokeWidth = context.dpf(1f)
        dash.pathEffect = android.graphics.DashPathEffect(floatArrayOf(context.dpf(3f), context.dpf(6f)), 0f)
        val r2 = r + context.dpf(28f)
        canvas.save(); canvas.rotate(-dashAngle * 1.7f, cx, cy)
        canvas.drawCircle(cx, cy, r2, dash)
        canvas.restore()
        // три "спутника" на орбите
        for (k in 0 until 3) {
            val oa = Math.toRadians((dashAngle * 2.3f + k * 120f).toDouble())
            fill.color = Pal.withAlpha(c, 230)
            canvas.drawCircle(cx + cos(oa).toFloat() * r2, cy + sin(oa).toFloat() * r2, context.dpf(2.6f), fill)
        }

        // волны сканирования, расходящиеся от центра
        if (busy) {
            waveT += dt * (if (state == State.ANALYZING) 1.6f else 0.7f)
            for (k in 0 until 3) {
                val f = ((waveT + k / 3f) % 1f)
                ring.color = Pal.withAlpha(c, ((1f - f) * 120).toInt())
                ring.strokeWidth = context.dpf(2f)
                canvas.drawCircle(cx, cy, r * f, ring)
            }
            ring.strokeWidth = sw
        }

        val speed = when (state) {
            State.IDLE -> 50f
            State.SCANNING -> 200f
            State.ANALYZING -> 420f
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
        val spawnChance = if (state == State.ANALYZING) 0.35f else if (state == State.SCANNING) 0.08f else 0.03f
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

    }

    private val busy get() = state == State.SCANNING || state == State.ANALYZING
    private val path = Path()
    private var beatT = 0f
    private var ecgShift = 0f

    /** Форма кардиограммы на отрезке 0..1: зубцы P, QRS, T. */
    private fun ecg(u: Float): Float {
        val x = ((u % 1f) + 1f) % 1f
        fun bump(c0: Float, w: Float, h: Float) = h * kotlin.math.exp(-((x - c0) / w) * ((x - c0) / w))
        return bump(0.18f, 0.035f, 0.12f) - bump(0.36f, 0.012f, 0.18f) + bump(0.40f, 0.014f, 1f) - bump(0.44f, 0.014f, 0.32f) + bump(0.66f, 0.06f, 0.24f)
    }

    /** Медицинский: биомонитор — кардиограмма, пульсирующий крест, кольца пульса. */
    private fun drawMed(canvas: Canvas, cx: Float, cy: Float, r: Float, c: Int, sw: Float, dt: Float) {
        val bpm = when (state) { State.SCANNING -> 96f; State.ANALYZING -> 140f; State.IDLE -> 64f; else -> 50f }
        if (state != State.OFFLINE) { beatT += dt * bpm / 60f; ecgShift += dt * (bpm / 60f) * 0.5f }
        val phase = beatT % 1f
        val pulse = kotlin.math.exp(-phase * 7f)

        fill.color = Pal.withAlpha(c, 12)
        canvas.drawCircle(cx, cy, r, fill)
        // сетка монитора
        ring.strokeWidth = sw
        ring.color = Pal.withAlpha(c, 26)
        val step = r / 5f
        var k = -5
        while (k <= 5) {
            val off = k * step
            val half = kotlin.math.sqrt((r * r - off * off).coerceAtLeast(0f))
            canvas.drawLine(cx + off, cy - half, cx + off, cy + half, ring)
            canvas.drawLine(cx - half, cy + off, cx + half, cy + off, ring)
            k++
        }
        ring.color = Pal.withAlpha(c, 160)
        ring.strokeWidth = context.dpf(2f)
        canvas.drawCircle(cx, cy, r, ring)
        ring.strokeWidth = sw
        ring.color = Pal.withAlpha(c, 60)
        canvas.drawCircle(cx, cy, r * 0.62f, ring)
        // кольца пульса
        if (state != State.OFFLINE) for (j in 0 until 2) {
            val f = ((phase + j * 0.5f) % 1f)
            ring.color = Pal.withAlpha(Look.accent2, ((1f - f) * (if (busy) 150 else 70)).toInt())
            ring.strokeWidth = context.dpf(2f)
            canvas.drawCircle(cx, cy, r * (0.25f + 0.75f * f), ring)
        }
        // кардиограмма — внутри круга
        canvas.save()
        path.reset(); path.addCircle(cx, cy, r - sw, Path.Direction.CW)
        canvas.clipPath(path)
        path.reset()
        val amp = r * (if (busy) 0.42f else 0.3f)
        val n = 120
        for (i in 0..n) {
            val x = cx - r + 2 * r * i / n
            val y = cy - amp * ecg(i / n.toFloat() * 2f - ecgShift)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        arc.color = Pal.withAlpha(c, 230)
        arc.strokeWidth = context.dpf(2.4f)
        arc.pathEffect = null
        canvas.drawPath(path, arc)
        canvas.restore()
        // крест в центре, «бьётся» с пульсом
        val s = r * (0.13f + 0.03f * pulse)
        val w = s * 0.36f
        fill.color = Pal.withAlpha(Look.accent2, (150 + 100 * pulse).toInt())
        canvas.drawRect(cx - w, cy - s, cx + w, cy + s, fill)
        canvas.drawRect(cx - s, cy - w, cx + s, cy + w, fill)
        // деления по краю
        ring.color = Pal.withAlpha(c, 110)
        ring.strokeWidth = sw
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val len = if (i % 5 == 0) context.dpf(8f) else context.dpf(3f)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            canvas.drawLine(cx + ca * (r + context.dpf(4f)), cy + sa * (r + context.dpf(4f)), cx + ca * (r + context.dpf(4f) + len), cy + sa * (r + context.dpf(4f) + len), ring)
        }
    }

    private val bars = FloatArray(72) { Random.nextFloat() }

    /** Научный: спектрометр — шестиугольные кольца, спектр по кругу, спираль в центре. */
    private fun drawSci(canvas: Canvas, cx: Float, cy: Float, r: Float, c: Int, sw: Float, dt: Float) {
        val spd = when (state) { State.SCANNING -> 70f; State.ANALYZING -> 160f; State.IDLE -> 18f; else -> 0f }
        angle = (angle + spd * dt) % 360f
        fill.color = Pal.withAlpha(c, 12)
        canvas.drawCircle(cx, cy, r, fill)
        // шестиугольные кольца
        ring.strokeWidth = sw
        for (i in 1..4) {
            ring.color = Pal.withAlpha(c, if (i == 4) 150 else 50)
            val rr = r * i / 4f
            path.reset()
            for (k in 0..6) {
                val a = Math.toRadians(60.0 * k + 30 + (if (i % 2 == 0) angle * 0.2 else -angle * 0.2))
                val x = cx + cos(a).toFloat() * rr; val y = cy + sin(a).toFloat() * rr
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, ring)
        }
        // спектр по кругу
        val target = if (busy) 1f else 0.35f
        for (k in bars.indices) {
            val goal = target * (0.25f + 0.75f * ((sin(angle / 30f + k * 0.7f) + 1f) / 2f) * (0.5f + 0.5f * Random.nextFloat()))
            bars[k] += (goal - bars[k]) * (dt * 6f).coerceAtMost(1f)
            val a = Math.toRadians(k * 5.0 + angle * 0.5)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val r0 = r + context.dpf(6f)
            val r1 = r0 + context.dpf(4f) + bars[k] * context.dpf(22f)
            val hue = Color.HSVToColor(floatArrayOf(90f + 120f * (k / bars.size.toFloat()), 0.75f, 1f))
            ring.color = Pal.withAlpha(if (state == State.FAIL) Pal.ERROR else hue, 200)
            ring.strokeWidth = context.dpf(2.6f)
            canvas.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * r1, cy + sa * r1, ring)
        }
        ring.strokeWidth = sw
        // двойная спираль в центре
        val h = r * 0.62f
        val turns = 2.2f
        for (i in 0..36) {
            val u = i / 36f
            val y = cy - h + 2 * h * u
            val ph = Math.toRadians((u * 360f * turns + angle * 2f).toDouble())
            val x1 = cx + cos(ph).toFloat() * r * 0.22f
            val x2 = cx - cos(ph).toFloat() * r * 0.22f
            val front = sin(ph).toFloat()
            if (i % 3 == 0) {
                ring.color = Pal.withAlpha(c, 60)
                canvas.drawLine(x1, y, x2, y, ring)
            }
            fill.color = Pal.withAlpha(c, (140 + 110 * front).toInt().coerceIn(40, 255))
            canvas.drawCircle(x1, y, context.dpf(2.6f + front), fill)
            fill.color = Pal.withAlpha(Look.accent2, (140 - 110 * front).toInt().coerceIn(40, 255))
            canvas.drawCircle(x2, y, context.dpf(2.6f - front), fill)
        }
        // сканирующая волна
        if (busy) {
            waveT += dt * (if (state == State.ANALYZING) 1.4f else 0.6f)
            val f = waveT % 1f
            ring.color = Pal.withAlpha(c, ((1f - f) * 150).toInt())
            ring.strokeWidth = context.dpf(2f)
            canvas.drawCircle(cx, cy, r * f, ring)
            ring.strokeWidth = sw
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        last = 0L
    }
}
