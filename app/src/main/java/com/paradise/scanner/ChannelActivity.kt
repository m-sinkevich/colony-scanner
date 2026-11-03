package com.paradise.scanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Создание канала связи со сканера (как «+ Создать канал» на терминале): название, участники — модули и персонажи,
 * для хакера — скрытый канал. Если у персонажа задана цена — сканируются кристаллы; списание кристаллов и создание
 * канала — одна операция в базе (create_channel_paid). Только онлайн.
 */
class ChannelActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var fx: Fx? = null
    private var person: Person? = null
    private val selMods = LinkedHashSet<String>()
    private val selChars = LinkedHashSet<String>()
    private val codes = ArrayList<String>()
    private var price = 0
    private var busy = false
    private lateinit var name: EditText
    private lateinit var hidden: CheckBox
    private lateinit var msg: TextView
    private lateinit var priceView: TextView
    private lateinit var go: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.apply(this)
        fx = Fx(this)
        val db = Repo.get(this)
        val p = db.person(Prefs(this).characterId)
        if (p == null || !p.canChannels) { finish(); return }
        person = p; price = p.channelPrice
        val accent = Look.accent
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = column(18)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Look.panel(context, accent, Pal.withAlpha(accent, 20), chamferDp = 16f)
        }
        head.addView(label(Look.kicker, 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp())
        head.addView(label("НОВЫЙ КАНАЛ СВЯЗИ", 20f, accent, bold = true).apply { letterSpacing = Look.letter }.glow(accent, 10f), lp().apply { topMargin = dp(4) })
        root.addView(head, lp())

        root.addView(label("НАЗВАНИЕ КАНАЛА", 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp().apply { topMargin = dp(16) })
        name = EditText(this).apply {
            setTextColor(Pal.TEXT); setHintTextColor(Pal.DIM); hint = "например: Подполье"; textSize = 17f; typeface = MONO_BOLD
            isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            val pd = dp(12); setPadding(pd, pd, pd, pd)
            background = boxBg(context, Pal.withAlpha(accent, 140), Pal.PANEL, 4, 1)
        }
        root.addView(name, lp().apply { topMargin = dp(4) })

        root.addView(label("УЧАСТНИКИ — МОДУЛИ ЦЕЛИКОМ", 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp().apply { topMargin = dp(16) })
        chips(root, db.modules.entries.sortedBy { it.value }.map { it.key to "▣ " + it.value }, selMods, accent)
        val groups = db.people.filter { it.id != p.id }.groupBy { it.module.ifBlank { "Без модуля" } }.toSortedMap()
        for ((mod, list) in groups) {
            root.addView(label("ПЕРСОНАЖИ · ${mod.uppercase()}", 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp().apply { topMargin = dp(12) })
            chips(root, list.sortedBy { it.name }.map { it.id to it.name }, selChars, accent)
        }

        hidden = CheckBox(this).apply {
            text = "Скрытый канал — я невидимый участник"; setTextColor(Pal.TEXT); isChecked = p.hacker
            visibility = if (p.hacker) View.VISIBLE else View.GONE
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        root.addView(hidden, lp().apply { topMargin = dp(12) })

        priceView = label("", 14f, accent, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(priceView, lp().apply { topMargin = dp(14) })
        msg = label("", 13f, Pal.DIM).apply { gravity = Gravity.CENTER }
        root.addView(msg, lp().apply { topMargin = dp(6) })
        go = termButton("", accent) { onGo() }.apply { minHeight = dp(60) }
        root.addView(go, lp().apply { topMargin = dp(12) })
        root.addView(View(this), lp(h = 0, weight = 1f))
        root.addView(termButton("НАЗАД", accent) { finish() }, lp().apply { topMargin = dp(12) })
        setContentView(spaceScreen(scroll, accent).first)
        show()
    }

    /** Кнопки-переключатели по две в ряд. */
    private fun chips(root: LinearLayout, items: List<Pair<String, String>>, sel: MutableSet<String>, accent: Int) {
        if (items.isEmpty()) { root.addView(label("—", 12f, Pal.DIM), lp()); return }
        var row: LinearLayout? = null
        items.forEachIndexed { i, (id, title) ->
            if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; root.addView(row, lp().apply { topMargin = dp(6) }) }
            val b = label(title, 13f, Pal.TEXT, bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 2; val pd = dp(8); setPadding(pd, pd, pd, pd); isClickable = true
            }
            val paint = { on: Boolean -> b.background = boxBg(this, if (on) accent else Pal.withAlpha(accent, 90), if (on) Pal.withAlpha(accent, 60) else Pal.PANEL, 4, 1); b.setTextColor(if (on) Pal.TEXT else Pal.DIM) }
            paint(false)
            b.setOnClickListener { if (!sel.remove(id)) sel.add(id); paint(id in sel); fx?.tick() }
            row!!.addView(b, lp(w = 0, weight = 1f).apply { if (i % 2 == 0) rightMargin = dp(6) })
        }
        if (items.size % 2 == 1) row!!.addView(View(this), lp(w = 0, weight = 1f))
    }

    private fun show() {
        priceView.text = if (price > 0) "ЭНЕРГИЯ КРИСТАЛЛОВ: ${codes.size} ИЗ $price" else ""
        priceView.visibility = if (price > 0) View.VISIBLE else View.GONE
        go.text = when {
            price > 0 && codes.size < price -> "◎  СКАНИРОВАТЬ КРИСТАЛЛ"
            else -> "⌁  СОЗДАТЬ КАНАЛ"
        }
    }

    private fun say(t: String, c: Int) { msg.text = t; msg.setTextColor(c) }

    private fun valid(): Boolean {
        if (name.text.toString().trim().isEmpty()) { say("Назовите канал.", Pal.WARN); fx?.fail(); return false }
        if (selMods.isEmpty() && selChars.isEmpty()) { say("Выберите хотя бы одного участника.", Pal.WARN); fx?.fail(); return false }
        return true
    }

    private fun onGo() {
        if (busy || !valid()) return
        if (price > 0 && codes.size < price) QrScan.start(this, "ЭНЕРГИЯ ДЛЯ КАНАЛА · наведите на код кристалла", codes)
        else create()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        QrScan.onPermission(this, requestCode, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val text = QrScan.result(requestCode, resultCode, data)
        if (text == null) { super.onActivityResult(requestCode, resultCode, data); return }
        if (text.isNotEmpty()) check(text)
    }

    /** Кристалл проверяется сразу (свободен ли), списывается — вместе с созданием канала. */
    private fun check(raw: String) {
        val code = Codes.candidates(listOf(raw)).firstOrNull()?.uppercase() ?: raw.trim().uppercase()
        if (code in codes) { say("Кристалл $code уже внесён. Отсканируйте другой.", Pal.WARN); return }
        busy = true; say("◌ ПРОВЕРКА КРИСТАЛЛА $code…", Pal.DIM)
        Thread {
            val res = try {
                val a = JSONArray(Store.get(applicationContext, "crystals?select=code,spent_at&code=eq.$code"))
                if (a.length() == 0) "unknown" else if (a.getJSONObject(0).isNull("spent_at")) "ok" else "spent"
            } catch (e: Exception) { null }
            ui.post {
                busy = false
                if (isFinishing) return@post
                when (res) {
                    null -> { say("НЕТ СВЯЗИ С ЭНЕРГОСЕТЬЮ КОЛОНИИ\nКанал можно создать только при подключении к сети.", Pal.ERROR); fx?.fail() }
                    "ok" -> {
                        codes.add(code); fx?.success(); show()
                        if (codes.size >= price) { say("✓ ЭНЕРГИИ ДОСТАТОЧНО — СОЗДАЮ КАНАЛ…", Look.accent); create() }
                        else say("◆ Энергия кристалла $code принята. Отсканируйте следующий.", Look.accent)
                    }
                    "spent" -> { say("Кристалл $code истощён — энергии в нём нет.", Pal.ERROR); fx?.fail() }
                    else -> { say("Код $code — не кристалл.", Pal.ERROR); fx?.fail() }
                }
            }
        }.start()
    }

    private fun create() {
        val p = person ?: return
        if (!valid()) return
        busy = true; say("◌ СОЗДАНИЕ КАНАЛА…", Pal.DIM)
        val title = name.text.toString().trim()
        val body = JSONObject().put("p_character", p.id).put("p_module", p.moduleId ?: JSONObject.NULL).put("p_source", "scanner")
            .put("p_name", title).put("p_allow_text", true).put("p_allow_voice", true).put("p_allow_photo", true)
            .put("p_hidden", p.hacker && hidden.isChecked)
            .put("p_member_modules", JSONArray(selMods.toList())).put("p_member_chars", JSONArray(selChars.toList()))
            .put("p_codes", JSONArray(codes))
        Thread {
            val r = try { JSONObject(Store.rpc(applicationContext, "create_channel_paid", body)) } catch (e: Exception) { JSONObject().put("result", "error").put("msg", e.message ?: "") }
            ui.post {
                busy = false
                if (isFinishing) return@post
                when (r.optString("result")) {
                    "ok" -> {
                        fx?.success(); fx?.vibrate(60, 60, 160)
                        if (codes.isNotEmpty()) Store.log(this, p, "crystal_spend", JSONObject().put("codes", JSONArray(codes)).put("result", "ok").put("purpose", "канал «$title»"))
                        Store.log(this, p, "comm_create", JSONObject().put("channelId", r.optString("channel_id")).put("members", selMods.size + selChars.size).put("crystals", r.optInt("spent")))
                        Store.pushLater(this)
                        History.add(this, p, "channel", "Канал «$title»", null, if (codes.isEmpty()) "" else "Энергия кристаллов: ${codes.size}")
                        say("✓ КАНАЛ «${title.uppercase()}» СОЗДАН\nПереписка — на терминале любого модуля: «Связь».", Look.accent)
                        go.visibility = View.GONE
                    }
                    "rejected" -> {
                        val bad = r.optJSONArray("bad")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                        codes.removeAll(bad.toSet()); show()
                        say("Кристаллы ${bad.joinToString()} уже истощены. Нужно ещё ${price - codes.size}.", Pal.ERROR); fx?.fail()
                    }
                    "need" -> { price = r.optInt("price", price); show(); say("Нужно кристаллов: $price.", Pal.WARN) }
                    "forbidden" -> { say("У вас нет права создавать каналы.", Pal.ERROR); fx?.fail() }
                    else -> {
                        val m = r.optString("msg")
                        say(if (m.contains("create_channel_paid") || m.contains("404")) "Энергосеть не настроена: мастеру нужно выполнить sql/channels_price.sql."
                            else "НЕТ СВЯЗИ С ЭНЕРГОСЕТЬЮ КОЛОНИИ\nПопробуйте ещё раз при подключении к сети.", Pal.ERROR); fx?.fail()
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        fx?.release()
    }
}
