package com.paradise.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator

/** Оптический скан (камера) — QR-коды бейджей и объектов. */
object QrScan {
    const val REQ_CAMERA = 41

    fun available(a: Activity) = a.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun start(a: Activity, prompt: String) {
        // сначала разрешение на камеру — системный запрос, без него сканер не откроется
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPrompt = prompt
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        launch(a, prompt)
    }

    private var pendingPrompt = ""

    private fun launch(a: Activity, prompt: String) {
        try {
            IntentIntegrator(a).apply {
                setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                setPrompt(prompt)
                setBeepEnabled(false)
                setOrientationLocked(true)
                setBarcodeImageEnabled(false)
            }.initiateScan()
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

    /** Текст QR-кода; null — не наш результат или отмена. Пустая строка — ничего не считано. */
    fun result(requestCode: Int, resultCode: Int, data: Intent?): String? {
        val r = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return null
        return r.contents?.trim() ?: ""
    }
}
