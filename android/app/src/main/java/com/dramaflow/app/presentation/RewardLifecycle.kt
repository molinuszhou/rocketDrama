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
import com.dramaflow.app.ads.AdConsent
import com.dramaflow.app.ads.AdConsentStore
import com.dramaflow.app.ads.TopOnAdsManager
import com.dramaflow.app.ads.TopOnSettings
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.local.LocalDrama
import com.dramaflow.app.local.LocalDramaApiClient
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.refreshRewardsSnapshot() {
        if (!rewardsApi.isConfigured || rewardsLoading) return
        rewardsLoading = true
        val endpoint = rewardsBaseUrl()
        logStatus("正在同步奖励服务：$endpoint")
        rewardsApi.bootstrap(anonymousInstallationId(), rewardsMarketCode(), { snapshot ->
            runOnUiThread {
                rewardsLoading = false
                applyRewardsSnapshot(snapshot)
            }
        }, { message ->
            runOnUiThread {
                rewardsLoading = false
                logStatus("奖励服务同步失败（$endpoint）：$message")
                if (catalogItems.isNotEmpty()) renderActiveTab()
                showStartupCheckinWhenReady()
            }
        })
    }

internal fun DramaAppActivity.applyRewardsSnapshot(snapshot: RewardsApiClient.Snapshot) {
        val previousGems = rewardsSnapshot?.wallet?.coins ?: lastConfirmedGemBalance
        rewardsSnapshot = snapshot
        unlockedEpisodeKeys.clear()
        unlockedEpisodeKeys.addAll(snapshot.unlockedEpisodeTargets)
        lastConfirmedGemBalance = snapshot.wallet.coins
        refreshFloatingRewardWheels()
        if (!playerShown) {
            if (withdrawalPageShown) showWithdrawalPage(snapshot.wallet) else if (catalogItems.isNotEmpty()) renderActiveTab()
        }
        updateEpisodeStatus()
        if (startupContentInitializationStarted) showStartupCheckinWhenReady()
        // 只有服务端确认余额增加时播放动效；首次同步和余额不变不会触发。
        if (previousGems != null && snapshot.wallet.coins > previousGems) {
            root.post { animateClaimedGems(previousGems, snapshot.wallet.coins) }
        }
        val pending = snapshot.ring.rewardCoins
        if (snapshot.ring.claimable && pending >= 10_000 && pending > lastFloatingClaimPromptedGems && !fullscreenAdInProgress) {
            lastFloatingClaimPromptedGems = pending
            root.post {
                if (!isFinishing && !isDestroyed && !fullscreenAdInProgress) {
                    showBrandedChoiceDialog(t("gems_ready_title"), t("gems_ready_message", pending), t("claim_all"), { requestRewardedClaimAllEpisodeRewards() }, t("later"))
                }
            }
        } else if (!snapshot.ring.claimable) {
            lastFloatingClaimPromptedGems = 0
        }
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
                root.postDelayed({
                    if (!isFinishing && !isDestroyed && rewardsSnapshot?.checkinSignedToday != true) showCheckinRewardDialog(rewardsSnapshot ?: snapshot)
                }, 15_000L)
            }
            return
        }
        startupCheckinPromptRequested = false
        startupCheckinPromptShown = true
        when {
            snapshot == null -> Unit
            snapshot.checkinSignedToday && snapshot.chest.availableCount > 0 -> showRandomChestEarnedDialog(0, snapshot.chest.availableCount) {}
            snapshot.checkinSignedToday -> Unit
            else -> showCheckinRewardDialog(snapshot)
        }
    }

