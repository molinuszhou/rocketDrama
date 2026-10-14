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
    fun `maps local zero based positions to one based PSSDK start episode numbers`() {
        assertEquals(1, PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(-5))
        assertEquals(1, PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(0))
        assertEquals(5, PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(4))
    }

    @Test
    fun `round trips the fifth local episode through PSSDK one based numbering`() {
        val providerEpisodeNumber = PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(4)

        assertEquals(4, PssdkEpisodeIndexMapper.callbackEpisodeNumberToLocalPosition(providerEpisodeNumber))
    }

    @Test
    fun `safely clamps invalid callback episode numbers`() {
        assertEquals(0, PssdkEpisodeIndexMapper.callbackEpisodeNumberToLocalPosition(0))
    }
}
