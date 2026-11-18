package pro.noodle.mobile.core.source

import android.content.Context
import pro.noodle.mobile.core.adapter.AdapterDefinition
import java.net.URI

/**
 * Зеркала и переезды доменов. Пакет адаптера (его файлы и хеши) никогда не меняется: активный домен
 * хранится здесь и подставляется в определение адаптера при чтении ([apply]). Так переезд сайта не
 * ломает целостность пакета, а старые ссылки из избранного/истории продолжают открываться — их
 * домен переписывается на актуальный ([rewriteUrl]).
 */
class MirrorStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("noodle_mirrors", Context.MODE_PRIVATE)

    fun active(id: String): String = prefs.getString("active_${id.lowercase()}", "").orEmpty()
    fun history(id: String): Set<String> = prefs.getStringSet("history_${id.lowercase()}", emptySet()).orEmpty()
    fun known(id: String): Set<String> = prefs.getStringSet("known_${id.lowercase()}", emptySet()).orEmpty()
    fun suggested(id: String): Set<String> = prefs.getStringSet("suggested_${id.lowercase()}", emptySet()).orEmpty()
    fun note(id: String): String = prefs.getString("note_${id.lowercase()}", "").orEmpty()

    private fun put(key: String, values: Set<String>) { prefs.edit().putStringSet(key, values.toSet()).apply() }

    fun addKnown(id: String, origin: String) { put("known_${id.lowercase()}", known(id) + origin) }
    fun addSuggested(id: String, origin: String) { if (origin !in known(id)) put("suggested_${id.lowercase()}", suggested(id) + origin) }
    fun forget(id: String, origin: String) {
        put("known_${id.lowercase()}", known(id) - origin)
        put("suggested_${id.lowercase()}", suggested(id) - origin)
    }

    /** Переключает адаптер на [origin]; прежний активный домен остаётся в истории для переписывания старых ссылок. */
    fun switchTo(id: String, currentOrigin: String, origin: String, reason: String) {
        val key = id.lowercase()
        put("history_$key", history(id) + currentOrigin)
        put("known_$key", known(id) + origin + currentOrigin)
        put("suggested_$key", suggested(id) - origin)
        prefs.edit().putString("active_$key", origin).putString("note_$key", "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}: $reason").apply()
    }

    fun reset(id: String) {
        val key = id.lowercase()
        prefs.edit().remove("active_$key").remove("note_$key").apply()
    }

    /** Определение адаптера с учётом активного зеркала. */
    fun apply(d: AdapterDefinition): AdapterDefinition {
        val active = active(d.id)
        if (active.isBlank()) return d
        val own = originOf(d.baseUrl)
        if (own.equals(active, true)) return d
        return retarget(d, history(d.id) + own, active)
    }

    /** Тот же адаптер, но направленный на [origin] (для проверки кандидата без сохранения). */
    fun preview(d: AdapterDefinition, origin: String): AdapterDefinition =
        retarget(d, history(d.id) + originOf(d.baseUrl) + active(d.id).ifBlank { originOf(d.baseUrl) }, origin)

    private fun retarget(d: AdapterDefinition, from: Set<String>, to: String): AdapterDefinition {
        val sources = from.filter { !it.equals(to, true) && it.isNotBlank() }.flatMap { variants(it) }.distinct()
        fun swap(s: String): String {
            var r = s
            for (o in sources) r = r.replace(o, to, ignoreCase = true)
            return r
        }
        val host = hostOf(to)
        val hosts = if (d.allowedHosts.isEmpty() || host.isBlank()) d.allowedHosts
        else (d.allowedHosts + "*.${registrable(host)}").distinct()
        return d.copy(
            baseUrl = to,
            homepage = swap(d.homepage),
            searchUrlTemplate = swap(d.searchUrlTemplate),
            searchPageTemplate = swap(d.searchPageTemplate),
            allowedHosts = hosts,
        )
    }

    /** Старый адрес ролика (из избранного/истории) → актуальный домен. */
    fun rewriteUrl(id: String, url: String): String {
        val active = active(id)
        if (active.isBlank()) return url
        for (o in history(id).flatMap { variants(it) }) {
            if (url.startsWith(o, ignoreCase = true) && !o.equals(active, true)) return active + url.substring(o.length)
        }
        return url
    }

    companion object {
        fun originOf(url: String): String = runCatching {
            val u = URI(url.trim())
            if (u.host.isNullOrBlank()) "" else "${u.scheme ?: "https"}://${u.host.lowercase()}${if (u.port > 0 && u.port != 443 && u.port != 80) ":${u.port}" else ""}"
        }.getOrDefault("")

        fun hostOf(url: String): String = runCatching { URI(url.trim()).host.orEmpty().lowercase().trimEnd('.') }.getOrDefault("")

        /** «https://www.a.com» ↔ «https://a.com», http/https. */
        fun variants(origin: String): List<String> {
            val host = hostOf(origin)
            if (host.isBlank()) return listOf(origin)
            val bare = host.removePrefix("www.")
            val out = mutableListOf(origin)
            for (scheme in listOf("https", "http")) for (h in listOf(bare, "www.$bare")) out += "$scheme://$h"
            return out.distinct()
        }

        fun registrable(host: String): String {
            val parts = host.removePrefix("www.").split('.')
            if (parts.size <= 2) return parts.joinToString(".")
            val sld = parts[parts.size - 2]
            val keep = if (sld in setOf("co", "com", "net", "org", "gov", "ac", "edu") && parts.last().length == 2) 3 else 2
            return parts.takeLast(keep).joinToString(".")
        }

        fun sameSite(a: String, b: String) = registrable(hostOf(a)).equals(registrable(hostOf(b)), true)
    }
}
