package com.dramaflow.app.presentation

import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedDramaLibraryTest {
    @Test
    fun `restores a watched provider title after its transient catalog page is gone`() {
        val original = drama(id = "pssdk-876", title = "Saved title", total = 12)
        val snapshot = requireNotNull(PersistedDramaSnapshot.from(original))

        val restored = PersistedDramaLibrary.mergeWithCurrentCatalog(
            currentCatalog = emptyList(),
            persistedSnapshots = listOf(snapshot),
        ).single()

        assertEquals("pssdk-876", restored.id)
        assertEquals("Saved title", restored.title)
        assertEquals(12, restored.episodes.size)
        assertEquals("pssdk-876-episode-1", restored.episodes.first().id)
    }

    @Test
    fun `keeps live provider metadata when it overlaps a persisted snapshot`() {
        val older = requireNotNull(PersistedDramaSnapshot.from(drama(id = "pssdk-876", title = "Old title", total = 10)))
        val live = drama(id = "pssdk-876", title = "Current title", total = 12)

        val merged = PersistedDramaLibrary.mergeWithCurrentCatalog(
            currentCatalog = listOf(live),
            persistedSnapshots = listOf(older),
        )

        assertEquals(1, merged.size)
        assertEquals("Current title", merged.single().title)
        assertTrue(merged.single().episodes.size == 12)
    }

    private fun drama(id: String, title: String, total: Int): LocalDrama = LocalDrama(
        id = id,
        title = title,
        desc = "Description",
        coverImage = "https://example.test/cover.jpg",
        total = total,
        freeEpisodes = 3,
        genres = listOf("Romance"),
        episodes = (0 until total).map { index ->
            LocalEpisode(
                id = "$id-episode-${index + 1}",
                dramaId = id,
                index = index,
                title = "Episode ${index + 1}",
                durationSeconds = 0,
                coverUrl = "https://example.test/cover.jpg",
                entitlement = "FREE",
            )
        },
    )
}
