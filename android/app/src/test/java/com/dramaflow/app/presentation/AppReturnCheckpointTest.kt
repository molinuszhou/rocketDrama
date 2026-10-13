package com.dramaflow.app.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReturnCheckpointTest {
    @Test
    fun `accepts a recent tab checkpoint without a player`() {
        val now = 1_000_000L
        val checkpoint = AppReturnCheckpoint(
            activeTabName = "TASKS",
            playerReturnTabName = "TASKS",
            playerVisible = false,
            dramaId = null,
            episodeIndex = -1,
            positionSeconds = -1,
            tasksScrollY = 240,
            savedAtMillis = now - 30_000L,
        )

        assertTrue(checkpoint.isFresh(now))
    }

    @Test
    fun `requires complete recent player data and rejects expired state`() {
        val now = 1_000_000L
        val validPlayer = AppReturnCheckpoint(
            activeTabName = "HOME",
            playerReturnTabName = "HOME",
            playerVisible = true,
            dramaId = "drama-1",
            episodeIndex = 2,
            positionSeconds = 94,
            tasksScrollY = 0,
            savedAtMillis = now - 1L,
        )

        assertTrue(validPlayer.isFresh(now))
        assertFalse(validPlayer.copy(dramaId = "").isFresh(now))
        assertFalse(validPlayer.copy(positionSeconds = -1).isFresh(now))
        assertFalse(validPlayer.copy(savedAtMillis = now - APP_RETURN_CHECKPOINT_MAX_AGE_MILLIS - 1L).isFresh(now))
    }
}
