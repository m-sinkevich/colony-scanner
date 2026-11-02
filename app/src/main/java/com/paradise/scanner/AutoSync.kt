package com.paradise.scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Фоновое обновление базы: при запуске и затем раз в 3 минуты, пока сканер открыт.
 * Молча: без окон и сообщений; нет сети — просто попробует в следующий раз.
 */
object AutoSync {
    private const val PERIOD = 3 * 60 * 1000L
    private val h = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var running = false
    private var visible = 0
    private var lastRun = 0L
    /** Вызывается после успешного обновления (в главном потоке). */
    var onUpdated: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (visible > 0) now()
            h.removeCallbacks(this)
            h.postDelayed(this, PERIOD)
        }
    }

    /** Экран сканера открыт: первый раз за запуск — обновить сразу, дальше по таймеру. */
    fun resumed(ctx: Context) {
        val first = app == null
        app = ctx.applicationContext
        visible++
        if (first || SystemClock.uptimeMillis() - lastRun > PERIOD) { h.removeCallbacks(tick); h.post(tick) }
    }

    fun paused() {
        visible = (visible - 1).coerceAtLeast(0)
    }

    fun now() {
        val ctx = app ?: return
        if (running || !Prefs(ctx).hasServer) return
        running = true
        lastRun = SystemClock.uptimeMillis()
        Sync.start(ctx, {}) { r, _ ->
            running = false
            if (r != null) onUpdated?.invoke()
        }
    }
}
