package com.fintracker.app.domain.statement

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * Minimal PDF text extractor for bank e-passbook PDFs (FlateDecode streams + Tj/TJ operators).
 * Not a full PDF engine — enough for typical Canara / Indian bank text statements.
 */
object PdfTextExtractor {

    fun extractText(pdfBytes: ByteArray): String {
        val raw = String(pdfBytes, Charsets.ISO_8859_1)
        val streams = Regex(
            """stream\r?\n([\s\S]*?)endstream""",
            RegexOption.IGNORE_CASE
        ).findAll(raw)
        val out = StringBuilder()
        for (match in streams) {
            val payload = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
            val decoded = decodeStream(raw, match.range.first, payload) ?: continue
            out.append(extractOperators(decoded))
            out.append('\n')
        }
        val text = out.toString()
        if (text.replace(Regex("\\s+"), "").length > 40) return cleanup(text)
        // Fallback: sometimes text is stored uncompressed / latin1 in the file body
        return cleanup(extractOperators(raw))
    }

    private fun decodeStream(fullPdf: String, streamPos: Int, payload: ByteArray): String? {
        val dictStart = fullPdf.lastIndexOf("<<", streamPos)
        val dict = if (dictStart >= 0) fullPdf.substring(dictStart, streamPos) else ""
        val bytes = if (dict.contains("/FlateDecode", ignoreCase = true)) {
            runCatching {
                InflaterInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
            }.getOrNull() ?: return null
        } else {
            payload
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun extractOperators(content: String): String {
        val sb = StringBuilder()
        // Literal strings: (Hello) Tj   or   [(H) 3 (ello)] TJ
        val tj = Regex("""\((?:\\.|[^\\)])*\)\s*Tj""")
        val tjArr = Regex("""\[(?:[^\[\]]|\[[^\[\]]*])*\]\s*TJ""")
        val quote = Regex("""\((?:\\.|[^\\)])*\)\s*'""")

        fun appendLiteral(lit: String) {
            var i = 0
            val body = lit.removeSurrounding("(", ")")
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val n = body[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '(', ')', '\\' -> sb.append(n)
                        else -> {
                            // octal \ddd
                            val oct = body.substring(i + 1).takeWhile { it in '0'..'7' }.take(3)
                            if (oct.isNotEmpty()) {
                                sb.append(oct.toInt(8).toChar())
                                i += oct.length
                            } else sb.append(n)
                        }
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
        }

        val tokens = mutableListOf<Pair<Int, String>>()
        tj.findAll(content).forEach { tokens += it.range.first to it.value }
        tjArr.findAll(content).forEach { tokens += it.range.first to it.value }
        quote.findAll(content).forEach { tokens += it.range.first to it.value }
        // Treat vertical moves / new lines as separators so passbook rows stay recoverable.
        Regex("""(?:T\*|Td|TD)\b""").findAll(content).forEach { tokens += it.range.first to "\n" }
        tokens.sortedBy { it.first }.forEach { (_, token) ->
            when {
                token == "\n" -> sb.append('\n')
                token.endsWith("Tj") || token.endsWith("'") -> {
                    val lit = Regex("""\((?:\\.|[^\\)])*\)""").find(token)?.value ?: return@forEach
                    appendLiteral(lit)
                    sb.append(' ')
                }
                token.endsWith("TJ") -> {
                    Regex("""\((?:\\.|[^\\)])*\)""").findAll(token).forEach { m ->
                        appendLiteral(m.value)
                    }
                    sb.append(' ')
                }
            }
        }
        return sb.toString()
    }

    private fun cleanup(text: String): String =
        text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
