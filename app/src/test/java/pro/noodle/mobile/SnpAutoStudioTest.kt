package pro.noodle.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.noodle.mobile.core.engine.runtime.EngineParsing
import pro.noodle.mobile.core.adapter.AdapterValidator
import pro.noodle.mobile.core.engine.impl.AutoCards
import pro.noodle.mobile.core.engine.impl.AutoCatalogEngine
import pro.noodle.mobile.core.engine.impl.DeclarativeHtmlEngine
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest
import pro.noodle.mobile.core.engine.runtime.RuntimeEngineRegistry
import pro.noodle.mobile.core.engine.runtime.SearchUrls
import pro.noodle.mobile.core.studio.auto.CardLearner
import pro.noodle.mobile.core.studio.auto.SnpAutoBuilder

class SnpAutoStudioTest {
    private val tube = """
        <html><head><title>Demo Tube - free videos</title></head><body>
          <nav><a href="/">Home</a><a href="/categories/">Categories</a><a href="/tags/blonde/">Tag</a><a href="/login/">Login</a></nav>
          <div class="list">
            <div class="thumb-box"><div class="inner"><a href="/videos/1001/sunset-beach-walk/" title="Sunset beach walk"><img data-src="/t/1001.jpg" src="/img/blank.gif"></a></div><span class="dur">12:05</span><span class="v">1.2K views</span></div>
            <div class="thumb-box"><div class="inner"><a href="/videos/1002/city-night-ride/"><img data-src="/t/1002.jpg"></a></div><a href="/videos/1002/city-night-ride/" class="t">City night ride</a><span class="dur">7:41</span></div>
            <div class="thumb-box"><div class="inner"><a href="/videos/1003/forest-trail/"><img src="/t/1003.jpg" alt="Forest trail"></a></div><span class="dur">1:02:11</span></div>
            <div class="thumb-box"><div class="inner"><a href="/videos/1004/mountain-lake/"><img srcset="/t/1004s.jpg 1x, /t/1004.jpg 2x"></a></div></div>
          </div>
          <div class="cats"><a href="/categories/a/"><img src="/c/a.jpg"></a><a href="/categories/b/"><img src="/c/b.jpg"></a><a href="/categories/c/"><img src="/c/c.jpg"></a></div>
          <div class="pager"><a href="/search/beach/2/">2</a><a href="/search/beach/3/">3</a></div>
        </body></html>
    """.trimIndent()

    @Test
    fun learnerPicksVideoCardsNotCategoriesOrNav() {
        val learned = CardLearner.learn(tube, "https://demo.test/search/beach/", "beach")
        assertNotNull(learned)
        assertEquals("/videos/#/*/", learned!!.shape)
        assertTrue(Regex(learned.pattern, RegexOption.IGNORE_CASE).containsMatchIn("/videos/1001/sunset-beach-walk/"))
        assertFalse(Regex(learned.pattern, RegexOption.IGNORE_CASE).containsMatchIn("/categories/a/"))
    }

    @Test
    fun autoCardsExtractTitlesThumbsAndDurations() {
        val learned = CardLearner.learn(tube, "https://demo.test/search/beach/", "beach")!!
        val items = AutoCards.extract(tube, "https://demo.test", Regex(learned.pattern, RegexOption.IGNORE_CASE), 40)
        assertEquals(4, items.size)
        assertEquals("Sunset beach walk", items[0].title)
        assertEquals("https://demo.test/t/1001.jpg", items[0].thumbnailUrl)
        assertEquals("12:05", items[0].durationText)
        assertEquals("City night ride", items[1].title)
        assertEquals("Forest trail", items[2].title)
        assertEquals("1:02:11", items[2].durationText)
        assertEquals("https://demo.test/t/1004.jpg", items[3].thumbnailUrl)
        assertEquals("1001", items[0].remoteId)
    }

    @Test
    fun autoCatalogEngineIsRegisteredAndParses() {
        val engine = RuntimeEngineRegistry.find("auto_catalog")
        assertTrue(engine is AutoCatalogEngine)
        val req = EngineSearchRequest(baseUrl = "https://demo.test", query = "beach", linkPattern = "^/videos/\\d+/[^/]+/?$")
        assertEquals(4, engine!!.parseSearch(tube, req).size)
    }

    @Test
    fun searchUrlsPreferTemplateAndNeverRepeatFirstPage() {
        val engine = RuntimeEngineRegistry.find("kvs_view")!!
        val first = EngineSearchRequest(baseUrl = "https://demo.test", query = "a b", page = -1, searchUrlTemplate = "/search/{query}/")
        assertEquals("https://demo.test/search/a%20b/", SearchUrls.candidates(engine, first).first())
        val second = first.copy(page = 1)
        assertFalse(SearchUrls.candidates(engine, second).contains("https://demo.test/search/a%20b/"))
        val paged = second.copy(searchPageTemplate = "/search/{query}/{page}/")
        assertEquals("https://demo.test/search/a%20b/2/", SearchUrls.candidates(engine, paged).first())
    }

    @Test
    fun declarativeEngineSupportsClassOnlyAndNestedBlocks() {
        val html = """<div class="card"><div class="pic"><a href="/v/1"><img src="/1.jpg"></a></div><div class="name">One</div></div>
            <div class="card"><div class="pic"><a href="/v/2"><img src="/2.jpg"></a></div><div class="name">Two</div></div>"""
        val req = EngineSearchRequest(baseUrl = "https://d.test", query = "x", itemSelector = ".card", titleSelector = "div.name", urlSelector = "a")
        val items = DeclarativeHtmlEngine().parseSearch(html, req)
        assertEquals(listOf("One", "Two"), items.map { it.title })
        assertEquals("https://d.test/2.jpg", items[1].thumbnailUrl)
    }

