package com.dramaflow.app.presentation

import android.content.SharedPreferences
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalEpisode

/**
 * Durable display metadata for a provider title that a user has watched or favorited.
 *
 * PSSDK discovery is a transient, paged feed. Its first page after a cold start does not
 * necessarily contain titles opened from a later page, category, or search result. Playback
 * progress and favorite flags already survive in SharedPreferences; this companion snapshot makes
 * those persisted states renderable independently of the current discovery response.
 */
internal data class PersistedDramaSnapshot(
    val id: String,
    val title: String,
    val description: String,
    val coverImage: String,
    val totalEpisodes: Int,
    val freeEpisodes: Int,
    val genres: List<String>,
) {
    fun toLocalDrama(): LocalDrama {
        val safeTotal = totalEpisodes.coerceAtLeast(1)
        return LocalDrama(
            id = id,
            title = title,
            desc = description,
            coverImage = coverImage,
            total = safeTotal,
            freeEpisodes = freeEpisodes.coerceIn(0, safeTotal),
            genres = genres,
            episodes = (0 until safeTotal).map { index ->
                LocalEpisode(
                    id = "$id-episode-${index + 1}",
                    dramaId = id,
                    index = index,
                    title = "Episode ${index + 1}",
                    durationSeconds = 0,
                    coverUrl = coverImage,
                    entitlement = if (index < freeEpisodes) "FREE" else "AD_UNLOCK",
                )
            },
        )
    }

    companion object {
        fun from(drama: LocalDrama): PersistedDramaSnapshot? {
            if (drama.id.isBlank() || drama.title.isBlank()) return null
            return PersistedDramaSnapshot(
                id = drama.id,
                title = drama.title,
                description = drama.desc,
                coverImage = drama.coverImage,
                totalEpisodes = drama.total.coerceAtLeast(1),
                freeEpisodes = drama.freeEpisodes.coerceAtLeast(0),
                genres = drama.genres.map(String::trim).filter(String::isNotBlank).distinct(),
            )
        }
    }
}

/** Keeps persisted titles visible even when the provider's current discovery page has changed. */
internal object PersistedDramaLibrary {
    private const val IDS_KEY = "persisted_drama_ids"
    private const val TITLE_KEY_PREFIX = "persisted_drama_title_"
    private const val DESCRIPTION_KEY_PREFIX = "persisted_drama_description_"
    private const val COVER_KEY_PREFIX = "persisted_drama_cover_"
    private const val TOTAL_KEY_PREFIX = "persisted_drama_total_"
    private const val FREE_EPISODES_KEY_PREFIX = "persisted_drama_free_episodes_"
    private const val GENRES_KEY_PREFIX = "persisted_drama_genres_"

    fun save(preferences: SharedPreferences, drama: LocalDrama) {
        val snapshot = PersistedDramaSnapshot.from(drama) ?: return
        val ids = preferences.getStringSet(IDS_KEY, emptySet()).orEmpty().toMutableSet().apply { add(snapshot.id) }
        preferences.edit()
            .putStringSet(IDS_KEY, ids)
            .putString(TITLE_KEY_PREFIX + snapshot.id, snapshot.title)
            .putString(DESCRIPTION_KEY_PREFIX + snapshot.id, snapshot.description)
            .putString(COVER_KEY_PREFIX + snapshot.id, snapshot.coverImage)
            .putInt(TOTAL_KEY_PREFIX + snapshot.id, snapshot.totalEpisodes)
            .putInt(FREE_EPISODES_KEY_PREFIX + snapshot.id, snapshot.freeEpisodes)
            .putStringSet(GENRES_KEY_PREFIX + snapshot.id, snapshot.genres.toSet())
            .apply()
    }

    fun snapshots(preferences: SharedPreferences): List<PersistedDramaSnapshot> =
        preferences.getStringSet(IDS_KEY, emptySet()).orEmpty().mapNotNull { id ->
            val title = preferences.getString(TITLE_KEY_PREFIX + id, null)?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            PersistedDramaSnapshot(
                id = id,
                title = title,
                description = preferences.getString(DESCRIPTION_KEY_PREFIX + id, null).orEmpty(),
                coverImage = preferences.getString(COVER_KEY_PREFIX + id, null).orEmpty(),
                totalEpisodes = preferences.getInt(TOTAL_KEY_PREFIX + id, 1).coerceAtLeast(1),
                freeEpisodes = preferences.getInt(FREE_EPISODES_KEY_PREFIX + id, 0).coerceAtLeast(0),
                genres = preferences.getStringSet(GENRES_KEY_PREFIX + id, emptySet()).orEmpty()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .sorted(),
            )
        }

    /** Current provider cards take precedence because they retain a native [ShortPlay] handle. */
    fun mergeWithCurrentCatalog(
        currentCatalog: List<LocalDrama>,
        persistedSnapshots: List<PersistedDramaSnapshot>,
    ): List<LocalDrama> = (currentCatalog + persistedSnapshots.map(PersistedDramaSnapshot::toLocalDrama))
        .distinctBy(LocalDrama::id)
}
