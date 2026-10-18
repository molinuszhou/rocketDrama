package com.dramaflow.app.presentation

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.provider.CalendarContract
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
import android.widget.HorizontalScrollView
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
import com.dramaflow.app.ads.AdConsent
import com.dramaflow.app.ads.AdConsentStore
import com.dramaflow.app.ads.TopOnAdsManager
import com.dramaflow.app.ads.TopOnSettings
import com.dramaflow.app.analytics.EngagementAnalytics
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.refreshRewardsSnapshot() {
        if (!rewardsApi.isConfigured || rewardsLoading) return
        rewardsLoading = true
        val requestGeneration = ++rewardsSnapshotGeneration
        val endpoint = rewardsBaseUrl()
        logStatus("正在同步奖励服务：$endpoint")
        rewardsApi.bootstrap(
            userId = anonymousInstallationId(),
            region = rewardsMarketCode(),
            attribution = EngagementAnalytics.get(applicationContext).currentAttributionForRewards(),
            onSuccess = { snapshot ->
            runOnUiThread {
                rewardsLoading = false
                if (requestGeneration != rewardsSnapshotGeneration) {
                    logStatus("忽略已过期的奖励快照：generation=$requestGeneration")
                    return@runOnUiThread
                }
                if (!startupContentInitializationStarted) {
                    startupRewardsFinished = true
                    updateStartupProgress(computeStartupProgress(), t("startup_content_ready"))
                }
                applyRewardsSnapshot(snapshot)
                retryPendingRewardedSettlement()
                retryPendingInterstitialIncentive()
                releaseSettledRewardedTargetIfPossible()
                releasePendingInterstitialIncentiveTargetIfPossible()
                if (!startupContentInitializationStarted) completeStartupWhenReady()
                else showStartupCheckinWhenReady()
            }
        }, onFailure = { error ->
            runOnUiThread {
                rewardsLoading = false
                if (requestGeneration != rewardsSnapshotGeneration) return@runOnUiThread
                logStatus("奖励服务同步失败（$endpoint）：$error")
                if (!startupContentInitializationStarted) {
                    startupRewardsFinished = true
                    updateStartupProgress(computeStartupProgress(), t("startup_content_ready"))
                    completeStartupWhenReady()
                } else if (catalogItems.isNotEmpty()) {
                    renderActiveTab()
                }
                if (startupContentInitializationStarted) showStartupCheckinWhenReady()
            }
        })
    }

