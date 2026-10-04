package pro.noodle.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.noodle.mobile.core.studio.SiteKind
import pro.noodle.mobile.core.studio.StudioDiscoveryHeuristics

class StudioDiscoveryHeuristicsTest {
    @Test
    fun detectsKvsViewFamilyAndScoresItStrongly() {
        val html = """
            <html><body>
              <a href="/view/12345/"><img src="/thumb.jpg"><span class="title">video test</span></a>
              <a href="/view/67890/">another video</a>
            </body></html>
        """.trimIndent()
        val signals = StudioDiscoveryHeuristics.inspectSearch(html)
        assertEquals(SiteKind.KVS_VIEW, signals.kind)
        assertEquals("12345", signals.remoteId)
        assertEquals("/view/12345/", signals.watchPath)
        assertTrue(StudioDiscoveryHeuristics.scoreSearch(signals, html, "video", "https://site.test/search/?q=video") > 60)
    }

    @Test
    fun detectsKvsVideosForSingularVideoPaths() {
        val html = """
            <div class="item"><a href="/video/demo-slug/"><img src="/thumb.jpg"><span class="title">demo</span></a></div>
            <div class="item"><a href="/video/second-slug/"><img src="/thumb2.jpg"></a></div>
        """.trimIndent()
        val signals = StudioDiscoveryHeuristics.inspectSearch(html)
        assertEquals(SiteKind.KVS_VIDEOS, signals.kind)
        assertEquals("demo-slug", signals.remoteId)
        assertEquals("/video/demo-slug/", signals.watchPath)
    }

    @Test
    fun detectsNoodleWatchFamily() {
        val html = """
            <a href="/watch/demo-id" class="item_link"><div class="title">demo video</div></a>
        """.trimIndent()
        val signals = StudioDiscoveryHeuristics.inspectSearch(html)
        assertEquals(SiteKind.NOODLE, signals.kind)
        assertEquals("demo-id", signals.remoteId)
        assertEquals("/watch/demo-id", signals.watchPath)
    }

    @Test
    fun detectsPlayerEvidenceInViewAndMediaHosts() {
        val html = """
            <script>
              window.playlist = {sources:[{file:"https://cdn.example.org/video/demo.m3u8"}]};
            </script>
        """.trimIndent()
        val view = StudioDiscoveryHeuristics.inspectView(html)
        assertTrue(view.hasPlaylist)
        assertTrue(view.hasVideoUrl)
        assertTrue(view.strong)
        assertTrue("cdn.example.org" in StudioDiscoveryHeuristics.extractMediaHosts(html))
    }
}
