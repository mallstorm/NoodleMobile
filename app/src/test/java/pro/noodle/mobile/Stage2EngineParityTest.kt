package pro.noodle.mobile

import org.junit.Assert.*
import org.junit.Test
import pro.noodle.mobile.core.engine.impl.*
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.resolver.ResolverFetcher
import pro.noodle.mobile.core.resolver.ResolverPipeline

class Stage2EngineParityTest {
    @Test fun runtimeRegistryImplementsAllCanonicalEngines() {
        assertEquals(15, RuntimeEngineRegistry.implementedIds.size)
    }

    @Test fun genericHtml5FindsSourcesAndSubtitles() {
        val html = """<html><head><title>Sample</title></head><body><video><source src="/720.mp4" type="video/mp4" label="720p"><track src="/ru.vtt" kind="subtitles" srclang="ru" default></video></body></html>"""
        val r = GenericHtml5Engine().resolvePlayback(html, "https://example.com/watch/1")
        assertTrue(r.success)
        assertEquals("https://example.com/720.mp4", r.sources.single().url)
        assertEquals("ru", r.tracks.single().language)
    }

    @Test fun hlsExpandsVariantsAndRejectsSampleAes() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
720.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
1080.m3u8
"""
        val ok = HlsManifestEngine().resolvePlayback(master, "https://example.com/master.m3u8")
        assertTrue(ok.success)
        assertEquals(2, ok.variants.size)
        val drm = HlsManifestEngine().resolvePlayback("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://license\"", "https://example.com/p.m3u8")
        assertEquals("HLS_DRM_UNSUPPORTED", drm.errorCode)
    }

    @Test fun dashExtractsTracksAndRejectsWidevine() {
        val mpd = """<MPD><Period><AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v1" width="1280" height="720" bandwidth="2000000"/></AdaptationSet><AdaptationSet contentType="audio" lang="ru" mimeType="audio/mp4"><Representation id="a1"/></AdaptationSet></Period></MPD>"""
        val ok = DashManifestEngine().resolvePlayback(mpd, "https://example.com/master.mpd")
        assertTrue(ok.success)
        assertEquals(1, ok.variants.size)
        assertEquals(1, ok.audioTracks.size)
        val protectedMpd = """<MPD><Period><AdaptationSet><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/></AdaptationSet></Period></MPD>"""
        assertEquals("DASH_DRM_UNSUPPORTED", DashManifestEngine().resolvePlayback(protectedMpd, "https://example.com/p.mpd").errorCode)
    }

    @Test fun jwVideoJsIframeAndOembedAreRecognized() {
        assertTrue(JwPlayerEngine().resolvePlayback("<script>jwplayer('p').setup({file:'/a.m3u8'});</script>", "https://example.com/v").success)
        assertTrue(VideoJsEngine().resolvePlayback("<video data-setup='{\"sources\":[{\"src\":\"/a.mpd\",\"type\":\"application/dash+xml\"}]}'></video>", "https://example.com/v").success)
        assertEquals("https://example.com/embed/1", IFrameChainEngine().resolvePlayback("<iframe src='/embed/1'></iframe>", "https://example.com/v").followUpPages.single())
        assertTrue(OEmbedEngine().resolvePlayback("<link rel='alternate' type='application/json+oembed' href='/oembed'>", "https://example.com/v").handled)
    }

    @Test fun resolverKeepsFallbackAndExpandsManifestWhenAvailable() {
        val html = """<video><source src="https://example.com/master.m3u8" type="application/vnd.apple.mpegurl"></video>"""
        val fallback = ResolverPipeline.resolve(html, "https://example.com/v", "generic_html5", listOf("hls_manifest"), ResolverFetcher { null })
        assertEquals(1, fallback.sources.size)
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n720.m3u8\n"
        val expanded = ResolverPipeline.resolve(html, "https://example.com/v", "generic_html5", listOf("hls_manifest"), ResolverFetcher { master })
        assertEquals(2, expanded.sources.size)
    }

    @Test fun declarativeJsonWordpressAndSosalkinoParsersWork() {
        val declarative = DeclarativeHtmlEngine().parseSearch(
            "<article class='video'><a href='/watch/42'><span class='title'>Demo</span></a></article>",
            EngineSearchRequest("https://example.com", "demo", itemSelector="article.video", titleSelector="span.title", urlSelector="a"),
        )
        assertEquals("Demo", declarative.single().title)
        val json = JsonApiEngine().parseSearch(
            "{\"data\":{\"items\":[{\"id\":\"42\",\"title\":\"API\",\"url\":\"/v/42\"}]}}",
            EngineSearchRequest("https://example.com", "api", itemsPath="$.data.items", idPath="$.id", titlePath="$.title", urlPath="$.url"),
        )
        assertEquals("42", json.single().remoteId)
        assertTrue(WordPressRestEngine().buildSearchUrl(EngineSearchRequest("https://example.com", "demo", 0, 25)).contains("wp-json"))
        val sos = SosalkinoCatalogEngine().parseSearch("<a href='/video/demo'><div class='video'><span class='videotitle'>Demo</span></div></a>", EngineSearchRequest("https://example.com", "demo"))
        assertEquals("demo", sos.single().remoteId)
    }
}
