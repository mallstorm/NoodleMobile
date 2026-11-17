package pro.noodle.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.noodle.mobile.core.model.MediaSource
import pro.noodle.mobile.core.resolver.DeepMediaMiner
import pro.noodle.mobile.core.resolver.DurationText
import pro.noodle.mobile.core.resolver.Mp4Duration
import pro.noodle.mobile.core.resolver.StreamFacts
import pro.noodle.mobile.core.resolver.StreamHints
import pro.noodle.mobile.core.resolver.StreamScorer
import pro.noodle.mobile.core.resolver.StreamVerdict
import java.nio.ByteBuffer
import java.util.Base64

class StreamSelectionTest {
    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()

    private fun mp4(durationSec: Int, mdatBytes: Int): ByteArray {
        val mvhdPayload = ByteBuffer.allocate(100).apply { putInt(0); putInt(0); putInt(0); putInt(1000); putInt(durationSec * 1000) }.array()
        val moov = box("moov", box("mvhd", mvhdPayload))
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isomiso2".toByteArray(Charsets.ISO_8859_1))
        return ftyp + box("mdat", ByteArray(mdatBytes)) + moov
    }

    @Test
    fun durationTextParsesCardBadges() {
        assertEquals(754, DurationText.seconds("12:34"))
        assertEquals(3723, DurationText.seconds("1:02:03"))
        assertEquals(3723, DurationText.seconds("PT1H2M3S"))
        assertEquals(720, DurationText.seconds("12 мин"))
        assertEquals(0, DurationText.seconds(""))
    }

    @Test
    fun mp4DurationFoundWhenMoovIsAtTheEnd() {
        val file = mp4(754, 5000)
        val d = Mp4Duration.read(file.copyOf(8192.coerceAtMost(file.size)), file.size.toLong()) { off, len ->
            if (off >= file.size) null else file.copyOfRange(off.toInt(), minOf(file.size, off.toInt() + len))
        }
        assertEquals(754.0, d, 0.01)
    }

    @Test
    fun scorerPicksFullStreamOverFiveSecondPreview() {
        val preview = MediaSource(label = "mp4", url = "https://cdn.x.com/p/abc_s.mp4", protocol = "http", isDefault = true)
        val full = MediaSource(label = "720p", url = "https://cdn.x.com/v/abc_720.mp4", protocol = "http")
        val facts = mapOf(
            preview.url to StreamFacts(preview.url, ok = true, kind = "mp4", durationSec = 5.0, bytes = 700_000),
            full.url to StreamFacts(full.url, ok = true, kind = "mp4", durationSec = 751.0, bytes = 150_000_000),
        )
        val ranked = StreamScorer.rank(listOf(preview, full), facts, 754, StreamHints())
        assertEquals(full.url, ranked.first().source.url)
        assertEquals(StreamVerdict.FULL, ranked.first().verdict)
        assertEquals(StreamVerdict.PREVIEW, ranked.last().verdict)
    }

    @Test
    fun scorerUsesSizeWhenDurationUnknown() {
        val a = MediaSource(url = "https://cdn.x.com/a.mp4", protocol = "http")
        val b = MediaSource(url = "https://cdn.x.com/b.mp4", protocol = "http")
        val facts = mapOf(
            a.url to StreamFacts(a.url, ok = true, kind = "mp4", bytes = 600_000),
            b.url to StreamFacts(b.url, ok = true, kind = "mp4", bytes = 90_000_000),
        )
        val ranked = StreamScorer.rank(listOf(a, b), facts, 0, StreamHints())
        assertEquals(b.url, ranked.first().source.url)
    }

    @Test
    fun learnedRulesRejectAndPrefer() {
        val a = MediaSource(url = "https://cdn.x.com/hover/1.mp4", protocol = "http")
        val b = MediaSource(url = "https://cdn.x.com/full/1.mp4", protocol = "http")
        val facts = mapOf(a.url to StreamFacts(a.url, ok = true, kind = "mp4"), b.url to StreamFacts(b.url, ok = true, kind = "mp4"))
        val ranked = StreamScorer.rank(listOf(a, b), facts, 0, StreamHints(prefer = "/full/", reject = "/hover/"))
        assertEquals(b.url, ranked.first().source.url)
        assertEquals(StreamVerdict.PREVIEW, ranked.last().verdict)
    }

