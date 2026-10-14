package com.dramaflow.app.content

/**
 * Keeps PSSDK's two index conventions at the integration boundary.
 *
 * `startPlayIndex` is a zero-based playback position, while the detail-page listener reports
 * `episodeIndex` as the human-facing, one-based episode number. App state, reward records and
 * checkpoint persistence consistently use a zero-based local position.
 */
internal object PssdkEpisodeIndexMapper {
    fun localPositionToStartPlayIndex(localPosition: Int): Int = localPosition.coerceAtLeast(0)

    fun callbackEpisodeNumberToLocalPosition(callbackEpisodeNumber: Int): Int =
        (callbackEpisodeNumber - FIRST_CALLBACK_EPISODE_NUMBER).coerceAtLeast(0)

    private const val FIRST_CALLBACK_EPISODE_NUMBER = 1
}