internal fun DramaAppActivity.applyRewardsSnapshot(snapshot: RewardsApiClient.Snapshot) {
        // A server-confirmed action response is newer than any bootstrap request issued before it.
        rewardsSnapshotGeneration += 1
        val previousGems = rewardsSnapshot?.wallet?.coins ?: lastConfirmedGemBalance
        val previousCash = rewardsSnapshot?.wallet?.cashWallet ?: lastConfirmedCashBalance
        val previousCrowns = rewardsSnapshot?.crownProgress.orEmpty()
        // A welcome claim removes its dedicated Reward Center card. That structural change needs
        // a full Tasks-page refresh; ordinary claims can keep the reader at Crown Road.
        val rewardCenterWelcomeStateChanged = rewardsSnapshot?.welcome?.available != snapshot.welcome.available
        // The task card contains the completed-episode and pending-reward counts, so refresh it
        // whenever server-confirmed task progress or claim state changes.
        val rewardCenterTasksChanged = rewardsSnapshot?.tasks != snapshot.tasks
        rewardsSnapshot = snapshot
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        val crownCooldown = snapshot.crownProgress.maxOfOrNull { it.chargeCooldownRemainingSeconds } ?: 0
        crownChargeCooldownUntilElapsedMillis = if (crownCooldown > 0) elapsedNow + crownCooldown * 1_000L else 0L
        val walletCooldown = snapshot.wallet.release.chargeCooldownRemainingSeconds
        walletChargeCooldownUntilElapsedMillis = if (walletCooldown > 0) elapsedNow + walletCooldown * 1_000L else 0L
        val refreshInSeconds = maxOf(crownCooldown, walletCooldown)
        if (refreshInSeconds > 0) {
            startChargeCooldownTicker()
        }
        unlockedEpisodeKeys.clear()
        unlockedEpisodeKeys.addAll(snapshot.unlockedEpisodeTargets)
        lastConfirmedGemBalance = snapshot.wallet.coins
        lastConfirmedCashBalance = snapshot.wallet.cashWallet
        // Crown Road is refreshed in place to preserve the user's scroll position. Its parent
        // Reward Center hero is intentionally not rebuilt, so update that live amount directly
        // before refreshing only the Crown panel. The receipt animation below can still replace
        // this text with the old value briefly, then settle it on the confirmed new value.
        val formattedWalletTotal = formatWalletTotal(snapshot.wallet, includeUsdReference = false)
        cashBalanceLabel?.text = formattedWalletTotal
        rewardHeroCashBalanceLabel?.text = formattedWalletTotal
        refreshFloatingRewardWheels()
        // PSSDK is still an active JoyeDrama player surface. Re-rendering a tab after its
        // reward callback removes the provider Fragment and incorrectly sends the user to a
        // catalogue page while an episode is completing.
        if (startupContentInitializationStarted && !playerShown && !sdkPlayerShown) {
            if (withdrawalPageShown) {
                showWithdrawalPage(snapshot.wallet)
            } else if (activeTab == MainTab.TASKS && (rewardCenterWelcomeStateChanged || rewardCenterTasksChanged)) {
                // Rebuild so the claimed welcome card disappears and the task counts / action
                // reflect the confirmed snapshot. renderActiveTab restores the saved scroll offset.
                renderActiveTab()
            } else if (activeTab == MainTab.TASKS && refreshCrownRoadPanelInPlace()) {
                // Preserve the reader's location at Crown Road while its reward/cooldown state updates.
            } else if (catalogItems.isNotEmpty()) {
                renderActiveTab()
            }
        }
        updateEpisodeStatus()
        if (startupContentInitializationStarted) showStartupCheckinWhenReady()
        // 只有服务端确认余额增加时播放动效；首次同步和余额不变不会触发。
        if (snapshot.wallet.cashMode && previousCash != null && (snapshot.wallet.cashWallet.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) > (previousCash.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)) {
            if (deferNextCashFlight) {
                deferNextCashFlight = false
                deferredCashFlight = previousCash to snapshot.wallet.cashWallet
            } else {
                root.post { animateClaimedCash(previousCash, snapshot.wallet.cashWallet) }
            }
        } else if (!snapshot.wallet.cashMode && previousGems != null && snapshot.wallet.coins > previousGems) {
            root.post { animateClaimedGems(previousGems, snapshot.wallet.coins) }
        }
        if (deferNextCashFlight) deferNextCashFlight = false
        if (startupContentInitializationStarted) {
            // Crown completion is independent of task reminders and must not wait for
            // an unrelated 30-second task prompt window.
            maybeShowCrownLevelReached(previousCrowns, snapshot)
        }
        val pending = snapshot.ring.rewardCoins
        val promptThreshold = snapshot.ring.claimPromptThresholdCoins.coerceAtLeast(0)
        if (startupContentInitializationStarted && promptThreshold > 0 && snapshot.ring.claimable && pending >= promptThreshold && pending - lastFloatingClaimPromptedGems >= promptThreshold && !fullscreenAdInProgress) {
            lastFloatingClaimPromptedGems = pending
            root.post {
                if (!isFinishing && !isDestroyed && !fullscreenAdInProgress) {
                    showBrandedChoiceDialog(t("claim_episode_rewards"), t("claim_episode_rewards_message"), t("claim_all"), { requestRewardedClaimAllEpisodeRewards() }, t("later"))
                }
            }
        } else if (!snapshot.ring.claimable) {
            lastFloatingClaimPromptedGems = 0
        }
        if (startupContentInitializationStarted) scheduleEpisodeTaskClaimPrompt()
    }