    @Test
    fun shapesRejectMenusAndAcceptItemSlugs() {
        assertEquals(null, CardLearner.shape("/about"))
        assertEquals(null, CardLearner.shape("/latest"))
        assertEquals("/*", CardLearner.shape("/hot-summer-trip-4512"))
        assertEquals("/#.html", CardLearner.shape("/4512.html"))
        assertEquals("/watch/*", CardLearner.shape("/watch/-193992740_456240441"))
    }

    @Test
    fun rootSlugPatternDoesNotMatchMenuLinks() {
        val re = Regex(CardLearner.toRegex("/*/"), RegexOption.IGNORE_CASE)
        assertTrue(re.containsMatchIn("/hot-summer-trip-4512/"))
        assertTrue(re.containsMatchIn("/my-long-video-title/"))
        assertFalse(re.containsMatchIn("/new/"))
        assertFalse(re.containsMatchIn("/babushki/"))
        assertFalse(re.containsMatchIn("/anal-molodye/"))
    }

    @Test
    fun inputNormalizationAcceptsBareDomains() {
        assertEquals("https://example.com/", SnpAutoBuilder.normalizeInput("example.com"))
        assertEquals("https://www.example.com/", SnpAutoBuilder.normalizeInput("http://www.example.com/some/page?x=1"))
        assertEquals(null, SnpAutoBuilder.normalizeInput("localhost"))
        assertEquals("example.co.uk", SnpAutoBuilder.registrable("m.example.co.uk"))
    }

    @Test
    fun autoAdapterSchemaValidates() {
        val json = """{"version":3,"id":"demo.test","name":"Demo","adapter_version":"1.0.0","base_url":"https://demo.test","engine":"auto_catalog",
            "engine_dependencies":["jwplayer_config","videojs_config","generic_player_config","generic_html5","oembed_provider","iframe_chain","hls_manifest","dash_manifest"],
            "permissions":{"allowed_hosts":["demo.test","*.demo.test"]},
            "search":{"url_template":"/search/{query}/","page_template":"/search/{query}/{page}/","link_pattern":"^/videos/\\d+/[^/]+/?$"},
            "view":{"strategies":["primary","jwplayer","videojs","player_config","generic_html5","oembed","iframe","hls","dash"]},
            "repair":{"mode":"conservative"}}"""
        val v = AdapterValidator.validate(json)
        assertTrue(v.errorCode + " " + v.errorMessage, v.isValid)
        assertEquals("^/videos/\\d+/[^/]+/?$", v.definition!!.linkPattern)
        val broken = AdapterValidator.validate(json.replace("\"page_template\":\"/search/{query}/{page}/\",", "\"page_template\":\"/search/x/\","))
        assertFalse(broken.isValid)
    }

    @Test
    fun genericResolverRejectsHoverPreviewEvenWithoutPreviewFilename() {
        val html = """
            <div class="related"><video class="hover-preview" muted loop playsinline
              src="https://cdn.demo.test/media/clip_711.mp4"></video></div>
            <div data-preview="https://cdn.demo.test/media/clip_712.mp4"></div>
            <script>new Playerjs({id:"player",file:"https://cdn.demo.test/media/full_1080.mp4"});</script>
        """.trimIndent()
        val resolved = pro.noodle.mobile.core.engine.impl.GenericPlayerConfigEngine()
            .resolvePlayback(html, "https://demo.test/watch/123")
        assertEquals(listOf("https://cdn.demo.test/media/full_1080.mp4"), resolved.sources.map { it.url })
    }

    @Test
    fun media3DurationGuardRejectsShortPreviewOnlyForFullLengthCards() {
        assertTrue(EngineParsing.isPreviewDuration("12:05", 5_000L))
        assertTrue(EngineParsing.isPreviewDuration("01:12:05", 5_000L))
        assertFalse(EngineParsing.isPreviewDuration("00:05", 5_000L))
        assertFalse(EngineParsing.isPreviewDuration("12:05", 120_000L))
        assertFalse(EngineParsing.isPreviewDuration("", 5_000L))
    }


    @Test
    fun relatedSearchUsesShortMeaningfulQueries() {
        val item = pro.noodle.mobile.core.model.VideoItem(title = "Смотреть Сестра онлайн", tags = listOf("семья"))
        assertEquals(listOf("сестра", "семья"),
            pro.noodle.mobile.core.recommendations.RelatedQueryPlanner.queries(item))
    }


    @Test
    fun searchGuardRejectsRedirectToHomeAndHomepageCategoryClone() {
        assertTrue(pro.noodle.mobile.core.studio.auto.SearchResponseGuard.reject(
            "https://video.test/search?q=sister", "https://video.test/", 0.0, 0))
        assertTrue(pro.noodle.mobile.core.studio.auto.SearchResponseGuard.reject(
            "https://video.test/search?q=sister", "https://video.test/search?q=sister", 0.95, 0))
        assertFalse(pro.noodle.mobile.core.studio.auto.SearchResponseGuard.reject(
            "https://video.test/?s=sister", "https://video.test/?s=sister", 0.2, 5))
        assertFalse(pro.noodle.mobile.core.studio.auto.SearchResponseGuard.reject(
            "https://video.test/search?q=sister", "https://video.test/search?q=sister", 0.3, 5))
    }

}
