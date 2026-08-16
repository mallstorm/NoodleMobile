package pro.noodle.mobile.core.engine

data class EngineDependencyValidation(
    val success: Boolean,
    val errorCode: String = "",
    val errorMessage: String = "",
    val resolvedDependencies: List<String> = emptyList(),
)

object EngineDependencies {
    fun requiredOf(engineId: String): List<String> = when (EngineRegistry.normalizeId(engineId)) {
        "iframe_chain" -> listOf("generic_html5")
        else -> emptyList()
    }

    fun optionalOf(engineId: String): List<String> = when (EngineRegistry.normalizeId(engineId)) {
        "generic_html5", "jwplayer_config", "videojs_config" -> listOf("hls_manifest", "dash_manifest")
        "iframe_chain" -> listOf("jwplayer_config", "videojs_config", "oembed_provider", "hls_manifest", "dash_manifest")
        "wordpress_rest" -> listOf("oembed_provider", "generic_html5", "hls_manifest", "dash_manifest")
        "kvs_view", "kvs_videos", "noodle_watch_playlist", "novideo_embed", "sosalkino_catalog", "declarative_catalog", "json_api_catalog" ->
            listOf("jwplayer_config", "videojs_config", "generic_html5", "oembed_provider", "iframe_chain", "hls_manifest", "dash_manifest")
        else -> emptyList()
    }

    fun recommendedFor(engineId: String): List<String> = (requiredOf(engineId) + optionalOf(engineId)).normalize()

    fun resolveClosure(engineId: String): List<String> {
        val root = EngineRegistry.normalizeId(engineId)
        if (root.isBlank()) return emptyList()
        val seen = linkedSetOf(root)
        val queue = ArrayDeque<String>().apply { add(root) }
        val out = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (raw in requiredOf(current)) {
                val dep = EngineRegistry.normalizeId(raw)
                if (dep.isNotBlank() && seen.add(dep)) {
                    queue.add(dep); out.add(dep)
                }
            }
        }
        return out
    }

    fun validate(engineId: String, declaredDependencies: List<String>): EngineDependencyValidation {
        val primary = EngineRegistry.normalizeId(engineId)
        if (!EngineRegistry.isKnown(primary)) return fail("ENGINE_DEP_PRIMARY_UNKNOWN", "Основной движок не зарегистрирован: $engineId")
        val declared = declaredDependencies.normalize()
        for (dep in declared) {
            if (dep.equals(primary, true)) return fail("ENGINE_DEP_SELF_REFERENCE", "Движок не может зависеть от самого себя: $primary")
            if (!EngineRegistry.isKnown(dep)) return fail("ENGINE_DEP_UNKNOWN", "Неизвестная зависимость движка: $dep")
        }
        for (required in resolveClosure(primary)) {
            if (required !in declared) return fail("ENGINE_DEP_REQUIRED_MISSING", "Для $primary требуется зависимость $required.")
        }
        for (dep in declared) {
            for (nested in resolveClosure(dep)) {
                if (nested.equals(primary, true) || nested !in declared) {
                    return fail("ENGINE_DEP_TRANSITIVE_REQUIRED_MISSING", "Для зависимости $dep требуется $nested.")
                }
            }
        }
        return EngineDependencyValidation(true, resolvedDependencies = declared)
    }

    fun validateRegistry(): EngineDependencyValidation {
        for (id in EngineRegistry.knownEngineIds) {
            for (dep in requiredOf(id)) if (!EngineRegistry.isKnown(dep)) return fail("ENGINE_REGISTRY_DEP_UNKNOWN", "$id requires unknown engine $dep")
        }
        return EngineDependencyValidation(true)
    }

    private fun List<String>.normalize(): List<String> = map { EngineRegistry.normalizeId(it) }.filter { it.isNotBlank() }.distinct()
    private fun fail(code: String, message: String) = EngineDependencyValidation(false, code, message)
}
