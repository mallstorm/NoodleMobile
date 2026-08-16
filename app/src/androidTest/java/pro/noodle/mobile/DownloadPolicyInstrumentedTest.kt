package pro.noodle.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import pro.noodle.mobile.core.download.AdaptiveDownloadSelection
import pro.noodle.mobile.core.download.DownloadPolicyCodec
import pro.noodle.mobile.core.network.NetworkPolicy

@RunWith(AndroidJUnit4::class)
class DownloadPolicyInstrumentedTest {
    @Test fun policyRoundTripRemainsFailClosed() {
        val policy = NetworkPolicy(listOf("cdn.example", "*.media.example"), allowHttp = false, allowCookies = true)
        val decoded = DownloadPolicyCodec.decode(DownloadPolicyCodec.encode("fixture", policy))
        assertNotNull(decoded)
        assertEquals("fixture", decoded!!.sourceKey)
        assertEquals(policy.allowedHosts, decoded.policy.allowedHosts)
        assertFalse(decoded.policy.allowHttp)
        assertEquals(null, DownloadPolicyCodec.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun selectionNormalizationNeverCreatesZeroSizedVideoConstraint() {
        val selection = AdaptiveDownloadSelection(0, -5, " ", " ru ", " en ", true).normalized()
        assertEquals(1, selection.maxVideoWidth)
        assertEquals(1, selection.maxVideoHeight)
        assertEquals("Auto", selection.videoLabel)
        assertEquals("ru", selection.audioLanguage)
        assertEquals("en", selection.textLanguage)
    }
}
