package com.paradise.scanner

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
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
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            var msg: NdefMessage? = ndef.cachedNdefMessage
            if (msg == null) {
                try {
                    ndef.connect()
                    msg = ndef.ndefMessage
                } catch (e: Exception) {
                } finally {
                    try {
                        ndef.close()
                    } catch (e: Exception) {
                    }
                }
            }
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
        }
        return TagRead(uid, texts.distinct())
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
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 300) }
        try {
            adapter.enableReaderMode(activity, { tag ->
                val read = try {
                    read(tag)
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