    @Test
    fun minerFindsEscapedAndBase64Streams() {
        val b64 = Base64.getEncoder().encodeToString("https://cdn.y.com/hls/master.m3u8".toByteArray())
        val page = """<script>var cfg={"video_url":"https:\/\/cdn.x.com\/v\/123.mp4?tok=1&e=2"}; var s=atob("$b64");</script>"""
        val urls = DeepMediaMiner.mine(page, "https://x.com/watch/1").map { it.url }
        assertTrue(urls.any { it.startsWith("https://cdn.x.com/v/123.mp4") && it.contains("tok=1&e=2") })
        assertTrue(urls.any { it == "https://cdn.y.com/hls/master.m3u8" })
    }

    @Test
    fun minerAppliesLearnedExtractRule() {
        val page = """<div data-x="stream:'//cdn.z.com/get/777'">"""
        val urls = DeepMediaMiner.mine(page, "https://z.com/v", listOf("stream:'((?:https?:)?//[^']+)'")).map { it.url }
        // no media extension → ignored by the safety filter, proving extract cannot smuggle arbitrary URLs
        assertTrue(urls.isEmpty())
    }

    @org.junit.Test
    fun shortClipIsPreviewEvenWithoutCardDuration() {
        val clip = pro.noodle.mobile.core.model.MediaSource(label = "720p", url = "https://cdn.example.com/v/123.mp4", mimeType = "video/mp4", protocol = "http")
        val ranked = pro.noodle.mobile.core.resolver.StreamScorer.rank(
            listOf(clip),
            mapOf(clip.url to pro.noodle.mobile.core.resolver.StreamFacts(clip.url, ok = true, kind = "mp4", durationSec = 5.2, bytes = 900_000)),
            0, pro.noodle.mobile.core.resolver.StreamHints(),
        )
        org.junit.Assert.assertEquals(pro.noodle.mobile.core.resolver.StreamVerdict.PREVIEW, ranked.first().verdict)
        // ...but a card that itself says 0:12 keeps its short video.
        val short = pro.noodle.mobile.core.resolver.StreamScorer.rank(
            listOf(clip),
            mapOf(clip.url to pro.noodle.mobile.core.resolver.StreamFacts(clip.url, ok = true, kind = "mp4", durationSec = 12.0, bytes = 2_000_000)),
            12, pro.noodle.mobile.core.resolver.StreamHints(),
        )
        org.junit.Assert.assertNotEquals(pro.noodle.mobile.core.resolver.StreamVerdict.PREVIEW, short.first().verdict)
    }

    @org.junit.Test
    fun previewUrlVariantsAndEmbeds() {
        val v = pro.noodle.mobile.core.resolver.PreviewUrlVariants.of("https://cdn.example.com/get_file/1/abc/123_preview.mp4/?t=1")
        org.junit.Assert.assertTrue(v.toString(), v.contains("https://cdn.example.com/get_file/1/abc/123.mp4/?t=1"))
        val w = pro.noodle.mobile.core.resolver.PreviewUrlVariants.of("https://cdn.example.com/previews/720/clip-trailer-720p.mp4")
        org.junit.Assert.assertTrue(w.toString(), w.contains("https://cdn.example.com/720/clip-720p.mp4"))
        val html = """<iframe src="//player.example.net/embed/55" width=1></iframe><iframe src="https://ads.exoclick.com/x"></iframe>
            <script type="application/ld+json">{"embedUrl":"https:\/\/v.example.org\/e\/77"}</script>"""
        val e = pro.noodle.mobile.core.resolver.EmbedFinder.find(listOf("https://site.example.com/video/1" to html))
        org.junit.Assert.assertTrue(e.toString(), e.contains("https://player.example.net/embed/55"))
        org.junit.Assert.assertTrue(e.toString(), e.contains("https://v.example.org/e/77"))
        org.junit.Assert.assertFalse(e.toString(), e.any { it.contains("exoclick") })
    }
}
