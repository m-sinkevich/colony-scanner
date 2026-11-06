package com.paradise.scanner

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * «✉ Центр управления» — переписка оператора сканера с мастерами (как на терминале модуля).
 * Видно только своё: что писал сам, что центр прислал лично ему и всему его модулю. Только текст. Нужна сеть.
 */
class CenterActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var fx: Fx? = null
    private var person: Person? = null
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var msg: TextView
    private var busy = false
    private var lastTop = ""
    private val poll = object : Runnable { override fun run() { load(false); ui.postDelayed(this, 15000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.apply(this)
        fx = Fx(this)
        val p = Repo.get(this).person(Prefs(this).characterId)
        if (p == null) { finish(); return }
        person = p
        val accent = Look.accent
        val root = column(18)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Look.panel(context, accent, Pal.withAlpha(accent, 20), chamferDp = 16f)
        }
        head.addView(label(Look.kicker, 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp())
        head.addView(label("✉ ЦЕНТР УПРАВЛЕНИЯ", 20f, accent, bold = true).apply { letterSpacing = Look.letter }.glow(accent, 10f), lp().apply { topMargin = dp(4) })
        head.addView(label("Связь с центром колонии. Пишите в любое время — сообщение получат сразу.", 12f, Pal.TEXT), lp().apply { topMargin = dp(6) })
        root.addView(head, lp())

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)) }
        root.addView(scroll, lp(h = 0, weight = 1f).apply { topMargin = dp(12) })

        msg = label("", 12f, Pal.DIM).apply { gravity = Gravity.CENTER }
        root.addView(msg, lp().apply { topMargin = dp(6) })
        input = EditText(this).apply {
            setTextColor(Pal.TEXT); setHintTextColor(Pal.DIM); hint = "Сообщение центру…"; textSize = 16f; typeface = MONO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 5
            val pd = dp(12); setPadding(pd, pd, pd, pd)
            background = boxBg(context, Pal.withAlpha(accent, 140), Pal.PANEL, 4, 1)
        }
        root.addView(input, lp().apply { topMargin = dp(6) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(termButton("НАЗАД", accent) { finish() }, lp(w = 0, weight = 1f).apply { rightMargin = dp(8) })
        row.addView(termButton("ОТПРАВИТЬ ›", accent) { send() }, lp(w = 0, weight = 1.4f))
        root.addView(row, lp().apply { topMargin = dp(10) })

        setContentView(spaceScreen(root, accent).first)
        say("Загрузка…", Pal.DIM)
    }

    override fun onResume() { super.onResume(); ui.removeCallbacks(poll); ui.post(poll) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }

    private fun say(t: String, color: Int) { msg.text = t; msg.setTextColor(color); msg.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE }

    private fun query(p: Person): String {
        val sel = "id,from_master,from_label,from_character_id,to_master,to_character_id,to_module_id,text,kind,created_at,read_at"
        val mod = p.moduleId?.let { ",and(from_master.is.true,to_module_id.eq.$it,to_character_id.is.null)" } ?: ""
        return "comm_messages?select=$sel&channel_id=is.null" +
            "&or=(and(to_master.is.true,from_character_id.eq.${p.id}),and(from_master.is.true,to_character_id.eq.${p.id})$mod)" +
            "&order=created_at.desc&limit=60"
    }

    private fun load(scrollDown: Boolean) {
        val p = person ?: return
        val app = applicationContext
        Thread {
            try {
                val arr = JSONArray(Store.get(app, query(p)))
                // отметить прочитанными сообщения центра
                val unread = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optBoolean("from_master") && it.isNull("read_at") }.map { it.optString("id") }
                if (unread.isNotEmpty()) try {
                    Store.send(app, "PATCH", "comm_messages?id=in.(${unread.joinToString(",")})", JSONObject().put("read_at", Store.iso(System.currentTimeMillis())).toString())
                } catch (e: Exception) { }
                ui.post { if (!isFinishing) render(arr, scrollDown, unread.isNotEmpty()) }
            } catch (e: Exception) {
                ui.post { if (!isFinishing && !busy) say("НЕТ СВЯЗИ С ЦЕНТРОМ. Переписка доступна только при подключении к сети.", Pal.WARN) }
            }
        }.start()
    }

    private fun render(arr: JSONArray, scrollDown: Boolean, fresh: Boolean) {
        val top = arr.optJSONObject(0)?.optString("id") ?: ""
        if (!busy) say("", Pal.DIM)
        if (top == lastTop && list.childCount > 0) return
        val changed = lastTop.isNotEmpty() && top != lastTop
        lastTop = top
        list.removeAllViews()
        val accent = Look.accent
        if (arr.length() == 0) {
            list.addView(label("Сообщений пока нет. Напишите в центр управления.", 13f, Pal.DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) }, lp())
        }
        for (i in arr.length() - 1 downTo 0) {
            val m = arr.optJSONObject(i) ?: continue
            val mine = !m.optBoolean("from_master")
            val who = if (mine) "ВЫ" else (m.optString("from_label").takeIf { it.isNotBlank() && it != "null" } ?: "ЦЕНТР УПРАВЛЕНИЯ").uppercase() +
                (if (!m.isNull("to_character_id")) " · ЛИЧНО" else "")
            val time = localTime(m.optString("created_at")) ?: ""
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pd = dp(10); setPadding(pd, dp(8), pd, dp(8))
                background = boxBg(context, if (mine) Pal.withAlpha(accent, 110) else accent, if (mine) Pal.PANEL else Pal.withAlpha(accent, 28), 4, 1)
            }
            box.addView(label("$who  ·  $time", 10.5f, if (mine) Pal.DIM else accent, bold = true).apply { letterSpacing = 0.08f }, lp())
            val text = if (m.isNull("text")) (if (m.optString("kind") == "audio") "🎤 голосовое — прослушать на терминале модуля" else if (m.optString("kind") == "photo") "📷 фото — смотреть на терминале модуля" else "") else m.optString("text")
            box.addView(label(text, 15f, Pal.TEXT), lp().apply { topMargin = dp(4) })
            list.addView(box, lp().apply { topMargin = dp(8); if (mine) leftMargin = dp(36) else rightMargin = dp(36) })
        }
        if (changed && fresh) fx?.beep()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun localTime(iso: String): String? = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val d = f.parse(iso.take(19))
        if (d == null) null else java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(d)
    } catch (e: Exception) { null }

    private fun send() {
        val p = person ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) { say("Введите текст сообщения.", Pal.WARN); fx?.fail(); return }
        if (busy) return
        busy = true; say("Отправка…", Pal.DIM)
        val body = JSONObject().put("from_module_id", p.moduleId ?: JSONObject.NULL).put("from_character_id", p.id)
            .put("to_master", true).put("allow_reply", true).put("kind", "text").put("text", text)
        val app = applicationContext
        Thread {
            val ok = try { Store.send(app, "POST", "comm_messages", body.toString()); true } catch (e: Exception) { false }
            ui.post {
                busy = false
                if (isFinishing) return@post
                if (ok) { input.setText(""); say("✓ Отправлено в центр управления", Look.accent); fx?.tick(); lastTop = "x"; load(true) }
                else { say("НЕТ СВЯЗИ С ЦЕНТРОМ. Сообщение не отправлено — попробуйте при подключении к сети.", Pal.ERROR); fx?.fail() }
            }
        }.start()
    }
}
