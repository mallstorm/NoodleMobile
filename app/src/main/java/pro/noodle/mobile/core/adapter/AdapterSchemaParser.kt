package pro.noodle.mobile.core.adapter

import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.json.*
import pro.noodle.mobile.core.version.SemanticVersion
import java.net.URI

object AdapterSchemaParser {
    private val idRegex = Regex("^[a-z0-9][a-z0-9._-]{0,127}$", RegexOption.IGNORE_CASE)
    private val hostRegex = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$", RegexOption.IGNORE_CASE)

    fun parse(text: String): AdapterLoadResult {
        val root = try { MiniJson.parse(text) as? JsonValue.Obj ?: return fail("ADAPTER_JSON_INVALID", "Корневой элемент должен быть JSON-объектом.") } catch (e: Exception) { return fail("ADAPTER_JSON_READ_FAILED", e.message ?: "Ошибка JSON") }
        val version = root.int("version") ?: 1
        if (version !in 1..3) return fail("ADAPTER_SCHEMA_UNSUPPORTED", "Поддерживаются схемы adapter.json версии 1, 2 и 3.")
        val name = root.string("name")?.trim().orEmpty()
        val baseUrl = root.string("base_url")?.trim().orEmpty()
        var id = root.string("id")?.trim().orEmpty()
        if (id.isBlank()) id = makeId(name, baseUrl)
        if (id.isBlank()) return fail("ADAPTER_ID_MISSING", "Не удалось определить стабильный id адаптера.")
        if (!idRegex.matches(id)) return fail("ADAPTER_ID_INVALID", "Поле id содержит недопустимые символы.")
        if (name.isBlank()) return fail("ADAPTER_NAME_MISSING", "Поле name отсутствует или пусто.")
        if (baseUrl.isBlank()) return fail("ADAPTER_BASE_URL_MISSING", "Поле base_url отсутствует или пусто.")
        val engine = EngineRegistry.normalizeId(root.string("engine").orEmpty())
        if (engine.isBlank()) return fail("ADAPTER_ENGINE_MISSING", "Поле engine отсутствует или пусто.")
        if (!EngineRegistry.isKnown(engine)) return fail("ADAPTER_ENGINE_UNKNOWN", "Неизвестный движок: $engine")
        val adapterVersion = root.string("adapter_version")?.trim().takeUnless { it.isNullOrEmpty() } ?: "0.0.0"
        val minApp = root.string("min_app_version")?.trim().orEmpty(); val maxApp = root.string("max_app_version")?.trim().orEmpty()
        val min = if (minApp.isNotEmpty()) SemanticVersion.parseOrNull(minApp) ?: return fail("ADAPTER_MIN_APP_VERSION_INVALID", "min_app_version должен быть SemVer.") else null
        val max = if (maxApp.isNotEmpty()) SemanticVersion.parseOrNull(maxApp) ?: return fail("ADAPTER_MAX_APP_VERSION_INVALID", "max_app_version должен быть SemVer.") else null
        if (min != null && max != null && min > max) return fail("ADAPTER_APP_VERSION_RANGE_INVALID", "min_app_version не может быть выше max_app_version.")

        val permissions = root.obj("permissions")
        var hosts = permissions?.arr("allowed_hosts")?.strings()?.map { it.trim().lowercase() }.orEmpty()
        if (hosts.isEmpty()) hosts = listOfNotNull(runCatching { URI(baseUrl).host?.lowercase() }.getOrNull())
        for (pattern in hosts) {
            val host = pattern.removePrefix("*.")
            if (host.isBlank() || !hostRegex.matches(host)) return fail("ADAPTER_ALLOWED_HOST_INVALID", "Некорректный элемент permissions.allowed_hosts: $pattern")
        }
        val capabilities = root.obj("capabilities")?.values?.filterValues { it == JsonValue.Bool(true) }?.keys?.toList().orEmpty()
        val view = root.obj("view")
        val search = root.obj("search")
        val update = root.obj("update")
        val health = root.obj("health")
        val repair = root.obj("repair")
        val healthAutoRepair = health?.bool("auto_repair") ?: false

        val d = AdapterDefinition(
            schemaVersion = version, id = id.lowercase(), name = name, adapterVersion = adapterVersion,
            publisher = root.string("publisher")?.trim().orEmpty(), homepage = root.string("homepage")?.trim().orEmpty(), minAppVersion = minApp, maxAppVersion = maxApp,
            baseUrl = baseUrl, engine = engine,
            engineDependencies = root.arr("engine_dependencies")?.strings()?.map(EngineRegistry::normalizeId).orEmpty(), enabled = root.bool("enabled") ?: true,
            capabilities = capabilities, allowedHosts = hosts, allowJavaScript = permissions?.bool("allow_javascript") ?: false, allowCookies = permissions?.bool("allow_cookies") ?: false,
            viewStrategies = view?.arr("strategies")?.strings().orEmpty(),
            searchUrlTemplate = search?.string("url_template")?.trim().orEmpty(), itemSelector = search?.string("item_selector")?.trim().orEmpty(), titleSelector = search?.string("title_selector")?.trim().orEmpty(), urlSelector = search?.string("url_selector")?.trim().orEmpty(),
            thumbnailSelector = search?.string("thumbnail_selector")?.trim().orEmpty(), thumbnailAttribute = search?.string("thumbnail_attribute")?.trim().orEmpty(), durationSelector = search?.string("duration_selector")?.trim().orEmpty(), viewsSelector = search?.string("views_selector")?.trim().orEmpty(),
            itemsPath = search?.string("items_path")?.trim().orEmpty(), idPath = search?.string("id_path")?.trim().orEmpty(), titlePath = search?.string("title_path")?.trim().orEmpty(), urlPath = search?.string("url_path")?.trim().orEmpty(), thumbnailPath = search?.string("thumbnail_path")?.trim().orEmpty(), durationPath = search?.string("duration_path")?.trim().orEmpty(), viewsPath = search?.string("views_path")?.trim().orEmpty(),
            updateFeedUrl = update?.string("feed_url")?.trim().orEmpty(), autoUpdate = update?.bool("auto_update") ?: false,
            healthQuery = health?.string("query")?.trim() ?: "video", healthMinResults = health?.int("min_results") ?: 1, healthRequirePlayback = health?.bool("require_playback") ?: true, healthAutoRepair = healthAutoRepair,
            repairEnabled = repair?.bool("enabled") ?: healthAutoRepair, repairMode = repair?.string("mode")?.trim()?.lowercase() ?: "conservative", repairStrategies = repair?.arr("strategies")?.strings().orEmpty(),
            repairFailureThreshold = repair?.int("failure_threshold") ?: 3, repairCooldownMinutes = repair?.int("cooldown_minutes") ?: 60, repairAutoQuarantine = repair?.bool("auto_quarantine") ?: false,
        )
        return AdapterLoadResult(true, definition = d)
    }

    private fun makeId(name: String, baseUrl: String): String {
        val seed = runCatching { URI(baseUrl).host?.removePrefix("www.") }.getOrNull().orEmpty().ifBlank { name }
        return seed.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.', '_')
    }
    private fun fail(c:String,m:String)=AdapterLoadResult(false,c,m)
}
