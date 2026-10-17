package com.dramaflow.app.presentation

import com.dramaflow.app.content.PssdkEpisodeIndexMapper
import java.util.UUID

/**
 * Persistence boundary for incentive interstitial recovery. Every transition uses commit because
 * an ad click or a process reclaim may happen before SharedPreferences.apply() reaches disk.
 */
internal fun DramaAppActivity.preparePendingInterstitialIncentive(
    kind: InterstitialIncentiveKind,
    interstitialPlacementId: String,
    originalPlacementId: String,
    targetId: String?,
    episodeIndex: Int?,
    episodeId: String?,
): PendingInterstitialIncentive {
    val pending = PendingInterstitialIncentive(
        eventId = "int-${UUID.randomUUID()}",
        userId = anonymousInstallationId(),
        interstitialPlacementId = interstitialPlacementId,
        episodeId = episodeId?.takeIf(String::isNotBlank),
        kind = kind,
        originalPlacementId = originalPlacementId,
        targetId = targetId?.takeIf(String::isNotBlank),
        episodeIndex = episodeIndex,
        dramaId = selectedDrama?.id,
        stage = InterstitialIncentiveStage.PREPARED,
        savedAtMillis = System.currentTimeMillis(),
    )
    writePendingInterstitialIncentive(pending)
    return pending
}

internal fun DramaAppActivity.readPendingInterstitialIncentive(): PendingInterstitialIncentive? {
    val storedEpisodeIndex = playbackHistory.getInt(PENDING_INTERSTITIAL_INCENTIVE_EPISODE_INDEX, -1)
    val kind = runCatching {
        InterstitialIncentiveKind.valueOf(
            playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_KIND, null).orEmpty(),
        )
    }.getOrNull()
    val stage = runCatching {
        InterstitialIncentiveStage.valueOf(
            playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_STAGE, null).orEmpty(),
        )
    }.getOrNull()
    if (kind == null || stage == null) {
        clearPendingInterstitialIncentive()
        return null
    }
    val pending = PendingInterstitialIncentive(
        eventId = playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_EVENT_ID, null).orEmpty(),
        userId = playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_USER_ID, null).orEmpty(),
        interstitialPlacementId = playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_PLACEMENT_ID, null).orEmpty(),
        episodeId = playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_EPISODE_ID, null)?.takeIf(String::isNotBlank),
        kind = kind,
        originalPlacementId = playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_ORIGINAL_PLACEMENT_ID, null).orEmpty(),
        targetId = playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_TARGET_ID, null)?.takeIf(String::isNotBlank),
        episodeIndex = storedEpisodeIndex.takeIf { it > 0 },
        dramaId = playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_DRAMA_ID, null)?.takeIf(String::isNotBlank),
        stage = stage,
        savedAtMillis = playbackHistory.getLong(PENDING_INTERSTITIAL_INCENTIVE_SAVED_AT_MILLIS, 0L),
    )
    return pending.takeIf { it.isFresh(System.currentTimeMillis()) }.also {
        if (it == null) clearPendingInterstitialIncentive()
    }
}

internal fun DramaAppActivity.markPendingInterstitialShown(
    pending: PendingInterstitialIncentive,
): PendingInterstitialIncentive? = updatePendingInterstitialIncentive(pending.eventId) {
    InterstitialIncentiveTransitions.afterShown(it, System.currentTimeMillis())
}

internal fun DramaAppActivity.markPendingInterstitialRecorded(
    pending: PendingInterstitialIncentive,
): PendingInterstitialIncentive? = updatePendingInterstitialIncentive(pending.eventId) {
    InterstitialIncentiveTransitions.afterRecorded(it, System.currentTimeMillis())
}

internal fun DramaAppActivity.markPendingInterstitialSettled(
    pending: PendingInterstitialIncentive,
): PendingInterstitialIncentive? = updatePendingInterstitialIncentive(pending.eventId) {
    InterstitialIncentiveTransitions.afterSettled(it, System.currentTimeMillis())
}

internal fun DramaAppActivity.clearPreparedInterstitialIncentive(eventId: String) {
    val pending = readPendingInterstitialIncentive() ?: return
    if (pending.eventId == eventId && pending.stage == InterstitialIncentiveStage.PREPARED) {
        clearPendingInterstitialIncentive(eventId)
    }
}