/** Queue the second-task reminder exactly once, thirty seconds after it became claimable. */
internal fun DramaAppActivity.scheduleEpisodeTaskClaimPrompt() {
    val now = android.os.SystemClock.elapsedRealtime()
    if (taskRewardPromptEarliestAtElapsedMillis <= now) return
    taskRewardPromptRunnable?.let(root::removeCallbacks)
    val prompt = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (fullscreenAdInProgress || fullscreenAdRequestInFlight) {
                // Keep the reminder deferred until any unlock/ad flow completes. No
                // network refresh or full Reward Center render is needed to retry it.
                root.postDelayed(this, 1_000L)
                return
            }
            taskRewardPromptEarliestAtElapsedMillis = 0L
            taskRewardPromptRunnable = null
            rewardsSnapshot?.let(::maybeShowEpisodeTaskClaimPrompt)
        }
    }
    taskRewardPromptRunnable = prompt
    root.postDelayed(prompt, (taskRewardPromptEarliestAtElapsedMillis - now).coerceAtLeast(0L))
}

/** Celebrate newly achieved Crown levels once, immediately after the server confirms the progress. */
internal fun DramaAppActivity.maybeShowCrownLevelReached(
    previous: List<RewardsApiClient.CrownProgress>,
    currentSnapshot: RewardsApiClient.Snapshot,
): Boolean {
    if (previous.isEmpty()) return false
    val previousEarned = previous.filter { it.earned }.map { it.code }.toSet()
    val reached = currentSnapshot.crownProgress
        .filter { it.earned && it.code !in previousEarned }
        .maxByOrNull { it.levelRank }
        ?: return false
    val reward = formatCurrencyAmount(currentSnapshot.wallet, reached.cash, null, includeUsdReference = false)
    showBrandedChoiceDialog(
        title = t("crown_level_reached_title"),
        message = t("crown_level_reached_message", t(crownTitleKey(reached.code)), reward),
        primaryLabel = t("done"),
    )
    return true
}

/** The first task is introductory; prompt from the second completed task unless the user opts out. */
internal fun DramaAppActivity.maybeShowEpisodeTaskClaimPrompt(snapshot: RewardsApiClient.Snapshot) {
    if (taskRewardPromptPreferences.getBoolean("disabled", false)) return
    val claimableTasks = snapshot.tasks.filter { it.status == "CLAIMABLE" }
    val candidate = snapshot.tasks.withIndex().firstOrNull { (index, task) ->
        index >= 1 && task.status == "CLAIMABLE"
    } ?: return
    val target = claimableTasks.maxOfOrNull { it.target } ?: candidate.value.target
    if (target <= taskRewardPromptPreferences.getInt("last_target", 0)) return
    taskRewardPromptPreferences.edit().putInt("last_target", target).apply()
    val completedEpisodes = maxOf(snapshot.wallet.watchedEpisodes, snapshot.tasks.maxOfOrNull { it.progress } ?: target)
    showBrandedChoiceDialog(
        title = t("task_reward_ready_title"),
        message = t("task_reward_ready_message", completedEpisodes, claimableTasks.size),
        primaryLabel = t("task_claim"),
        onPrimary = { claimEpisodeTaskCash(claimableTasks.first().target) },
        secondaryLabel = t("task_reward_dont_remind"),
        onSecondary = { taskRewardPromptPreferences.edit().putBoolean("disabled", true).apply() },
    )
}

internal fun DramaAppActivity.startChargeCooldownTicker() {
        if (chargeCooldownTickerRunning) return
        chargeCooldownTickerRunning = true
        fun tick() {
            if (isFinishing || isDestroyed) {
                chargeCooldownTickerRunning = false
                return
            }
            refreshFloatingRewardWheels()
            val stillCooling = crownChargeCooldownRemainingSeconds() > 0 || walletChargeCooldownRemainingSeconds() > 0
            if (stillCooling) {
                root.postDelayed({ tick() }, 250L)
            } else {
                chargeCooldownTickerRunning = false
                refreshRewardsSnapshot()
            }
        }
        tick()
    }

