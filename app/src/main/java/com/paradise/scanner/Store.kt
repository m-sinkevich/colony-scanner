package com.paradise.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Офлайн-журнал сканера: открытия объектов, первые попытки (для таймеров) и записи журнала действий.
 * Всё копится в files/queue.json и отправляется в базу при обновлении или когда появилась сеть.
 * Прогресс персонажа = прогресс, скачанный с сервера + ещё не отправленные события.
 */
object Store {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "queue.json")

    private fun read(ctx: Context): JSONArray = try {
        file(ctx).takeIf { it.exists() }?.readText()?.let { JSONArray(it) } ?: JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    private fun write(ctx: Context, a: JSONArray) {
        val tmp = File(ctx.filesDir, "queue.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file(ctx))) { file(ctx).writeText(a.toString()); tmp.delete() }
    }

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun add(ctx: Context, ev: JSONObject) = synchronized(lock) {
        val a = read(ctx); a.put(ev.put("at", System.currentTimeMillis())); write(ctx, a)
    }

    fun pending(ctx: Context): Int = synchronized(lock) { read(ctx).length() }

    /** Объект открыт персонажем (как recordProgress + лог content_open в терминале). */
    fun opened(ctx: Context, p: Person, item: ContentItem) {
        add(ctx, JSONObject().put("k", "open").put("c", p.id).put("i", item.id))
        log(ctx, p, "content_open", JSONObject().put("contentId", item.id).put("type", item.type).put("source", "scanner"))
    }

    /** Первая попытка открыть объект с таймером — от неё считается время. */
    fun attempt(ctx: Context, p: Person, item: ContentItem) {
        if (progress(ctx, p.id, item.id).firstAt > 0) return
        add(ctx, JSONObject().put("k", "attempt").put("c", p.id).put("i", item.id))
    }

    fun log(ctx: Context, p: Person?, action: String, details: JSONObject) {
        add(ctx, JSONObject().put("k", "log").put("c", p?.id ?: JSONObject.NULL).put("m", p?.moduleId ?: JSONObject.NULL)
            .put("a", action).put("d", details.put("source", "scanner")))
    }

    fun progress(ctx: Context, charId: String, contentId: String): Prog {
        val base = Repo.get(ctx).progress["$charId|$contentId"] ?: Prog(0, 0L)
        var n = base.count
        var first = base.firstAt
        synchronized(lock) {
            val a = read(ctx)
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                if (e.optString("c") != charId || e.optString("i") != contentId) continue
                if (e.optString("k") == "open") n++
                val at = e.optLong("at")
                if ((e.optString("k") == "open" || e.optString("k") == "attempt") && (first == 0L || at < first)) first = at
            }
        }
        return Prog(n, first)
    }

    /* ---------------- отправка ---------------- */

    /** Отправить накопленное. Возвращает число отправленных событий; при ошибке сети — исключение, очередь цела. */
    fun push(ctx: Context): Int {
        val prefs = Prefs(ctx)
        if (!prefs.hasServer) return 0
        val base = prefs.supabaseUrl.trim().trimEnd('/')
        val key = prefs.supabaseKey.trim()
        val snapshot = synchronized(lock) { read(ctx) }
        val n = snapshot.length()
        if (n == 0) return 0

        // 1) журнал действий
        val logs = JSONArray()
        // 2) прогресс: по паре персонаж+объект — сколько открытий и самая ранняя отметка
        class Acc(var opens: Int = 0, var first: Long = 0L, var last: Long = 0L)
        val acc = LinkedHashMap<Pair<String, String>, Acc>()
        val viewed = LinkedHashSet<String>()
        for (i in 0 until n) {
            val e = snapshot.optJSONObject(i) ?: continue
            val at = e.optLong("at")
            when (e.optString("k")) {
                "log" -> logs.put(JSONObject()
                    .put("module_id", if (e.isNull("m")) JSONObject.NULL else e.optString("m"))
                    .put("character_id", if (e.isNull("c")) JSONObject.NULL else e.optString("c"))
                    .put("action_type", e.optString("a"))
                    .put("details", e.optJSONObject("d") ?: JSONObject())
                    .put("timestamp", iso(at)))
                "open", "attempt" -> {
                    val a = acc.getOrPut(e.optString("c") to e.optString("i")) { Acc() }
                    if (e.optString("k") == "open") { a.opens++; a.last = maxOf(a.last, at); viewed.add(e.optString("i")) }
                    if (a.first == 0L || at < a.first) a.first = at
                }
            }
        }
        if (logs.length() > 0) request(base, key, "POST", "logs", logs.toString(), "return=minimal")
        for ((pair, a) in acc) {
            val (c, i) = pair
            val cur = JSONArray(request(base, key, "GET", "character_progress?select=*&character_id=eq.$c&content_id=eq.$i", null, null))
            val ex = cur.optJSONObject(0)
            val row = JSONObject().put("character_id", c).put("content_id", i)
                .put("play_count", (ex?.optInt("play_count") ?: 0) + a.opens)
            val exFirst = ex?.optString("first_opened_at")?.takeIf { it.isNotBlank() && it != "null" }
            row.put("first_opened_at", exFirst ?: iso(a.first))
            val exLast = ex?.optString("last_opened_at")?.takeIf { it.isNotBlank() && it != "null" }
            row.put("last_opened_at", if (a.last > 0) iso(a.last) else (exLast ?: iso(a.first)))
            request(base, key, "POST", "character_progress", row.toString(), "resolution=merge-duplicates,return=minimal")
        }
        // отметка «просмотрено» в мастерке; если такой колонки нет — не страшно
        for (id in viewed) try {
            request(base, key, "PATCH", "content_items?id=eq.$id&viewed=is.false", JSONObject().put("viewed", true).put("viewed_at", iso(System.currentTimeMillis())).toString(), "return=minimal")
        } catch (e: Exception) { }

        synchronized(lock) {
            val now = read(ctx)
            val rest = JSONArray()
            for (k in n until now.length()) rest.put(now.get(k))
            write(ctx, rest)
        }
        return n
    }

    /** Попробовать отправить в фоне, не мешая игре (нет сети — останется в очереди). */
    fun pushLater(ctx: Context) {
        val app = ctx.applicationContext
        Thread { try { push(app) } catch (e: Exception) { } }.start()
    }

    private fun request(base: String, key: String, method: String, path: String, body: String?, prefer: String?): String {
        val c = URL("$base/rest/v1/$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.requestMethod = method   // PATCH на Android поддерживается
            c.setRequestProperty("apikey", key)
            if (key.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code $path: ${txt.take(160)}")
            return txt.ifBlank { "[]" }
        } finally {
            c.disconnect()
        }
    }
}
