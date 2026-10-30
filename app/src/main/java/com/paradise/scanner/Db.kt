package com.paradise.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** Тип сканера (роль игрока): медицинский, технический и т.п. */
data class Mode(
    val id: String,
    val name: String,
    val color: Int,
    val colorHex: String,
    val subtitle: String,
)

/** Одна запись базы: что показать для кода метки при сканировании данным типом сканера. */
data class Entry(
    val code: String,      // код метки (как ввёл мастер)
    val mode: String,      // id сканера, "*" — для любого
    val title: String,
    val body: String,
    val image: String?,    // имя файла в локальном кэше или "asset:имя"
)

/** Персонаж игры (таблица characters «Терминала колониста»): опознаётся по коду бейджа. */
data class Person(
    val id: String,
    val name: String,
    val codes: List<String>,   // код бейджа (qr_code) и номер бейджа (badge_id)
    val tagline: String,
    val module: String,        // модуль приписки
    val photo: String?,        // имя файла в локальном кэше
)

sealed class Lookup {
    /** person — если код метки одновременно код бейджа персонажа. */
    data class Found(val entry: Entry, val code: String, val person: Person? = null) : Lookup()
    /** Бейдж персонажа, для которого у этого профиля нет отдельной записи. */
    data class Identified(val person: Person, val code: String) : Lookup()
    data class WrongScanner(val code: String) : Lookup()
    data class Unknown(val code: String) : Lookup()
}

object Codes {
    /** Нормализация: регистр, пробелы, двоеточия и дефисы не важны — "04:a2-b3" == "04A2B3". */
    fun norm(s: String): String =
        s.trim().uppercase().filter { !it.isWhitespace() && it != ':' && it != '-' }
}

class Database(
    val modes: List<Mode>,
    val entries: List<Entry>,
    val people: List<Person>,
    val updatedAt: Long,
    val fromServer: Boolean,
) {
    private val index = HashMap<String, Entry>()
    private val knownCodes = HashSet<String>()
    private val peopleIndex = HashMap<String, Person>()

    init {
        for (p in people) for (code in p.codes) {
            val c = Codes.norm(code)
            if (c.isNotEmpty()) peopleIndex[c] = p
        }
        for (e in entries) {
            val c = Codes.norm(e.code)
            if (c.isEmpty()) continue
            index["$c|${e.mode}"] = e
            knownCodes.add(c)
        }
    }

    fun mode(id: String?): Mode? = modes.firstOrNull { it.id == id }

    /**
     * candidates — коды, считанные с метки, в порядке приоритета
     * (сначала записанный на метку текст, потом заводской UID).
     */
    fun find(candidates: List<String>, modeId: String): Lookup {
        val norm = candidates.map { Codes.norm(it) }.filter { it.isNotEmpty() }
        for (c in norm) {
            val e = index["$c|$modeId"] ?: index["$c|*"]
            if (e != null) return Lookup.Found(e, c, peopleIndex[c])
        }
        for (c in norm) {
            peopleIndex[c]?.let { return Lookup.Identified(it, c) }
        }
        for (c in norm) {
            if (c in knownCodes) return Lookup.WrongScanner(c)
        }
        return Lookup.Unknown(norm.firstOrNull() ?: "—")
    }

    fun toJson(): JSONObject {
        val m = JSONArray()
        modes.forEach {
            m.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.colorHex).put("subtitle", it.subtitle))
        }
        val t = JSONArray()
        entries.forEach {
            t.put(
                JSONObject().put("code", it.code).put("scanner", it.mode).put("title", it.title)
                    .put("body", it.body).put("image", it.image ?: JSONObject.NULL)
            )
        }
        val pp = JSONArray()
        people.forEach {
            pp.put(
                JSONObject().put("id", it.id).put("name", it.name).put("codes", JSONArray(it.codes))
                    .put("tagline", it.tagline).put("module", it.module).put("photo", it.photo ?: JSONObject.NULL)
            )
        }
        return JSONObject().put("updated_at", updatedAt).put("from_server", fromServer).put("modes", m).put("tags", t).put("people", pp)
    }

    companion object {
        fun parse(json: String): Database {
            val o = JSONObject(json)
            val modes = ArrayList<Mode>()
            val ma = o.optJSONArray("modes") ?: JSONArray()
            for (i in 0 until ma.length()) {
                val x = ma.getJSONObject(i)
                val id = x.optString("id").trim()
                if (id.isEmpty()) continue
                val hex = x.optString("color", "#3DFFB0")
                modes.add(Mode(id, x.optString("name", id), Pal.parse(hex), hex, x.optString("subtitle", "")))
            }
            val entries = ArrayList<Entry>()
            val ta = o.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until ta.length()) {
                val x = ta.getJSONObject(i)
                val img = if (x.isNull("image")) null else x.optString("image").ifBlank { null }
                entries.add(
                    Entry(
                        code = x.optString("code"),
                        mode = x.optString("scanner", "*").trim().ifEmpty { "*" },
                        title = x.optString("title"),
                        body = x.optString("body"),
                        image = img,
                    )
                )
            }
            val people = ArrayList<Person>()
            val pa = o.optJSONArray("people") ?: JSONArray()
            for (i in 0 until pa.length()) {
                val x = pa.getJSONObject(i)
                val ca = x.optJSONArray("codes") ?: JSONArray()
                val codes = (0 until ca.length()).map { ca.optString(it) }.filter { it.isNotBlank() }
                people.add(
                    Person(
                        id = x.optString("id"),
                        name = x.optString("name"),
                        codes = codes,
                        tagline = x.optString("tagline"),
                        module = x.optString("module"),
                        photo = if (x.isNull("photo")) null else x.optString("photo").ifBlank { null },
                    )
                )
            }
            return Database(modes, entries, people, o.optLong("updated_at", 0L), o.optBoolean("from_server", false))
        }
    }
}

/** Хранилище: база лежит в памяти телефона (files/db.json), картинки — в files/img. */
object Repo {
    @Volatile
    private var cached: Database? = null

    fun dbFile(ctx: Context) = File(ctx.filesDir, "db.json")
    fun imgDir(ctx: Context) = File(ctx.filesDir, "img").apply { mkdirs() }

    fun get(ctx: Context): Database {
        cached?.let { return it }
        return load(ctx).also { cached = it }
    }

    fun reload(ctx: Context): Database = load(ctx).also { cached = it }

    private fun load(ctx: Context): Database {
        val f = dbFile(ctx)
        if (f.exists()) {
            try {
                return Database.parse(f.readText())
            } catch (e: Exception) {
                // повреждённый файл — откатываемся на встроенную базу
            }
        }
        return builtin(ctx)
    }

    fun builtin(ctx: Context): Database =
        Database.parse(ctx.assets.open("db.json").bufferedReader().use { it.readText() })

    /** Загрузка картинки с уменьшением под ширину экрана, чтобы не упасть по памяти. */
    fun loadImage(ctx: Context, ref: String?, maxWidth: Int): Bitmap? {
        if (ref.isNullOrBlank()) return null
        val open: () -> InputStream? = {
            try {
                if (ref.startsWith("asset:")) ctx.assets.open(ref.removePrefix("asset:"))
                else File(imgDir(ctx), ref).takeIf { it.exists() }?.inputStream()
            } catch (e: Exception) {
                null
            }
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // при inJustDecodeBounds decodeStream всегда возвращает null — нас интересуют только размеры
            val s = open() ?: return null
            s.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            open()?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Throwable) {
            null
        }
    }
}
