package com.paradise.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Объект контента «Терминала колониста» в сканере: страница из блоков (текст, картинки, видео, звук),
 * вариант для персонажа или его модуля, ветвление — кнопки выбора ведут к следующим объектам.
 */
class ContentActivity : Activity() {

    companion object {
        private const val K_ID = "id"
        /** Открыт из журнала сканирований — повторный просмотр. */
        const val K_REVIEW = "review"
        fun intent(ctx: Context, id: String): Intent = Intent(ctx, ContentActivity::class.java).putExtra(K_ID, id)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val history = ArrayList<String>()
    private var fx: Fx? = null
    private var player: MediaPlayer? = null
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var person: Person? = null
    private val accent get() = Look.accent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Look.apply(this)
        fx = Fx(this)
        person = Repo.get(this).person(Prefs(this).characterId)
        scroll = ScrollView(this).apply { isFillViewport = true }
        root = column(18)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        setContentView(spaceScreen(scroll, accent).first)
        val id = intent.getStringExtra(K_ID) ?: return finish()
        history.add(id)
        render()
    }

    private fun stopMedia() {
        try { player?.release() } catch (e: Exception) { }
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMedia()
        ui.removeCallbacksAndMessages(null)
        fx?.release()
    }

    override fun onPause() {
        super.onPause()
        try { player?.pause() } catch (e: Exception) { }
    }

    override fun onBackPressed() {
        if (history.size > 1) { history.removeAt(history.size - 1); render() } else super.onBackPressed()
    }

    private fun render(gate: Access.Gate.Denied? = null) {
        stopMedia()
        root.removeAllViews()
        val db = Repo.get(this)
        val item = db.content(history.last())
        if (item == null) { Toast.makeText(this, "Объект не найден в базе сканера", Toast.LENGTH_SHORT).show(); finish(); return }
        val modeName = (db.mode(Prefs(this).role)?.name ?: "").uppercase()
        val now = Date()
        val time = SimpleDateFormat("dd.MM.", Locale.getDefault()).format(now) + ResultActivity.GAME_YEAR + " · " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
        val c = if (gate != null) Pal.WARN else accent

        // --- заголовок ---
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = Look.panel(context, c, Pal.withAlpha(c, 22), chamferDp = 16f)
        }
        head.addView(label(if (gate != null) "◈ ДОСТУП ОГРАНИЧЕН" else "◈ ДАННЫЕ ОБЪЕКТА", 11f, c, bold = true).apply { letterSpacing = 0.16f }, lp())
        head.addView(label("$modeName · $time", 10f, Pal.DIM).apply { letterSpacing = 0.06f }, lp().apply { topMargin = dp(2) })
        head.addView(label(if (gate != null) gate.title else "✓ ДАННЫЕ УСПЕШНО СЧИТАНЫ", if (gate != null) 20f else 10f, c, bold = gate != null)
            .apply { letterSpacing = 0.08f; if (gate != null) glow(c, 10f) }, lp().apply { topMargin = dp(if (gate != null) 8 else 2) })
        if (history.size > 1) head.addView(label("ШАГ ${history.size} · ← НАЗАД", 10f, Pal.TEXT).apply {
            letterSpacing = 0.1f; setPadding(0, dp(8), 0, 0); setOnClickListener { onBackPressed() }
        }, lp())
        root.addView(head, lp().apply { bottomMargin = dp(14) })
        head.alpha = 0f; head.translationY = dp(-12).toFloat()
        head.animate().alpha(1f).translationY(0f).setDuration(400).start()

        if (gate != null) {
            root.addView(textPanel(Md.render(gate.message, c, dp(18)), c, "plain"), lp())
        } else {
            val v = Access.variantFor(item, person)
            val blocks = v?.blocks?.takeIf { it.isNotEmpty() } ?: if (v != null && v.body.isNotBlank()) listOf(Block("text", v.body, null, emptyList(), "", "")) else item.blocks()
            val choices = v?.choices ?: item.choices
            var delay = 120L
            for (b in blocks) {
                val view = blockView(b, db) ?: continue
                root.addView(view, lp(h = if (b.type == "divider") dp(1) else android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
                view.alpha = 0f
                view.animate().alpha(1f).setStartDelay(delay).setDuration(450).start()
                delay += 160
            }
            if (blocks.isEmpty()) root.addView(label("Объект не содержит данных.", 14f, Pal.DIM), lp())
            if (choices.isNotEmpty()) {
                root.addView(label("ВЫБОР", 11f, c, bold = true).apply { letterSpacing = 0.2f }, lp().apply { topMargin = dp(10); bottomMargin = dp(6) })
                for (ch in choices) {
                    root.addView(termButton(ch.label.uppercase(), c) { choose(item, ch) }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f) },
                        lp().apply { bottomMargin = dp(8) })
                }
            }
        }
        root.addView(View(this), lp(h = 0, weight = 1f))
        root.addView(termButton(Look.againLabel, c) { finish() }, lp().apply { topMargin = dp(18) })
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun choose(from: ContentItem, ch: Choice) {
        val db = Repo.get(this)
        val p = person ?: return
        val next = db.content(ch.next)
        if (next == null) { fx?.fail(); Toast.makeText(this, "Данные этой ветки не загружены. Обновите базу сканера.", Toast.LENGTH_LONG).show(); return }
        Store.log(this, p, "content_choice", JSONObject().put("contentId", from.id).put("nextContentId", next.id).put("label", ch.label))
        when (val g = Access.check(this, next, p)) {
            is Access.Gate.Denied -> {
                Store.log(this, p, "content_open", JSONObject().put("contentId", next.id).put("result", "blocked"))
                history.add(next.id); fx?.fail(); render(g)
            }
            Access.Gate.Ok -> {
                Store.opened(this, p, next)
                history.add(next.id); fx?.success(); render()
            }
        }
        Store.pushLater(this)
    }

