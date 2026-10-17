package com.paradise.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Экран результата: заголовок, картинка, текст (печатается как на терминале — тап, чтобы показать сразу). */
class ResultActivity : Activity() {

    companion object {
        private const val K_KIND = "kind"
        private const val K_CODE = "code"
        private const val K_TITLE = "title"
        private const val K_BODY = "body"
        private const val K_IMAGE = "image"
        private const val K_MODE_NAME = "mode_name"
        private const val K_COLOR = "color"

        fun intent(ctx: Context, mode: Mode, r: Lookup): Intent {
            val i = Intent(ctx, ResultActivity::class.java)
                .putExtra(K_MODE_NAME, mode.name)
                .putExtra(K_COLOR, mode.color)
            when (r) {
                is Lookup.Found -> i.putExtra(K_KIND, "found")
                    .putExtra(K_CODE, r.code)
                    .putExtra(K_TITLE, r.entry.title)
                    .putExtra(K_BODY, r.entry.body)
                    .putExtra(K_IMAGE, r.entry.image)
                is Lookup.WrongScanner -> i.putExtra(K_KIND, "wrong").putExtra(K_CODE, r.code)
                is Lookup.Unknown -> i.putExtra(K_KIND, "unknown").putExtra(K_CODE, r.code)
            }
            return i
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var fullText = ""
    private var shown = 0
    private lateinit var bodyView: TextView
    private var fx: Fx? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fx = Fx(this)

        val kind = intent.getStringExtra(K_KIND) ?: "unknown"
        val code = intent.getStringExtra(K_CODE) ?: "—"
        val modeName = intent.getStringExtra(K_MODE_NAME) ?: ""
        val accent = when (kind) {
            "found" -> intent.getIntExtra(K_COLOR, Pal.DEFAULT_ACCENT)
            "wrong" -> Pal.WARN
            else -> Pal.ERROR
        }

        val title: String
        val body: String
        val image: String?
        when (kind) {
            "found" -> {
                title = intent.getStringExtra(K_TITLE).orEmpty()
                body = intent.getStringExtra(K_BODY).orEmpty()
                image = intent.getStringExtra(K_IMAGE)
            }
            "wrong" -> {
                title = "АНАЛИЗ НЕВОЗМОЖЕН"
                body = "Объект зарегистрирован, но не содержит данных, доступных для сканера типа «$modeName».\n\nИспользуйте сканер другого профиля."
                image = null
            }
            else -> {
                title = "ОБЪЕКТ НЕ ОПОЗНАН"
                body = "Сигнатура объекта отсутствует в базе данных.\n\nВозможно, база сканера устарела или объект не представляет интереса."
                image = null
            }
        }

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Pal.BG) }
        val root = column(18)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        root.addView(label("РЕЗУЛЬТАТ СКАНИРОВАНИЯ", 13f, accent, bold = true).apply { letterSpacing = 0.12f }, lp())
        root.addView(label("$modeName · $time · ID $code", 11f, Pal.DIM), lp().apply { topMargin = dp(2) })

        // разделитель
        root.addView(View(this).apply { setBackgroundColor(Pal.withAlpha(accent, 120)) },
            lp(h = dp(2)).apply { topMargin = dp(10); bottomMargin = dp(14) })

        if (title.isNotBlank()) {
            root.addView(label(title, 22f, accent, bold = true), lp().apply { bottomMargin = dp(12) })
        }

        val maxW = resources.displayMetrics.widthPixels
        val bmp = Repo.loadImage(this, image, maxW)
        if (bmp != null) {
            val frame = FrameLayout(this).apply {
                background = boxBg(context, Pal.withAlpha(accent, 160), Pal.PANEL, 4, 1)
                val p = dp(4)
                setPadding(p, p, p, p)
            }
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            }
            frame.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            root.addView(frame, lp().apply { bottomMargin = dp(14) })
            frame.alpha = 0f
            frame.animate().alpha(1f).setDuration(600).start()
        }

        bodyView = label("", 16f, Pal.TEXT)
        root.addView(bodyView, lp(h = 0, weight = 1f))
        fullText = body
        bodyView.setOnClickListener { showAll() }
        root.setOnClickListener { showAll() }

        val back = termButton("НОВОЕ СКАНИРОВАНИЕ", accent) { finish() }
        root.addView(back, lp().apply { topMargin = dp(18) })

        setContentView(scroll)
        ui.postDelayed(typer, 250)
    }

    private val typer = object : Runnable {
        override fun run() {
            if (shown >= fullText.length) return
            // печатаем по несколько символов, чтобы длинный текст не тянулся вечно
            val step = if (fullText.length > 600) 6 else if (fullText.length > 250) 3 else 2
            shown = (shown + step).coerceAtMost(fullText.length)
            bodyView.text = fullText.substring(0, shown) + if (shown < fullText.length) "▌" else ""
            if (shown % 12 < step) fx?.tick()
            if (shown < fullText.length) ui.postDelayed(this, 18)
        }
    }

    private fun showAll() {
        shown = fullText.length
        bodyView.text = fullText
        ui.removeCallbacks(typer)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        fx?.release()
    }
}
