package com.paradise.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Журнал сканирований этого телефона (хранится только на нём, последние 300 записей). */
object History {
    data class Item(val at: Long, val charId: String, val name: String, val kind: String, val title: String, val contentId: String?, val note: String)

    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    fun add(ctx: Context, p: Person?, kind: String, title: String, contentId: String? = null, note: String = "") = synchronized(lock) {
        val a = try { JSONArray(file(ctx).takeIf { it.exists() }?.readText() ?: "[]") } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("at", System.currentTimeMillis()).put("c", p?.id ?: "").put("n", p?.name ?: "")
            .put("k", kind).put("t", title).put("i", contentId ?: "").put("x", note))
        val out = JSONArray()
        for (i in maxOf(0, a.length() - 300) until a.length()) out.put(a.get(i))
        file(ctx).writeText(out.toString())
    }

    fun all(ctx: Context): List<Item> = synchronized(lock) {
        val a = try { JSONArray(file(ctx).takeIf { it.exists() }?.readText() ?: "[]") } catch (e: Exception) { JSONArray() }
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Item(it.optLong("at"), it.optString("c"), it.optString("n"), it.optString("k"), it.optString("t"), it.optString("i").ifBlank { null }, it.optString("x"))
        }.reversed()
    }

    /** Заголовок объекта для журнала: первая строка первого текста без разметки. */
    fun titleOf(item: ContentItem, p: Person?): String {
        val v = Access.variantFor(item, p)
        val blocks = v?.blocks?.takeIf { it.isNotEmpty() } ?: item.blocks()
        val txt = blocks.firstOrNull { it.type == "text" && it.md.isNotBlank() }?.md ?: v?.body ?: item.body
        val line = txt.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(it) }
            ?.replace(Regex("^#{1,3}\\s+|^>\\s?|^[-*•+]\\s+|^\\d+[.)]\\s+"), "")
            ?.replace(Regex("[*_`~]"), "")?.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        return when {
            !line.isNullOrBlank() -> if (line.length > 70) line.take(68) + "…" else line
            blocks.any { it.type == "image" || it.type == "gallery" } -> "Изображение"
            blocks.any { it.type == "video" } -> "Видеозапись"
            blocks.any { it.type == "audio" } -> "Аудиозапись"
            else -> "Объект"
        }
    }
}
