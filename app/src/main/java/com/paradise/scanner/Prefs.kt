package com.paradise.scanner

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("scanner", Context.MODE_PRIVATE)

    /** id выбранного сканера (роль). */
    var role: String?
        get() = p.getString("role", null)
        set(v) = p.edit().putString("role", v).apply()

    var supabaseUrl: String
        get() = p.getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_URL
        set(v) = p.edit().putString("url", v.trim()).apply()

    var supabaseKey: String
        get() = p.getString("key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_KEY
        set(v) = p.edit().putString("key", v.trim()).apply()

    var pin: String
        get() = p.getString("pin", null) ?: BuildConfig.MASTER_PIN
        set(v) = p.edit().putString("pin", v.trim()).apply()

    /** Последние считанные с метки коды — мастер видит их в служебном меню. */
    var lastCodes: String
        get() = p.getString("last_codes", "") ?: ""
        set(v) = p.edit().putString("last_codes", v).apply()

    /** Длительность сессии сканирования в секундах; 0 — без ограничения (до метки или отмены). */
    var sessionSeconds: Int
        get() = p.getInt("session_sec", 60)
        set(v) = p.edit().putInt("session_sec", v.coerceIn(0, 3600)).apply()

    /** Реагировать на метку, даже если сканирование не запущено кнопкой. */
    var autoScan: Boolean
        get() = p.getBoolean("auto_scan", false)
        set(v) = p.edit().putBoolean("auto_scan", v).apply()

    /** Опрашивать все типы NFC (B, F, V). По умолчанию только тип A — так метки ловятся быстрее. */
    var allTagTypes: Boolean
        get() = p.getBoolean("all_tag_types", false)
        set(v) = p.edit().putBoolean("all_tag_types", v).apply()

    val hasServer: Boolean
        get() = supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()
}
