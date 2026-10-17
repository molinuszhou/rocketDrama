package com.dramaflow.app.presentation

/** A reserved ID is never a reward: only a verified callback may enter settlement recovery. */
internal enum class RewardedSettlementStage {
    PREPARED,
    VERIFIED,
    SETTLED_TARGET_PENDING,
}

/**
 * A reward callback is authoritative even when a creative immediately opens Google Play and the
 * Android process is reclaimed. Keeping the original event ID makes every retry server-idempotent.
 */
internal data class PendingRewardedSettlement(
    val eventId: String,
    val userId: String,
    val placementId: String,
    val targetId: String?,
    val episodeIndex: Int?,
    val savedAtMillis: Long,
    val stage: RewardedSettlementStage = RewardedSettlementStage.VERIFIED,
    val dramaId: String? = null,
) {
    fun isFresh(
        nowMillis: Long,
        maxAgeMillis: Long = PENDING_REWARDED_SETTLEMENT_MAX_AGE_MILLIS,
    ): Boolean =
        eventId.length >= 8 &&
            userId.isNotBlank() &&
            placementId.isNotBlank() &&
            (episodeIndex == null || episodeIndex > 0) &&
            savedAtMillis > 0L &&
            nowMillis >= savedAtMillis &&
            nowMillis - savedAtMillis <= maxAgeMillis

    fun requiresTargetRelease(): Boolean =
        stage == RewardedSettlementStage.SETTLED_TARGET_PENDING &&
            placementId == "rv_episode_pass" &&
            !dramaId.isNullOrBlank() &&
            episodeIndex != null
}
