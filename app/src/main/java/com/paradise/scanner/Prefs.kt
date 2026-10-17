package com.paradise.scanner

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("scanner", Context.MODE_PRIVATE)

    /** id выбранного сканера (роль). */
    var role: String?
        get() = p.getString("role", null)
        set(v) = p.edit().putString("role", v).apply()

    var supabaseUrl: String
        get() = p.getString("url", null) ?: BuildConfig.SUPABASE_URL
        set(v) = p.edit().putString("url", v.trim()).apply()

    var supabaseKey: String
        get() = p.getString("key", null) ?: BuildConfig.SUPABASE_KEY
        set(v) = p.edit().putString("key", v.trim()).apply()

    var pin: String
        get() = p.getString("pin", null) ?: BuildConfig.MASTER_PIN
        set(v) = p.edit().putString("pin", v.trim()).apply()

    /** Последние считанные с метки коды — мастер видит их в служебном меню. */
    var lastCodes: String
        get() = p.getString("last_codes", "") ?: ""
        set(v) = p.edit().putString("last_codes", v).apply()

    val hasServer: Boolean
        get() = supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()
}
