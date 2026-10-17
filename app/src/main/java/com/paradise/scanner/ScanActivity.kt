package com.paradise.scanner

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Главный экран — прибор-сканер.
 * Кнопка запускает сессию сканирования (по умолчанию 60 с, с таймером), которая длится,
 * пока не будет найдена метка, не истечёт время или игрок не нажмёт «Отменить».
 */
class ScanActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var fx: Fx
    private var adapter: NfcAdapter? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var stars: StarfieldView
    private lateinit var headerPanel: LinearLayout
    private lateinit var kicker: TextView
    private lateinit var header: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var timerLabel: TextView
    private lateinit var timer: TextView
    private lateinit var scanBtn: TextView
    private lateinit var radar: RadarView
    private lateinit var logPanel: LinearLayout
    private lateinit var telemetry: TextView
    private lateinit var log: TextView
    private lateinit var footer: TextView

    private var mode: Mode? = null
    private val accent: Int get() = mode?.color ?: Pal.DEFAULT_ACCENT

    private var sessionActive = false
    private var analyzing = false
    private var sessionStart = 0L
    private var sessionLimitMs = 0L
    private var lastPhraseAt = 0L
    private var lastSecond = -1L
    private var animator: ValueAnimator? = null

    // чтобы метка, оставшаяся у телефона после возврата с экрана результата, не сработала повторно
    private var lastUid: String? = null
    private var lastUidAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        fx = Fx(this)
        adapter = NfcAdapter.getDefaultAdapter(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    // ------------------------------------------------------------------ интерфейс

    private fun buildUi() {
        val root = column(16)

        // --- заголовок ---
        headerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        kicker = label("◈ КОЛОНИАЛЬНЫЙ СКАНЕР · v${BuildConfig.VERSION_NAME}", 10f, Pal.DIM).apply { letterSpacing = 0.15f }
        header = label("СКАНЕР", 24f, Pal.DEFAULT_ACCENT, bold = true).apply { letterSpacing = 0.12f }
        subtitle = label("", 11f, Pal.DIM)
        headerPanel.addView(kicker, lp())
        headerPanel.addView(header, lp().apply { topMargin = dp(2) })
        headerPanel.addView(subtitle, lp())
        root.addView(headerPanel, lp())

        // служебное меню: удерживать заголовок 3 секунды
        var downAt = 0L
        val holdCheck = Runnable { if (downAt != 0L) { downAt = 0L; askPin() } }
        headerPanel.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downAt = SystemClock.uptimeMillis(); ui.postDelayed(holdCheck, 3000) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { downAt = 0L; ui.removeCallbacks(holdCheck) }
            }
            true
        }

        // --- статус + таймер ---
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = label("", 15f, Pal.TEXT, bold = true).apply { letterSpacing = 0.06f; maxLines = 2 }
        statusRow.addView(status, lp(w = 0, weight = 1f))
        val timerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        timerLabel = label("СЕССИЯ", 9f, Pal.DIM).apply { letterSpacing = 0.2f; gravity = Gravity.END }
        timer = label("--:--", 34f, Pal.DIM, bold = true).apply { gravity = Gravity.END; letterSpacing = 0.05f }
        timerBox.addView(timerLabel, lp(w = LinearLayout.LayoutParams.WRAP_CONTENT))
        timerBox.addView(timer, lp(w = LinearLayout.LayoutParams.WRAP_CONTENT))
        statusRow.addView(timerBox, lp(w = LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(statusRow, lp().apply { topMargin = dp(14); leftMargin = dp(4); rightMargin = dp(4) })

        // --- кнопка (высоко, под большой палец) ---
        scanBtn = termButton("▶  НАЧАТЬ СКАНИРОВАНИЕ", Pal.DEFAULT_ACCENT) { onButton() }.apply {
            minHeight = dp(68)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        root.addView(scanBtn, lp().apply { topMargin = dp(12) })

        // --- радар ---
        radar = RadarView(this)
        root.addView(radar, lp(h = 0, weight = 1f).apply { topMargin = dp(6); bottomMargin = dp(6) })

        // --- журнал ---
        logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        telemetry = label("", 10f, Pal.DIM).apply { letterSpacing = 0.05f; maxLines = 1 }
        log = label("", 12.5f, Pal.TEXT).apply { minLines = 2; maxLines = 2 }
        logPanel.addView(telemetry, lp())
        logPanel.addView(log, lp().apply { topMargin = dp(4) })
        root.addView(logPanel, lp())

        footer = label("", 10f, Pal.DIM).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { syncDialog() }
        }
        root.addView(footer, lp())

        val (screen, sf) = spaceScreen(root, Pal.DEFAULT_ACCENT)
        stars = sf
        setContentView(screen)
        ui.post(telemetryTick)
    }

    private fun applyMode() {
        val m = mode ?: return
        val c = m.color
        header.text = m.name.uppercase()
        header.setTextColor(c)
        header.glow(c, 12f)
        subtitle.text = m.subtitle
        radar.color = c
        stars.accent = c
        headerPanel.background = HudDrawable(this, c, Pal.withAlpha(c, 18), chamferDp = 16f)
        logPanel.background = HudDrawable(this, Pal.withAlpha(c, 160), 0xCC060D11.toInt(), chamferDp = 10f, brackets = false)
        if (!sessionActive && !analyzing) styleButton(scanBtn, c, filled = false)
    }

    private fun refreshFooter() {
        val db = Repo.get(this)
        val whenStr = if (db.updatedAt > 0)
            SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(db.updatedAt)) else "—"
        val src = if (db.fromServer) "БАЗА $whenStr" else "ДЕМО-БАЗА"
        footer.text = "◇ ЗАПИСЕЙ ${db.entries.size} · $src · ОБНОВИТЬ ⟳"
    }

    /** Фальшивая телеметрия для атмосферы. */
    private val telemetryTick = object : Runnable {
        override fun run() {
            val sig = if (sessionActive || analyzing) -40 - Random.nextInt(30) else -85 - Random.nextInt(10)
            val rad = 0.08 + Random.nextDouble() * 0.09
            val temp = 17 + Random.nextInt(4)
            val grav = 0.91 + Random.nextDouble() * 0.02
            telemetry.text = String.format(Locale.US, "SIG %ddB ◇ ФОН %.2f мЗв ◇ T %+d°C ◇ g %.2f", sig, rad, temp, grav)
            ui.postDelayed(this, 900)
        }
    }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onResume() {
        super.onResume()
        mode = Repo.get(this).mode(prefs.role)
        if (mode == null) {
            startActivity(Intent(this, RoleActivity::class.java))
            return
        }
        applyMode()
        refreshFooter()
        setIdle()
        NfcRead.enable(this, adapter) { onTag(it) }
    }

    override fun onPause() {
        super.onPause()
        NfcRead.disable(this, adapter)
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        fx.release()
        ui.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ состояния

    private fun setIdle() {
        stopAll()
        timer.text = "--:--"
        timer.setTextColor(Pal.DIM)
        timer.setShadowLayer(0f, 0f, 0f, 0)
        timerLabel.text = "СЕССИЯ"
        val a = adapter
        when {
            a == null -> {
                radar.state = RadarView.State.OFFLINE
                setStatus("NFC НЕДОСТУПЕН", Pal.ERROR)
                log.text = "В этом телефоне нет NFC-модуля.\nСканирование меток невозможно."
                scanBtn.visibility = View.GONE
            }
            !a.isEnabled -> {
                radar.state = RadarView.State.OFFLINE
                setStatus("ДАТЧИК ОТКЛЮЧЁН", Pal.WARN)
                log.text = "Включите NFC в настройках телефона."
                scanBtn.visibility = View.VISIBLE
                scanBtn.text = "ОТКРЫТЬ НАСТРОЙКИ NFC"
                styleButton(scanBtn, Pal.WARN, filled = false)
                scanBtn.isEnabled = true
                scanBtn.alpha = 1f
            }
            else -> {
                radar.state = RadarView.State.IDLE
                radar.progress = 0f
                setStatus("РЕЖИМ ОЖИДАНИЯ", Pal.TEXT)
                log.text = "Нажмите «Начать сканирование»\nи поднесите сканер к объекту."
                scanBtn.visibility = View.VISIBLE
                scanBtn.text = "▶  НАЧАТЬ СКАНИРОВАНИЕ"
                styleButton(scanBtn, accent, filled = false)
                scanBtn.isEnabled = true
                scanBtn.alpha = 1f
            }
        }
    }

    private fun setStatus(s: String, color: Int) {
        status.text = s
        status.setTextColor(color)
        status.glow(color, 6f)
    }

    private fun stopAll() {
        ui.removeCallbacks(sessionTick)
        animator?.cancel()
        animator = null
        sessionActive = false
        analyzing = false
    }

    private fun onButton() {
        val a = adapter ?: return
        if (!a.isEnabled) {
            try {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            return
        }
        when {
            analyzing -> return
            sessionActive -> cancelSession()
            else -> startSession()
        }
    }

    // ------------------------------------------------------------------ сессия сканирования

    private val phrases = listOf(
        "Калибровка сенсорной матрицы…",
        "Прогрев квантового детектора…",
        "Фильтрация фонового излучения…",
        "Поиск сигнатур в радиусе 0.1 м…",
        "Подстройка фазы резонатора…",
        "Синхронизация с орбитальным ретранслятором…",
        "Сканирование спектра 13.56 МГц…",
        "Компенсация магнитных помех…",
    )

    private fun startSession() {
        sessionActive = true
        sessionStart = SystemClock.uptimeMillis()
        sessionLimitMs = prefs.sessionSeconds * 1000L
        lastPhraseAt = 0L
        lastSecond = -1L
        radar.state = RadarView.State.SCANNING
        radar.progress = 0f
        setStatus("ПОИСК СИГНАЛА…", accent)
        timerLabel.text = if (sessionLimitMs > 0) "ОСТАЛОСЬ" else "ВРЕМЯ СЕССИИ"
        timer.setTextColor(accent)
        timer.glow(accent, 10f)
        scanBtn.text = "■  ОТМЕНИТЬ СКАНИРОВАНИЕ"
        styleButton(scanBtn, Pal.ERROR, filled = false)
        fx.beep()
        fx.vibrate(30)
        ui.post(sessionTick)
    }

    private val sessionTick = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            val now = SystemClock.uptimeMillis()
            val elapsed = now - sessionStart
            if (sessionLimitMs > 0) {
                val left = (sessionLimitMs - elapsed).coerceAtLeast(0)
                timer.text = mmss((left + 999) / 1000)
                radar.progress = elapsed.toFloat() / sessionLimitMs
                if (left <= 10_000) {
                    timer.setTextColor(Pal.WARN)
                    timer.alpha = if ((left / 500) % 2 == 0L) 1f else 0.45f
                }
                if (left <= 0) {
                    timeout(); return
                }
            } else {
                timer.text = mmss(elapsed / 1000)
                radar.progress = (elapsed % 10_000) / 10_000f
            }
            val sec = elapsed / 1000
            if (sec != lastSecond) {
                lastSecond = sec
                if (sec > 0) fx.tick()
            }
            if (now - lastPhraseAt > 2800) {
                lastPhraseAt = now
                log.text = "${phrases[Random.nextInt(phrases.size)]}\nПоднесите сканер вплотную к объекту."
            }
            ui.postDelayed(this, 100)
        }
    }

    private fun mmss(totalSec: Long): String = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)

    private fun cancelSession() {
        stopAll()
        timer.alpha = 1f
        radar.state = RadarView.State.FAIL
        setStatus("СКАНИРОВАНИЕ ПРЕРВАНО", Pal.WARN)
        log.text = "Сессия остановлена оператором."
        fx.fail()
        scanBtn.isEnabled = false
        scanBtn.alpha = 0.4f
        ui.postDelayed({ if (!sessionActive && !analyzing) setIdle() }, 1300)
    }

    private fun timeout() {
        stopAll()
        timer.alpha = 1f
        timer.text = "00:00"
        timer.setTextColor(Pal.ERROR)
        radar.state = RadarView.State.FAIL
        setStatus("СИГНАЛ НЕ ОБНАРУЖЕН", Pal.ERROR)
        log.text = "Время сессии истекло.\nОбъект вне зоны действия сканера."
        fx.fail()
        fx.vibrate(120, 80, 120)
        scanBtn.isEnabled = false
        scanBtn.alpha = 0.4f
        ui.postDelayed({ if (!sessionActive && !analyzing) setIdle() }, 2500)
    }

    // ------------------------------------------------------------------ метка

    private fun onTag(read: TagRead) {
        val now = SystemClock.uptimeMillis()
        if (read.uid == lastUid && now - lastUidAt < 2500) return
        lastUid = read.uid
        lastUidAt = now
        prefs.lastCodes = read.describe()
        if (analyzing) return

        if (!sessionActive && !prefs.autoScan) {
            // метка поднесена без запуска — подсказка
            fx.tick()
            log.text = "Обнаружен объект, но сканер не активен.\nНажмите «Начать сканирование»."
            scanBtn.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).withEndAction {
                scanBtn.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
            return
        }
        analyze(read)
    }

    private val analysisPhrases = listOf(
        "Сигнал захвачен. Фиксация…",
        "Декодирование сигнатуры…",
        "Сверка с архивом колонии…",
        "Расшифровка показаний…",
        "Формирование отчёта…",
    )

    private fun analyze(read: TagRead) {
        ui.removeCallbacks(sessionTick)
        sessionActive = false
        analyzing = true
        timer.alpha = 1f
        timer.setTextColor(accent)
        timerLabel.text = "АНАЛИЗ"
        radar.state = RadarView.State.ANALYZING
        setStatus("СИГНАЛ ЗАХВАЧЕН", accent)
        scanBtn.text = "АНАЛИЗ ДАННЫХ…"
        styleButton(scanBtn, accent, filled = true)
        scanBtn.isEnabled = false
        fx.beep()
        fx.vibrate(40)

        var lastStep = -1
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            setDuration(2600L)
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val p = va.animatedValue as Float
                radar.progress = p
                timer.text = String.format(Locale.US, "%3d%%", (p * 100).toInt())
                val step = (p * analysisPhrases.size).toInt().coerceAtMost(analysisPhrases.size - 1)
                if (step != lastStep) {
                    lastStep = step
                    fx.tick()
                    log.text = "${analysisPhrases[step]}\n[${bar(p)}]"
                }
            }
            start()
        }
        ui.postDelayed({ if (analyzing) finishAnalysis(read) }, 2650L)
    }

    private fun bar(p: Float): String {
        val n = 20
        val filled = (p * n).toInt().coerceIn(0, n)
        return "▰".repeat(filled) + "▱".repeat(n - filled)
    }

    private fun finishAnalysis(read: TagRead) {
        val m = mode ?: return
        animator?.cancel()
        val result = Repo.get(this).find(read.candidates, m.id)
        radar.state = RadarView.State.LOCKED
        timer.text = "100%"
        setStatus(if (result is Lookup.Found) "ОБЪЕКТ ИДЕНТИФИЦИРОВАН" else "АНАЛИЗ ЗАВЕРШЁН", accent)
        if (result is Lookup.Found) fx.success() else fx.fail()
        fx.vibrate(60, 60, 160)
        ui.postDelayed({
            if (!analyzing) return@postDelayed
            analyzing = false
            lastUidAt = SystemClock.uptimeMillis() // защита от повтора — с момента возврата
            startActivity(ResultActivity.intent(this, m, result))
        }, 750)
    }

    // ------------------------------------------------------------------ служебное

    private fun askPin() {
        fx.vibrate(30)
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN мастера"
        }
        AlertDialog.Builder(this)
            .setTitle("Служебный доступ")
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(field, lp())
            })
            .setPositiveButton("Войти") { _, _ ->
                if (field.text.toString().trim() == prefs.pin) {
                    startActivity(Intent(this, MasterActivity::class.java))
                } else {
                    fx.fail()
                    Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun syncDialog() {
        if (sessionActive || analyzing) return
        SyncUi.show(this) {
            mode = Repo.get(this).mode(prefs.role)
            if (mode == null) startActivity(Intent(this, RoleActivity::class.java))
            else {
                applyMode(); refreshFooter(); setIdle()
            }
        }
    }
}
