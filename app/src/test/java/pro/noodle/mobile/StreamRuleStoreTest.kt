package pro.noodle.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.noodle.mobile.core.player.StreamRuleStore

class StreamRuleStoreTest {
    @Test fun previewPathIsGeneralisedButPlainPathIsExact() {
        val preview = Regex(StreamRuleStore.patternFor("https://cdn.x.com/preview/123/456.mp4?token=a")!!)
        assertTrue(preview.containsMatchIn("https://cdn.x.com/preview/999/1.mp4"))
        val exact = StreamRuleStore.patternFor("https://cdn.x.com/v/123/456.mp4?token=a")!!
        assertTrue(Regex(exact).containsMatchIn("https://cdn.x.com/v/123/456.mp4?token=b"))
        assertFalse(Regex(exact).containsMatchIn("https://cdn.x.com/v/123/457.mp4"))
    }
}