/** Full-screen welcome celebration: content is genuinely centered inside a transparent full-window Dialog. */
internal fun DramaAppActivity.showWelcomeRewardDialog(snapshot: RewardsApiClient.Snapshot, onFinished: () -> Unit) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val page = FrameLayout(this).apply {
            background = gradientDrawable(intArrayOf(0xFFFFE2D1.toInt(), 0xFFF5EDFF.toInt(), 0xFFDDF7F4.toInt()), 0)
        }
        addWelcomeConfetti(page)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(20), dp(30), dp(20))
        }
        val gem = ImageView(this@showWelcomeRewardDialog).apply {
            setImageResource(R.drawable.reward_gem_crystal)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        content.addView(gem, LinearLayout.LayoutParams(dp(154), dp(154)))
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = t("welcome_title"); textSize = 30f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY); setPadding(0, dp(6), 0, 0)
        })
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = t("welcome_subtitle"); textSize = 15f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), 0, dp(16))
        })
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = "+${formatGems(snapshot.welcome.gems)} ${t("gems")}"; textSize = 34f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_CORAL)
            background = roundedDrawable(0xB8FFFFFF.toInt(), 20); setPadding(dp(22), dp(14), dp(22), dp(14))
        })
        val cashValue = formatWelcomeRewardCashValue(snapshot)
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = cashValue?.let { t("welcome_cash_value", it) } ?: t("welcome_cash_value_syncing")
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_PURPLE); setPadding(0, dp(10), 0, 0)
        })
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = t("welcome_reward_message", snapshot.welcome.gems); textSize = 13f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(12), dp(0), dp(16))
        })
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = t("watch_ad_claim"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(COLOR_CORAL, COLOR_SUNSET), 18); setPadding(dp(18), dp(15), dp(18), dp(15)); isClickable = true
            setOnClickListener { dialog.dismiss(); claimWelcomeBonus { onFinished() } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        content.addView(TextView(this@showWelcomeRewardDialog).apply {
            text = t("later"); textSize = 14f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(18), 0, 0); isClickable = true
            setOnClickListener { dialog.dismiss(); onFinished() }
        })
        page.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dialog.setContentView(page)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onFinished() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            attributes = attributes.apply { dimAmount = 0f }
        }
        gem.animate().scaleX(1.14f).scaleY(1.14f).setDuration(320L).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
            gem.animate().scaleX(1f).scaleY(1f).setDuration(420L).start()
        }.start()
    }

/** Uses the server-provided market policy rather than a client-side hardcoded gem-to-cash rate. */
internal fun DramaAppActivity.formatWelcomeRewardCashValue(snapshot: RewardsApiClient.Snapshot): String? {
        val wallet = snapshot.wallet
        val rate = wallet.exchangeRateGems.coerceAtLeast(1L)
        val localPerRate = wallet.exchangeValuePer10000Gems.toBigDecimalOrNull() ?: return null
        val usdPerRate = wallet.exchangeValuePer10000GemsUsdReference.toBigDecimalOrNull() ?: return null
        if (localPerRate.signum() <= 0 || usdPerRate.signum() <= 0) return null
        val multiplier = java.math.BigDecimal(snapshot.welcome.gems).divide(java.math.BigDecimal(rate), 8, java.math.RoundingMode.HALF_UP)
        return formatCurrencyAmount(wallet, localPerRate.multiply(multiplier).toPlainString(), usdPerRate.multiply(multiplier).toPlainString(), includeUsdReference = true)
    }

internal fun DramaAppActivity.addWelcomeConfetti(host: FrameLayout) {
        val colors = intArrayOf(COLOR_CORAL, COLOR_SUNSET, COLOR_AQUA, COLOR_PURPLE, 0xFFFFDB6A.toInt())
        repeat(16) { index ->
            val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors[index % colors.size]) }; alpha = 0f }
            host.addView(dot, FrameLayout.LayoutParams(dp(7 + index % 4 * 2), dp(7 + index % 4 * 2)).apply { leftMargin = dp(18 + (index * 41) % 300); topMargin = dp(18 + (index * 29) % 210) })
            dot.postDelayed({
                AnimatorSet().apply { playTogether(ObjectAnimator.ofFloat(dot, View.ALPHA, 0f, .95f, 0f), ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(40 + index * 4).toFloat()), ObjectAnimator.ofFloat(dot, View.ROTATION, 0f, 160f)); duration = 1050L + index * 35L; start() }
            }, index * 70L)
        }
    }

    /** 明亮奖励弹窗：七日格子和金额均以服务端快照为准；服务离线时仅展示同步状态，不发放奖励。 */
