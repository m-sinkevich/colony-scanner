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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран: прибор-сканер.
 * Метка, поднесённая к телефону, запускает анимацию анализа, затем открывается результат.
 * Кнопка "Сканировать" запускает "поиск" — если за время поиска метку поднесли, она будет найдена.
 */
class ScanActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var fx: Fx
    private var adapter: NfcAdapter? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var header: TextView
    private lateinit var subtitle: TextView
    private lateinit var radar: RadarView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scanBtn: TextView
    private lateinit var footer: TextView

    private var mode: Mode? = null
    private var scanning = false
    private var pending: TagRead? = null
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

    private fun buildUi() {
        val root = column(18)
        root.setBackgroundColor(Pal.BG)

        header = label("СКАНЕР", 20f, Pal.DEFAULT_ACCENT, bold = true).apply { letterSpacing = 0.1f }
        root.addView(header, lp())
        subtitle = label("", 12f, Pal.DIM)
        root.addView(subtitle, lp())

        // Служебное меню: удерживать заголовок 3 секунды
        var downAt = 0L
        val holdCheck = Runnable { if (downAt != 0L) { downAt = 0L; askPin() } }
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downAt = SystemClock.uptimeMillis(); ui.postDelayed(holdCheck, 3000) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { downAt = 0L; ui.removeCallbacks(holdCheck) }
            }
            true
        }

        radar = RadarView(this)
        root.addView(radar, lp(h = 0, weight = 1f).apply { topMargin = dp(10); bottomMargin = dp(6) })

        status = label("", 20f, Pal.TEXT, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f }
        root.addView(status, lp())
        log = label("", 12f, Pal.DIM).apply { gravity = Gravity.CENTER; minLines = 3; maxLines = 3 }
        root.addView(log, lp().apply { topMargin = dp(6); bottomMargin = dp(12) })

        scanBtn = termButton("СКАНИРОВАТЬ", Pal.DEFAULT_ACCENT, filled = false) { manualScan() }
        root.addView(scanBtn, lp())

        footer = label("", 11f, Pal.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { syncDialog() }
        }
        root.addView(footer, lp())

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val db = Repo.get(this)
        val roleId = prefs.role
        mode = db.mode(roleId)
        if (mode == null) {
            // роль не выбрана (или пропала из базы) — выбор роли
            startActivity(Intent(this, RoleActivity::class.java))
            return
        }
        applyMode()
        refreshFooter()
        if (!scanning) setIdle()
        NfcRead.enable(this, adapter) { onTag(it) }
    }

    override fun onPause() {
        super.onPause()
        NfcRead.disable(this, adapter)
        if (scanning) cancelScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        fx.release()
        ui.removeCallbacksAndMessages(null)
    }

    private fun applyMode() {
        val m = mode ?: return
        header.text = "СКАНЕР // ${m.name.uppercase()}"
        header.setTextColor(m.color)
        subtitle.text = m.subtitle
        radar.color = m.color
        scanBtn.setTextColor(m.color)
        scanBtn.background = boxBg(this, m.color, Pal.withAlpha(m.color, 24), 6, 2)
    }

    private fun refreshFooter() {
        val db = Repo.get(this)
        val tags = db.entries.size
        val whenStr = if (db.updatedAt > 0)
            SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(db.updatedAt)) else "—"
        val src = if (db.fromServer) "база от $whenStr" else "встроенная демо-база"
        footer.text = "ЗАПИСЕЙ: $tags · $src · обновить ⟳"
    }

    private fun setIdle() {
        val a = adapter
        when {
            a == null -> {
                radar.state = RadarView.State.OFFLINE
                status.text = "NFC НЕДОСТУПЕН"
                status.setTextColor(Pal.ERROR)
                log.text = "В этом телефоне нет NFC-модуля.\nСканирование меток невозможно."
                scanBtn.visibility = View.GONE
            }
            !a.isEnabled -> {
                radar.state = RadarView.State.OFFLINE
                status.text = "ДАТЧИК ОТКЛЮЧЁН"
                status.setTextColor(Pal.WARN)
                log.text = "Включите NFC в настройках телефона."
                scanBtn.visibility = View.VISIBLE
                scanBtn.text = "ОТКРЫТЬ НАСТРОЙКИ NFC"
                scanBtn.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    }
                }
            }
            else -> {
                radar.state = RadarView.State.IDLE
                radar.progress = 0f
                status.text = "ГОТОВ К СКАНИРОВАНИЮ"
                status.setTextColor(Pal.TEXT)
                log.text = "Поднесите сканер вплотную к объекту\nили нажмите «Сканировать»"
                scanBtn.visibility = View.VISIBLE
                scanBtn.text = "СКАНИРОВАТЬ"
                scanBtn.isEnabled = true
                scanBtn.alpha = 1f
                scanBtn.setOnClickListener { manualScan() }
            }
        }
    }

    private fun onTag(read: TagRead) {
        val now = SystemClock.uptimeMillis()
        if (read.uid == lastUid && now - lastUidAt < 2500) return
        lastUid = read.uid
        lastUidAt = now
        prefs.lastCodes = read.describe()

        if (scanning) {
            // идёт "поиск" по кнопке — запоминаем метку, ускоряем завершение
            if (pending == null) {
                pending = read
                fx.beep()
                fx.vibrate(40)
                log.text = "Сигнал захвачен…\nАнализ сигнатуры…"
            }
            return
        }
        fx.beep()
        fx.vibrate(40)
        runScan(read, 2400L)
    }

    private fun manualScan() {
        if (scanning) return
        runScan(null, 3600L)
    }

    private val phrases = listOf(
        "Калибровка сенсоров…",
        "Синхронизация с объектом…",
        "Сбор спектральных данных…",
        "Анализ сигнатуры…",
        "Сверка с базой данных…",
        "Расшифровка показаний…",
    )

    private fun runScan(read: TagRead?, duration: Long) {
        scanning = true
        pending = read
        radar.state = RadarView.State.SCANNING
        status.text = if (read != null) "АНАЛИЗ ОБЪЕКТА" else "ПОИСК СИГНАЛА"
        status.setTextColor(mode?.color ?: Pal.TEXT)
        scanBtn.isEnabled = false
        scanBtn.alpha = 0.4f
        if (read == null) log.text = "Поднесите сканер к объекту…"

        var lastStep = -1
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            setDuration(duration)
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val p = va.animatedValue as Float
                radar.progress = p
                val step = (p * phrases.size).toInt().coerceAtMost(phrases.size - 1)
                if (step != lastStep) {
                    lastStep = step
                    fx.tick()
                    val pct = (p * 100).toInt()
                    if (pending != null || read != null) {
                        log.text = "${phrases[step]}\n[${bar(p)}] $pct%"
                    } else {
                        log.text = "Поднесите сканер к объекту…\n[${bar(p)}] $pct%"
                    }
                }
            }
            start()
        }
        ui.postDelayed(finishRunnable, duration)
    }

    private val finishRunnable = Runnable { finishScan() }

    private fun bar(p: Float): String {
        val n = 16
        val filled = (p * n).toInt().coerceIn(0, n)
        return "■".repeat(filled) + "·".repeat(n - filled)
    }

    private fun cancelScan() {
        ui.removeCallbacks(finishRunnable)
        animator?.cancel()
        scanning = false
        pending = null
    }

    private fun finishScan() {
        animator?.cancel()
        val read = pending
        scanning = false
        pending = null
        val m = mode ?: return

        if (read == null) {
            radar.state = RadarView.State.FAIL
            status.text = "СИГНАЛ НЕ ОБНАРУЖЕН"
            status.setTextColor(Pal.ERROR)
            log.text = "Объект вне зоны действия сканера.\nПоднесите сканер ближе."
            fx.fail()
            fx.vibrate(120, 80, 120)
            ui.postDelayed({ if (!scanning) setIdle() }, 2500)
            return
        }

        val result = Repo.get(this).find(read.candidates, m.id)
        radar.state = RadarView.State.LOCKED
        status.text = when (result) {
            is Lookup.Found -> "ОБЪЕКТ ИДЕНТИФИЦИРОВАН"
            else -> "АНАЛИЗ ЗАВЕРШЁН"
        }
        if (result is Lookup.Found) fx.success() else fx.fail()
        fx.vibrate(60, 60, 160)

        ui.postDelayed({
            lastUidAt = SystemClock.uptimeMillis() // отсчёт защиты от повтора — с момента возврата
            startActivity(ResultActivity.intent(this, m, result))
        }, 700)
    }

    // ---------- служебное ----------

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
                    android.widget.Toast.makeText(this, "Неверный PIN", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun syncDialog() {
        if (scanning) return
        SyncUi.show(this) {
            mode = Repo.get(this).mode(prefs.role)
            if (mode == null) startActivity(Intent(this, RoleActivity::class.java))
            else {
                applyMode(); refreshFooter()
            }
        }
    }
}
