package com.paradise.scanner

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ProgressBar

/** Диалог синхронизации базы с прогрессом. */
object SyncUi {
    fun show(activity: Activity, onFinished: () -> Unit) {
        val prefs = Prefs(activity)
        if (!prefs.hasServer) {
            AlertDialog.Builder(activity)
                .setTitle("Сервер не настроен")
                .setMessage("Адрес Supabase и ключ задаются в служебном меню (удерживайте заголовок сканера 3 секунды и введите PIN мастера).")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val text = activity.label("Подключение…", 13f, Pal.TEXT)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = activity.dp(20)
            setPadding(p, p, p, 0)
            addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }, lp())
            addView(text, lp())
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("Загрузка базы")
            .setView(box)
            .setCancelable(false)
            .show()

        Sync.start(activity, { msg -> text.text = msg }) { report, error ->
            if (activity.isFinishing || activity.isDestroyed) return@start
            try {
                dlg.dismiss()
            } catch (e: Exception) {
            }
            val msg = if (report != null) {
                buildString {
                    append("База обновлена.\n\n")
                    append("Типов сканеров: ${report.modes}\n")
                    append("Записей меток: ${report.tags}\n")
                    append("Картинок скачано: ${report.images}")
                    if (report.imageErrors > 0) append("\nНе удалось скачать картинок: ${report.imageErrors}")
                    if (report.settings.isNotEmpty()) append("\n\nНастройки из терминала мастера:\n• " + report.settings.joinToString("\n• "))
                    append("\n\nТеперь сканер работает без интернета.")
                }
            } else {
                "Не удалось обновить базу:\n$error\n\nПрежняя база сохранена."
            }
            AlertDialog.Builder(activity)
                .setTitle(if (report != null) "Готово" else "Ошибка")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
            onFinished()
        }
    }
}
