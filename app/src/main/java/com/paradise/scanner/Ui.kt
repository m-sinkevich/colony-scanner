package com.paradise.scanner

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.media.AudioManager
import android.media.ToneGenerator
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Общая палитра и мелкие помощники для интерфейса, собранного кодом (без XML-разметки). */
object Pal {
    val BG = Color.parseColor("#05090C")
    val PANEL = Color.parseColor("#0B1419")
    val TEXT = Color.parseColor("#D8F3EA")
    val DIM = Color.parseColor("#5E7A74")
    val WARN = Color.parseColor("#FFB84D")
    val ERROR = Color.parseColor("#FF5A5A")
    val DEFAULT_ACCENT = Color.parseColor("#3DFFB0")

    fun parse(hex: String?, fallback: Int = DEFAULT_ACCENT): Int =
        try {
            if (hex.isNullOrBlank()) fallback
            else Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (e: Exception) {
            fallback
        }

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

val MONO: Typeface = Typeface.MONOSPACE
val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.label(
    s: String,
    sizeSp: Float = 15f,
    color: Int = Pal.TEXT,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = if (bold) MONO_BOLD else MONO
    setLineSpacing(0f, 1.15f)
}

fun boxBg(ctx: Context, stroke: Int, fill: Int = Color.TRANSPARENT, radiusDp: Int = 4, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = ctx.dpf(radiusDp.toFloat())
        setStroke(ctx.dp(strokeDp).coerceAtLeast(1), stroke)
    }

/** Кнопка в стиле космического терминала: скошенные углы, свечение, моноширинный текст. */
fun Context.termButton(text: String, color: Int, filled: Boolean = false, onClick: () -> Unit): TextView =
    label(text, 17f, if (filled) Pal.BG else color, bold = true).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        letterSpacing = 0.14f
        val pad = dp(16)
        setPadding(pad, pad, pad, pad)
        styleButton(this, color, filled)
        setOnClickListener {
            animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }.start()
            onClick()
        }
    }

fun styleButton(v: TextView, color: Int, filled: Boolean) {
    val ctx = v.context
    v.setTextColor(if (filled) Pal.BG else color)
    v.background = HudDrawable(ctx, color, if (filled) color else Pal.withAlpha(color, 30), chamferDp = 12f, strokeDp = 1.6f)
    if (!filled) v.setShadowLayer(ctx.dpf(8f), 0f, 0f, Pal.withAlpha(color, 200)) else v.setShadowLayer(0f, 0f, 0f, 0)
}

/** Мягкое неоновое свечение текста. */
fun TextView.glow(color: Int, radiusDp: Float = 10f): TextView {
    setShadowLayer(context.dpf(radiusDp), 0f, 0f, Pal.withAlpha(color, 210))
    return this
}

fun Context.input(hint: String, value: String, secret: Boolean = false): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    setHintTextColor(Pal.DIM)
    setTextColor(Pal.TEXT)
    typeface = MONO
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    isSingleLine = true
    inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    val p = dp(10)
    setPadding(p, p, p, p)
    background = boxBg(context, Pal.DIM, Pal.PANEL, 4, 1)
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun View.margins(ctx: Context, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): View {
    val p = (layoutParams as? ViewGroup.MarginLayoutParams) ?: lp()
    p.setMargins(ctx.dp(left), ctx.dp(top), ctx.dp(right), ctx.dp(bottom))
    layoutParams = p
    return this
}

fun Context.column(padDp: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(padDp)
    setPadding(p, p, p, p)
}

/** Звук и вибрация — для ощущения "прибора". Всё обёрнуто в try: на некоторых телефонах звук недоступен. */
class Fx(private val activity: Activity) {
    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    } catch (e: Exception) {
        null
    }

    fun beep() = play(ToneGenerator.TONE_PROP_BEEP, 80)
    fun success() = play(ToneGenerator.TONE_PROP_ACK, 250)
    fun fail() = play(ToneGenerator.TONE_PROP_NACK, 300)
    fun tick() = play(ToneGenerator.TONE_CDMA_PIP, 40)

    private fun play(t: Int, ms: Int) {
        try {
            tone?.startTone(t, ms)
        } catch (e: Exception) {
        }
    }

    fun vibrate(vararg pattern: Long) {
        try {
            @Suppress("DEPRECATION")
            val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (pattern.size == 1) v.vibrate(VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, *pattern), -1))
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            tone?.release()
        } catch (e: Exception) {
        }
        tone = null
    }
}
