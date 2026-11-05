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
    val module: String,        // название модуля приписки
    val photo: String?,        // имя файла в локальном кэше
    val scannerMode: String? = null,   // medic | tech | sci; пусто — сканер ему недоступен
    val moduleId: String? = null,      // id модуля приписки — для «доступно в модулях»
    val canChannels: Boolean = false,  // может создавать каналы связи
    val hacker: Boolean = false,       // хакер: может создать скрытый канал
    val channelPrice: Int = 0,         // цена канала в кристаллах
)

/* ---------------- контент «Терминала колониста» (content_items) ---------------- */
data class Block(val type: String, val md: String, val url: String?, val urls: List<String>, val caption: String, val style: String)
data class Choice(val label: String, val next: String)
data class Variant(val chars: List<String>, val mods: List<String>, val body: String, val blocks: List<Block>, val choices: List<Choice>?,
                   val id: String = "", val price: Int = 0)

class ContentItem(val raw: JSONObject) {
    val id: String = raw.optString("id")
    val code: String = raw.optString("trigger_code")
    val type: String = raw.optString("type", "text").ifBlank { "text" }
    val body: String = if (raw.isNull("body")) "" else raw.optString("body")
    val playback: String = raw.optString("playback", "unlimited")
    val price: Int = raw.optInt("price", 0)            // цена в кристаллах (0 — бесплатно)
    val moduleScope: List<String> = strs(raw.optJSONArray("module_scope"))
    val charScope: List<String> = strs(raw.optJSONArray("character_scope"))
    val unlockType: String = raw.optJSONObject("unlock_condition")?.optString("type")?.ifBlank { null } ?: "always"
    val timerMinutes: Double = raw.optJSONObject("unlock_condition")?.optDouble("timerMinutes", 0.0) ?: 0.0
    val choices: List<Choice> = choicesOf(raw.optJSONArray("choice_options"))
    val variants: List<Variant> = (raw.optJSONArray("variants") ?: JSONArray()).let { a ->
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { v ->
            Variant(strs(v.optJSONArray("character_ids")), strs(v.optJSONArray("module_ids")), v.optString("body"),
                blocksOf(v.optJSONArray("blocks")), if (v.has("choice_options") && !v.isNull("choice_options")) choicesOf(v.optJSONArray("choice_options")) else null,
                v.optString("id", ""), v.optInt("price", 0))
        }
    }

    /** Блоки страницы; у старых объектов — картинка/медиа + текст. */
    fun blocks(): List<Block> {
        val b = blocksOf(raw.optJSONArray("blocks"))
        if (b.isNotEmpty()) return b
        val out = ArrayList<Block>()
        val media = if (raw.isNull("media_url")) "" else raw.optString("media_url")
        if (media.isNotBlank() && type in listOf("image", "video", "audio")) out.add(Block(type, "", media, emptyList(), "", ""))
        if (body.isNotBlank()) out.add(Block("text", body, null, emptyList(), "", ""))
        return out
    }

    /** Все ссылки на медиафайлы объекта (для скачивания заранее). */
    fun mediaUrls(): List<String> {
        val all = ArrayList<Block>(blocks())
        variants.forEach { all.addAll(it.blocks) }
        return all.flatMap { listOfNotNull(it.url) + it.urls }.filter { it.isNotBlank() }.distinct()
    }

    companion object {
        fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        fun choicesOf(a: JSONArray?): List<Choice> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { Choice(it.optString("label"), it.optString("nextContentId")) }.filter { it.label.isNotBlank() && it.next.isNotBlank() }
        fun blocksOf(a: JSONArray?): List<Block> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Block(it.optString("type", "text"), it.optString("md"), it.optString("url").ifBlank { null }, strs(it.optJSONArray("urls")),
                it.optString("caption"), it.optString("style"))
        }
    }
}

/** Прогресс персонажа по объекту (как character_progress на сервере). */
data class Prog(val count: Int, val firstAt: Long)

sealed class Lookup {
    /** person — если код метки одновременно код бейджа персонажа. */
    data class Found(val entry: Entry, val code: String, val person: Person? = null) : Lookup()
    /** Бейдж персонажа, для которого у этого профиля нет отдельной записи. */
    data class Identified(val person: Person, val code: String) : Lookup()
    /** Объект контента «Терминала колониста». */
    data class Content(val item: ContentItem, val code: String) : Lookup()
    data class WrongScanner(val code: String) : Lookup()
    data class Unknown(val code: String) : Lookup()
}

object Codes {
    /** Нормализация: регистр, пробелы, двоеточия и дефисы не важны — "04:a2-b3" == "04A2B3". */
    fun norm(s: String): String =
        s.trim().uppercase().filter { !it.isWhitespace() && it != ':' && it != '-' }

    private val TOKEN = Regex("[A-Za-z0-9]{4,12}")