internal fun DramaAppActivity.showStartupCheckinWhenReady() {
        if (!startupCheckinPromptRequested || startupCheckinPromptShown || isFinishing || isDestroyed) return
        val snapshot = rewardsSnapshot
        if (snapshot == null && rewardsLoading) return
        if (snapshot?.welcome?.available == true && !startupWelcomePromptShown) {
            startupWelcomePromptShown = true
            startupCheckinPromptRequested = false
            showWelcomeRewardDialog(snapshot) {
                startupCheckinPromptShown = true
                showPendingShortDramaCatalogDiagnostic()
                root.postDelayed({
                    if (!isFinishing && !isDestroyed && !startupCheckinPromptSuppressed && rewardsSnapshot?.checkinSignedToday != true) {
                        showCheckinRewardDialog(rewardsSnapshot ?: snapshot, onboarding = true)
                    }
                }, 15_000L)
            }
            return
        }
        startupCheckinPromptRequested = false
        startupCheckinPromptShown = true
        when {
            snapshot == null -> Unit
            snapshot.checkinSignedToday -> Unit
            else -> showCheckinRewardDialog(snapshot, onboarding = true)
        }
        showPendingShortDramaCatalogDiagnostic()
    }

/** Full-screen welcome celebration: content is genuinely centered inside a transparent full-window Dialog. */
internal fun DramaAppActivity.showWelcomeRewardDialog(snapshot: RewardsApiClient.Snapshot, onFinished: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val page = FrameLayout(this).apply {
            background = gradientDrawable(intArrayOf(COLOR_BACKGROUND, COLOR_CARD, COLOR_BACKGROUND), 0)
        }
        addWelcomeConfetti(page)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(18), dp(30), dp(18))
        }
        val rewardIcon = ImageView(this@showWelcomeRewardDialog).apply {
            setImageResource(R.drawable.reward_cash_stack_3d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        content.addView(rewardIcon, LinearLayout.LayoutParams(dp(112), dp(112)))
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = t("welcome_title")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(5), 0, dp(2))
        })
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = formatWelcomeRewardCashValue(snapshot) ?: "—"
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_REWARD_GAIN)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(34, 58, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        // The cash amount already communicates the reward. Keep a little breathing room rather
        // than repeating a second "cash reward" label below it.
        content.addView(View(this@showWelcomeRewardDialog), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(14),
        ))
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = t("watch_ad_claim")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_REWARD_ACTION_TEXT)
            background = primaryActionDrawable()
            setPadding(dp(18), dp(15), dp(18), dp(15))
            isClickable = true
            contentDescription = t("welcome_claim_hint")
            setOnClickListener {
                userBehaviorReporter.record("ONBOARDING", "ONBOARDING_WELCOME_CLAIM_CLICKED")
                dialog.dismiss()
                claimWelcomeBonus { onFinished() }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        page.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dialog.setContentView(page)
        // Welcome reward is a required onboarding action. Do not allow Back or outside-touch
        // dismissal to bypass its sole CTA and leave the user in a partially completed flow.
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        userBehaviorReporter.record("ONBOARDING", "ONBOARDING_WELCOME_SHOWN")
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            attributes = attributes.apply { dimAmount = 0f }
        }
        rewardIcon.animate().scaleX(1.14f).scaleY(1.14f).setDuration(440L).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
            rewardIcon.animate().scaleX(1f).scaleY(1f).setDuration(360L).start()
        }.start()
    }

/** Uses the server-provided market policy rather than a client-side hardcoded gem-to-cash rate. */
internal fun DramaAppActivity.formatWelcomeRewardCashValue(snapshot: RewardsApiClient.Snapshot): String? {
        val wallet = snapshot.wallet
        if (wallet.cashMode) return formatCurrencyAmount(wallet, snapshot.welcome.cash, snapshot.welcome.cashUsdReference, includeUsdReference = true)
        val rate = wallet.exchangeRateGems.coerceAtLeast(1L)
        val localPerRate = wallet.exchangeValuePer10000Gems.toBigDecimalOrNull() ?: return null
        val usdPerRate = wallet.exchangeValuePer10000GemsUsdReference.toBigDecimalOrNull() ?: return null
        if (localPerRate.signum() <= 0 || usdPerRate.signum() <= 0) return null
        val multiplier = java.math.BigDecimal(snapshot.welcome.gems).divide(java.math.BigDecimal(rate), 8, java.math.RoundingMode.HALF_UP)
        return formatCurrencyAmount(wallet, localPerRate.multiply(multiplier).toPlainString(), usdPerRate.multiply(multiplier).toPlainString(), includeUsdReference = true)
    }

