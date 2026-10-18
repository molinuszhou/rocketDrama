package com.dramaflow.app.presentation

/**
 * Durable journal for an incentive whose entitlement is earned by a shown interstitial.
 *
 * The server intentionally refuses to settle an incentive without its display record.  The
 * journal therefore persists the stable event identity before either network request and restores
 * record -> settlement in that exact order after a process death or a lost response.
 */
internal enum class InterstitialIncentiveKind {
    ORGANIC_INTERSTITIAL,
    REWARDED_FALLBACK,
}

internal enum class InterstitialIncentiveStage {
    /** An ID is reserved, but the ad has not been confirmed shown and is never recoverable. */
    PREPARED,
    /** The SDK confirmed the creative was displayed; recordInterstitial must complete before settlement. */
    SHOWN_NEEDS_RECORD,
    /** The server acknowledged the display record; the original incentive may now settle. */
    RECORDED_NEEDS_SETTLEMENT,
    /** Server settlement succeeded; an episode player still needs one target-aware release. */
    SETTLED_TARGET_PENDING,
}

internal data class PendingInterstitialIncentive(
    val eventId: String,
    val userId: String,
    val interstitialPlacementId: String,
    val episodeId: String?,
    val kind: InterstitialIncentiveKind,
    val originalPlacementId: String,
    val targetId: String?,
    val episodeIndex: Int?,
    val dramaId: String?,
    val stage: InterstitialIncentiveStage,
    val savedAtMillis: Long,
) {
    fun isFresh(
        nowMillis: Long,
        maxAgeMillis: Long = PENDING_INTERSTITIAL_INCENTIVE_MAX_AGE_MILLIS,
    ): Boolean =
        eventId.length >= 8 &&
            userId.isNotBlank() &&
            interstitialPlacementId.isNotBlank() &&
            originalPlacementId.isNotBlank() &&
            (episodeIndex == null || episodeIndex > 0) &&
            savedAtMillis > 0L &&
            nowMillis >= savedAtMillis &&
            nowMillis - savedAtMillis <= maxAgeMillis

    fun requiresRecordRecovery(): Boolean = stage == InterstitialIncentiveStage.SHOWN_NEEDS_RECORD

    fun requiresSettlementRecovery(): Boolean = stage == InterstitialIncentiveStage.RECORDED_NEEDS_SETTLEMENT

    fun requiresTargetRelease(): Boolean =
        stage == InterstitialIncentiveStage.SETTLED_TARGET_PENDING &&
            originalPlacementId == "rv_episode_pass" &&
            !dramaId.isNullOrBlank() &&
            episodeIndex != null
}

/** Pure state transitions shared by persistence and regression tests. */
internal object InterstitialIncentiveTransitions {
    fun afterShown(pending: PendingInterstitialIncentive, nowMillis: Long): PendingInterstitialIncentive =
        pending.copy(stage = InterstitialIncentiveStage.SHOWN_NEEDS_RECORD, savedAtMillis = nowMillis)

    fun afterRecorded(pending: PendingInterstitialIncentive, nowMillis: Long): PendingInterstitialIncentive =
        pending.copy(stage = InterstitialIncentiveStage.RECORDED_NEEDS_SETTLEMENT, savedAtMillis = nowMillis)

    fun afterSettled(pending: PendingInterstitialIncentive, nowMillis: Long): PendingInterstitialIncentive =
        pending.copy(stage = InterstitialIncentiveStage.SETTLED_TARGET_PENDING, savedAtMillis = nowMillis)
}
