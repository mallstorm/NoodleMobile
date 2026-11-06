package pro.noodle.mobile.core.adapter

import pro.noodle.mobile.core.engine.EngineDependencies
import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.version.SemanticVersion
import java.net.URI

object AdapterValidator {
    private val cssLite = Regex("^(?:[a-z][a-z0-9:_-]*)?(?:\\.[a-z0-9_-]+)?(?:#[a-z0-9_-]+)?$", RegexOption.IGNORE_CASE)
    private val attr = Regex("^[a-zA-Z_:][a-zA-Z0-9:._-]*$")
    private val jsonPath = Regex("^\\$?(?:\\.[a-zA-Z0-9_-]+|[a-zA-Z0-9_-]+)(?:\\.[a-zA-Z0-9_-]+|\\[\\d+\\])*$")

    fun validate(text: String): AdapterValidationResult {
        val load = AdapterSchemaParser.parse(text)
        if (!load.success) return fail(load.errorCode, load.errorMessage)
        val d = load.definition!!
        if (SemanticVersion.parseOrNull(d.adapterVersion) == null) return fail("ADAPTER_VERSION_INVALID", "adapter_version должен соответствовать SemVer, например 1.2.3.")
        if (d.healthMinResults !in 0..100) return fail("ADAPTER_HEALTH_MIN_RESULTS_INVALID", "health.min_results должен быть в диапазоне 0..100.")
        if (d.healthQuery.length > 256) return fail("ADAPTER_HEALTH_QUERY_TOO_LONG", "health.query превышает 256 символов.")
        if (d.updateFeedUrl.isNotBlank()) UrlPolicy.evaluateNavigation(d.updateFeedUrl).let { if (!it.allowed) return fail("ADAPTER_UPDATE_FEED_UNSAFE", "${it.errorCode}: ${it.reason}") }
        UrlPolicy.evaluateNavigation(d.baseUrl).let { if (!it.allowed) return fail("ADAPTER_BASE_URL_UNSAFE", "${it.errorCode}: ${it.reason}") }
        if (!EngineRegistry.isKnown(d.engine)) return fail("ADAPTER_ENGINE_UNKNOWN", "Неизвестный движок: ${d.engine}")

        if (d.schemaVersion >= 3) {
            val deps = EngineDependencies.validate(d.engine, d.engineDependencies)
            if (!deps.success) return fail(deps.errorCode, deps.errorMessage)
            for (strategy in d.viewStrategies) {
                // Desktop Stage 9 intends kvs_flashvars to remain a legacy/meta strategy.
                // It is also registered as a kvs_view alias, so checking the raw token first
                // avoids falsely requiring kvs_view as a declared dependency for kvs_videos.
                if (strategy.trim().equals("kvs_flashvars", ignoreCase = true)) continue
                if (!EngineRegistry.isKnown(strategy)) continue
                val normalized = EngineRegistry.normalizeId(strategy)
                if (normalized == d.engine) continue
                if (d.engineDependencies.none { EngineRegistry.normalizeId(it) == normalized }) return fail("ADAPTER_VIEW_ENGINE_UNDECLARED", "view.strategies использует незаявленную engine dependency: $normalized")
            }
            if (d.repairMode != "conservative") return fail("ADAPTER_REPAIR_MODE_UNSUPPORTED", "Schema v3 поддерживает repair.mode=conservative.")
            if (d.repairFailureThreshold !in 1..20) return fail("ADAPTER_REPAIR_THRESHOLD_INVALID", "repair.failure_threshold должен быть в диапазоне 1..20.")
            if (d.repairCooldownMinutes !in 0..10080) return fail("ADAPTER_REPAIR_COOLDOWN_INVALID", "repair.cooldown_minutes должен быть в диапазоне 0..10080.")
            for (strategy in d.repairStrategies) if (strategy.lowercase() !in setOf("selector_relearn", "resolver_refresh")) return fail("ADAPTER_REPAIR_STRATEGY_UNKNOWN", "Неизвестная repair strategy: $strategy")
        }

        val baseHost = runCatching { URI(d.baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
        if (!UrlPolicy.isHostAllowed(baseHost, d.allowedHosts)) return fail("ADAPTER_BASE_HOST_NOT_ALLOWED", "Домен base_url должен присутствовать в permissions.allowed_hosts.")

        if (d.searchUrlTemplate.startsWith("https://", true) || d.searchUrlTemplate.startsWith("http://", true)) {
            val u = d.searchUrlTemplate.replace("{base_url}", d.baseUrl, true).replace("{query}", "test", true).replace("{page}", "1", true)
            val decision = UrlPolicy.evaluateNavigation(u)
            if (!decision.allowed) return fail("ADAPTER_SEARCH_URL_UNSAFE", "${decision.errorCode}: ${decision.reason}")
            val searchHost = runCatching { URI(decision.normalizedUrl).host.orEmpty().lowercase() }.getOrDefault("")
            if (!UrlPolicy.isHostAllowed(searchHost, d.allowedHosts)) return fail("ADAPTER_SEARCH_HOST_NOT_ALLOWED", "Домен search.url_template должен присутствовать в permissions.allowed_hosts.")
        }

        if (d.searchPageTemplate.isNotBlank()) {
            if (!d.searchPageTemplate.contains("{page}", true) || !d.searchPageTemplate.contains("{query}", true)) return fail("ADAPTER_PAGE_TEMPLATE_INVALID", "search.page_template должен содержать {query} и {page}.")
            if (d.searchPageTemplate.startsWith("https://", true) || d.searchPageTemplate.startsWith("http://", true)) {
                val u = d.searchPageTemplate.replace("{base_url}", d.baseUrl, true).replace("{query}", "test", true).replace("{page}", "2", true)
                val decision = UrlPolicy.evaluateNavigation(u)
                if (!decision.allowed) return fail("ADAPTER_PAGE_TEMPLATE_UNSAFE", "${decision.errorCode}: ${decision.reason}")
                val pageHost = runCatching { URI(decision.normalizedUrl).host.orEmpty().lowercase() }.getOrDefault("")
                if (!UrlPolicy.isHostAllowed(pageHost, d.allowedHosts)) return fail("ADAPTER_PAGE_TEMPLATE_HOST_NOT_ALLOWED", "Домен search.page_template должен присутствовать в permissions.allowed_hosts.")
            }
        }
        if (d.linkPattern.isNotBlank()) {
            if (d.linkPattern.length > 300) return fail("ADAPTER_LINK_PATTERN_TOO_LONG", "search.link_pattern превышает 300 символов.")
            if (runCatching { Regex(d.linkPattern) }.isFailure) return fail("ADAPTER_LINK_PATTERN_INVALID", "search.link_pattern не является корректным регулярным выражением.")
        }
        if (d.engine == "auto_catalog") {
            if (d.searchUrlTemplate.isBlank()) return fail("ADAPTER_AUTO_URL_MISSING", "Для auto_catalog требуется search.url_template.")
            if (d.linkPattern.isBlank()) return fail("ADAPTER_AUTO_LINK_PATTERN_MISSING", "Для auto_catalog требуется search.link_pattern.")
        }
        if (d.engine == "declarative_catalog") {
            if (d.searchUrlTemplate.isBlank()) return fail("ADAPTER_DECLARATIVE_URL_MISSING", "Для declarative_catalog требуется search.url_template.")
            if (d.itemSelector.isBlank() || d.titleSelector.isBlank() || d.urlSelector.isBlank()) return fail("ADAPTER_DECLARATIVE_SELECTORS_MISSING", "Для declarative_catalog требуются item_selector, title_selector и url_selector.")
            if (listOf(d.itemSelector,d.titleSelector,d.urlSelector,d.thumbnailSelector,d.durationSelector,d.viewsSelector).filter { it.isNotBlank() }.any { !cssLite.matches(it) }) return fail("ADAPTER_DECLARATIVE_SELECTOR_UNSAFE", "Один из search CSS-lite селекторов использует неподдерживаемый синтаксис.")
            if (d.thumbnailAttribute.isNotBlank() && !attr.matches(d.thumbnailAttribute)) return fail("ADAPTER_DECLARATIVE_ATTRIBUTE_UNSAFE", "thumbnail_attribute содержит недопустимое имя атрибута.")
        }
        if (d.engine == "json_api_catalog") {
            if (d.searchUrlTemplate.isBlank()) return fail("ADAPTER_JSON_API_URL_MISSING", "Для json_api_catalog требуется search.url_template.")
            if (d.itemsPath.isBlank() || d.titlePath.isBlank() || d.urlPath.isBlank()) return fail("ADAPTER_JSON_API_PATHS_MISSING", "Для json_api_catalog требуются items_path, title_path и url_path.")
            if (listOf(d.itemsPath,d.titlePath,d.urlPath,d.idPath,d.thumbnailPath,d.durationPath,d.viewsPath).filter { it.isNotBlank() }.any { !jsonPath.matches(it) }) return fail("ADAPTER_JSON_API_PATH_UNSAFE", "Один из search JSON-path использует неподдерживаемый синтаксис.")
        }
        return AdapterValidationResult(true, definition=d)
    }
    private fun fail(c:String,m:String)=AdapterValidationResult(false,c,m)
}
