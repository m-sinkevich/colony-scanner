package com.paradise.scanner

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал сканирований этого сканера: что и когда считано; открытые объекты можно посмотреть снова. */
class JournalActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.apply(this)
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
        head.addView(label("ЖУРНАЛ СКАНИРОВАНИЙ", 20f, accent, bold = true).apply { letterSpacing = Look.letter }.glow(accent, 10f), lp().apply { topMargin = dp(4) })
        root.addView(head, lp().apply { bottomMargin = dp(12) })

        val db = Repo.get(this)
        val me = Prefs(this).characterId
        // только записи текущего оператора
        val items = History.all(this).filter { it.charId == me }
        val day = SimpleDateFormat("dd.MM", Locale.getDefault())
        val tm = SimpleDateFormat("HH:mm", Locale.getDefault())
        if (items.isEmpty()) root.addView(label("Сканирований ещё не было.", 14f, Pal.DIM).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = dp(30) })
        for (it in items) {
            val (tag, col) = when (it.kind) {
                "content" -> "ДАННЫЕ СЧИТАНЫ" to accent
                "person" -> "ЛИЧНОСТЬ" to accent
                "tag" -> "ОБЪЕКТ" to accent
                "denied" -> "ДОСТУП ОГРАНИЧЕН" to Pal.WARN
                "pay" -> "НУЖНА ЭНЕРГИЯ" to Pal.WARN
                "paid" -> "ЭНЕРГИЯ ВНЕСЕНА" to accent
                "crystal" -> "КРИСТАЛЛ" to accent
                "wrong" -> "АНАЛИЗ НЕВОЗМОЖЕН" to Pal.WARN
                else -> "НЕ ОПОЗНАН" to Pal.ERROR
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = Look.panel(context, Pal.withAlpha(col, 150), 0xCC060D11.toInt(), chamferDp = 10f, brackets = false)
            }
            row.addView(label("${day.format(Date(it.at))}.${ResultActivity.GAME_YEAR} · ${tm.format(Date(it.at))} · $tag", 10f, col).apply { letterSpacing = 0.06f }, lp())
            row.addView(label(it.title, 15f, Pal.TEXT, bold = true), lp().apply { topMargin = dp(3) })
            if (it.note.isNotBlank()) row.addView(label(it.note, 12f, Pal.DIM), lp().apply { topMargin = dp(2) })
            val item = db.content(it.contentId)
            val meP = db.person(me)
            if (it.kind == "pay" && item != null && meP != null && Access.energy(item, meP).let { (v, pr) -> pr > 0 && !Crystals.paid(this, meP.id, item.id, v) }) {
                row.addView(label("› внести энергию кристаллов", 11f, accent), lp().apply { topMargin = dp(4) })
                row.setOnClickListener { startActivity(PayActivity.intent(this, item.id)) }
            }
            if ((it.kind == "content" || it.kind == "paid") && item != null) {
                row.addView(label(if (item.playback == "once") "запись одноразовая — повторный просмотр закрыт" else "› открыть снова", 11f,
                    if (item.playback == "once") Pal.DIM else accent), lp().apply { topMargin = dp(4) })
                if (item.playback != "once") row.setOnClickListener {
                    // повторный просмотр из журнала: без нового «открытия» в прогрессе
                    startActivity(ContentActivity.intent(this, item.id).putExtra(ContentActivity.K_REVIEW, true))
                }
                else row.setOnClickListener { Toast.makeText(this, "Эта запись доступна только один раз", Toast.LENGTH_SHORT).show() }
            }
            root.addView(row, lp().apply { bottomMargin = dp(8) })
        }
        root.addView(android.view.View(this), lp(h = 0, weight = 1f))
        root.addView(termButton("НАЗАД", accent) { finish() }, lp().apply { topMargin = dp(12) })
        setContentView(spaceScreen(scroll, accent).first)
    }
}
