package com.dramaflow.app.presentation

import com.dramaflow.app.analytics.UserBehaviorEventReporter
import java.util.UUID

/** A full-screen ad interaction and its user-visible content context. */
internal data class AdBehaviorInteraction(
    val interactionId: String,
    val placementId: String,
    val adFormat: String,
    val earlyVideoRank: Int?,
    val dramaId: String?,
    val episodeId: String?,
    val contentTitle: String?,
) {
    fun toProperties(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = buildMap {
        put("ad_interaction_id", interactionId)
        put("placement_id", placementId)
        put("ad_format", adFormat)
        earlyVideoRank?.let { put("early_video_rank", it) }
        dramaId?.let { put("drama_id", it) }
        episodeId?.let { put("episode_id", it) }
        contentTitle?.let { put("content_title", it) }
        putAll(extra)
    }
}

/**
 * The event names in this file are the stable analytics contract. They deliberately contain
 * no creative URL, store package, IP address or AppsFlyer payload.
 */
internal fun DramaAppActivity.currentBehaviorContentKey(): String? {
    val drama = selectedDrama ?: return null
    val episode = selectedEpisode() ?: return null
    return "${drama.id}:${episode.id}"
}

/** Records a high-value, user-initiated navigation or control action without persisting typed text. */
internal fun DramaAppActivity.recordUiAction(action: String, properties: Map<String, Any?> = emptyMap()) {
    userBehaviorReporter.record(
        "UI",
        action,
        mapOf("active_tab" to activeTab.name).plus(properties),
    )
}

internal fun DramaAppActivity.recordEarlyVideoStarted(provider: String, episodeIndex: Int = currentEpisodeIndex) {
    if (sdkDiscoverPreviewActive) return
    val drama = selectedDrama ?: return
    val episode = drama.episodes.getOrNull(episodeIndex) ?: return
    userBehaviorReporter.recordEarlyVideoStarted(
        contentKey = "${drama.id}:${episode.id}",
        provider = provider,
        episodeIndex = episodeIndex + 1,
        dramaId = drama.id,
        episodeId = episode.id,
        contentTitle = drama.title,
    )
}

internal fun DramaAppActivity.recordEarlyVideoCompleted(provider: String, episodeIndex: Int) {
    if (sdkDiscoverPreviewActive) return
    val drama = selectedDrama ?: return
    val episode = drama.episodes.getOrNull(episodeIndex) ?: return
    userBehaviorReporter.recordEarlyVideoCompleted(
        contentKey = "${drama.id}:${episode.id}",
        provider = provider,
        episodeIndex = episodeIndex + 1,
        dramaId = drama.id,
        episodeId = episode.id,
        contentTitle = drama.title,
    )
}

internal fun DramaAppActivity.recordEarlyVideoPlaybackFailure(provider: String, episodeIndex: Int, reason: String) {
    val drama = selectedDrama ?: return
    val episode = drama.episodes.getOrNull(episodeIndex) ?: return
    val rank = userBehaviorReporter.currentEarlyVideoRank("${drama.id}:${episode.id}") ?: return
    userBehaviorReporter.record(
        "PLAYBACK",
        "EARLY_VIDEO_PLAYBACK_FAILED",
        mapOf(
            "early_video_rank" to rank,
            "provider" to provider,
            "episode_index" to episodeIndex + 1,
            "drama_id" to drama.id,
            "episode_id" to episode.id,
            "content_title" to drama.title,
            "reason" to reason,
        ),
    )
}

internal fun DramaAppActivity.beginAdBehaviorInteraction(placementId: String, adFormat: String): AdBehaviorInteraction {
    // A new full-screen request starts a new causal chain. Do not let a previously returned
    // click be mislabelled as resuming because a later, unrelated ad has just closed.
    if (adClickReturnRecorded) clearExternalAdReturnCheckpoint()
    lastClickedAdBehaviorInteraction = null
    adClickReturnRecorded = false
    adClickPlaybackResumeRecorded = false
    val drama = selectedDrama
    val episode = selectedEpisode()
    val rank = currentBehaviorContentKey()?.let(userBehaviorReporter::currentEarlyVideoRank)
    return AdBehaviorInteraction(
        interactionId = "ad-${UUID.randomUUID()}",
        placementId = placementId,
        adFormat = adFormat,
        earlyVideoRank = rank,
        dramaId = drama?.id,
        episodeId = episode?.id,
        contentTitle = drama?.title,
    ).also { interaction ->
        userBehaviorReporter.record("AD", "AD_REQUEST_STARTED", interaction.toProperties())
    }
}

internal fun DramaAppActivity.recordAdBehavior(
    interaction: AdBehaviorInteraction,
    eventName: String,
    extra: Map<String, Any?> = emptyMap(),
) {
    userBehaviorReporter.record("AD", eventName, interaction.toProperties(extra))
}

internal fun DramaAppActivity.recordAdClickAndCheckpoint(interaction: AdBehaviorInteraction) {
    lastClickedAdBehaviorInteraction = interaction
    adClickReturnRecorded = false
    recordAdBehavior(interaction, "AD_CLICKED")
    markExternalAdReturnCheckpoint()
}

/**
 * Android foregrounding is evidence only that this host resumed after an ad click. It does not
 * prove a browser/store was opened or that an external surface returned, so the durable recovery
 * checkpoint remains intact while telemetry records the weaker, directly observed fact.
 */
internal fun DramaAppActivity.recordExternalAdReturnIfPending() {
    val interaction = lastClickedAdBehaviorInteraction ?: return
    if (adClickReturnRecorded) return
    recordAdBehavior(
        interaction,
        "AD_HOST_RESUMED",
        mapOf("external_return_confirmed" to false),
    )
    adClickReturnRecorded = true
}

internal fun DramaAppActivity.recordPlaybackResumedAfterAdClickIfPending() {
    val interaction = lastClickedAdBehaviorInteraction ?: return
    if (!adClickReturnRecorded || adClickPlaybackResumeRecorded) return
    recordAdBehavior(interaction, "PLAYBACK_RESUMED_AFTER_AD_CLICK")
    adClickPlaybackResumeRecorded = true
}