internal fun DramaAppActivity.clearPendingInterstitialIncentive(eventId: String? = null) {
    if (eventId != null && playbackHistory.getString(PENDING_INTERSTITIAL_INCENTIVE_EVENT_ID, null) != eventId) return
    playbackHistory.edit()
        .remove(PENDING_INTERSTITIAL_INCENTIVE_EVENT_ID)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_USER_ID)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_PLACEMENT_ID)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_EPISODE_ID)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_KIND)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_ORIGINAL_PLACEMENT_ID)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_TARGET_ID)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_EPISODE_INDEX)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_DRAMA_ID)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_STAGE)
        .remove(PENDING_INTERSTITIAL_INCENTIVE_SAVED_AT_MILLIS)
        .apply()
}

/** Do not consume an unlock while its original player is absent or rejects the resume request. */
internal fun DramaAppActivity.resumeSettledEpisodeTargetIfReady(dramaId: String, targetIndex: Int): Boolean {
    if (settledEpisodeTargetReleaseInFlight) return false
    val drama = selectedDrama ?: return false
    if (drama.id != dramaId || targetIndex !in drama.episodes.indices || needsUnlock(targetIndex)) return false
    val sdkFragment = if (sdkPlayerShown) currentSdkPlayerFragment() else null
    if (sdkPlayerShown) {
        if (sdkFragment == null || !sdkFragment.isAdded || sdkFragment.view == null) return false
    } else if (!playerShown || exoPlayer == null) return false
    val previousTransition = episodeTransitionInProgress
    episodeTransitionInProgress = false
    settledEpisodeTargetReleaseInFlight = true
    try {
        return runCatching {
            if (sdkFragment != null) {
                sdkFragment.startPlayIndex(PssdkEpisodeIndexMapper.localPositionToStartPlayIndex(targetIndex))
            } else {
                selectEpisode(targetIndex)
            }
            true
        }.getOrElse {
            episodeTransitionInProgress = previousTransition
            logStatus("广告解锁播放恢复等待重试：${it.javaClass.simpleName}")
            false
        }
    } finally {
        settledEpisodeTargetReleaseInFlight = false
    }
}

/** Delivers a server-confirmed episode unlock only when the original player and entitlement exist. */
internal fun DramaAppActivity.releasePendingInterstitialIncentiveTargetIfPossible(): Boolean {
    val pending = readPendingInterstitialIncentive()?.takeIf(PendingInterstitialIncentive::requiresTargetRelease)
        ?: return false
    val drama = selectedDrama ?: return false
    val targetDramaId = pending.dramaId ?: return false
    val targetIndex = (pending.episodeIndex ?: return false) - 1
    if (!resumeSettledEpisodeTargetIfReady(targetDramaId, targetIndex)) return false
    clearPendingInterstitialIncentive(pending.eventId)
    logStatus("已恢复插屏奖励解锁：${drama.id} #${targetIndex + 1}")
    return true
}

private fun DramaAppActivity.updatePendingInterstitialIncentive(
    eventId: String,
    transform: (PendingInterstitialIncentive) -> PendingInterstitialIncentive,
): PendingInterstitialIncentive? {
    val pending = readPendingInterstitialIncentive() ?: return null
    if (pending.eventId != eventId) return null
    return transform(pending).also(::writePendingInterstitialIncentive)
}

private fun DramaAppActivity.writePendingInterstitialIncentive(pending: PendingInterstitialIncentive) {
    playbackHistory.edit()
        .putString(PENDING_INTERSTITIAL_INCENTIVE_EVENT_ID, pending.eventId)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_USER_ID, pending.userId)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_PLACEMENT_ID, pending.interstitialPlacementId)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_EPISODE_ID, pending.episodeId)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_KIND, pending.kind.name)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_ORIGINAL_PLACEMENT_ID, pending.originalPlacementId)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_TARGET_ID, pending.targetId)
        .putInt(PENDING_INTERSTITIAL_INCENTIVE_EPISODE_INDEX, pending.episodeIndex ?: -1)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_DRAMA_ID, pending.dramaId)
        .putString(PENDING_INTERSTITIAL_INCENTIVE_STAGE, pending.stage.name)
        .putLong(PENDING_INTERSTITIAL_INCENTIVE_SAVED_AT_MILLIS, pending.savedAtMillis)
        .commit()
}
