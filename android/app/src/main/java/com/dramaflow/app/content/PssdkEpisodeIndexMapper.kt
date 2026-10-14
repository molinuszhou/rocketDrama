package com.dramaflow.app.content

/**
 * Keeps PSSDK's two index conventions at the integration boundary.
 *
 * PSSDK's `startPlayIndex` and detail-page listener both use the human-facing, one-based episode
 * number. App state, reward records and checkpoint persistence consistently use a zero-based
 * local position, so this is the sole integration boundary for both conversions.
 */
internal object PssdkEpisodeIndexMapper {
    fun localPositionToStartPlayIndex(localPosition: Int): Int =
        localPosition.coerceAtLeast(0) + FIRST_CALLBACK_EPISODE_NUMBER

    fun callbackEpisodeNumberToLocalPosition(callbackEpisodeNumber: Int): Int =
        (callbackEpisodeNumber - FIRST_CALLBACK_EPISODE_NUMBER).coerceAtLeast(0)

    private const val FIRST_CALLBACK_EPISODE_NUMBER = 1
}
