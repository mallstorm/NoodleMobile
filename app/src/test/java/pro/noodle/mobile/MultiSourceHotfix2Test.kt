package pro.noodle.mobile

import org.junit.Assert.*
import org.junit.Test
import pro.noodle.mobile.core.engine.impl.KvsSourceEngine
import pro.noodle.mobile.core.engine.impl.NovideoEmbedEngine
import pro.noodle.mobile.core.engine.runtime.EngineSearchRequest

class MultiSourceHotfix2Test {
    @Test fun kvsMixedLayoutDoesNotLetCatalogLinksHideRealVideos(){
        val html="""<a href='/videos/feed' class='item'>feed</a><a href='/video/real-video-123'><img src='/thumb.jpg' alt='Real'></a>"""
        val items=KvsSourceEngine("kvs_videos", emptySet(), KvsSourceEngine.Mode.VIDEOS).parseSearch(html,EngineSearchRequest("https://example.com","q"))
        assertEquals(1,items.size);assertEquals("/video/real-video-123",items[0].watchPath)
    }
    @Test fun kvsViewStillParsesClassicView(){
        val html="""<ul id='list_videos_common_items'><a href='/view/42/'><img src='/42.jpg' alt='Classic'></a></ul>"""
        val items=KvsSourceEngine("kvs_view", emptySet(), KvsSourceEngine.Mode.VIEW).parseSearch(html,EngineSearchRequest("https://example.com","q"))
        assertEquals(1,items.size);assertEquals("/view/42/",items[0].watchPath)
    }
    @Test fun novideoUsesConfirmedCategoryEndpoint(){
        val first = NovideoEmbedEngine().buildSearchUrl(EngineSearchRequest("https://video.example", "mamki"))
        assertEquals("https://video.example/mamki/", first)
        val second = NovideoEmbedEngine().buildSearchUrl(EngineSearchRequest("https://video.example", "mamki", page = 1))
        assertEquals("https://video.example/mamki/2/", second)
    }
}
