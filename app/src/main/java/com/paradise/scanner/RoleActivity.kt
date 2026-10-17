package com.paradise.scanner

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Первый запуск: выбор профиля сканера.
 * Потом сменить профиль можно только через служебное меню (PIN мастера).
 */
class RoleActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = column(20)
        scroll.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        root.addView(label("◈ КОЛОНИАЛЬНЫЙ СКАНЕР", 11f, Pal.DIM).apply { letterSpacing = 0.18f }, lp().apply { topMargin = dp(20) })
        root.addView(label("ИНИЦИАЛИЗАЦИЯ", 26f, Pal.DEFAULT_ACCENT, bold = true).apply { letterSpacing = 0.12f }.glow(Pal.DEFAULT_ACCENT, 12f), lp())
        root.addView(label("Выберите профиль сканера.\nСменить его позже сможет только мастер.", 13f, Pal.DIM),
            lp().apply { topMargin = dp(6); bottomMargin = dp(20) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, lp())

        root.addView(label("Сначала загрузите актуальную базу, если есть интернет:", 12f, Pal.DIM).apply { gravity = Gravity.CENTER },
            lp().apply { topMargin = dp(24) })
        root.addView(termButton("ЗАГРУЗИТЬ БАЗУ", Pal.DIM) { SyncUi.show(this) { fill() } }, lp().apply { topMargin = dp(8) })

        setContentView(spaceScreen(scroll, Pal.DEFAULT_ACCENT).first)
        fill()
    }

    private fun fill() {
        list.removeAllViews()
        val db = Repo.get(this)
        for (m in db.modes) {
            val b = termButton(m.name.uppercase(), m.color) {
                Prefs(this).role = m.id
                finish()
            }
            list.addView(b, lp().apply { bottomMargin = dp(4) })
            if (m.subtitle.isNotBlank()) {
                list.addView(label(m.subtitle, 12f, Pal.DIM), lp().apply { bottomMargin = dp(14) })
            } else {
                list.addView(android.view.View(this), lp(h = dp(10)))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // без профиля сканер не работает — назад = выход из приложения
        finishAffinity()
    }
}
