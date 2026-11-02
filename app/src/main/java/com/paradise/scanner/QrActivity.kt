package com.paradise.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import kotlin.math.min
import kotlin.math.sin

/**
 * Оптический скан: камера на весь экран, рамка-прицел в стиле сканера.
 * Видно, что код пойман (точки на рамке), и что он считан (рамка вспыхивает, вибрация).
 * Читает и обычные, и инвертированные QR (светлые на тёмном — как на инопланетном терминале).
 */
class QrActivity : Activity() {

    companion object {
        const val REQ = 4242
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROMPT = "prompt"
        fun intent(ctx: Context, prompt: String): Intent = Intent(ctx, QrActivity::class.java).putExtra(EXTRA_PROMPT, prompt)
    }

    private lateinit var barcode: BarcodeView
    private lateinit var overlay: Reticle
    private lateinit var status: TextView
    private lateinit var torchBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var torch = false
    private var done = false
    private var fx: Fx? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        Look.apply(this)
        fx = Fx(this)
        val accent = Look.accent

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        barcode = BarcodeView(this).apply {
            cameraSettings = CameraSettings().apply {
                isAutoFocusEnabled = true
                isContinuousFocusEnabled = true
                isBarcodeSceneModeEnabled = true
                isExposureEnabled = true
            }
            val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
            // 2 = смешанный режим: обычные и инвертированные (светлые на тёмном) коды
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE), hints, "UTF-8", 2)
            setMarginFraction(0.08)
        }
        root.addView(barcode, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = Reticle(this, accent)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // нажатие по экрану — перефокусировка
        overlay.setOnClickListener { try { barcode.pause(); barcode.resume() } catch (e: Exception) { } ; startDecode() }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(12))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        top.addView(label(Look.kicker + " · ОПТИЧЕСКИЙ СКАН", 10f, Pal.withAlpha(accent, 220), bold = true).apply { letterSpacing = 0.14f }, lp())
        status = label(intent.getStringExtra(EXTRA_PROMPT) ?: "Наведите сканер на код", 16f, Color.WHITE, bold = true).apply { setPadding(0, dp(6), 0, 0) }
        top.addView(status, lp())
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        torchBtn = termButton("ФОНАРЬ", accent) { toggleTorch() }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) }
        bottom.addView(torchBtn, lp(w = 0, weight = 1f).apply { rightMargin = dp(8) })
        bottom.addView(termButton("ОТМЕНА", Pal.DIM) { finish() }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) }, lp(w = 0, weight = 1f))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
        startDecode()
    }

    private fun startDecode() {
        barcode.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text?.trim().orEmpty()
                if (done || text.isEmpty()) return
                done = true
                barcode.pause()
                overlay.success()
                status.text = "✓ КОД СЧИТАН"
                status.setTextColor(Look.accent)
                fx?.success(); fx?.vibrate(40, 50, 90)
                ui.postDelayed({
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                    finish()
                }, 450)
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {
                if (!done && resultPoints.isNotEmpty()) overlay.seen(resultPoints.size)
            }
        })
    }

    private fun toggleTorch() {
        torch = !torch
        try { barcode.setTorch(torch) } catch (e: Exception) { }
        styleButton(torchBtn, Look.accent, filled = torch)
    }

    override fun onResume() {
        super.onResume()
        barcode.resume()
    }

    override fun onPause() {
        super.onPause()
        barcode.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        fx?.release()
    }

    /** Прицел: затемнение вокруг квадрата, уголки, центр; «ловит» — уголки сжимаются, считан — вспышка. */
    private class Reticle(ctx: Context, private val accent: Int) : View(ctx) {
        private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var seenAt = 0L
        private var seenN = 0
        private var okAt = 0L

        fun seen(n: Int) { seenAt = SystemClock.uptimeMillis(); seenN = n; invalidate() }
        fun success() { okAt = SystemClock.uptimeMillis(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val now = SystemClock.uptimeMillis()
            val side = min(w, h) * 0.72f
            val cx = w / 2; val cy = h / 2
            val catching = now - seenAt < 600
            val ok = okAt > 0
            val shrink = if (ok) 0.94f else if (catching) 0.97f else 1f + 0.012f * sin(now / 300f)
            val s = side * shrink / 2
            rect.set(cx - s, cy - s, cx + s, cy + s)
            canvas.drawRect(0f, 0f, w, rect.top, dim)
            canvas.drawRect(0f, rect.bottom, w, h, dim)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
            canvas.drawRect(rect.right, rect.top, w, rect.bottom, dim)
            val col = if (ok) accent else if (catching) Pal.WARN else accent
            if (ok) { fill.color = Pal.withAlpha(accent, 70); canvas.drawRect(rect, fill) }
            line.color = col
            line.strokeWidth = context.dpf(if (ok || catching) 5f else 4f)
            val k = side * 0.16f
            when (Look.kind) {
                Kind.MED -> {
                    // скруглённая рамка-капсула и крест в центре
                    line.strokeWidth = context.dpf(3f)
                    canvas.drawRoundRect(rect, side * 0.08f, side * 0.08f, line)
                    val c = side * 0.04f
                    line.strokeWidth = context.dpf(2.5f)
                    canvas.drawLine(cx - c, cy, cx + c, cy, line); canvas.drawLine(cx, cy - c, cx, cy + c, line)
                }
                Kind.SCI -> {
                    // восемь граней — шестиугольный прицел по углам
                    val c = side * 0.06f
                    canvas.drawLine(rect.left, rect.top + c + k, rect.left, rect.top + c, line); canvas.drawLine(rect.left, rect.top + c, rect.left + c, rect.top, line); canvas.drawLine(rect.left + c, rect.top, rect.left + c + k, rect.top, line)
                    canvas.drawLine(rect.right - c - k, rect.top, rect.right - c, rect.top, line); canvas.drawLine(rect.right - c, rect.top, rect.right, rect.top + c, line); canvas.drawLine(rect.right, rect.top + c, rect.right, rect.top + c + k, line)
                    canvas.drawLine(rect.left, rect.bottom - c - k, rect.left, rect.bottom - c, line); canvas.drawLine(rect.left, rect.bottom - c, rect.left + c, rect.bottom, line); canvas.drawLine(rect.left + c, rect.bottom, rect.left + c + k, rect.bottom, line)
                    canvas.drawLine(rect.right - c - k, rect.bottom, rect.right - c, rect.bottom, line); canvas.drawLine(rect.right - c, rect.bottom, rect.right, rect.bottom - c, line); canvas.drawLine(rect.right, rect.bottom - c, rect.right, rect.bottom - c - k, line)
                    fill.color = col; canvas.drawCircle(cx, cy, context.dpf(3f), fill)
                }
                Kind.TECH -> {
                    // уголки-скобы и перекрестие
                    canvas.drawLine(rect.left, rect.top, rect.left + k, rect.top, line); canvas.drawLine(rect.left, rect.top, rect.left, rect.top + k, line)
                    canvas.drawLine(rect.right, rect.top, rect.right - k, rect.top, line); canvas.drawLine(rect.right, rect.top, rect.right, rect.top + k, line)
                    canvas.drawLine(rect.left, rect.bottom, rect.left + k, rect.bottom, line); canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - k, line)
                    canvas.drawLine(rect.right, rect.bottom, rect.right - k, rect.bottom, line); canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - k, line)
                    val c = side * 0.035f
                    line.strokeWidth = context.dpf(1.5f)
                    canvas.drawLine(cx - c, cy, cx + c, cy, line); canvas.drawLine(cx, cy - c, cx, cy + c, line)
                }
            }
            if (!ok) {
                fill.color = Color.WHITE
                fill.textSize = context.dpf(12f)
                fill.typeface = MONO_BOLD
                fill.textAlign = Paint.Align.CENTER
                canvas.drawText(if (catching) "КОД В ПРИЦЕЛЕ… ДЕРЖИТЕ РОВНО" else "ПОМЕСТИТЕ КОД В РАМКУ", cx, rect.bottom + context.dpf(26f), fill)
                fill.textAlign = Paint.Align.LEFT
            }
            postInvalidateDelayed(60)
        }
    }
}
