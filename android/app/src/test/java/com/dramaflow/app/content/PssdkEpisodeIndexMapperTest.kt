package com.dramaflow.app.content

import org.junit.Assert.assertEquals
import org.junit.Test

class PssdkEpisodeIndexMapperTest {
    @Test
    fun `maps the provider first episode number to local position zero`() {
        assertEquals(0, PssdkEpisodeIndexMapper.callbackEpisodeNumberToLocalPosition(1))
    }

    @Test
    fun `keeps later provider episode numbers aligned with local positions`() {
        assertEquals(1, PssdkEpisodeIndexMapper.callbackEpisodeNumberToLocalPosition(2))
        assertEquals(17, PssdkEpisodeIndexMapper.callbackEpisodeNumberToLocalPosition(18))
    }

    @Test
    fun `keeps start play positions zero based and safely clamps invalid callback numbers`() {
        assertEquals(0, PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(-5))
        assertEquals(4, PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(4))
        assertEquals(0, PssdkEpisodeIndexMapper.callbackEpisodeNumberToLocalPosition(0))
    }
}