    private fun textPanel(text: CharSequence, c: Int, style: String): TextView {
        val col = when (style) { "alert" -> Pal.ERROR; "note" -> c; else -> Pal.withAlpha(c, 150) }
        val fill = when (style) { "alert" -> Pal.withAlpha(Pal.ERROR, 30); "note" -> Pal.withAlpha(c, 26); else -> 0xCC060D11.toInt() }
        return label("", 16f, Pal.TEXT).apply {
            this.text = text
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Look.panel(context, col, fill, chamferDp = 12f, brackets = false)
        }
    }

    private fun mediaFile(db: Database, url: String?): File? {
        val name = db.local(url) ?: return null
        return File(Repo.imgDir(this), name).takeIf { it.exists() }
    }

    private fun blockView(b: Block, db: Database): View? {
        val c = accent
        return when (b.type) {
            "text" -> if (b.md.isBlank()) null else textPanel(Md.render(b.md, c, dp(18)), c, b.style)
            "divider" -> View(this).apply { setBackgroundColor(Pal.withAlpha(c, 120)) }
            "image" -> imageBox(listOfNotNull(b.url), b.caption, db)
            "gallery" -> imageBox(b.urls, b.caption, db)
            "video" -> videoBox(b, db)
            "audio" -> audioBox(b, db)
            else -> null
        }
    }

    private fun frame(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.panel(context, Pal.withAlpha(accent, 170), 0xCC060D11.toInt(), chamferDp = 14f)
        val p = dp(6); setPadding(p, p, p, p)
    }

    private fun caption(box: LinearLayout, text: String) {
        if (text.isNotBlank()) box.addView(label(text, 12f, Pal.DIM).apply { setPadding(dp(6), dp(6), dp(6), dp(2)) }, lp())
    }

    private fun imageBox(urls: List<String>, cap: String, db: Database): View? {
        if (urls.isEmpty()) return null
        val box = frame()
        val maxW = resources.displayMetrics.widthPixels
        urls.forEachIndexed { k, u ->
            val bmp = Repo.loadImage(this, db.local(u), maxW)
            if (bmp != null) box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            }, lp().apply { if (k > 0) topMargin = dp(6) })
            else box.addView(label("[ изображение не загружено — обновите базу ]", 12f, Pal.DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) }, lp())
        }
        caption(box, cap)
        return box
    }

    private fun videoBox(b: Block, db: Database): View {
        val box = frame()
        val f = mediaFile(db, b.url)
        if (f == null) {
            box.addView(label("[ видеозапись не загружена — обновите базу ]", 12f, Pal.DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) }, lp())
        } else {
            val vv = VideoView(this)
            val mc = MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc)
            vv.setVideoURI(Uri.fromFile(f))
            vv.setOnPreparedListener { it.isLooping = false; vv.seekTo(1) }
            box.addView(vv, lp(h = (resources.displayMetrics.widthPixels * 0.56f).toInt()))
            box.addView(termButton("▶  ВОСПРОИЗВЕСТИ", accent) { if (vv.isPlaying) vv.pause() else vv.start() }.apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            }, lp().apply { topMargin = dp(6) })
        }
        caption(box, b.caption)
        return box
    }

    private fun audioBox(b: Block, db: Database): View {
        val box = frame()
        val f = mediaFile(db, b.url)
        if (f == null) {
            box.addView(label("[ аудиозапись не загружена — обновите базу ]", 12f, Pal.DIM).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) }, lp())
        } else {
            lateinit var btn: TextView
            btn = termButton("▶  ВОСПРОИЗВЕСТИ ЗАПИСЬ", accent) {
                val p = player
                if (p != null && p.isPlaying) { p.pause(); btn.text = "▶  ПРОДОЛЖИТЬ" }
                else if (p != null) { p.start(); btn.text = "❚❚  ПАУЗА" }
                else try {
                    player = MediaPlayer().apply {
                        setDataSource(f.absolutePath)
                        setOnCompletionListener { btn.text = "▶  ВОСПРОИЗВЕСТИ ЗАПИСЬ"; stopMedia() }
                        prepare(); start()
                    }
                    btn.text = "❚❚  ПАУЗА"
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось воспроизвести запись", Toast.LENGTH_SHORT).show()
                }
            }
            box.addView(btn, lp())
        }
        caption(box, b.caption)
        return box
    }
}
