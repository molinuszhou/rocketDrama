package com.dramaflow.app.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReturnCheckpointTest {
    @Test
    fun `accepts a recent non-player ad return checkpoint without forcing playback`() {
        val now = 1_000_000L
        val checkpoint = ExternalAdReturnCheckpoint(
            activeTabName = "TASKS",
            playerReturnTabName = "TASKS",
            playerVisible = false,
            dramaId = null,
            episodeIndex = -1,
            positionSeconds = -1,
            tasksScrollY = 240,
            withdrawalPageShown = false,
            profileCollectionPageShown = false,
            profileCollectionShowsFavorites = false,
            catalogCategoryId = null,
            catalogGenre = null,
            savedAtMillis = now - 30_000L,
        )

        assertTrue(checkpoint.isFresh(now))
    }

    @Test
    fun `requires complete player data only when the click originated in a player`() {
        val now = 1_000_000L
        val checkpoint = ExternalAdReturnCheckpoint(
            activeTabName = "HOME",
            playerReturnTabName = "HOME",
            playerVisible = true,
            dramaId = "drama-1",
            episodeIndex = 2,
            positionSeconds = 94,
            tasksScrollY = 0,
            withdrawalPageShown = false,
            profileCollectionPageShown = false,
            profileCollectionShowsFavorites = false,
            catalogCategoryId = 101L,
            catalogGenre = "Romance",
            savedAtMillis = now - 1L,
        )

        assertTrue(checkpoint.isFresh(now))
        assertFalse(checkpoint.copy(dramaId = "").isFresh(now))
        assertFalse(checkpoint.copy(episodeIndex = -1).isFresh(now))
        assertFalse(checkpoint.copy(positionSeconds = -1).isFresh(now))
        assertFalse(checkpoint.copy(savedAtMillis = now - AD_RETURN_CHECKPOINT_MAX_AGE_MILLIS - 1L).isFresh(now))
    }
}
