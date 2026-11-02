package com.paradise.scanner

import android.annotation.SuppressLint
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
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sin

/**
 * Оптический скан: камера высокого разрешения + распознавание Google ML Kit (встроенная модель, без сети).
 * Мелкие (1–2 см), повреждённые и цветные коды читаются уверенно; каждый второй кадр дополнительно
 * проверяется в инверсии — для светлых кодов на тёмном фоне. Зум (кнопка или щипок), фокус по нажатию, фонарь.
 */
class QrActivity : ComponentActivity() {

    companion object {
        const val REQ = 4242
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROMPT = "prompt"
        fun intent(ctx: Context, prompt: String): Intent = Intent(ctx, QrActivity::class.java).putExtra(EXTRA_PROMPT, prompt)
    }

    private lateinit var preview: PreviewView
    private lateinit var overlay: Reticle
    private lateinit var status: TextView
    private lateinit var torchBtn: TextView
    private lateinit var zoomBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var analysisExec: ExecutorService? = null
    private var scanner: BarcodeScanner? = null
    @Volatile private var done = false
    private var torch = false
    private var zoomStep = 0
    private var frame = 0L
    private var fx: Fx? = null
    private val startedAt = SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        Look.apply(this)
        fx = Fx(this)
        val accent = Look.accent

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = Reticle(this, accent)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // нажатие — фокус в эту точку; щипок — зум
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val z = cam.cameraInfo.zoomState.value ?: return false
                cam.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        overlay.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !scale.isInProgress) focusAt(e.x, e.y)
            true
        }

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
        zoomBtn = termButton("ЗУМ ×1", accent) { cycleZoom() }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f) }
        torchBtn = termButton("ФОНАРЬ", accent) { toggleTorch() }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f) }
        bottom.addView(zoomBtn, lp(w = 0, weight = 1f).apply { rightMargin = dp(6) })
        bottom.addView(torchBtn, lp(w = 0, weight = 1f).apply { rightMargin = dp(6) })
        bottom.addView(termButton("ОТМЕНА", Pal.DIM) { finish() }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f) }, lp(w = 0, weight = 1f))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        analysisExec = Executors.newSingleThreadExecutor()
        startCamera()
        // подсказка, если долго не получается
        ui.postDelayed(hint, 6000)
    }

    private val hint = Runnable {
        if (!done) status.text = "Не читается? Поднесите ближе или нажмите «Зум», коснитесь кода для фокуса, включите фонарь"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try { future.get() } catch (e: Exception) { status.text = "Камера недоступна"; return@addListener }
            val sel = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val pv = Preview.Builder().setResolutionSelector(sel).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val an = ImageAnalysis.Builder()
                .setResolutionSelector(sel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            an.setAnalyzer(analysisExec!!) { img -> analyze(img) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an)
                // мелкие коды: начинаем с небольшого приближения, чтобы не подносить камеру ближе фокусного расстояния
                camera?.cameraControl?.setZoomRatio(1.0f)
            } catch (e: Exception) {
                status.text = "Камера недоступна"
            }
        }, ContextCompatExec.main(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(img: ImageProxy) {
        val sc = scanner
        if (done || sc == null) { img.close(); return }
        frame++
        val rot = img.imageInfo.rotationDegrees
        // каждый второй кадр — инверсия (светлый код на тёмном)
        val input: InputImage = try {
            if (frame % 2 == 0L) invertedInput(img, rot) else InputImage.fromMediaImage(img.image!!, rot)
        } catch (e: Exception) { img.close(); return }
        sc.process(input)
            .addOnSuccessListener { list ->
                val text = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.trim()
                if (text != null) found(text)
                else if (list.isNotEmpty()) ui.post { overlay.seen() }
            }
            .addOnCompleteListener { img.close() }
    }

    /** Кадр с инвертированной яркостью: для QR, напечатанных или показанных светлым по тёмному. */
    private fun invertedInput(img: ImageProxy, rot: Int): InputImage {
        val w = img.width; val h = img.height
        val yPlane = img.planes[0]
        val buf = yPlane.buffer
        val rowStride = yPlane.rowStride
        val out = ByteArray(w * h * 3 / 2)
        val row = ByteArray(rowStride)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(row, 0, min(rowStride, buf.remaining()))
            val o = y * w
            for (x in 0 until w) out[o + x] = (255 - (row[x].toInt() and 0xFF)).toByte()
        }
        java.util.Arrays.fill(out, w * h, out.size, 128.toByte())   // цвет не нужен
        return InputImage.fromByteArray(out, w, h, rot, InputImage.IMAGE_FORMAT_NV21)
    }

    private fun found(text: String) {
        if (done) return
        done = true
        ui.post {
            overlay.success()
            status.text = "✓ КОД СЧИТАН"
            status.setTextColor(Look.accent)
            fx?.success(); fx?.vibrate(40, 50, 90)
            ui.postDelayed({
                setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                finish()
            }, 400)
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val p = preview.meteringPointFactory.createPoint(x, y)
            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(p).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
            overlay.focus(x, y)
        } catch (e: Exception) { }
    }

    private fun cycleZoom() {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value ?: return
        val steps = listOf(1f, 2f, 3f).filter { it <= z.maxZoomRatio + 0.01f }
        zoomStep = (zoomStep + 1) % steps.size
        cam.cameraControl.setZoomRatio(steps[zoomStep])
        zoomBtn.text = "ЗУМ ×${steps[zoomStep].toInt()}"
        styleButton(zoomBtn, Look.accent, filled = zoomStep > 0)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) { status.text = "Фонаря нет на этом телефоне"; return }
        torch = !torch
        cam.cameraControl.enableTorch(torch)
        styleButton(torchBtn, Look.accent, filled = torch)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        try { scanner?.close() } catch (e: Exception) { }
        analysisExec?.shutdown()
        fx?.release()
    }

    /** Прицел: затемнение вокруг квадрата, рамка по типу сканера; код распознан — вспышка. */
    private class Reticle(ctx: Context, private val accent: Int) : View(ctx) {
        private val dim = Paint().apply { color = Color.argb(110, 0, 0, 0) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var seenAt = 0L
        private var okAt = 0L
        private var focusAt = 0L
        private var fx = 0f
        private var fy = 0f

        fun seen() { seenAt = SystemClock.uptimeMillis() }
        fun success() { okAt = SystemClock.uptimeMillis(); invalidate() }
        fun focus(x: Float, y: Float) { fx = x; fy = y; focusAt = SystemClock.uptimeMillis(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val now = SystemClock.uptimeMillis()
            val side = min(w, h) * 0.7f
            val cx = w / 2; val cy = h / 2
            val ok = okAt > 0
            val catching = now - seenAt < 500
            val shrink = if (ok) 0.94f else 1f + 0.012f * sin(now / 300f)
            val s = side * shrink / 2
            rect.set(cx - s, cy - s, cx + s, cy + s)
            canvas.drawRect(0f, 0f, w, rect.top, dim)
            canvas.drawRect(0f, rect.bottom, w, h, dim)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
            canvas.drawRect(rect.right, rect.top, w, rect.bottom, dim)
            val col = if (catching && !ok) Pal.WARN else accent
            if (ok) { fill.color = Pal.withAlpha(accent, 70); canvas.drawRect(rect, fill) }
            line.color = col
            line.strokeWidth = context.dpf(if (ok) 5f else 4f)
            val k = side * 0.16f
            when (Look.kind) {
                Kind.MED -> {
                    line.strokeWidth = context.dpf(3f)
                    canvas.drawRoundRect(rect, side * 0.08f, side * 0.08f, line)
                    val c = side * 0.04f
                    line.strokeWidth = context.dpf(2.5f)
                    canvas.drawLine(cx - c, cy, cx + c, cy, line); canvas.drawLine(cx, cy - c, cx, cy + c, line)
                }
                Kind.SCI -> {
                    val c = side * 0.06f
                    canvas.drawLine(rect.left, rect.top + c + k, rect.left, rect.top + c, line); canvas.drawLine(rect.left, rect.top + c, rect.left + c, rect.top, line); canvas.drawLine(rect.left + c, rect.top, rect.left + c + k, rect.top, line)
                    canvas.drawLine(rect.right - c - k, rect.top, rect.right - c, rect.top, line); canvas.drawLine(rect.right - c, rect.top, rect.right, rect.top + c, line); canvas.drawLine(rect.right, rect.top + c, rect.right, rect.top + c + k, line)
                    canvas.drawLine(rect.left, rect.bottom - c - k, rect.left, rect.bottom - c, line); canvas.drawLine(rect.left, rect.bottom - c, rect.left + c, rect.bottom, line); canvas.drawLine(rect.left + c, rect.bottom, rect.left + c + k, rect.bottom, line)
                    canvas.drawLine(rect.right - c - k, rect.bottom, rect.right - c, rect.bottom, line); canvas.drawLine(rect.right - c, rect.bottom, rect.right, rect.bottom - c, line); canvas.drawLine(rect.right, rect.bottom - c, rect.right, rect.bottom - c - k, line)
                    fill.color = col; canvas.drawCircle(cx, cy, context.dpf(3f), fill)
                }
                Kind.TECH -> {
                    canvas.drawLine(rect.left, rect.top, rect.left + k, rect.top, line); canvas.drawLine(rect.left, rect.top, rect.left, rect.top + k, line)
                    canvas.drawLine(rect.right, rect.top, rect.right - k, rect.top, line); canvas.drawLine(rect.right, rect.top, rect.right, rect.top + k, line)
                    canvas.drawLine(rect.left, rect.bottom, rect.left + k, rect.bottom, line); canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - k, line)
                    canvas.drawLine(rect.right, rect.bottom, rect.right - k, rect.bottom, line); canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - k, line)
                    val c = side * 0.035f
                    line.strokeWidth = context.dpf(1.5f)
                    canvas.drawLine(cx - c, cy, cx + c, cy, line); canvas.drawLine(cx, cy - c, cx, cy + c, line)
                }
            }
            // кружок фокуса там, где коснулись
            if (now - focusAt < 900) {
                line.color = Pal.withAlpha(Color.WHITE, (255 * (1f - (now - focusAt) / 900f)).toInt())
                line.strokeWidth = context.dpf(2f)
                canvas.drawCircle(fx, fy, context.dpf(28f), line)
            }
            if (!ok) {
                fill.color = Color.WHITE
                fill.textSize = context.dpf(12f)
                fill.typeface = MONO_BOLD
                fill.textAlign = Paint.Align.CENTER
                canvas.drawText("КОД — ВНУТРИ РАМКИ · КАСАНИЕ — ФОКУС", cx, rect.bottom + context.dpf(26f), fill)
                fill.textAlign = Paint.Align.LEFT
            }
            postInvalidateDelayed(50)
        }
    }
}

/** Главный поток для колбэков CameraX без зависимости от ContextCompat. */
object ContextCompatExec {
    fun main(ctx: Context): java.util.concurrent.Executor = ctx.mainExecutor
}
