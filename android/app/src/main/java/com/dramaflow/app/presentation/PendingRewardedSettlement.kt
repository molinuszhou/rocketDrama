package com.dramaflow.app.presentation

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
) {
    fun isFresh(
        nowMillis: Long,
        maxAgeMillis: Long = PENDING_REWARDED_SETTLEMENT_MAX_AGE_MILLIS,
    ): Boolean =
        eventId.length >= 8 &&
            userId.isNotBlank() &&
            placementId.isNotBlank() &&
            (episodeIndex == null || episodeIndex > 0) &&
            nowMillis >= savedAtMillis &&
            nowMillis - savedAtMillis <= maxAgeMillis
}
