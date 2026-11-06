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

    /** Сервер ответил ошибкой (в отличие от «нет сети»). */
    class HttpFail(val code: Int, msg: String) : java.io.IOException(msg)

    /** Оплата кристаллом без сети — дослать при появлении сети (spend_crystal_offline, sql/offline.sql). */
    fun spendOffline(ctx: Context, p: Person, item: ContentItem, variant: String, code: String) {
        add(ctx, JSONObject().put("k", "spend").put("c", p.id).put("i", item.id).put("v", variant).put("code", code)
            .put("m", p.moduleId ?: JSONObject.NULL))
    }

    /** Есть ли недосланные оплаты без сети. */
    fun pendingSpends(ctx: Context): Boolean = synchronized(lock) {
        val a = read(ctx); (0 until a.length()).any { val e = a.optJSONObject(it); e != null && !e.has("s") && e.optString("k") == "spend" }
    }

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun add(ctx: Context, ev: JSONObject) = synchronized(lock) {
        val a = read(ctx); a.put(ev.put("at", System.currentTimeMillis())); write(ctx, a)
    }

    /** Сколько событий ещё не отправлено. */
    fun pending(ctx: Context): Int = synchronized(lock) { val a = read(ctx); (0 until a.length()).count { !(a.optJSONObject(it)?.has("s") ?: true) } }

    /**
     * Отправленные события хранятся, пока не скачан свежий прогресс с сервера, — иначе «один раз»
     * и таймеры на время между отправкой и обновлением базы «забыли» бы их. Вызывается после загрузки базы.
     */
    fun purgeSent(ctx: Context, before: Long) = synchronized(lock) {
        val a = read(ctx); val rest = JSONArray()
        for (i in 0 until a.length()) { val e = a.optJSONObject(i) ?: continue; if (!(e.has("s") && e.optLong("s") < before)) rest.put(e) }
        write(ctx, rest)
    }

    private val pushLock = Any()

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
    fun push(ctx: Context): Int = synchronized(pushLock) { pushNow(ctx) }

    private fun pushNow(ctx: Context): Int {
        val prefs = Prefs(ctx)
        if (!prefs.hasServer) return 0
        val base = prefs.supabaseUrl.trim().trimEnd('/')
        val key = prefs.supabaseKey.trim()
        val all = synchronized(lock) { read(ctx) }
        val snapshot = JSONArray()
        for (i in 0 until all.length()) { val e = all.optJSONObject(i) ?: continue; if (!e.has("s")) snapshot.put(e) }
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
        var spendsSent = 0
        // оплаты кристаллами без сети: засчитываются всегда, повтор кристалла сервер пишет в журнал (crystal_conflict)
        for (i in 0 until n) {
            val e = snapshot.optJSONObject(i) ?: continue
            if (e.optString("k") != "spend") continue
            val body = JSONObject().put("p_code", e.optString("code")).put("p_character", e.optString("c")).put("p_content", e.optString("i"))
                .put("p_module", if (e.isNull("m")) JSONObject.NULL else e.optString("m")).put("p_source", "scanner")
                .put("p_variant", e.optString("v")).put("p_at", iso(e.optLong("at")))
            try { request(base, key, "POST", "rpc/spend_crystal_offline", body.toString(), null) }
            catch (x: HttpFail) {
                if (x.code == 404) { body.remove("p_at"); request(base, key, "POST", "rpc/spend_crystal", body.toString(), null) } else throw x
            }
            // сразу отмечаем отправленной — чтобы при обрыве посреди досылки не заплатить дважды
            synchronized(lock) {
                val now = read(ctx)
                for (k in 0 until now.length()) {
                    val q = now.optJSONObject(k) ?: continue
                    if (!q.has("s") && q.optString("k") == "spend" && q.optLong("at") == e.optLong("at") && q.optString("code") == e.optString("code")) { q.put("s", System.currentTimeMillis()); break }
                }
                write(ctx, now)
            }
            spendsSent++
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

        // отмечаем отправленными (по положению в очереди: новые события дописываются в конец)
        synchronized(lock) {
            val now = read(ctx); val sentAt = System.currentTimeMillis()
            var left = n - spendsSent
            for (k in 0 until now.length()) {
                if (left == 0) break
                val e = now.optJSONObject(k) ?: continue
                if (!e.has("s")) { e.put("s", sentAt); left-- }
            }
            write(ctx, now)
        }
        return n
    }

    /** Попробовать отправить в фоне, не мешая игре (нет сети — останется в очереди). */
    fun pushLater(ctx: Context) {
        val app = ctx.applicationContext
        Thread { try { push(app) } catch (e: Exception) { } }.start()
    }

    /** GET-запрос к таблице (например crystals?select=code,spent_at&code=eq.X). Нужна сеть. */
    fun get(ctx: Context, path: String): String {
        val prefs = Prefs(ctx)
        if (!prefs.hasServer) throw java.io.IOException("сервер не настроен")
        return request(prefs.supabaseUrl.trim().trimEnd('/'), prefs.supabaseKey.trim(), "GET", path, null, null)
    }

    /** Вызов функции базы (например spend_crystal). Нужна сеть: при ошибке — исключение. */
    fun rpc(ctx: Context, fn: String, body: JSONObject): String {
        val prefs = Prefs(ctx)
        if (!prefs.hasServer) throw java.io.IOException("сервер не настроен")
        return request(prefs.supabaseUrl.trim().trimEnd('/'), prefs.supabaseKey.trim(), "POST", "rpc/$fn", body.toString(), null)
    }

    /** Запись в таблицу (POST/PATCH). Нужна сеть: при ошибке — исключение. */
    fun send(ctx: Context, method: String, path: String, body: String): String {
        val prefs = Prefs(ctx)
        if (!prefs.hasServer) throw java.io.IOException("сервер не настроен")
        return request(prefs.supabaseUrl.trim().trimEnd('/'), prefs.supabaseKey.trim(), method, path, body, "return=minimal")
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
            if (code !in 200..299) throw HttpFail(code, "HTTP $code $path: ${txt.take(160)}")
            return txt.ifBlank { "[]" }
        } finally {
            c.disconnect()
        }
    }
}