internal fun DramaAppActivity.addWelcomeConfetti(host: FrameLayout) {
        val colors = intArrayOf(COLOR_CORAL, COLOR_SUNSET, COLOR_AQUA, COLOR_PURPLE, COLOR_REWARD_GAIN)
        repeat(28) { index ->
            val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors[index % colors.size]) }; alpha = 0f }
            val size = dp(8 + index % 5 * 2)
            host.addView(dot, FrameLayout.LayoutParams(size, size).apply { leftMargin = dp(12 + (index * 47) % 340); topMargin = dp(12 + (index * 31) % 250) })
            dot.postDelayed({
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(dot, View.ALPHA, 0f, .95f, .85f, 0f),
                        ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(70 + index * 3).toFloat()),
                        ObjectAnimator.ofFloat(dot, View.SCALE_X, .55f, 1.35f, .8f),
                        ObjectAnimator.ofFloat(dot, View.SCALE_Y, .55f, 1.35f, .8f),
                        ObjectAnimator.ofFloat(dot, View.ROTATION, 0f, 260f),
                    )
                    duration = 1700L + index * 45L
                    start()
                }
            }, index * 55L)
        }
    }

    /** 深色奖励弹窗：七日格子和金额均以服务端快照为准；服务离线时仅展示同步状态，不发放奖励。 */
internal fun DramaAppActivity.showCheckinRewardDialog(
    snapshot: RewardsApiClient.Snapshot?,
    onboarding: Boolean = false,
) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(22), dp(12), dp(18))
            background = gradientDrawable(intArrayOf(COLOR_CARD, COLOR_SURFACE), 26)
        }
        content.addView(TextView(this).apply {
            text = t("checkin_rewards")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        content.addView(TextView(this).apply {
            text = if (snapshot == null) t("checkin_syncing") else t("checkin_seven_day_total", formatSevenDayCheckinTotal(snapshot))
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(5), 0, dp(8))
        })
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("checkin_done_message", snapshot.checkinStreak)
                else -> t("checkin_day_cash", snapshot.checkinTodayIndex, formatRewardUnitsAsCash(snapshot, snapshot.checkinNextRewardCoins.toLong()))
            }
            textSize = 28f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_CORAL)
            setPadding(0, dp(7), 0, dp(16))
        })
        content.addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("checkin_done_message", snapshot.checkinStreak)
                else -> t("checkin_watch_ad_cash", formatRewardUnitsAsCash(snapshot, snapshot.checkinNextRewardCoins.toLong()))
            }
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (snapshot == null) COLOR_TEXT_MUTED else COLOR_SUNSET)
            setPadding(0, dp(15), 0, dp(12))
        })
        val primary = TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("done")
                else -> t("watch_ad_checkin")
            }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_REWARD_ACTION_TEXT)
            background = primaryActionDrawable()
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener {
                if (onboarding) {
                    userBehaviorReporter.record(
                        "ONBOARDING",
                        if (snapshot == null) "ONBOARDING_CHECKIN_SYNC_CLICKED" else "ONBOARDING_CHECKIN_CLAIM_CLICKED",
                    )
                }
                dialog.dismiss()
                when {
                    snapshot == null -> refreshRewardsSnapshot()
                    !snapshot.checkinSignedToday -> completeDailyCheckin()
                    else -> Unit
                }
            }
            isClickable = true
        }
        content.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        if (!onboarding) {
            content.addView(TextView(this).apply {
                text = t("later")
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(dp(12), dp(14), dp(12), dp(4))
                setOnClickListener { dialog.dismiss() }
                isClickable = true
            })
        }
        dialog.setView(content)
        // During onboarding, the primary CTA is the only way to advance. The ordinary
        // Reward Center check-in dialog remains dismissible and keeps its Later action.
        dialog.setCancelable(!onboarding)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        if (onboarding) userBehaviorReporter.record("ONBOARDING", "ONBOARDING_CHECKIN_SHOWN")
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.98f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 奖励数据只提供“连续天数”和“今日是否已签”；具体奖励列表来自服务端动态规则，且按七日周期循环。
     */
