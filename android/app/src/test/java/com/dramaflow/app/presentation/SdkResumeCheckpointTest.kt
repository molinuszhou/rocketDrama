package com.dramaflow.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkResumeCheckpointTest {
    @Test
    fun `holds saved history position while PSSDK first callback reports zero`() {
        val checkpoint = SdkResumeCheckpoint(episodeIndex = 3, positionSeconds = 94)

        assertTrue(checkpoint.matches(3))
        assertEquals(94, checkpoint.displayedProgress(0))
        assertFalse(checkpoint.isConfirmedBy(0))
    }

    @Test
    fun `confirms only when PSSDK reaches the requested history position`() {
        val checkpoint = SdkResumeCheckpoint(episodeIndex = 3, positionSeconds = 94)

        assertFalse(checkpoint.isConfirmedBy(93))
        assertTrue(checkpoint.isConfirmedBy(94))
        assertEquals(112, checkpoint.displayedProgress(112))
    }

    @Test
    fun `does not apply a resume request to another episode`() {
        val checkpoint = SdkResumeCheckpoint(episodeIndex = 3, positionSeconds = 94)

        assertFalse(checkpoint.matches(2))
    }
}
