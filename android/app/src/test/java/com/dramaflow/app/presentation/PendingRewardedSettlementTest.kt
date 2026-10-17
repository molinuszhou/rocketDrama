package com.dramaflow.app.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRewardedSettlementTest {
    @Test
    fun `accepts a recent completed reward with stable event ID`() {
        val now = 1_000_000L
        val pending = PendingRewardedSettlement(
            eventId = "topon-event-123",
            userId = "install-123",
            placementId = "rv_crown_upgrade",
            targetId = "crown-1",
            episodeIndex = null,
            savedAtMillis = now - 30_000L,
        )

        assertTrue(pending.isFresh(now))
    }

    @Test
    fun `rejects expired or incomplete pending reward settlements`() {
        val now = 1_000_000L

        assertFalse(PendingRewardedSettlement("topon-event-123", "install-123", "rv_crown_upgrade", null, null, now - PENDING_REWARDED_SETTLEMENT_MAX_AGE_MILLIS - 1L).isFresh(now))
        assertFalse(PendingRewardedSettlement("short", "install-123", "rv_crown_upgrade", null, null, now - 1L).isFresh(now))
        assertFalse(PendingRewardedSettlement("topon-event-123", "", "rv_crown_upgrade", null, null, now - 1L).isFresh(now))
        assertFalse(PendingRewardedSettlement("topon-event-123", "install-123", "", null, null, now - 1L).isFresh(now))
        assertFalse(PendingRewardedSettlement("topon-event-123", "install-123", "rv_crown_upgrade", null, 0, now - 1L).isFresh(now))
    }

    @Test
    fun `reserved ID is not a verified completion and settled episode target remains identifiable`() {
        val reserved = PendingRewardedSettlement(
            eventId = "topon-event-123",
            userId = "install-123",
            placementId = "rv_episode_pass",
            targetId = "pssdk-9:4",
            episodeIndex = 4,
            savedAtMillis = 1_000L,
            stage = RewardedSettlementStage.PREPARED,
            dramaId = "pssdk-9",
        )

        assertFalse(reserved.requiresTargetRelease())
        assertTrue(reserved.copy(stage = RewardedSettlementStage.SETTLED_TARGET_PENDING).requiresTargetRelease())
    }
}