internal fun DramaAppActivity.showCheckinRewardDialog(snapshot: RewardsApiClient.Snapshot?) {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            background = gradientDrawable(intArrayOf(0xFFFFF0E6.toInt(), COLOR_CARD), 26)
        }
        content.addView(TextView(this).apply {
            text = t("checkin_rewards")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("checkin_done_message", snapshot.checkinStreak)
                else -> t("today_day_reward", snapshot.checkinTodayIndex, snapshot.checkinNextRewardCoins)
            }
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(7), 0, dp(16))
        })
        content.addView(createCheckinTierStrip(snapshot), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("checkin_open_chest_hint")
                else -> t("checkin_ad_reward", snapshot.checkinNextRewardCoins)
            }
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (snapshot == null) COLOR_TEXT_MUTED else 0xFFFFDB6A.toInt())
            setPadding(0, dp(15), 0, dp(12))
        })
        val primary = TextView(this).apply {
            text = when {
                snapshot == null -> t("checkin_syncing")
                snapshot.checkinSignedToday -> t("open_chest")
                else -> t("watch_ad_checkin")
            }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
            background = gradientDrawable(intArrayOf(COLOR_ACCENT, COLOR_PURPLE), 18)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener {
                dialog.dismiss()
                when {
                    snapshot == null -> refreshRewardsSnapshot()
                    !snapshot.checkinSignedToday -> completeDailyCheckin()
                    else -> requestRandomChestOpen()
                }
            }
            isClickable = true
        }
        content.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(TextView(this).apply {
            text = t("later")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(dp(12), dp(14), dp(12), dp(4))
            setOnClickListener { dialog.dismiss() }
            isClickable = true
        })
        dialog.setView(content)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 奖励数据只提供“连续天数”和“今日是否已签”；具体宝石列表来自服务端动态规则。超过七天时保持最高奖励档。
     */
internal fun DramaAppActivity.createCheckinTierStrip(snapshot: RewardsApiClient.Snapshot?): View {
        val tiers = snapshot?.checkinRewardTiers?.take(7).orEmpty().ifEmpty { listOf(0, 0, 0, 0, 0, 0, 0) }
        // checkinStreak 是已经完成的天数：首日未签到时为 0，必须突出“今日第 1 天”而非打勾。
        val completedDays = snapshot?.checkinStreak ?: 0
        val signedToday = snapshot?.checkinSignedToday == true
        val maxTierIndex = tiers.lastIndex
        val todayIndex = ((snapshot?.checkinTodayIndex ?: 1).coerceAtLeast(1) - 1).coerceAtMost(maxTierIndex)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tiers.forEachIndexed { index, coins ->
                val isToday = snapshot != null && index == todayIndex
                val isDone = snapshot != null && index < completedDays.coerceAtMost(tiers.size)
                addView(LinearLayout(this@createCheckinTierStrip).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(3), dp(8), dp(3), dp(8))
                    background = roundedDrawable(
                        when {
                            isToday -> COLOR_ACCENT
                            isDone -> COLOR_PURPLE
                            else -> COLOR_SURFACE
                        },
                        12,
                    )
                    addView(TextView(this@createCheckinTierStrip).apply {
                        text = if (isToday && !signedToday) t("today") else t("day_number", index + 1)
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) Color.WHITE else COLOR_TEXT_MUTED)
                    })
                    addView(TextView(this@createCheckinTierStrip).apply {
                        text = if (isDone) "✓" else if (isToday) t("claim_short") else "○"
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) 0xFFFFDB6A.toInt() else COLOR_TEXT_MUTED)
                        setPadding(0, dp(3), 0, dp(2))
                    })
                    addView(TextView(this@createCheckinTierStrip).apply {
                        text = if (snapshot == null) t("syncing") else "+$coins"
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(if (isToday || isDone) Color.WHITE else COLOR_TEXT_MUTED)
                    })
                }, LinearLayout.LayoutParams(0, dp(84), 1f).apply {
                    if (index > 0) marginStart = dp(4)
                })
            }
        }
    }

internal fun DramaAppActivity.completeDailyCheckin() {
        requestRewardedAction("rv_checkin_complete", onSettled = { action ->
            applyRewardsSnapshot(action.snapshot)
            resumeAfterFullscreenAdIfNeeded()
            if (action.idempotent) {
                toast("今天已经签到")
                if (action.snapshot.chest.availableCount > 0) showRandomChestEarnedDialog(0, action.snapshot.chest.availableCount) {}
            } else {
                toast("签到成功，获得 ${action.gemsAdded} 宝石")
                if (action.snapshot.chest.availableCount > 0) showRandomChestEarnedDialog(0, action.snapshot.chest.availableCount) {}
            }
        })
    }

    /** 全屏广告展示时暂停原视频；TopOn 回调已保证在广告关闭后才会进入这里的结果分支。 */
