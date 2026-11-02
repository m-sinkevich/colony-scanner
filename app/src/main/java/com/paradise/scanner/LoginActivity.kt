package com.paradise.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Первый запуск: идентификация оператора по коду с бейджа (QR, NFC-метка пропуска или ввод вручную).
 * Сканер доступен только персонажам с типом сканера в мастерке; остальным — «Доступ запрещён».
 */
class LoginActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var fx: Fx
    private var adapter: NfcAdapter? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var statusBox: LinearLayout
    private lateinit var footer: TextView
    private var busy = false
    private val accent = Pal.DEFAULT_ACCENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        fx = Fx(this)
        adapter = NfcAdapter.getDefaultAdapter(this)
        Look.kind = Kind.TECH

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = column(20)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = HudDrawable(context, accent, Pal.withAlpha(accent, 18), chamferDp = 16f)
        }
        head.addView(label("◈ КОЛОНИАЛЬНЫЙ СКАНЕР · ${ResultActivity.GAME_YEAR}", 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp())
        head.addView(label("ИДЕНТИФИКАЦИЯ", 26f, accent, bold = true).apply { letterSpacing = 0.12f }.glow(accent, 12f), lp().apply { topMargin = dp(4) })
        head.addView(label("ОПЕРАТОРА", 15f, Pal.TEXT, bold = true).apply { letterSpacing = 0.2f }, lp())
        root.addView(head, lp().apply { topMargin = dp(12) })
        // служебное меню мастера: удерживать заголовок 3 секунды
        var downAt = 0L
        val hold = Runnable { if (downAt != 0L) { downAt = 0L; askPin() } }
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downAt = SystemClock.uptimeMillis(); ui.postDelayed(hold, 3000) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { downAt = 0L; ui.removeCallbacks(hold) }
            }
            true
        }

        root.addView(label("Приложите пропуск к задней стороне сканера\nили отсканируйте QR-код своего бейджа.", 14f, Pal.TEXT).apply { gravity = Gravity.CENTER },
            lp().apply { topMargin = dp(26) })

        if (QrScan.available(this)) {
            root.addView(termButton("◎  СКАНИРОВАТЬ БЕЙДЖ", accent) { if (!busy) QrScan.start(this, "ИДЕНТИФИКАЦИЯ · наведите на QR-код бейджа") }.apply {
                minHeight = dp(64)
            }, lp().apply { topMargin = dp(20) })
        }

        root.addView(label("или введите код с бейджа", 11f, Pal.DIM).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dp(18) })
        val code = EditText(this).apply {
            hint = "КОД"
            setHintTextColor(Pal.DIM)
            setTextColor(Pal.TEXT)
            typeface = MONO_BOLD
            textSize = 22f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            val p = dp(12); setPadding(p, p, p, p)
            background = boxBg(context, Pal.withAlpha(accent, 140), Pal.PANEL, 4, 1)
        }
        root.addView(code, lp().apply { topMargin = dp(6) })
        val go = { val s = code.text.toString().trim(); if (s.isNotEmpty() && !busy) identify(listOf(s)) }
        code.setOnEditorActionListener { _, _, _ -> go(); true }
        root.addView(termButton("ВОЙТИ", accent) { go() }, lp().apply { topMargin = dp(8) })

        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = android.view.View.INVISIBLE
        }
        status = label("", 22f, accent, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f }
        detail = label("", 13f, Pal.TEXT).apply { gravity = Gravity.CENTER }
        statusBox.addView(status, lp())
        statusBox.addView(detail, lp().apply { topMargin = dp(6) })
        root.addView(statusBox, lp().apply { topMargin = dp(22) })

        root.addView(android.view.View(this), lp(h = 0, weight = 1f))
        footer = label("", 10f, Pal.DIM).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(14), 0, 0)
            setOnClickListener { AutoSync.now() }
        }
        root.addView(footer, lp())
        setContentView(spaceScreen(scroll, accent).first)
    }

    private fun refreshFooter() {
        val db = Repo.get(this)
        footer.text = if (db.people.isEmpty()) "◇ НЕТ СВЯЗИ С РЕЕСТРОМ КОЛОНИИ · ПРОВЕРЬТЕ ИНТЕРНЕТ" else ""
    }

    override fun onResume() {
        super.onResume()
        refreshFooter()
        AutoSync.onUpdated = { refreshFooter() }
        AutoSync.resumed(this)
        NfcRead.enable(this, adapter) { r -> if (!busy) identify(r.candidates) }
    }

    override fun onPause() {
        super.onPause()
        AutoSync.paused()
        NfcRead.disable(this, adapter)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        fx.release()
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        QrScan.onPermission(this, requestCode, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val text = QrScan.result(requestCode, resultCode, data)
        if (text == null) { super.onActivityResult(requestCode, resultCode, data); return }
        if (text.isNotEmpty()) ui.post { identify(listOf(text)) }
    }

    private fun show(title: String, text: String, color: Int) {
        statusBox.visibility = android.view.View.VISIBLE
        statusBox.background = HudDrawable(this, color, Pal.withAlpha(color, 26), chamferDp = 14f)
        status.text = title
        status.setTextColor(color)
        status.glow(color, 10f)
        detail.text = text
        statusBox.alpha = 0f
        statusBox.animate().alpha(1f).setDuration(250).start()
    }

    private fun identify(texts: List<String>) {
        val db = Repo.get(this)
        if (db.people.isEmpty()) {
            show("НЕТ СВЯЗИ С РЕЕСТРОМ", "Подключитесь к интернету — данные колонии загрузятся сами.", Pal.WARN)
            AutoSync.now()
            fx.fail(); return
        }
        val codes = Codes.candidates(texts)
        val p = codes.firstNotNullOfOrNull { db.personByCode(it) }
        if (p == null) {
            show("ПРОПУСК НЕ РАСПОЗНАН", "Код не найден в реестре колонии.", Pal.ERROR)
            fx.fail(); fx.vibrate(120, 80, 120)
            return
        }
        if (p.scannerMode !in listOf("medic", "tech", "sci")) {
            Store.log(this, p, "auth", JSONObject().put("result", "denied"))
            Store.pushLater(this)
            show("ДОСТУП ЗАПРЕЩЁН", "${p.name}\nУ вас нет допуска к этому оборудованию.", Pal.ERROR)
            fx.fail(); fx.vibrate(200, 100, 200)
            return
        }
        busy = true
        prefs.characterId = p.id
        prefs.role = p.scannerMode
        Look.kind = Look.of(p.scannerMode)
        Store.log(this, p, "auth", JSONObject().put("result", "ok"))
        Store.pushLater(this)
        val c = Look.accent
        val kindName = when (Look.kind) { Kind.MED -> "МЕДИЦИНСКИЙ"; Kind.TECH -> "ТЕХНИЧЕСКИЙ"; Kind.SCI -> "НАУЧНЫЙ" }
        show("ДОСТУП РАЗРЕШЁН", "${p.name}\nПрофиль: $kindName", c)
        fx.success(); fx.vibrate(60, 60, 160)
        ui.postDelayed({
            startActivity(Intent(this, ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, 1300)
    }

    private fun askPin() {
        fx.vibrate(30)
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN мастера"
        }
        AlertDialog.Builder(this)
            .setTitle("Служебный доступ")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, lp()) })
            .setPositiveButton("Войти") { _, _ ->
                if (field.text.toString().trim() == prefs.pin) startActivity(Intent(this, MasterActivity::class.java))
                else { fx.fail(); Toast.makeText(this, "Неверный PIN", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
