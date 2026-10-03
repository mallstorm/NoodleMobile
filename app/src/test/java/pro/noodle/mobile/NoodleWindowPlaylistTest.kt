package pro.noodle.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.noodle.mobile.core.engine.impl.JwPlayerEngine

class NoodleWindowPlaylistTest {
    @Test
    fun resolvesWindowPlaylistIntoDirectCdnSources() {
        val html = """
            <html><head><script>
            window.playlist = {
              "image":"https://cdn2.pvvstream.pro/videos/a/b/preview_800.jpg",
              "sources":[
                {"file":"https://cdn.pvvstream.pro/videos/a/b/vid_720p.mp4?secure=abc%3D%3D%2C9999999999","label":"720","type":"mp4"},
                {"file":"https://cdn.pvvstream.pro/videos/a/b/vid_480p.mp4?secure=def%3D%3D%2C9999999999","label":"480","type":"mp4","default":true}
              ],
              "trusted":true
            };
            </script></head></html>
        """.trimIndent()

        val result = JwPlayerEngine().resolvePlayback(
            html,
            "https://noodlemagazine.com/watch/test",
        )

        assertTrue(result.success)
        assertEquals(2, result.sources.size)
        assertTrue(result.sources.all { it.url.startsWith("https://cdn.pvvstream.pro/") })
        assertEquals("480", result.sources.first { it.isDefault }.label)
        assertEquals("https://noodlemagazine.com/watch/test", result.playerPageUrl)
    }
}
