package pro.noodle.mobile

import org.junit.Assert.*
import org.junit.Test
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.EngineDependencies
import pro.noodle.mobile.core.engine.EngineRegistry
import pro.noodle.mobile.core.security.HostResolver
import pro.noodle.mobile.core.security.UrlPolicy
import pro.noodle.mobile.core.version.SemanticVersion
import java.net.InetAddress

class CoreParityTest {
    @Test fun registryHasDesktopParity(){ assertEquals(15,EngineRegistry.knownEngineIds.size); assertEquals("kvs_view",EngineRegistry.normalizeId("fapaxi")); assertEquals("novideo_embed",EngineRegistry.normalizeId("iframe_flashvars")) }
    @Test fun iframeRequiresGeneric(){ assertTrue(EngineDependencies.validate("iframe_chain", listOf("generic_html5")).success); assertEquals("ENGINE_DEP_REQUIRED_MISSING",EngineDependencies.validate("iframe_chain", emptyList()).errorCode) }
    @Test fun rejectsTransitiveMissing(){ assertEquals("ENGINE_DEP_TRANSITIVE_REQUIRED_MISSING",EngineDependencies.validate("kvs_videos", listOf("iframe_chain")).errorCode) }
    @Test fun semverParity(){ assertTrue(SemanticVersion.parseOrNull("1.2.3-alpha.1")!! < SemanticVersion.parseOrNull("1.2.3")!!); assertNull(SemanticVersion.parseOrNull("01.2.3")) }
    @Test fun urlDesktopParity(){ assertTrue(UrlPolicy.evaluateNavigation("https://example.com/video").allowed); assertFalse(UrlPolicy.evaluateNavigation("javascript:alert(1)").allowed); assertFalse(UrlPolicy.evaluateNavigation("http://example.com/video").allowed); assertTrue(UrlPolicy.evaluateNavigation("http://example.com/video",allowHttp=true).allowed); assertFalse(UrlPolicy.evaluateNavigation("https://127.0.0.1/video").allowed); assertTrue(UrlPolicy.isHttpsDowngrade("https://example.com/a","http://example.com/a")) }
    @Test fun dnsSsrfDefense(){ val fake=HostResolver{ listOf(InetAddress.getByName("192.168.1.10")) }; val d=UrlPolicy.evaluateNavigation("https://public.example/a",resolveDns=true,resolver=fake); assertFalse(d.allowed); assertEquals("URL_DNS_PRIVATE_BLOCKED",d.errorCode) }
    @Test fun wildcardDoesNotMatchEvilSuffix(){ assertTrue(UrlPolicy.isHostAllowed("cdn.example.com", listOf("*.example.com"))); assertFalse(UrlPolicy.isHostAllowed("evil-example.com", listOf("*.example.com"))) }
    @Test fun schemaV3RepairFixture(){ val json="""{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"iframe_chain","engine_dependencies":["generic_html5","hls_manifest"],"permissions":{"allowed_hosts":["example.com"]},"repair":{"enabled":true,"mode":"conservative","failure_threshold":3,"cooldown_minutes":60,"auto_quarantine":true,"strategies":["resolver_refresh"]}}"""; val r=AdapterValidator.validate(json); assertTrue("${r.errorCode} ${r.errorMessage}",r.isValid) }
    @Test fun schemaV3RejectsUndeclaredViewEngine(){ val json="""{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"generic_html5","engine_dependencies":[],"permissions":{"allowed_hosts":["example.com"]},"view":{"strategies":["generic_html5","jwplayer_config"]},"repair":{"enabled":false,"mode":"conservative","failure_threshold":3,"cooldown_minutes":60,"strategies":["resolver_refresh"]}}"""; assertEquals("ADAPTER_VIEW_ENGINE_UNDECLARED",AdapterValidator.validate(json).errorCode) }
    @Test fun schemaV3RejectsUnsafeRepair(){ val json="""{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"generic_html5","engine_dependencies":[],"permissions":{"allowed_hosts":["example.com"]},"repair":{"enabled":true,"mode":"execute_script","failure_threshold":3,"cooldown_minutes":60,"strategies":["resolver_refresh"]}}"""; assertEquals("ADAPTER_REPAIR_MODE_UNSUPPORTED",AdapterValidator.validate(json).errorCode) }
    @Test fun kvsFlashvarsRemainsLegacyMetaStrategy(){ val json="""{"version":3,"id":"sample","name":"Sample","adapter_version":"1.0.0","base_url":"https://example.com","engine":"kvs_videos","engine_dependencies":["generic_html5","iframe_chain","jwplayer_config","videojs_config","oembed_provider","hls_manifest","dash_manifest"],"permissions":{"allowed_hosts":["example.com"]},"view":{"strategies":["kvs_videos","kvs_flashvars","generic_html5"]},"repair":{"enabled":false,"mode":"conservative","failure_threshold":3,"cooldown_minutes":60,"strategies":["resolver_refresh"]}}"""; val r=AdapterValidator.validate(json); assertTrue("${r.errorCode} ${r.errorMessage}",r.isValid) }
}
