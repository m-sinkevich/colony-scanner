package com.paradise.scanner

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * Markdown, как в «Терминале колониста»: заголовки #/##/###, **жирный**, *курсив*, ~~зачёркнутый~~,
 * `моноширинный`, [ссылка](https://…) (показывается только текст), > цитата, списки - и 1., --- линия.
 */
object Md {

    private const val ESC_BASE = 0xE000
    private const val ESCAPABLE = "-.!#*_()[]>+`\\"

    fun render(src: String, accent: Int, indentPx: Int): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val lines = src.replace("\r", "").split('\n')
        var blank = false
        var inList = false
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) {
                if (out.isNotEmpty()) blank = true
                inList = false
                continue
            }
            fun newBlock(gap: Boolean) {
                if (out.isEmpty()) return
                out.append('\n')
                if (gap) out.append('\n')
            }
            if (HR.matches(t)) {
                newBlock(true); blank = false; inList = false
                val s = out.length
                out.append("──────────────────")
                out.setSpan(ForegroundColorSpan(Pal.withAlpha(accent, 140)), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            val h = Regex("^(#{1,3})\\s+(.*)$").find(t)
            if (h != null) {
                newBlock(true); blank = false; inList = false
                val s = out.length
                inline(out, h.groupValues[2], accent)
                val size = when (h.groupValues[1].length) { 1 -> 1.3f; 2 -> 1.15f; else -> 1.05f }
                out.setSpan(RelativeSizeSpan(size), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(accent), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            val q = Regex("^>\\s?(.*)$").find(t)
            if (q != null) {
                newBlock(blank); blank = false; inList = false
                val s = out.length
                inline(out, q.groupValues[1], accent)
                out.setSpan(QuoteSpan(accent), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(Pal.DIM), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            val ol = Regex("^(\\d+)[.)]\\s+(.*)$").find(t)
            val ul = if (ol == null) Regex("^[-*•+]\\s+(.*)$").find(t) else null
            if (ol != null || ul != null) {
                newBlock(blank || (!inList && out.isNotEmpty())); blank = false; inList = true
                val s = out.length
                val marker = if (ol != null) "${ol.groupValues[1]}. " else "▸ "
                out.append(marker)
                out.setSpan(ForegroundColorSpan(accent), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                inline(out, if (ol != null) ol.groupValues[2] else ul!!.groupValues[1], accent)
                out.setSpan(LeadingMarginSpan.Standard(0, indentPx), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            if (inList && raw.startsWith("  ")) {
                // продолжение пункта списка
                out.append('\n')
                val s = out.length
                inline(out, t, accent)
                out.setSpan(LeadingMarginSpan.Standard(indentPx), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            // обычный абзац: переносы строк внутри абзаца сохраняются
            newBlock(blank || inList); blank = false; inList = false
            inline(out, t, accent)
        }
        return out
    }

    private val HR = Regex("^(-{3,}|\\*{3,}|_{3,})$")

    private val INLINE = Regex(
        "\\*\\*(.+?)\\*\\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\\[([^\\]]+)]\\((https?:[^)\\s]+)\\)" +
            "|(?<![\\w*])\\*([^*\\n]+)\\*|(?<![\\w_])_([^_\\n]+)_"
    )

    private fun inline(out: SpannableStringBuilder, text: String, accent: Int) {
        // \* \_ и т.п. — экранированные символы показываются как есть
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
                sb.append((ESC_BASE + ESCAPABLE.indexOf(text[i + 1])).toChar()); i += 2
            } else {
                sb.append(c); i++
            }
        }
        val s = sb.toString()
        var pos = 0
        for (m in INLINE.findAll(s)) {
            append(out, s.substring(pos, m.range.first))
            val g = m.groupValues
            val start = out.length
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> {
                    append(out, g[1].ifEmpty { g[2] })
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[3].isNotEmpty() -> {
                    append(out, g[3])
                    out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[4].isNotEmpty() -> {
                    append(out, g[4])
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[5].isNotEmpty() -> {
                    append(out, g[5])
                    out.setSpan(UnderlineSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(accent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[7].isNotEmpty() || g[8].isNotEmpty() -> {
                    append(out, g[7].ifEmpty { g[8] })
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            pos = m.range.last + 1
        }
        append(out, s.substring(pos))
    }

    private fun append(out: SpannableStringBuilder, s: String) {
        for (c in s) {
            val k = c.code - ESC_BASE
            out.append(if (k in ESCAPABLE.indices) ESCAPABLE[k] else c)
        }
    }
}
