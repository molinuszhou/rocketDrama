package com.dramaflow.app.presentation

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.PlayerView
import coil.load
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.R
import com.dramaflow.app.presentation.DramaAppActivity.MainTab
import com.dramaflow.app.i18n.AppLanguage
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.toggleEpisodePicker() {
        episodePickerDialog?.let { active ->
            if (active.isShowing) {
                active.dismiss()
                return
            }
        }
        showEpisodePicker()
    }

internal fun DramaAppActivity.showEpisodePicker() {
        val drama = selectedDrama ?: return
        val sheet = AlertDialog.Builder(this).create()
        episodePickerDialog = sheet
        sheet.setCanceledOnTouchOutside(true)
        sheet.setOnDismissListener { if (episodePickerDialog === sheet) episodePickerDialog = null }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(22))
            background = topRoundedDrawable(COLOR_CARD, 26)
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = t("episodes")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "${t("collapse")} ⌄"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { sheet.dismiss() }
            isClickable = true
        })
        container.addView(titleRow)
        container.addView(TextView(this).apply {
            text = t("free_episode_message", rewardsSnapshot?.unlock?.freeEpisodesDefault ?: FREE_EPISODES)
            textSize = 13f
            setTextColor(Color.rgb(196, 181, 204))
            setPadding(0, dp(6), 0, dp(16))
        })
        val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
        repeat(drama.total.coerceAtLeast(1)) { index ->
            val locked = needsUnlock(index)
            grid.addView(TextView(this).apply {
                text = if (locked) "🔒 ${index + 1}" else "${index + 1}"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedDrawable(if (index == currentEpisodeIndex) COLOR_ACCENT else COLOR_SURFACE, 12)
                setOnClickListener {
                    sheet.dismiss()
                    selectEpisode(index)
                }
                isClickable = true
            }, android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = dp(44)
                columnSpec = android.widget.GridLayout.spec(index % 5, 1f)
                setMargins(if (index % 5 == 0) 0 else dp(5), 0, if (index % 5 == 4) 0 else dp(5), dp(8))
            })
        }
        val gridScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        container.addView(gridScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(390)))
        sheet.setView(container)
        sheet.show()
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

internal fun DramaAppActivity.selectEpisode(index: Int) {
        val drama = selectedDrama ?: return
        val card = sdkDramaCardsByCatalogId[drama.id]
        if (card == null) {
            toast(t("player_not_ready"))
            return
        }
        if (sdkPlayerShown && currentSdkPlayerFragment() != null) {
            playSdkEpisodeAt(index)
            return
        }
        showSdkDramaPlayer(drama, card, index.coerceIn(0, drama.episodes.lastIndex.coerceAtLeast(0)))
    }

internal fun DramaAppActivity.selectedEpisode(): LocalEpisode? = selectedDrama?.episodes?.getOrNull(currentEpisodeIndex)

internal fun DramaAppActivity.playNextEpisode() {
        startNextEpisodeAutomatically(currentEpisodeIndex, userInitiated = true)
    }

internal fun DramaAppActivity.startNextEpisodeAutomatically(completedIndex: Int, userInitiated: Boolean = false) {
        if (episodeTransitionInProgress) return
        val drama = selectedDrama ?: return
        val nextIndex = completedIndex + 1
        if (nextIndex >= drama.total.coerceAtLeast(1)) {
            if (userInitiated) switchDramaBySwipe(forward = true) else toast(t("drama_completed"))
            return
        }
        if (needsUnlock(nextIndex)) {
            unlockEpisodeThenAutoPlay(nextIndex)
        } else {
            episodeTransitionInProgress = true
            if (!userInitiated) toast(t("entering_episode", nextIndex + 1))
            selectEpisode(nextIndex)
        }
    }

    /** 免费集数用完后由用户明确确认；优先展示激励广告解锁。 */
internal fun DramaAppActivity.unlockEpisodeThenAutoPlay(
    index: Int,
    onUnlocked: () -> Unit = {},
    onDeclined: () -> Unit = {},
) {
        if (episodeTransitionInProgress) return
        selectedDrama?.episodes?.getOrNull(index) ?: return
        val unlockCount = rewardsSnapshot?.unlock?.episodesPerRewardedUnlock ?: 1
        showBrandedChoiceDialog(
            title = t("free_episodes_used"),
            message = if (unlockCount > 1) t("unlock_episode_bundle_message", index + 1, unlockCount)
                else t("unlock_episode_message", index + 1),
            primaryLabel = t("unlock_with_ad"),
            onPrimary = { startEpisodeUnlockAfterConfirmation(index, onUnlocked) },
            secondaryLabel = t("later"),
            onSecondary = {
                episodeTransitionInProgress = false
                exoPlayer?.pause()
                playerIsPaused = true
                updatePauseOverlayVisibility(true)
                recordRewardedSkipThenMaybeShowInterstitial(false, "episode_unlock_declined")
                onDeclined()
            },
        )
    }

    /** 用户确认后完整观看激励广告；服务端结算成功后才向内容服务申请本集解锁并自动播放。 */
