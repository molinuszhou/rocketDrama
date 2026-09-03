package com.dramaflow.app.local

data class LocalDrama(
    val id: String,
    val title: String,
    val desc: String,
    val coverImage: String,
    val total: Int,
    val freeEpisodes: Int,
    val genres: List<String> = emptyList(),
    val episodes: List<LocalEpisode> = emptyList(),
)

data class LocalEpisode(
    val id: String,
    val dramaId: String,
    val index: Int,
    val title: String,
    val durationSeconds: Int,
    val coverUrl: String,
    val entitlement: String,
)

data class LocalPlaybackSession(
    val episodeId: String,
    val streamUrl: String,
    val entitlement: String,
    val message: String?,
)
