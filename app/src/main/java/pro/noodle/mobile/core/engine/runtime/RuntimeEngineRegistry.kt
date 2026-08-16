package pro.noodle.mobile.core.engine.runtime

import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.engine.impl.*

object RuntimeEngineRegistry {
    private val engines: List<SourceEngine> = listOf(
        KvsSourceEngine("kvs_view", setOf("kvs","kvs_flashvars","fapaxi"), KvsSourceEngine.Mode.VIEW),
        KvsSourceEngine("kvs_videos", setOf("pornobomba","kvs_videos_flashvars"), KvsSourceEngine.Mode.VIDEOS),
        NoodleWatchEngine(),
        SosalkinoCatalogEngine(),
        NovideoEmbedEngine(),
        GenericHtml5Engine(),
        JwPlayerEngine(),
        VideoJsEngine(),
        IFrameChainEngine(),
        HlsManifestEngine(),
        DashManifestEngine(),
        OEmbedEngine(),
        DeclarativeHtmlEngine(),
        JsonApiEngine(),
        WordPressRestEngine(),
    )
    private val byId: Map<String, SourceEngine> = buildMap {
        engines.forEach { engine ->
            put(engine.id.lowercase(), engine)
            engine.aliases.forEach { put(it.lowercase(), engine) }
        }
    }

    val implementedIds: Set<String> get() = engines.mapTo(linkedSetOf()) { it.id }

    fun find(idOrAlias: String): SourceEngine? {
        val raw = idOrAlias.trim().lowercase()
        return byId[raw] ?: byId[EngineRegistry.normalizeId(raw)]
    }
}