internal fun DramaAppActivity.startEpisodeUnlockAfterConfirmation(index: Int, onUnlocked: () -> Unit = {}) {
        val episode = selectedDrama?.episodes?.getOrNull(index) ?: run {
            episodeTransitionInProgress = false
            return
        }
        if (rewardsSnapshot != null && !rewardsSnapshot!!.unlock.nextEpisodeRewardedEnabled) {
            episodeTransitionInProgress = false
            toast(t("unlock_rewarded_disabled"))
            return
        }
        episodeTransitionInProgress = true
        requestRewardedAction(
            placementId = "rv_episode_pass",
            targetId = episode.id,
            episodeIndex = index + 1,
            onSettled = { action ->
                settleEpisodeUnlockAfterAd(index, unlockMethod = "AD_REWARDED", unlockCount = action.unlockEpisodeCount) {
                    selectEpisode(index)
                    onUnlocked()
                }
            },
            onSettlementUnavailable = { message ->
                episodeTransitionInProgress = false
                resumeAfterFullscreenAdIfNeeded()
                toast(t("unlock_settlement_pending_generic"))
            },
        )
    }

internal fun DramaAppActivity.handleEpisodeCompleted(index: Int) {
        val key = episodeKey(index)
        if (!completedEpisodeKeys.add(key)) return
        currentEpisodeIndex = index
        updateEpisodeStatus()
        updatePlayerProgress(playerDurationSeconds, playerDurationSeconds)
        persistNextEpisodeResume(index)
        recordCompletedEpisodeForRewards(index) {
            startNextEpisodeAutomatically(index)
        }
    }

internal fun DramaAppActivity.settleEpisodeUnlockAfterAd(
    index: Int,
    unlockMethod: String,
    unlockCount: Int = 1,
    onUnlocked: () -> Unit = {},
) {
    val episodes = selectedDrama?.episodes.orEmpty()
    val indexes = (index until (index + unlockCount.coerceAtLeast(1)).coerceAtMost(episodes.size)).toList()
    if (indexes.isEmpty()) {
        episodeTransitionInProgress = false
        return
    }
    indexes.forEach { unlockedIndex -> sessionUnlockedEpisodeKeys.add(episodeKey(unlockedIndex)) }
    pendingUnlockIndex = -1
    episodeTransitionInProgress = false
    updateEpisodeStatus()
    toast(if (indexes.size > 1) t("episodes_unlocked", indexes.size) else t("episode_unlocked", index + 1))
    onUnlocked()
}

internal fun DramaAppActivity.unlockEpisodeForCurrentSession(index: Int, message: String) {
        sessionUnlockedEpisodeKeys.add(episodeKey(index))
        pendingUnlockIndex = -1
        updateEpisodeStatus()
        toast(message)
    }

internal fun DramaAppActivity.recordCompletedEpisodeForRewards(index: Int, onContinuePlayback: () -> Unit) {
        val drama = selectedDrama ?: run { onContinuePlayback(); return }
        val episode = drama.episodes.getOrNull(index) ?: run { onContinuePlayback(); return }
        val target = "${drama.id}:${episode.id}"
        if (!rewardWatchRecordedEpisodeKeys.add(target) || !rewardsApi.isConfigured) { onContinuePlayback(); return }
        rewardsApi.recordWatchEvent(
            userId = anonymousInstallationId(),
            dramaId = drama.id,
            episodeId = episode.id,
            validWatchSeconds = rewardsSnapshot?.unlock?.validWatchSeconds ?: 60,
            onSuccess = { reward -> runOnUiThread {
                // The first task is introductory. Only the second (or a later) newly
                // claimable task triggers the optional reminder, thirty seconds after
                // the completion so it cannot collide with the episode-three unlock UI.
                val nextPromptTask = reward.snapshot.tasks.withIndex().firstOrNull { (taskIndex, task) ->
                    taskIndex >= 1 && task.status == "CLAIMABLE" &&
                        task.target > taskRewardPromptPreferences.getInt("last_target", 0)
                }
                if (nextPromptTask != null && !taskRewardPromptPreferences.getBoolean("disabled", false)) {
                    taskRewardPromptEarliestAtElapsedMillis = android.os.SystemClock.elapsedRealtime() + 30_000L
                }
                applyRewardsSnapshot(reward.snapshot)
                // The compact savings-jar amount now reflects a newly claimable
                // cash reward. Avoid repeating an in-player instruction banner.
                // v1.6.4.7: random chests are replaced by the ad-gated watch-task redpacket line.
                onContinuePlayback()
            } },
            onFailure = { message -> runOnUiThread {
                logStatus("Watch reward sync failed: $message")
                onContinuePlayback()
            } },
        )
    }

internal fun DramaAppActivity.needsUnlock(index: Int): Boolean {
        // The trial is a product-wide promise. Older local catalog responses
        // may still mark episode 2 or 3 as AD_UNLOCK, so never let their stale
        // entitlement override the first three free episodes.
        if (index < FREE_EPISODES) return false
        val drama = selectedDrama
        val episode = drama?.episodes?.getOrNull(index)
        val contentRequiresUnlock = if (episode != null) {
            episode.entitlement in setOf("AD_UNLOCK", "COIN_UNLOCK")
        } else {
            index >= (drama?.freeEpisodes ?: FREE_EPISODES)
        }
        val key = episodeKey(index)
        return contentRequiresUnlock && key !in unlockedEpisodeKeys && key !in sessionUnlockedEpisodeKeys
    }

internal fun DramaAppActivity.episodeKey(index: Int): String = "${selectedDrama?.id ?: 0}:${index + 1}"

internal fun DramaAppActivity.updateEpisodeStatus() {
        val drama = selectedDrama ?: return
        val lockText = if (needsUnlock(currentEpisodeIndex)) " · ${t("unlock_with_ad")}" else " · ${t("watchable")}"
        episodeStatusView?.text = t("episode_progress", currentEpisodeIndex + 1, drama.total.coerceAtLeast(1), lockText)
    }
