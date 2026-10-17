package com.paradise.scanner

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.os.Bundle

/** Что удалось прочитать с метки. */
data class TagRead(val uid: String, val texts: List<String>) {
    /** Порядок поиска: сначала то, что мастер записал на метку, потом заводской UID. */
    val candidates: List<String> get() = texts + uid

    fun describe(): String = buildString {
        append("UID: ").append(uid)
        texts.forEach { append("\nТекст: ").append(it) }
    }
}

object NfcRead {

    fun read(tag: Tag): TagRead {
        val uid = (tag.id ?: ByteArray(0)).joinToString("") { "%02X".format(it) }
        val texts = ArrayList<String>()
        // 1) метку проверила система (обычный режим) — сообщение уже прочитано
        var msg: NdefMessage? = Ndef.get(tag)?.cachedNdefMessage
        // 2) быстрый режим: система NDEF не читала — читаем сами, коротко и без повторов
        if (msg == null) msg = try { readUltralight(tag) } catch (e: Exception) { null }
        if (msg == null) msg = try { readNdef(tag) } catch (e: Exception) { null }
        msg?.records?.forEach { r ->
            parse(r)?.let { s ->
                val t = s.trim()
                if (t.isNotEmpty()) {
                    texts.add(t)
                    // для ссылок вида https://…/MED-01 или …?code=MED-01 — ещё и хвост
                    if (t.contains("://")) {
                        val q = t.substringAfter("code=", "").substringBefore('&')
                        if (q.isNotEmpty()) texts.add(q)
                        val tail = t.substringBefore('?').trimEnd('/').substringAfterLast('/')
                        if (tail.isNotEmpty()) texts.add(tail)
                    }
                }
            }
        }
        return TagRead(uid, texts.distinct())
    }

    private fun readNdef(tag: Tag): NdefMessage? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            ndef.ndefMessage
        } finally {
            try { ndef.close() } catch (e: Exception) {}
        }
    }

    /**
     * Прямое чтение NTAG/Ultralight (самые распространённые наклейки) по страницам.
     * Нужно для быстрого режима: метка могла уйти из поля раньше, чем система её дочитает.
     */
    private fun readUltralight(tag: Tag): NdefMessage? {
        val mu = MifareUltralight.get(tag) ?: return null
        try {
            mu.connect()
            mu.timeout = 250
            val buf = java.io.ByteArrayOutputStream()
            var page = 4
            var reads = 0
            while (reads < 60) {
                val chunk = try { mu.readPages(page) } catch (e: Exception) { null } ?: break
                buf.write(chunk)
                page += 4
                reads++
                val b = buf.toByteArray()
                val tlv = findNdefTlv(b) ?: continue
                if (tlv.first < 0) return null // метка пустая
                val (start, len) = tlv
                if (len == 0) return null
                if (b.size >= start + len) return NdefMessage(b.copyOfRange(start, start + len))
            }
            return null
        } finally {
            try { mu.close() } catch (e: Exception) {}
        }
    }

    /** Возвращает (начало, длина) NDEF-сообщения; null — данных пока мало; (-1,0) — NDEF нет. */
    private fun findNdefTlv(b: ByteArray): Pair<Int, Int>? {
        var i = 0
        while (i < b.size) {
            val t = b[i].toInt() and 0xFF
            if (t == 0x00) { i++; continue }
            if (t == 0xFE) return -1 to 0
            if (i + 1 >= b.size) return null
            var len = b[i + 1].toInt() and 0xFF
            var start = i + 2
            if (len == 0xFF) {
                if (i + 3 >= b.size) return null
                len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                start = i + 4
            }
            if (t == 0x03) return start to len
            i = start + len
        }
        return null
    }

    private fun parse(r: NdefRecord): String? = try {
        when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                val p = r.payload
                if (p.isEmpty()) null
                else {
                    val status = p[0].toInt()
                    val langLen = status and 0x3F
                    val charset = if ((status and 0x80) != 0) Charsets.UTF_16 else Charsets.UTF_8
                    val start = 1 + langLen
                    if (start > p.size) null else String(p, start, p.size - start, charset)
                }
            }
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) -> r.toUri()?.toString()
            r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> String(r.type, Charsets.UTF_8)
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> String(r.payload, Charsets.UTF_8)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Включить чтение меток, пока активность на экране. Системный звук NFC выключаем — у нас свой. */
    fun enable(activity: Activity, adapter: NfcAdapter?, onTag: (TagRead) -> Unit) {
        if (adapter == null || !adapter.isEnabled) return
        val prefs = Prefs(activity)
        // Чувствительность: опрашиваем только тип A (NTAG, Mifare — почти все наклейки) — цикл
        // опроса короче, и метка ловится даже при быстром проносе. Проверку NDEF системой
        // пропускаем: событие приходит сразу, как только получен UID, а текст дочитываем сами.
        var flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        if (prefs.allTagTypes) {
            flags = flags or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        }
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000) }
        try {
            adapter.enableReaderMode(activity, { tag ->
                val read = try {
                    UidCache.apply(activity, read(tag))
                } catch (e: Exception) {
                    null
                }
                if (read != null) activity.runOnUiThread { onTag(read) }
            }, flags, extras)
        } catch (e: Exception) {
        }
    }

    fun disable(activity: Activity, adapter: NfcAdapter?) {
        try {
            adapter?.disableReaderMode(activity)
        } catch (e: Exception) {
        }
    }
}

/**
 * Запоминает "UID → текст" для меток, которые хоть раз прочитались полностью.
 * Если в следующий раз метку пронесли слишком быстро и текст не успел считаться,
 * код всё равно найдётся по UID.
 */
object UidCache {
    fun apply(ctx: android.content.Context, r: TagRead): TagRead {
        val p = ctx.getSharedPreferences("uid_cache", android.content.Context.MODE_PRIVATE)
        if (r.uid.isEmpty()) return r
        return if (r.texts.isNotEmpty()) {
            p.edit().putString(r.uid, r.texts.joinToString("\u0001")).apply()
            r
        } else {
            val cached = p.getString(r.uid, null) ?: return r
            r.copy(texts = cached.split("\u0001").filter { it.isNotEmpty() })
        }
    }
}