    /** Варианты кода из прочитанного текста: весь текст, затем отдельные «слова» вида F3VP7T. */
    fun candidates(texts: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        texts.forEach { s ->
            norm(s).takeIf { it.isNotEmpty() }?.let { out.add(it) }
            TOKEN.findAll(s).forEach { out.add(it.value.uppercase()) }
        }
        return out.toList()
    }
}

class Database(
    val modes: List<Mode>,
    val entries: List<Entry>,
    val people: List<Person>,
    val updatedAt: Long,
    val fromServer: Boolean,
    val contents: List<ContentItem> = emptyList(),
    val media: Map<String, String> = emptyMap(),          // ссылка → локальный файл
    val progress: Map<String, Prog> = emptyMap(),          // "персонаж|объект" → прогресс на момент загрузки
    val paid: Set<String> = emptySet(),                    // "персонаж|объект" — оплачено кристаллами
    val crystals: Set<String> = emptySet(),                // коды кристаллов (чтобы узнать кристалл вне оплаты)
    val modules: Map<String, String> = emptyMap(),          // модули колонии: id → название (для создания канала)
    val masterModules: Set<String> = emptySet(),            // мастерские терминалы («Центр управления»)
) {
    private val index = HashMap<String, Entry>()
    private val knownCodes = HashSet<String>()
    private val peopleIndex = HashMap<String, Person>()
    private val contentByCode = HashMap<String, ContentItem>()
    private val contentById = HashMap<String, ContentItem>()

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
        for (ci in contents) {
            contentById[ci.id] = ci
            Codes.norm(ci.code).takeIf { it.isNotEmpty() }?.let { contentByCode[it] = ci }
        }
    }

    fun mode(id: String?): Mode? = modes.firstOrNull { it.id == id }
    fun person(id: String?): Person? = people.firstOrNull { it.id == id }
    fun personByCode(code: String): Person? = peopleIndex[Codes.norm(code)]
    fun content(id: String?): ContentItem? = id?.let { contentById[it] }
    fun local(url: String?): String? = url?.let { media[it] }

    /**
     * candidates — коды, считанные с метки или QR, в порядке приоритета.
     * Сначала контент терминала, затем записи меток сканера, затем бейджи персонажей.
     */
    fun find(candidates: List<String>, modeId: String): Lookup {
        val norm = candidates.map { Codes.norm(it) }.filter { it.isNotEmpty() }
        for (c in norm) contentByCode[c]?.let { return Lookup.Content(it, c) }
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
                    .put("scanner_mode", it.scannerMode ?: JSONObject.NULL).put("module_id", it.moduleId ?: JSONObject.NULL)
                    .put("can_channels", it.canChannels).put("hacker", it.hacker).put("channel_price", it.channelPrice)
            )
        }
        val ca = JSONArray(); contents.forEach { ca.put(it.raw) }
        val md = JSONObject(); media.forEach { (k, v) -> md.put(k, v) }
        val pr = JSONObject(); progress.forEach { (k, v) -> pr.put(k, JSONObject().put("n", v.count).put("f", v.firstAt)) }
        return JSONObject().put("updated_at", updatedAt).put("from_server", fromServer).put("modes", m).put("tags", t).put("people", pp)
            .put("contents", ca).put("media", md).put("progress", pr)
            .put("paid", JSONArray(paid.toList())).put("crystals", JSONArray(crystals.toList()))
            .put("modules", JSONObject().also { o -> modules.forEach { (k, v) -> o.put(k, v) } })
            .put("master_modules", JSONArray(masterModules.toList()))
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
                        scannerMode = if (x.isNull("scanner_mode")) null else x.optString("scanner_mode").ifBlank { null },
                        moduleId = if (x.isNull("module_id")) null else x.optString("module_id").ifBlank { null },
                        canChannels = x.optBoolean("can_channels", false),
                        hacker = x.optBoolean("hacker", false),
                        channelPrice = x.optInt("channel_price", 0),
                    )
                )
            }
            val contents = ArrayList<ContentItem>()
            val ca = o.optJSONArray("contents") ?: JSONArray()
            for (i in 0 until ca.length()) ca.optJSONObject(i)?.let { contents.add(ContentItem(it)) }
            val media = HashMap<String, String>()
            o.optJSONObject("media")?.let { md -> md.keys().forEach { k -> media[k] = md.optString(k) } }
            val progress = HashMap<String, Prog>()
            o.optJSONObject("progress")?.let { pr -> pr.keys().forEach { k -> pr.optJSONObject(k)?.let { progress[k] = Prog(it.optInt("n"), it.optLong("f")) } } }
            val paid = o.optJSONArray("paid")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val crystals = o.optJSONArray("crystals")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val mods = HashMap<String, String>()
            o.optJSONObject("modules")?.let { mo -> mo.keys().forEach { k -> mods[k] = mo.optString(k) } }
            val masters = o.optJSONArray("master_modules")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            return Database(modes, entries, people, o.optLong("updated_at", 0L), o.optBoolean("from_server", false), contents, media, progress, paid, crystals, mods, masters)
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
