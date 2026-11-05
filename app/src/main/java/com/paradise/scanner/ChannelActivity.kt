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
 * Создание канала связи со сканера в два шага: 1) кого позвать — центр управления, модули целиком, люди модулей
 * (персонажи без модуля не показываются); 2) название (можно не вводить — подставится) и энергия кристаллов,
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
        this.scroll = scroll
        val root = column(18)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Look.panel(context, accent, Pal.withAlpha(accent, 20), chamferDp = 16f)
        }
        head.addView(label(Look.kicker, 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp())
        head.addView(label("НОВЫЙ КАНАЛ СВЯЗИ", 20f, accent, bold = true).apply { letterSpacing = Look.letter }.glow(accent, 10f), lp().apply { topMargin = dp(4) })
        stepView = label("", 12f, Pal.TEXT, bold = true).apply { letterSpacing = 0.1f }
        head.addView(stepView, lp().apply { topMargin = dp(6) })
        root.addView(head, lp())

        // ---------- шаг 1: кого позвать ----------
        step1 = column(0)
        root.addView(step1, lp())
        // центр управления (мастерский терминал) — отдельной первой строкой
        val center = db.modules.entries.filter { it.key in db.masterModules }.sortedBy { it.value }
        if (center.isNotEmpty()) {
            step1.addView(label("ЦЕНТР УПРАВЛЕНИЯ", 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp().apply { topMargin = dp(16) })
            chips(step1, center.map { it.key to "✉ " + it.value.uppercase() }, selMods, accent, perRow = 1)
        }
        // модули: «весь модуль» + люди модуля. Персонажи без модуля не показываются.
        val byModule = db.people.filter { it.id != p.id && it.moduleId != null && it.moduleId in db.modules && it.moduleId !in db.masterModules }
            .groupBy { it.moduleId!! }
        for ((mid, mname) in db.modules.entries.filter { it.key !in db.masterModules }.sortedBy { it.value }) {
            step1.addView(label("▣ ${mname.uppercase()}", 12f, accent, bold = true).apply { letterSpacing = 0.12f }, lp().apply { topMargin = dp(18) })
            chips(step1, listOf(mid to "ВЕСЬ МОДУЛЬ · все, кто на терминале"), selMods, accent, perRow = 1)
            val people = byModule[mid].orEmpty().sortedBy { it.name }
            if (people.isNotEmpty()) chips(step1, people.map { it.id to it.name }, selChars, accent)
        }
        countView = label("", 13f, Pal.DIM).apply { gravity = Gravity.CENTER }
        step1.addView(countView, lp().apply { topMargin = dp(18) })
        step1.addView(termButton("ДАЛЕЕ  ›", accent) { toStep2() }.apply { minHeight = dp(60) }, lp().apply { topMargin = dp(8) })

        // ---------- шаг 2: название и запуск ----------
        step2 = column(0)
        root.addView(step2, lp())
        step2.addView(label("УЧАСТНИКИ", 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp().apply { topMargin = dp(16) })
        membersView = label("", 15f, Pal.TEXT, bold = true)
        step2.addView(membersView, lp().apply { topMargin = dp(4) })
        step2.addView(termButton("‹  ИЗМЕНИТЬ УЧАСТНИКОВ", accent) { toStep1() }, lp().apply { topMargin = dp(8) })
        step2.addView(label("НАЗВАНИЕ КАНАЛА (можно не вводить)", 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp().apply { topMargin = dp(18) })
        defaultName = "Связь · " + p.name.trim().split(" ").first() + " · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        name = EditText(this).apply {
            setTextColor(Pal.TEXT); setHintTextColor(Pal.DIM); hint = defaultName; textSize = 17f; typeface = MONO_BOLD
            isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            val pd = dp(12); setPadding(pd, pd, pd, pd)
            background = boxBg(context, Pal.withAlpha(accent, 140), Pal.PANEL, 4, 1)
        }
        step2.addView(name, lp().apply { topMargin = dp(4) })
        hidden = CheckBox(this).apply {
            text = "Скрытый канал — я невидимый участник"; setTextColor(Pal.TEXT); isChecked = p.hacker
            visibility = if (p.hacker) View.VISIBLE else View.GONE
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        step2.addView(hidden, lp().apply { topMargin = dp(12) })
        priceView = label("", 14f, accent, bold = true).apply { gravity = Gravity.CENTER }
        step2.addView(priceView, lp().apply { topMargin = dp(14) })
        msg = label("", 13f, Pal.DIM).apply { gravity = Gravity.CENTER }
        step2.addView(msg, lp().apply { topMargin = dp(6) })
        go = termButton("", accent) { onGo() }.apply { minHeight = dp(60) }
        step2.addView(go, lp().apply { topMargin = dp(12) })

        root.addView(View(this), lp(h = 0, weight = 1f))
        root.addView(termButton("НАЗАД", accent) { if (step2.visibility == View.VISIBLE && !done) toStep1() else finish() }, lp().apply { topMargin = dp(12) })
        setContentView(spaceScreen(scroll, accent).first)
        toStep1()
        show()
    }

    private lateinit var step1: LinearLayout
    private lateinit var step2: LinearLayout
    private lateinit var stepView: TextView
    private lateinit var countView: TextView
    private lateinit var membersView: TextView
    private var scroll: ScrollView? = null
    private var defaultName = ""
    private var done = false

    private fun memberNames(): List<String> {
        val db = Repo.get(this)
        return selMods.map { (if (it in db.masterModules) "✉ " else "▣ ") + (db.modules[it] ?: "модуль") } +
            selChars.mapNotNull { id -> db.people.firstOrNull { it.id == id }?.name?.let { "◉ $it" } }
    }

    private fun updateCount() {
        val n = selMods.size + selChars.size
        countView.text = if (n == 0) "Отметьте, кого позвать в канал" else "Выбрано: $n"
        countView.setTextColor(if (n == 0) Pal.DIM else Look.accent)
    }

    private fun toStep1() {
        step1.visibility = View.VISIBLE; step2.visibility = View.GONE
        stepView.text = "ШАГ 1 ИЗ 2 · КОГО ПОЗВАТЬ"
        updateCount(); scroll?.post { scroll?.scrollTo(0, 0) }
    }

    private fun toStep2() {
        if (selMods.isEmpty() && selChars.isEmpty()) { countView.text = "Выберите хотя бы одного участника."; countView.setTextColor(Pal.WARN); fx?.fail(); return }
        step1.visibility = View.GONE; step2.visibility = View.VISIBLE
        stepView.text = if (price > 0) "ШАГ 2 ИЗ 2 · НАЗВАНИЕ И ЭНЕРГИЯ" else "ШАГ 2 ИЗ 2 · НАЗВАНИЕ"
        membersView.text = memberNames().joinToString("\n")
        say("", Pal.DIM); show(); fx?.tick(); scroll?.post { scroll?.scrollTo(0, 0) }
    }

    private fun channelTitle(): String = name.text.toString().trim().ifEmpty { defaultName }

    /** Кнопки-переключатели: по две в ряд (или по одной — для модулей). */
    private fun chips(root: LinearLayout, items: List<Pair<String, String>>, sel: MutableSet<String>, accent: Int, perRow: Int = 2) {
        if (items.isEmpty()) { root.addView(label("—", 12f, Pal.DIM), lp()); return }
        var row: LinearLayout? = null
        items.forEachIndexed { i, (id, title) ->
            if (i % perRow == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; root.addView(row, lp().apply { topMargin = dp(6) }) }
            val b = label(title, 13f, Pal.TEXT, bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 2; val pd = dp(10); setPadding(pd, pd, pd, pd); isClickable = true; minHeight = dp(48)
            }
            val paint = { on: Boolean -> b.background = boxBg(this, if (on) accent else Pal.withAlpha(accent, 90), if (on) Pal.withAlpha(accent, 60) else Pal.PANEL, 4, 1); b.setTextColor(if (on) Pal.TEXT else Pal.DIM); b.text = (if (on) "✓ " else "") + title }
            paint(id in sel)
            b.setOnClickListener { if (!sel.remove(id)) sel.add(id); paint(id in sel); fx?.tick(); updateCount() }
            row!!.addView(b, lp(w = 0, weight = 1f).apply { if (perRow > 1 && i % perRow == 0) rightMargin = dp(6) })
        }
        if (perRow > 1 && items.size % perRow == 1) row!!.addView(View(this), lp(w = 0, weight = 1f))
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
        if (selMods.isEmpty() && selChars.isEmpty()) { toStep1(); countView.text = "Выберите хотя бы одного участника."; countView.setTextColor(Pal.WARN); fx?.fail(); return false }
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
        val title = channelTitle()
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
                        go.visibility = View.GONE; done = true
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
