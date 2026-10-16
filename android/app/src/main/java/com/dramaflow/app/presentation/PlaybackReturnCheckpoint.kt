package com.dramaflow.app.presentation

/**
 * A short-lived snapshot retained only after a full-screen ad opens an external store/browser.
 * It is deliberately independent from ordinary background history: an external click must return
 * to the exact pre-click route, and must never turn a non-player screen into a forced player.
 */
internal data class ExternalAdReturnCheckpoint(
    val activeTabName: String,
    val playerReturnTabName: String,
    val playerVisible: Boolean,
    val dramaId: String?,
    val episodeIndex: Int,
    val positionSeconds: Int,
    val tasksScrollY: Int,
    val withdrawalPageShown: Boolean,
    val profileCollectionPageShown: Boolean,
    val profileCollectionShowsFavorites: Boolean,
    val catalogCategoryId: Long?,
    val catalogGenre: String?,
    val adInteractionId: String?,
    val adPlacementId: String?,
    val adFormat: String?,
    val adEarlyVideoRank: Int?,
    val savedAtMillis: Long,
) {
    fun isFresh(nowMillis: Long, maxAgeMillis: Long = AD_RETURN_CHECKPOINT_MAX_AGE_MILLIS): Boolean {
        val hasValidPlayer = !playerVisible || (
            !dramaId.isNullOrBlank() && episodeIndex >= 0 && positionSeconds >= 0
        )
        return activeTabName.isNotBlank() &&
            playerReturnTabName.isNotBlank() &&
            tasksScrollY >= 0 &&
            savedAtMillis > 0L &&
            nowMillis >= savedAtMillis &&
            nowMillis - savedAtMillis <= maxAgeMillis &&
            hasValidPlayer
    }
}
