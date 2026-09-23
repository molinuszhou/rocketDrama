package com.dramaflow.app.presentation

/**
 * A short-lived resume point retained only while a full-screen ad may send the user to an
 * external store/browser. It is deliberately separate from ordinary watch history so an old
 * click cannot unexpectedly reopen a player days later.
 */
internal data class PlaybackReturnCheckpoint(
    val dramaId: String,
    val episodeIndex: Int,
    val positionSeconds: Int,
    val savedAtMillis: Long,
) {
    fun isFresh(nowMillis: Long, maxAgeMillis: Long = AD_RETURN_CHECKPOINT_MAX_AGE_MILLIS): Boolean =
        dramaId.isNotBlank() &&
            episodeIndex >= 0 &&
            positionSeconds >= 0 &&
            nowMillis >= savedAtMillis &&
            nowMillis - savedAtMillis <= maxAgeMillis
}
