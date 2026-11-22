package pro.noodle.mobile.core.source

import java.net.URI

/** Короткое имя источника: «Кинокордон», а не «Порно фильмы онлайн 18+ | Порнокино с русским переводом». */
object SourceNames {
    private val tr = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h",
        'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")
    /** Латинский вариант запроса, если в нём есть кириллица; иначе null. */
    fun latinQuery(q: String): String? {
        if (q.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return null
        val t = translit(q.replace('ё', 'е').replace('Ё', 'Е')).replace(Regex("\\s+"), " ").trim()
        return t.ifBlank { null }?.takeIf { !it.equals(q.trim(), true) }
    }
    /** Обратная транслитерация для запроса латиницей: «ne snimaya dzhinsov» → «не снимая джинсов»; иначе null. */
    fun cyrillicQuery(q: String): String? {
        val src = q.trim().lowercase()
        if (src.none { it in 'a'..'z' } || src.any { it in 'а'..'я' || it == 'ё' }) return null
        val pairs = listOf("shch" to "щ", "sch" to "щ", "tsya" to "ться", "dzh" to "дж", "zh" to "ж", "sh" to "ш", "ch" to "ч", "kh" to "х",
            "ts" to "ц", "yo" to "ё", "yu" to "ю", "ya" to "я", "ye" to "е", "iy" to "ий", "yy" to "ый", "ey" to "ей", "ay" to "ай", "oy" to "ой", "uy" to "уй")
        val single = mapOf('a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х", 'i' to "и", 'j' to "й", 'k' to "к",
            'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в",
            'x' to "кс", 'y' to "ы", 'z' to "з")
        val sb = StringBuilder()
        var i = 0
        while (i < src.length) {
            val hit = pairs.firstOrNull { src.startsWith(it.first, i) }
            if (hit != null) { sb.append(hit.second); i += hit.first.length; continue }
            val c = src[i]
            sb.append(single[c] ?: c.toString())
            i++
        }
        val out = sb.toString().replace(Regex("\\s+"), " ").trim()
        return out.takeIf { it.isNotBlank() && it != src }
    }

    /** Второй вариант запроса: латиница ↔ кириллица. */
    fun altQuery(q: String): String? = latinQuery(q) ?: cyrillicQuery(q)

    fun translit(s: String) = s.lowercase().map { c -> tr[c] ?: c.toString() }.joinToString("")

    fun label(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return parts.firstOrNull().orEmpty()
        val sld = parts[parts.size - 2]
        return if (sld in setOf("co", "com", "net", "org") && parts.size >= 3) parts[parts.size - 3] else sld
    }

    /** Ищет среди фрагментов (заголовок, og:site_name, generator) тот, что совпадает с доменом. */
    fun brand(candidates: List<String>, host: String): String {
        val lbl = label(host).replace(Regex("[^a-z0-9]"), "")
        if (lbl.length < 3) return ""
        val pieces = candidates.flatMap { it.split(" - ", " | ", " — ", " – ", ": ", "(", ")", ",") }.map { it.trim() }.filter { it.length in 2..30 }
        return pieces.firstOrNull { p ->
            val t = translit(p).replace(Regex("[^a-z0-9]"), "")
            t.isNotEmpty() && (t == lbl || (t.length >= 5 && (lbl.startsWith(t.take(6)) || t.startsWith(lbl.take(6)))))
        }.orEmpty()
    }

    fun short(name: String, baseUrl: String): String {
        val n = name.trim()
        if (n.length <= 26 && !n.contains(" | ")) return n.ifBlank { fromHost(baseUrl) }
        val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("")
        return brand(listOf(n), host).ifBlank { fromHost(baseUrl) }.ifBlank { n }
    }

    fun fromHost(baseUrl: String): String {
        val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("")
        return label(host).replaceFirstChar { it.uppercase() }
    }
}
