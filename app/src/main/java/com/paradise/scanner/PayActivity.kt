package com.paradise.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Оплата платного объекта кристаллами: сканируем QR кристаллов, пока не наберётся цена. Только онлайн. */
class PayActivity : Activity() {
    companion object {
        private const val K_ID = "content_id"
        fun intent(ctx: Context, id: String): Intent = Intent(ctx, PayActivity::class.java).putExtra(K_ID, id)
    }

    private val ui = Handler(Looper.getMainLooper())
    private var fx: Fx? = null
    private lateinit var gems: TextView
    private lateinit var count: TextView
    private lateinit var msg: TextView
    private lateinit var btn: TextView
    private var item: ContentItem? = null
    private var person: Person? = null
    private var paid = 0
    private var busy = false
    private val seen = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.apply(this)
        fx = Fx(this)
        val db = Repo.get(this)
        item = db.content(intent.getStringExtra(K_ID))
        person = db.person(Prefs(this).characterId)
        val it = item; val p = person
        if (it == null || p == null) { finish(); return }
        val accent = Look.accent
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = column(18)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Look.panel(context, Pal.WARN, Pal.withAlpha(Pal.WARN, 20), chamferDp = 16f)
        }
        head.addView(label(Look.kicker, 10f, Pal.DIM).apply { letterSpacing = 0.15f }, lp())
        head.addView(label("ТРЕБУЕТСЯ ДОПОЛНИТЕЛЬНАЯ ЭНЕРГИЯ", 20f, Pal.WARN, bold = true).apply { letterSpacing = Look.letter }.glow(Pal.WARN, 10f), lp().apply { topMargin = dp(4) })
        head.addView(label(History.titleOf(it, p), 13f, Pal.TEXT), lp().apply { topMargin = dp(4) })
        root.addView(head, lp())

        root.addView(label("Для расшифровки этих данных нужна энергия кристаллов. Энергия вносится один раз — затем данные доступны вам без ограничений.", 14f, Pal.TEXT),
            lp().apply { topMargin = dp(18) })
        gems = label("", 34f, accent, bold = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }
        root.addView(gems, lp().apply { topMargin = dp(22) })
        count = label("", 14f, Pal.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(count, lp().apply { topMargin = dp(4) })
        msg = label("", 13f, Pal.DIM).apply { gravity = Gravity.CENTER }
        root.addView(msg, lp().apply { topMargin = dp(14) })

        btn = termButton("◎  СКАНИРОВАТЬ КРИСТАЛЛ", accent) { scan() }.apply { minHeight = dp(64) }
        root.addView(btn, lp().apply { topMargin = dp(20) })
        root.addView(android.view.View(this), lp(h = 0, weight = 1f))
        root.addView(termButton("НАЗАД", accent) { finish() }, lp().apply { topMargin = dp(12) })
        setContentView(spaceScreen(scroll, accent).first)
        show()
    }

    private fun show() {
        val price = item?.price ?: 0
        gems.text = (0 until price).joinToString(" ") { if (it < paid) "◆" else "◇" }
        count.text = "ЭНЕРГИЯ $paid ИЗ $price"
    }

    private fun say(text: String, color: Int) { msg.text = text; msg.setTextColor(color) }

    private fun scan() {
        if (busy) return
        QrScan.start(this, "ЭНЕРГИЯ · наведите на код кристалла")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        QrScan.onPermission(this, requestCode, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val text = QrScan.result(requestCode, resultCode, data)
        if (text == null) { super.onActivityResult(requestCode, resultCode, data); return }
        if (text.isNotEmpty()) spend(text)
    }

    private fun spend(raw: String) {
        val it = item ?: return; val p = person ?: return
        val code = Codes.candidates(listOf(raw)).firstOrNull()?.uppercase() ?: raw.trim().uppercase()
        if (code in seen) { say("Энергия кристалла $code уже внесена. Отсканируйте другой.", Pal.WARN); fx?.fail(); return }
        busy = true
        say("◌ ПРОВЕРКА КРИСТАЛЛА $code…", Pal.DIM)
        Thread {
            val r = try { Crystals.spend(applicationContext, code, p, it) } catch (e: Exception) { null }
            ui.post {
                busy = false
                if (isFinishing) return@post
                when {
                    r == null -> { say("НЕТ СВЯЗИ С ЭНЕРГОСЕТЬЮ КОЛОНИИ\nЭнергию можно внести только при подключении к сети.", Pal.ERROR); fx?.fail() }
                    r.result == "ok" || r.result == "already_paid" -> {
                        seen.add(code); paid = r.paid; show()
                        if (r.completed || r.result == "already_paid") {
                            fx?.success(); fx?.vibrate(60, 60, 160)
                            say("✓ ЭНЕРГИИ ДОСТАТОЧНО", Look.accent)
                            Store.opened(this, p, it)
                            History.add(this, p, "paid", History.titleOf(it, p), it.id)
                            Store.pushLater(this)
                            ui.postDelayed({ startActivity(ContentActivity.intent(this, it.id)); finish() }, 900)
                        } else {
                            fx?.success()
                            say("◆ Энергия кристалла $code принята. Отсканируйте следующий.", Look.accent)
                        }
                    }
                    r.result == "spent" -> { say("Кристалл $code истощён — энергии в нём нет.", Pal.ERROR); fx?.fail(); fx?.vibrate(120, 80, 120) }
                    else -> { say("Код $code — не кристалл.", Pal.ERROR); fx?.fail() }
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
