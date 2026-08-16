package pro.noodle.mobile.core.selftest

import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.EngineDependencies
import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.engine.impl.GenericHtml5Engine
import pro.noodle.mobile.core.engine.impl.HlsManifestEngine
import pro.noodle.mobile.core.resolver.ResolverFetcher
import pro.noodle.mobile.core.resolver.ResolverPipeline
import pro.noodle.mobile.core.security.HostResolver
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.version.SemanticVersion
import java.net.InetAddress

data class Check(val name:String,val passed:Boolean,val detail:String="")
data class SelfTestReport(val checks:List<Check>){ val passed get()=checks.all{it.passed} }

object CoreSelfTest {
    fun run(): SelfTestReport {
        val checks=mutableListOf<Check>()
        checks += Check("15 canonical engines", EngineRegistry.knownEngineIds.size==15, EngineRegistry.knownEngineIds.joinToString())
        checks += Check("15 runtime engines", RuntimeEngineRegistry.implementedIds.size==15, RuntimeEngineRegistry.implementedIds.sorted().joinToString())
        val dep=EngineDependencies.validateRegistry(); checks += Check("Engine dependency graph",dep.success,dep.errorMessage)
        checks += Check("Legacy alias normalization",EngineRegistry.normalizeId("kvs")=="kvs_view")
        checks += Check("iframe requires generic_html5",EngineDependencies.validate("iframe_chain",listOf("generic_html5")).success)
        checks += Check("Unknown dependency rejected",EngineDependencies.validate("generic_html5",listOf("does_not_exist")).errorCode=="ENGINE_DEP_UNKNOWN")
        checks += Check("Transitive dependency rejected",EngineDependencies.validate("kvs_videos",listOf("iframe_chain")).errorCode=="ENGINE_DEP_TRANSITIVE_REQUIRED_MISSING")
        checks += Check("HTTPS allowed",UrlPolicy.evaluateNavigation("https://example.com/video").allowed)
        checks += Check("JavaScript blocked",!UrlPolicy.evaluateNavigation("javascript:alert(1)").allowed)
        checks += Check("HTTP blocked by default",!UrlPolicy.evaluateNavigation("http://example.com/video").allowed)
        checks += Check("Explicit legacy HTTP allowed",UrlPolicy.evaluateNavigation("http://example.com/video",allowHttp=true).allowed)
        checks += Check("Loopback blocked",!UrlPolicy.evaluateNavigation("https://127.0.0.1/video").allowed)
        checks += Check("HTTPS downgrade detected",UrlPolicy.isHttpsDowngrade("https://example.com/a","http://example.com/a"))
        checks += Check("Wildcard host safe",UrlPolicy.isHostAllowed("cdn.example.com",listOf("*.example.com"))&&!UrlPolicy.isHostAllowed("evil-example.com",listOf("*.example.com")))
        val fakePrivate=HostResolver{listOf(InetAddress.getByName("192.168.1.10"))}
        checks += Check("DNS private target blocked",UrlPolicy.evaluateNavigation("https://public.example/a",resolveDns=true,resolver=fakePrivate).errorCode=="URL_DNS_PRIVATE_BLOCKED")
        checks += Check("SemVer prerelease",SemanticVersion.parseOrNull("1.2.3-alpha.1")!! < SemanticVersion.parseOrNull("1.2.3")!!)
        val schema = """{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"iframe_chain","engine_dependencies":["generic_html5","hls_manifest"],"permissions":{"allowed_hosts":["example.com"]},"repair":{"enabled":true,"mode":"conservative","failure_threshold":3,"cooldown_minutes":60,"auto_quarantine":true,"strategies":["resolver_refresh"]}}"""
        val av=AdapterValidator.validate(schema); checks += Check("Schema v3 valid fixture",av.isValid,"${av.errorCode} ${av.errorMessage}")
        val missing=schema.replace("[\"generic_html5\",\"hls_manifest\"]","[]"); val mv=AdapterValidator.validate(missing); checks += Check("Missing iframe dependency rejected",!mv.isValid&&mv.errorCode=="ENGINE_DEP_REQUIRED_MISSING",mv.errorCode)
        val undeclared="""{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"generic_html5","engine_dependencies":[],"permissions":{"allowed_hosts":["example.com"]},"view":{"strategies":["generic_html5","jwplayer_config"]},"repair":{"mode":"conservative"}}"""
        checks += Check("Undeclared view engine rejected",AdapterValidator.validate(undeclared).errorCode=="ADAPTER_VIEW_ENGINE_UNDECLARED")
        val unsafeRepair="""{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"generic_html5","engine_dependencies":[],"permissions":{"allowed_hosts":["example.com"]},"repair":{"enabled":true,"mode":"execute_script","strategies":["resolver_refresh"]}}"""
        checks += Check("Unsafe repair mode rejected",AdapterValidator.validate(unsafeRepair).errorCode=="ADAPTER_REPAIR_MODE_UNSUPPORTED")
        val unknownRepair="""{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"generic_html5","engine_dependencies":[],"permissions":{"allowed_hosts":["example.com"]},"repair":{"enabled":true,"mode":"conservative","strategies":["arbitrary_code"]}}"""
        checks += Check("Unknown repair strategy rejected",AdapterValidator.validate(unknownRepair).errorCode=="ADAPTER_REPAIR_STRATEGY_UNKNOWN")
        val generic = GenericHtml5Engine().resolvePlayback("<video><source src=\"/v.mp4\" type=\"video/mp4\"></video>", "https://example.com/watch/1")
        checks += Check("Generic HTML5 runtime", generic.success && generic.sources.firstOrNull()?.url == "https://example.com/v.mp4")
        val hls = HlsManifestEngine().resolvePlayback("#EXTM3U\n#EXTINF:6,\nseg.ts", "https://cdn.example/media.m3u8")
        checks += Check("HLS media runtime", hls.success && hls.sources.firstOrNull()?.protocol == "hls")
        val pipeline = ResolverPipeline.resolve("<video><source src=\"https://cdn.example/master.m3u8\"></video>", "https://example.com/watch", "generic_html5", listOf("hls_manifest"), ResolverFetcher { if (it.contains("master.m3u8")) "#EXTM3U\n#EXTINF:5,\nseg.ts" else null })
        checks += Check("Resolver pipeline runtime", pipeline.success && pipeline.sources.isNotEmpty())
        return SelfTestReport(checks)
    }
}
