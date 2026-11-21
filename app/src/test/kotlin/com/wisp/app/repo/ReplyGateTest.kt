package com.wisp.app.repo

import com.wisp.app.repo.ReplyGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyGateTest {

    private fun decide(
        m: Int?, f: Int?, score: Float?,
        mute: Boolean = true, follow: Boolean = true
    ) = ReplyGate.decide(m, f, score, mute, follow)

    @Test fun `m3 f6 hides regardless of model`() {
        assertEquals(Verdict.HIDE, decide(3, 6, null))
        assertEquals(Verdict.HIDE, decide(3, 6, 0.0f))
    }

    @Test fun `m3 f7 ratio fails and falls to the follow rule`() {
        assertEquals(Verdict.KEEP, decide(3, 7, 0.95f))
        assertEquals(Verdict.KEEP, decide(3, 7, null))
    }

    @Test fun `m2 f0 lets the model hide`() {
        assertEquals(Verdict.HIDE, decide(2, 0, 0.9f))
    }

    @Test fun `m0 f3 keeps even with model 1`() {
        assertEquals(Verdict.KEEP, decide(0, 3, 1.0f))
    }

    @Test fun `below every threshold the model decides`() {
        assertEquals(Verdict.KEEP, decide(2, 2, 0.69f))
        assertEquals(Verdict.HIDE, decide(2, 2, 0.7f))
        assertEquals(Verdict.ASK_MODEL, decide(2, 2, null))
    }

    @Test fun `muted rule beats followed rule`() {
        assertEquals(Verdict.HIDE, decide(5, 10, 0.0f))
        assertEquals(Verdict.HIDE, decide(3, 0, 0.0f))
    }

    @Test fun `mute flag off skips rule 1`() {
        assertEquals(Verdict.KEEP, decide(3, 6, 0.9f, mute = false))
        assertEquals(Verdict.KEEP, decide(4, 0, 0.1f, mute = false))
        assertEquals(Verdict.HIDE, decide(4, 0, 0.9f, mute = false))
    }

    @Test fun `follow flag off skips rule 2`() {
        assertEquals(Verdict.HIDE, decide(0, 3, 1.0f, follow = false))
        assertEquals(Verdict.ASK_MODEL, decide(3, 7, null, follow = false))
        assertEquals(Verdict.HIDE, decide(3, 6, null, follow = false))
    }

    @Test fun `both flags off equals the model alone`() {
        for ((m, f) in listOf(3 to 6, 0 to 3, 10 to 0)) {
            assertEquals(Verdict.HIDE, decide(m, f, 0.8f, mute = false, follow = false))
            assertEquals(Verdict.KEEP, decide(m, f, 0.2f, mute = false, follow = false))
            assertEquals(Verdict.ASK_MODEL, decide(m, f, null, mute = false, follow = false))
        }
    }

    @Test fun `no graph data equals today`() {
        assertEquals(Verdict.HIDE, decide(null, null, 0.7f))
        assertEquals(Verdict.KEEP, decide(null, null, 0.69f))
        assertEquals(Verdict.ASK_MODEL, decide(null, null, null))
        assertNull(ReplyGate.networkVerdict(null, null, true, true))
    }

    @Test fun `missing follow counts turn off the mute rule`() {
        // Rule 1 needs both counts.
        assertEquals(Verdict.KEEP, decide(5, null, 0.1f))
        // Rule 2 needs f only.
        assertEquals(Verdict.KEEP, decide(null, 3, 1.0f))
    }

    @Test fun `networkVerdict is null when the model should decide`() {
        assertNull(ReplyGate.networkVerdict(2, 2, true, true))
        assertEquals(Verdict.HIDE, ReplyGate.networkVerdict(3, 6, true, true))
        assertEquals(Verdict.KEEP, ReplyGate.networkVerdict(3, 7, true, true))
    }

    @Test fun `table lookups below threshold give the same decision as exact counts`() {
        // PubkeyCountTable keeps only counts >= 3; absent reads 0. Check that
        // replacing any count below 3 with 0 never changes the decision.
        for (m in 0..8) for (f in 0..20) for (score in listOf(null, 0.1f, 0.9f)) {
            val mT = if (m >= ReplyGate.MIN_MUTED_BY) m else 0
            val fT = if (f >= ReplyGate.MIN_FOLLOWED_BY) f else 0
            assertEquals("m=$m f=$f s=$score", decide(m, f, score), decide(mT, fT, score))
        }
    }
}
