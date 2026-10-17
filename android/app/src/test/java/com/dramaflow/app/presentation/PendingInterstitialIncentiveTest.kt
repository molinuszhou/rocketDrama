package com.dramaflow.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInterstitialIncentiveTest {
    private val prepared = PendingInterstitialIncentive(
        eventId = "int-event-123",
        userId = "install-123",
        interstitialPlacementId = "int_rewarded_fallback",
        episodeId = "pssdk-9-episode-4",
        kind = InterstitialIncentiveKind.REWARDED_FALLBACK,
        originalPlacementId = "rv_episode_pass",
        targetId = "pssdk-9:4",
        episodeIndex = 4,
        dramaId = "pssdk-9",
        stage = InterstitialIncentiveStage.PREPARED,
        savedAtMillis = 1_000L,
    )

    @Test
    fun `shown incentive restores record before settlement with the same event ID`() {
        val shown = InterstitialIncentiveTransitions.afterShown(prepared, 2_000L)
        val recorded = InterstitialIncentiveTransitions.afterRecorded(shown, 3_000L)

        assertEquals("int-event-123", recorded.eventId)
        assertTrue(shown.requiresRecordRecovery())
        assertFalse(shown.requiresSettlementRecovery())
        assertFalse(recorded.requiresRecordRecovery())
        assertTrue(recorded.requiresSettlementRecovery())
    }

    @Test
    fun `prepared interstitial is never a recoverable reward`() {
        assertFalse(prepared.requiresRecordRecovery())
        assertFalse(prepared.requiresSettlementRecovery())
        assertFalse(prepared.requiresTargetRelease())
    }

    @Test
    fun `settled episode target remains available for one player release`() {
        val settled = InterstitialIncentiveTransitions.afterSettled(prepared, 4_000L)

        assertTrue(settled.requiresTargetRelease())
        assertTrue(settled.isFresh(4_001L))
        assertFalse(settled.copy(savedAtMillis = 0L).isFresh(4_001L))
    }
}
