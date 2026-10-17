package com.paradise.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Служебное меню мастера:
 * смена профиля, чтение кода метки (чтобы занести его в базу), настройка Supabase, загрузка базы, смена PIN.
 */
class MasterActivity : Activity() {

    private lateinit var prefs: Prefs
    private var adapter: NfcAdapter? = null
    private lateinit var readout: TextView
    private lateinit var roles: LinearLayout
    private lateinit var info: TextView
    private var lastRead: TagRead? = null
    private var fx: Fx? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        adapter = NfcAdapter.getDefaultAdapter(this)
        fx = Fx(this)

        val scroll = ScrollView(this)
        val root = column(18)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val accent = Pal.WARN

        root.addView(label("СЛУЖЕБНОЕ МЕНЮ", 20f, accent, bold = true).apply { letterSpacing = 0.1f }, lp())
        root.addView(label("версия ${BuildConfig.VERSION_NAME}", 11f, Pal.DIM), lp())

        // --- метка ---
        section(root, "КОД МЕТКИ")
        root.addView(label("Поднесите метку к телефону — здесь появится её код. Его нужно указать в столбце code таблицы scan_tags.", 12f, Pal.DIM), lp())
        readout = label(prefs.lastCodes.ifBlank { "— метка ещё не считывалась —" }, 15f, Pal.TEXT, bold = true).apply {
            val p = dp(12)
            setPadding(p, p, p, p)
            background = boxBg(context, Pal.withAlpha(accent, 140), Pal.PANEL, 4, 1)
            setTextIsSelectable(true)
        }
        root.addView(readout, lp().apply { topMargin = dp(8) })
        root.addView(termButton("КОПИРОВАТЬ КОД", accent) { copyCode() }, lp().apply { topMargin = dp(8) })

        // --- сессия ---
        section(root, "СЕССИЯ СКАНИРОВАНИЯ")
        root.addView(label("Длительность в секундах (0 — без ограничения, до метки или отмены)", 12f, Pal.DIM), lp())
        val secs = input("60", prefs.sessionSeconds.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(secs, lp().apply { topMargin = dp(6) })
        root.addView(termButton("СОХРАНИТЬ ДЛИТЕЛЬНОСТЬ", accent) {
            val v = secs.text.toString().trim().toIntOrNull()
            if (v == null || v < 0) toast("Введите число секунд")
            else {
                prefs.sessionSeconds = v
                toast(if (v == 0) "Без ограничения по времени" else "Сессия: $v с")
            }
        }, lp().apply { topMargin = dp(8) })
        val autoBtn = termButton("", accent) {}
        fun paintAuto() {
            autoBtn.text = if (prefs.autoScan) "МЕТКА БЕЗ КНОПКИ: СРАБАТЫВАЕТ" else "МЕТКА БЕЗ КНОПКИ: ИГНОРИРУЕТСЯ"
            styleButton(autoBtn, accent, filled = prefs.autoScan)
        }
        autoBtn.setOnClickListener { prefs.autoScan = !prefs.autoScan; paintAuto() }
        paintAuto()
        root.addView(autoBtn, lp().apply { topMargin = dp(8) })

        // --- профиль ---
        section(root, "ПРОФИЛЬ СКАНЕРА")
        roles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(roles, lp())

        // --- сервер ---
        section(root, "СЕРВЕР SUPABASE")
        val url = input("https://xxxx.supabase.co", prefs.supabaseUrl)
        val key = input("anon / publishable key", prefs.supabaseKey, secret = true)
        root.addView(label("Project URL", 12f, Pal.DIM), lp())
        root.addView(url, lp().apply { topMargin = dp(4); bottomMargin = dp(8) })
        root.addView(label("Публичный ключ (anon или publishable)", 12f, Pal.DIM), lp())
        root.addView(key, lp().apply { topMargin = dp(4) })
        root.addView(termButton("СОХРАНИТЬ И ЗАГРУЗИТЬ БАЗУ", accent, filled = true) {
            prefs.supabaseUrl = url.text.toString()
            prefs.supabaseKey = key.text.toString()
            SyncUi.show(this) { refresh() }
        }, lp().apply { topMargin = dp(12) })

        info = label("", 12f, Pal.DIM)
        root.addView(info, lp().apply { topMargin = dp(10) })

        // --- PIN ---
        section(root, "PIN МАСТЕРА")
        val pin = input("новый PIN", "", secret = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        root.addView(pin, lp())
        root.addView(termButton("СМЕНИТЬ PIN", accent) {
            val v = pin.text.toString().trim()
            if (v.length < 4) toast("PIN — минимум 4 цифры")
            else {
                prefs.pin = v; pin.setText(""); toast("PIN изменён")
            }
        }, lp().apply { topMargin = dp(8) })

        // --- сброс ---
        section(root, "ПРОЧЕЕ")
        root.addView(termButton("СБРОСИТЬ БАЗУ К ДЕМО", Pal.ERROR) {
            AlertDialog.Builder(this)
                .setTitle("Сбросить базу?")
                .setMessage("Скачанная база и картинки будут удалены, останется встроенная демо-база.")
                .setPositiveButton("Сбросить") { _, _ ->
                    Repo.dbFile(this).delete()
                    Repo.imgDir(this).listFiles()?.forEach { it.delete() }
                    Repo.reload(this)
                    refresh()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }, lp())
        root.addView(termButton("ВЫЙТИ ИЗ МЕНЮ", Pal.DIM) { finish() }, lp().apply { topMargin = dp(10); bottomMargin = dp(20) })

        setContentView(spaceScreen(scroll, Pal.WARN).first)
        refresh()
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(label(title, 13f, Pal.WARN, bold = true).apply { letterSpacing = 0.1f },
            lp().apply { topMargin = dp(26); bottomMargin = dp(8) })
    }

    private fun refresh() {
        val db = Repo.get(this)
        roles.removeAllViews()
        val current = prefs.role
        for (m in db.modes) {
            val selected = m.id == current
            roles.addView(termButton((if (selected) "● " else "") + m.name.uppercase() + "  [${m.id}]", m.color, filled = selected) {
                prefs.role = m.id
                toast("Профиль: ${m.name}")
                refresh()
            }, lp().apply { bottomMargin = dp(8) })
        }
        val perMode = db.entries.groupingBy { it.mode }.eachCount()
            .entries.joinToString(", ") { "${it.key}: ${it.value}" }
        info.text = "В базе ${db.entries.size} записей (${perMode.ifBlank { "—" }}).\n" +
            (if (db.fromServer) "Загружена с сервера." else "Сейчас используется встроенная демо-база.")
    }

    private fun copyCode() {
        val r = lastRead
        val code = r?.texts?.firstOrNull() ?: r?.uid
        if (code == null) {
            toast("Сначала поднесите метку"); return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tag", code))
        toast("Скопировано: $code")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        NfcRead.enable(this, adapter) { read ->
            lastRead = read
            prefs.lastCodes = read.describe()
            readout.text = read.describe()
            fx?.beep()
            fx?.vibrate(40)
        }
    }

    override fun onPause() {
        super.onPause()
        NfcRead.disable(this, adapter)
    }

    override fun onDestroy() {
        super.onDestroy()
        fx?.release()
    }
}
