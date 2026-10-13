package com.dramaflow.app.presentation

/**
 * Lightweight state saved when the task moves to background. Android may reclaim a stopped
 * process at any time, so this checkpoint restores the last surface without trying to keep the
 * process artificially alive.
 */
internal data class AppReturnCheckpoint(
    val activeTabName: String,
    val playerReturnTabName: String,
    val playerVisible: Boolean,
    val dramaId: String?,
    val episodeIndex: Int,
    val positionSeconds: Int,
    val tasksScrollY: Int,
    val savedAtMillis: Long,
) {
    fun isFresh(
        nowMillis: Long,
        maxAgeMillis: Long = APP_RETURN_CHECKPOINT_MAX_AGE_MILLIS,
    ): Boolean {
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
