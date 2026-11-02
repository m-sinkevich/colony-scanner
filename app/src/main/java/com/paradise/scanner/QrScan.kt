package com.paradise.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/** Оптический скан (камера) — QR-коды бейджей и объектов. */
object QrScan {
    const val REQ_CAMERA = 41

    fun available(a: Activity) = a.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun start(a: Activity, prompt: String, skip: Collection<String> = emptyList()) {
        pendingSkip = skip
        // сначала разрешение на камеру — системный запрос, без него сканер не откроется
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPrompt = prompt
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        launch(a, prompt)
    }

    private var pendingPrompt = ""
    private var pendingSkip: Collection<String> = emptyList()

    private fun launch(a: Activity, prompt: String) {
        try {
            a.startActivityForResult(QrActivity.intent(a, prompt, pendingSkip), QrActivity.REQ)
        } catch (e: Exception) {
            Toast.makeText(a, "Камера недоступна", Toast.LENGTH_SHORT).show()
        }
    }

    /** true — это был ответ на запрос камеры. */
    fun onPermission(a: Activity, requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQ_CAMERA) return false
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) a.window.decorView.post { launch(a, pendingPrompt) }
        else Toast.makeText(a, "Нет доступа к камере — разрешите его в настройках телефона", Toast.LENGTH_LONG).show()
        return true
    }

    /** Текст QR-кода; null — не наш результат. Пустая строка — отмена. */
    fun result(requestCode: Int, resultCode: Int, data: Intent?): String? {
        if (requestCode != QrActivity.REQ) return null
        if (resultCode != Activity.RESULT_OK) return ""
        return data?.getStringExtra(QrActivity.EXTRA_TEXT)?.trim() ?: ""
    }
}
