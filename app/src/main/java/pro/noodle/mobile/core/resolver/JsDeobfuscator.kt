package pro.noodle.mobile.core.resolver

/**
 * Лёгкая деобфускация JS без выполнения кода: многие плееры прячут ссылку на видео в
 * `eval(function(p,a,c,k,e,d){…}('…',62,N,'a|b|c'.split('|'),0,{}))`, в `String.fromCharCode(…)`
 * или в `\xNN` / `\uNNNN`-строках. Результат только дописывается к тексту страницы — майнер потоков
 * ищет в нём ссылки так же, как в обычном HTML.
 */
object JsDeobfuscator {
    private val packed = Regex(
        "\\}\\(\\s*(['\"])((?:\\\\.|(?!\\1).)*)\\1\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(['\"])((?:\\\\.|(?!\\5).)*)\\5\\s*\\.split\\(\\s*['\"]\\|['\"]\\s*\\)",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val fromCharCode = Regex("String\\.fromCharCode\\(\\s*((?:\\d{1,5}\\s*,\\s*){3,}\\d{1,5})\\s*\\)")
    private val escaped = Regex("(?:\\\\x[0-9a-fA-F]{2}|\\\\u[0-9a-fA-F]{4}){6,}")
    private val unescapeCall = Regex("unescape\\(\\s*['\"]((?:%[0-9a-fA-F]{2}|%u[0-9a-fA-F]{4}|[^'\"%]){12,})['\"]\\s*\\)")

    /** Исходный текст + всё, что удалось раскрутить (до трёх уровней вложенности). */
    fun expand(raw: String): String {
        if (raw.length < 40) return raw
        val sb = StringBuilder(raw)
        var layer = raw
        repeat(3) {
            val found = decodeLayer(layer)
            if (found.isBlank()) return sb.toString()
            sb.append('\n').append(found)
            layer = found
        }
        return sb.toString()
    }

    private fun decodeLayer(text: String): String {
        val out = StringBuilder()
        if (text.contains("eval(function(p,a,c,k", ignoreCase = false) || text.contains("function(p,a,c,k,e")) {
            var n = 0
            for (m in packed.findAll(text)) {
                if (n++ >= 8) break
                runCatching { unpack(m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[6]) }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
            }
        }
        if (text.contains("fromCharCode")) {
            for (m in fromCharCode.findAll(text).take(12)) {
                val s = runCatching { m.groupValues[1].split(',').map { it.trim().toInt().toChar() }.joinToString("") }.getOrNull()
                if (!s.isNullOrBlank() && (s.contains("http") || s.contains("m3u8") || s.contains("mp4") || s.contains("{"))) out.append(s).append('\n')
            }
        }
        if (text.contains("\\x") || text.contains("\\u")) {
            for (m in escaped.findAll(text).take(30)) {
                val s = unescapeJs(m.value)
                if (s.contains("http") || s.contains("m3u8") || s.contains("mp4")) out.append(s).append('\n')
            }
        }
        if (text.contains("unescape(")) {
            for (m in unescapeCall.findAll(text).take(10)) {
                val s = runCatching { percentDecode(m.groupValues[1]) }.getOrNull()
                if (!s.isNullOrBlank()) out.append(s).append('\n')
            }
        }
        return out.toString()
    }

    /** Dean Edwards packer: подстановка слов словаря по base-N индексам. */
    internal fun unpack(payloadRaw: String, base: Int, count: Int, dictRaw: String): String {
        val payload = payloadRaw.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
        val dict = dictRaw.split('|')
        val map = HashMap<String, String>(count * 2)
        fun encode(c: Int): String {
            val head = if (c < base) "" else encode(c / base)
            val r = c % base
            return head + (if (r > 35) (r + 29).toChar().toString() else r.toString(36))
        }
        for (i in 0 until minOf(count, dict.size)) if (dict[i].isNotEmpty()) map[encode(i)] = dict[i]
        return Regex("\\b\\w+\\b").replace(payload) { map[it.value] ?: it.value }
    }

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'x' -> if (i + 3 < s.length) { sb.append(s.substring(i + 2, i + 4).toInt(16).toChar()); i += 4; continue }
                    'u' -> if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                }
            }
            sb.append(s[i]); i++
        }
        return sb.toString()
    }

    private fun percentDecode(s: String): String {
        val sb = StringBuilder()
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() { if (bytes.size() > 0) { sb.append(String(bytes.toByteArray(), Charsets.UTF_8)); bytes.reset() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 5 < s.length && s[i + 1] == 'u') { flush(); sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
            else if (c == '%' && i + 2 < s.length) { bytes.write(s.substring(i + 1, i + 3).toInt(16)); i += 3 }
            else { flush(); sb.append(c); i++ }
        }
        flush()
        return sb.toString()
    }
}