internal fun DramaAppActivity.createCheckinTierStrip(snapshot: RewardsApiClient.Snapshot?): View {
    val serverTiers = snapshot?.checkinRewardTiers?.take(7).orEmpty()
    val tiers = if (serverTiers.isEmpty()) List(7) { 0 } else serverTiers + List(7 - serverTiers.size) { serverTiers.last() }
    val completedDays = snapshot?.checkinStreak ?: 0
    val signedToday = snapshot?.checkinSignedToday == true
    val todayIndex = ((snapshot?.checkinTodayIndex ?: 1).coerceAtLeast(1) - 1).coerceAtMost(tiers.lastIndex)
    val track = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), 0, dp(2), 0)
        tiers.indices.forEach { index ->
            val isToday = snapshot != null && index == todayIndex
            val isDone = snapshot != null && index < completedDays.coerceAtMost(tiers.size)
            addView(LinearLayout(this@createCheckinTierStrip).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                background = roundedDrawable(
                    when {
                        isToday -> COLOR_CHECKIN_TODAY_SURFACE
                        isDone -> 0xFF2E473F.toInt()
                        else -> COLOR_SURFACE
                    },
                    17,
                )
                addView(TextView(this@createCheckinTierStrip).apply {
                    text = when {
                        isToday && !signedToday -> t("today")
                        isDone -> t("checkin_complete_short")
                        else -> "D${index + 1}"
                    }
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (isToday) COLOR_REWARD_ACTION_TEXT else if (isDone) Color.WHITE else COLOR_TEXT_MUTED)
                })
                addView(TextView(this@createCheckinTierStrip).apply {
                    text = if (snapshot == null) t("syncing") else formatRewardUnitsAsCash(snapshot, tiers[index].toLong())
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (isToday) COLOR_REWARD_ACTION_TEXT else if (isDone) COLOR_SUNSET else COLOR_TEXT_PRIMARY)
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(dp(102), dp(82)).apply { if (index > 0) marginStart = dp(8) })
        }
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(track, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(82)))
        post { scrollTo((todayIndex * dp(110) - dp(110)).coerceAtLeast(0), 0) }
    }
}

/** The seven-day cycle is always the product-facing check-in unit, independent of today's streak. */
internal fun DramaAppActivity.formatSevenDayCheckinTotal(snapshot: RewardsApiClient.Snapshot): String {
    val tiers = snapshot.checkinRewardTiers.take(7)
    return formatRewardUnitsAsCash(snapshot, tiers.sumOf { it.toLong() })
}

internal fun DramaAppActivity.completeDailyCheckin(suppressStartupPrompt: Boolean = false) {
        if (suppressStartupPrompt) {
            // A deliberate check-in from Reward Center supersedes the delayed onboarding prompt.
            startupCheckinPromptRequested = false
            startupCheckinPromptShown = true
            startupCheckinPromptSuppressed = true
        }
        requestRewardedAction("rv_checkin_complete", onSettled = { action ->
            resumeAfterFullscreenAdIfNeeded()
            if (action.idempotent) {
                toast(t("checkin_completed_today"))
            } else {
                showCheckinCalendarReminderPrompt()
            }
        })
    }

/** Opens the system calendar's add-event surface; Android owns permissions and final confirmation. */
internal fun DramaAppActivity.showCheckinCalendarReminderPrompt(onFinished: () -> Unit = {}) {
    showBrandedChoiceDialog(t("calendar_reminder_title"), t("calendar_reminder_message"), t("calendar_reminder_open"), {
        val reminderTime = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DATE, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 19)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val start = reminderTime.timeInMillis
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, t("calendar_reminder_event"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 15 * 60 * 1000L)
            .putExtra(CalendarContract.Events.HAS_ALARM, true)
            .putExtra(CalendarContract.Reminders.MINUTES, 0)
            .putExtra(CalendarContract.Events.DESCRIPTION, t("calendar_reminder_description"))
        runCatching { startActivity(intent) }.onFailure { toast(t("calendar_unavailable")) }
        onFinished()
    }, t("not_now"), onSecondary = onFinished)
}

    /** 全屏广告展示时暂停原视频；TopOn 回调已保证在广告关闭后才会进入这里的结果分支。 */
