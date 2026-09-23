package com.dramaflow.app.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReturnCheckpointTest {
    @Test
    fun `accepts a recent valid ad return checkpoint`() {
        val now = 1_000_000L
        val checkpoint = PlaybackReturnCheckpoint("drama-1", 2, 94, now - 30_000L)

        assertTrue(checkpoint.isFresh(now))
    }

    @Test
    fun `rejects expired or malformed ad return checkpoints`() {
        val now = 1_000_000L

        assertFalse(PlaybackReturnCheckpoint("drama-1", 2, 94, now - AD_RETURN_CHECKPOINT_MAX_AGE_MILLIS - 1L).isFresh(now))
        assertFalse(PlaybackReturnCheckpoint("", 2, 94, now - 1L).isFresh(now))
        assertFalse(PlaybackReturnCheckpoint("drama-1", -1, 94, now - 1L).isFresh(now))
        assertFalse(PlaybackReturnCheckpoint("drama-1", 2, -1, now - 1L).isFresh(now))
    }
}
