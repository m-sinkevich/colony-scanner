package com.paradise.scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Скачивание базы из Supabase в память телефона.
 * Таблицы: scan_modes (типы сканеров, необязательна) и scan_tags (записи меток).
 * Картинки скачиваются заранее, поэтому на игре интернет не нужен.
 */
object Sync {
    const val BUCKET = "content-media"
    private const val PAGE = 1000

    /** settings — список применённых настроек из терминала мастера (пусто, если не заданы). */
    class Report(val modes: Int, val tags: Int, val images: Int, val imageErrors: Int, val settings: List<String> = emptyList())

    /** Запуск в фоне; onProgress и onDone вызываются в главном потоке. */
    fun start(
        ctx: Context,
        onProgress: (String) -> Unit,
        onDone: (Report?, String?) -> Unit,
    ) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val r = doSync(app) { msg -> main.post { onProgress(msg) } }
                main.post { onDone(r, null) }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                main.post { onDone(null, msg) }
            }
        }.start()
    }

    private fun doSync(ctx: Context, progress: (String) -> Unit): Report {
        val prefs = Prefs(ctx)
        val base = prefs.supabaseUrl.trim().trimEnd('/')
        val key = prefs.supabaseKey.trim()
        if (base.isEmpty() || key.isEmpty()) throw IOException("Не задан адрес Supabase или ключ (служебное меню)")

        val old = Repo.get(ctx)

        progress("Загрузка типов сканеров…")
        val modes: List<Mode> = try {
            val arr = fetchAll(base, key, "scan_modes", "sort.asc,id.asc")
            val list = ArrayList<Mode>()
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i)
                if (x.has("active") && !x.isNull("active") && !x.optBoolean("active", true)) continue
                val id = x.optString("id").trim()
                if (id.isEmpty()) continue
                val hex = x.optString("color").ifBlank { "#3DFFB0" }
                list.add(Mode(id, x.optString("name").ifBlank { id }, Pal.parse(hex), hex, x.optString("subtitle", "")))
            }
            if (list.isEmpty()) old.modes else list
        } catch (e: HttpError) {
            // таблицы scan_modes может не быть — тогда оставляем текущий список ролей
            if (e.code == 404 || e.code == 400) old.modes else throw e
        }

        progress("Загрузка меток…")
        val rows = fetchAll(base, key, "scan_tags", "code.asc")
        progress("Получено записей: ${rows.length()}")

        val dir = Repo.imgDir(ctx)
        val usedFiles = HashSet<String>()
        val entries = ArrayList<Entry>()
        var imgOk = 0
        var imgErr = 0
        val downloaded = HashMap<String, String?>() // url -> локальное имя (одна картинка на несколько меток)

        for (i in 0 until rows.length()) {
            val x = rows.getJSONObject(i)
            if (x.has("active") && !x.isNull("active") && !x.optBoolean("active", true)) continue
            val code = x.optString("code").trim()
            if (code.isEmpty()) continue
            val scanner = str(x, "scanner", "mode").ifBlank { "*" }
            val rawImg = str(x, "image_url", "image")

            var local: String? = null
            if (rawImg.isNotBlank()) {
                val url = imageUrl(base, rawImg)
                if (downloaded.containsKey(url)) {
                    local = downloaded[url]
                } else {
                    progress("Картинка ${imgOk + imgErr + 1}: ${rawImg.substringAfterLast('/')}")
                    local = try {
                        download(url, key, dir).also { imgOk++ }
                    } catch (e: Exception) {
                        imgErr++
                        // нет сети к картинке — пробуем оставить ранее скачанную
                        fileNameFor(url).takeIf { File(dir, it).exists() }
                    }
                    downloaded[url] = local
                }
                local?.let { usedFiles.add(it) }
            }

            entries.add(
                Entry(
                    code = code,
                    mode = scanner.trim(),
                    title = x.optString("title").takeIf { !x.isNull("title") } ?: "",
                    body = str(x, "body", "text"),
                    image = local,
                )
            )
        }

        val db = Database(modes, entries, System.currentTimeMillis(), true)
        val tmp = File(ctx.filesDir, "db.json.tmp")
        tmp.writeText(db.toJson().toString())
        if (!tmp.renameTo(Repo.dbFile(ctx))) {
            Repo.dbFile(ctx).writeText(tmp.readText())
            tmp.delete()
        }
        Repo.reload(ctx)

        // удаляем картинки, на которые больше никто не ссылается
        dir.listFiles()?.forEach { if (it.name !in usedFiles) it.delete() }

        progress("Настройки сканера…")
        val applied = applySettings(base, key, prefs)

        return Report(modes.size, entries.size, imgOk, imgErr, applied)
    }

    /**
     * Настройки из терминала мастера (таблица scan_settings, одна строка id = 1).
     * Пустое поле (null) — настройка не задана централизованно, на телефоне остаётся своё значение.
     * Заданные значения заменяют выставленные вручную в служебном меню.
     */
    private fun applySettings(base: String, key: String, prefs: Prefs): List<String> {
        val arr = try {
            fetchAll(base, key, "scan_settings", "id.asc")
        } catch (e: HttpError) {
            if (e.code == 404 || e.code == 400) return emptyList() else throw e
        }
        if (arr.length() == 0) return emptyList()
        val x = arr.getJSONObject(0)
        val out = ArrayList<String>()
        if (x.has("session_seconds") && !x.isNull("session_seconds")) {
            prefs.sessionSeconds = x.optInt("session_seconds", prefs.sessionSeconds)
            out.add(if (prefs.sessionSeconds == 0) "сессия без ограничения" else "сессия ${prefs.sessionSeconds} с")
        }
        if (x.has("auto_scan") && !x.isNull("auto_scan")) {
            prefs.autoScan = x.optBoolean("auto_scan", prefs.autoScan)
            out.add(if (prefs.autoScan) "метка без кнопки срабатывает" else "метка без кнопки игнорируется")
        }
        if (x.has("all_tag_types") && !x.isNull("all_tag_types")) {
            prefs.allTagTypes = x.optBoolean("all_tag_types", prefs.allTagTypes)
            out.add(if (prefs.allTagTypes) "все типы меток" else "только NTAG/MIFARE")
        }
        val pin = if (x.has("master_pin") && !x.isNull("master_pin")) x.optString("master_pin").trim() else ""
        if (pin.length >= 4 && pin.all { it.isDigit() }) {
            prefs.pin = pin
            out.add("PIN мастера обновлён")
        }
        return out
    }

    private fun str(x: JSONObject, vararg names: String): String {
        for (n in names) {
            if (x.has(n) && !x.isNull(n)) {
                val v = x.optString(n)
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    /** Полная ссылка или путь внутри публичного бакета scan-images. */
    private fun imageUrl(base: String, raw: String): String {
        val r = raw.trim()
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        val path = r.trimStart('/').removePrefix("$BUCKET/")
        val enc = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$base/storage/v1/object/public/$BUCKET/$enc"
    }

    private fun fileNameFor(url: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val hash = md.joinToString("") { "%02x".format(it) }.take(20)
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "webp", "gif") } ?: "img"
        return "$hash.$ext"
    }

    private fun download(url: String, key: String, dir: File): String {
        val name = fileNameFor(url)
        val c = open(url, key)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, "HTTP $code при загрузке картинки")
            val tmp = File(dir, "$name.part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val target = File(dir, name)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Не удалось сохранить картинку")
            return name
        } finally {
            c.disconnect()
        }
    }

    class HttpError(val code: Int, msg: String) : IOException(msg)

    private fun open(url: String, key: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("apikey", key)
        // новые ключи Supabase (sb_publishable_…) не являются JWT — для них Bearer не нужен
        if (key.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $key")
        return c
    }

    private fun fetchAll(base: String, key: String, table: String, order: String): JSONArray {
        val all = JSONArray()
        var offset = 0
        while (true) {
            val url = "$base/rest/v1/$table?select=*&order=$order&limit=$PAGE&offset=$offset"
            val c = open(url, key)
            c.setRequestProperty("Accept", "application/json")
            val page: JSONArray
            try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val hint = when (code) {
                        401, 403 -> " (проверьте ключ и политику чтения RLS)"
                        404 -> " (нет таблицы $table?)"
                        else -> ""
                    }
                    throw HttpError(code, "HTTP $code для $table$hint: ${body.take(160)}")
                }
                page = JSONArray(body)
            } finally {
                c.disconnect()
            }
            for (i in 0 until page.length()) all.put(page.get(i))
            if (page.length() < PAGE) break
            offset += PAGE
        }
        return all
    }
}
