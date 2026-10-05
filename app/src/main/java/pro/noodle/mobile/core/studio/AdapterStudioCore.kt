package pro.noodle.mobile.core.studio

import pro.noodle.mobile.core.version.BuildIdentity
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.json.*
import java.net.URI


data class AdapterStudioDraft(
    val adapterId: String,
    val name: String,
    val baseUrl: String,
    val engine: String,
    val searchUrl: String,
    val itemSelector: String = "",
    val titleSelector: String = "",
    val urlSelector: String = "",
    val thumbnailSelector: String = "",
    val thumbnailAttribute: String = "src",
    val durationSelector: String = "",
    val viewsSelector: String = "",
    val healthQuery: String = "video",
    val allowedHosts: List<String>,
    val allowJavaScript: Boolean = false,
    val allowCookies: Boolean = false,
    val notes: List<String> = emptyList(),
)

object AdapterStudioCore {
    fun analyze(url: String, html: String): Result<AdapterStudioDraft> = runCatching {
        val uri = URI(url.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "Adapter Studio принимает только HTTPS URL." }
        val host = uri.host.lowercase()
        val id = host.replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(128)
        val wp = html.contains("wp-content", true) || html.contains("/wp-json/", true)
        val repeated = if (!wp) guessRepeatedClass(html) else ""
        AdapterStudioDraft(
            adapterId = id,
            name = host,
            baseUrl = "https://$host",
            engine = if (wp) "wordpress_rest" else "declarative_catalog",
            // Generic /search is no longer guessed. StudioDiscovery must confirm a real endpoint.
            searchUrl = if (wp) "/wp-json/wp/v2/search?search={query}&page={page}&per_page=20" else "",
            itemSelector = if (wp) "" else repeated.ifBlank { "article" },
            titleSelector = if (wp) "" else "a",
            urlSelector = if (wp) "" else "a",
            thumbnailSelector = if (wp) "" else "img",
            thumbnailAttribute = if (html.contains("data-src", true)) "data-src" else "src",
            allowedHosts = listOf(host, "*.$host"),
            notes = buildList {
                add(if (wp) "Обнаружены признаки WordPress REST." else "Локальный черновик без неподтверждённого search URL.")
                if (repeated.isNotBlank()) add("Повторяющийся CSS class: $repeated")
            },
        )
    }

    fun toAdapterJson(d: AdapterStudioDraft, publisher: String = "local-user"): String {
        val searchValues = linkedMapOf<String, JsonValue>("url_template" to JsonValue.Str(d.searchUrl))
        if (d.engine == "declarative_catalog") {
            searchValues["item_selector"] = JsonValue.Str(d.itemSelector)
            searchValues["title_selector"] = JsonValue.Str(d.titleSelector)
            searchValues["url_selector"] = JsonValue.Str(d.urlSelector)
            searchValues["thumbnail_selector"] = JsonValue.Str(d.thumbnailSelector)
            searchValues["thumbnail_attribute"] = JsonValue.Str(d.thumbnailAttribute)
            if (d.durationSelector.isNotBlank()) searchValues["duration_selector"] = JsonValue.Str(d.durationSelector)
            if (d.viewsSelector.isNotBlank()) searchValues["views_selector"] = JsonValue.Str(d.viewsSelector)
        }
        val root = JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(3.0), "id" to JsonValue.Str(d.adapterId), "name" to JsonValue.Str(d.name), "adapter_version" to JsonValue.Str("0.1.0"), "publisher" to JsonValue.Str(publisher), "homepage" to JsonValue.Str(d.baseUrl), "min_app_version" to JsonValue.Str(BuildIdentity.SNP_COMPATIBILITY_VERSION), "base_url" to JsonValue.Str(d.baseUrl), "engine" to JsonValue.Str(d.engine),
            "engine_dependencies" to JsonValue.Arr(listOf("jwplayer_config","videojs_config","generic_html5","oembed_provider","iframe_chain","hls_manifest","dash_manifest").filter { it != d.engine }.map { JsonValue.Str(it) }), "enabled" to JsonValue.Bool(false),
            "permissions" to JsonValue.Obj(linkedMapOf("allowed_hosts" to JsonValue.Arr(d.allowedHosts.map { JsonValue.Str(it) }), "allow_javascript" to JsonValue.Bool(d.allowJavaScript), "allow_cookies" to JsonValue.Bool(d.allowCookies))),
            "search" to JsonValue.Obj(searchValues),
            "view" to JsonValue.Obj(linkedMapOf("strategies" to JsonValue.Arr(listOf("primary","jwplayer","videojs","generic_html5","oembed","iframe","hls","dash").map { JsonValue.Str(it) }))),
            "health" to JsonValue.Obj(linkedMapOf("query" to JsonValue.Str(d.healthQuery.ifBlank { "video" }), "min_results" to JsonValue.Num(1.0), "require_playback" to JsonValue.Bool(true), "auto_repair" to JsonValue.Bool(true))),
            "repair" to JsonValue.Obj(linkedMapOf("enabled" to JsonValue.Bool(true), "mode" to JsonValue.Str("conservative"), "failure_threshold" to JsonValue.Num(3.0), "cooldown_minutes" to JsonValue.Num(60.0), "auto_quarantine" to JsonValue.Bool(false), "strategies" to JsonValue.Arr(buildList { if(d.engine=="declarative_catalog") add(JsonValue.Str("selector_relearn")); add(JsonValue.Str("resolver_refresh")) }))),
        ))
        return root.toJson(pretty = true)
    }

    fun validate(d: AdapterStudioDraft) = AdapterValidator.validate(toAdapterJson(d))

    private fun guessRepeatedClass(html: String): String {
        val re = Regex("class\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val counts = mutableMapOf<String, Int>()
        re.findAll(html).forEach { m ->
            m.groupValues[1].split(Regex("\\s+")).filter { it.length in 2..64 && it.matches(Regex("[A-Za-z0-9_-]+")) }.forEach { c -> counts[c] = (counts[c] ?: 0) + 1 }
        }
        val best = counts.filterValues { it >= 2 }.maxByOrNull { it.value }?.key.orEmpty()
        return if (best.isBlank()) "" else ".$best"
    }
}
