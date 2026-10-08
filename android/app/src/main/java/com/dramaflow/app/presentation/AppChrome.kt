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
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.createInlineNotice(message: String): View = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(COLOR_TEXT_MUTED)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(28), dp(18), dp(28))
        background = roundedDrawable(COLOR_CARD, 16)
    }

    /** Non-reward pages expose one simple, direct-cash wallet. */
    /** 固定资产栏使用系统状态栏/挖孔 inset；真实设备上不会再被顶部传感器或状态栏遮挡。 */
internal fun DramaAppActivity.createPersistentAssetHeader(): View {
        val shell = FrameLayout(this).apply { background = pageCanvasDrawable() }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            val wallet = rewardsSnapshot?.wallet
            val balanceBar = LinearLayout(this@createPersistentAssetHeader).apply {
                gravity = Gravity.CENTER_VERTICAL; background = roundedDrawable(COLOR_CARD, 20); setPadding(dp(5), dp(4), dp(5), dp(4)); clipToOutline = true
                addView(createAssetPill(icon = R.drawable.wallet_money, label = t("wallet_balance"), value = wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—", isGem = false) { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) }, LinearLayout.LayoutParams(0, dp(72), 1f))
                val charge = TextView(this@createPersistentAssetHeader).apply {
                    textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 1
                    setPadding(dp(3), dp(5), dp(3), dp(5)); contentDescription = t("bonus_cash")
                }
                walletHeaderBoostButtons.add(charge)
                updateWalletHeaderBoostButton(charge)
                val chargeSlot = LinearLayout(this@createPersistentAssetHeader).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(charge, LinearLayout.LayoutParams(0, dp(46), 1f))
                    val cooldown = ChargeCooldownRingView(this@createPersistentAssetHeader)
                    walletChargeCooldownRings.add(cooldown); updateWalletChargeCooldownRing(cooldown)
                    addView(cooldown, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(2) })
                }
                addView(chargeSlot, LinearLayout.LayoutParams(dp(126), dp(60)).apply { marginStart = dp(2) })
            }
            addView(balanceBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))
        }
        shell.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76), Gravity.TOP).apply { topMargin = dp(30) })
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val params = row.layoutParams as FrameLayout.LayoutParams
            val margin = maxOf(safeTop, dp(18)) + dp(14)
            if (params.topMargin != margin) { params.topMargin = margin; row.layoutParams = params }
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        return shell
    }

/** Keeps Home discovery controls reachable beneath the persistent wallet while the catalogue scrolls. */
internal fun DramaAppActivity.createHomeBrowseControls(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(8))
        background = pageCanvasDrawable()
        addView(createSearchPrompt(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        addView(createCategoryTabs(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
    }

internal fun DramaAppActivity.createAssetPill(icon: Int, label: String, value: String, isGem: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(2))
        elevation = 0f
        isClickable = true
        contentDescription = "$label $value"
        setOnClickListener { onClick() }
        val image = ImageView(this@createAssetPill).apply { setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        if (isGem) gemBalanceAnchor = image else cashBalanceAnchor = image
        addView(image, LinearLayout.LayoutParams(dp(66), dp(66)))
        addView(LinearLayout(this@createAssetPill).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), 0, 0, 0)
            val valueView = TextView(this@createAssetPill).apply { text = value; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 1; includeFontPadding = false; letterSpacing = -0.02f; setAutoSizeTextTypeUniformWithConfiguration(16, 27, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
        if (isGem) gemBalanceLabel = valueView else cashBalanceLabel = valueView
            addView(valueView)
            addView(TextView(this@createAssetPill).apply { text = label; textSize = 11f; setTextColor(COLOR_TEXT_MUTED); maxLines = 1 })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

internal fun DramaAppActivity.createCashoutSummary(wallet: RewardsApiClient.Wallet): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(14)); background = roundedDrawable(COLOR_CARD, 22)
        val items = listOf(
            Triple(R.drawable.wallet_money, t("available_balance"), formatCurrencyAmount(wallet, wallet.availableBalance, wallet.availableBalanceUsdReference, includeUsdReference = false)),
            Triple(R.drawable.wallet_money, t("tomorrow_transfer"), formatCurrencyAmount(wallet, wallet.release.expectedTomorrowTransfer, wallet.release.expectedTomorrowTransferUsdReference, includeUsdReference = false)),
            Triple(R.drawable.wallet_money, t("ad_boost"), t("random_cash_reward")),
        )
        items.forEachIndexed { index, item ->
            addView(LinearLayout(this@createCashoutSummary).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(ImageView(this@createCashoutSummary).apply { setImageResource(item.first); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(TextView(this@createCashoutSummary).apply { text = item.second; textSize = 10f; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_MUTED); maxLines = 2; setPadding(0, dp(4), 0, dp(2)) })
                addView(TextView(this@createCashoutSummary).apply { text = item.third; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_TEXT_PRIMARY) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (index < items.lastIndex) addView(View(this@createCashoutSummary).apply { setBackgroundColor(0x553C3C46) }, LinearLayout.LayoutParams(dp(1), dp(55)))
        }
    }

internal fun DramaAppActivity.createCashoutExplainer(wallet: RewardsApiClient.Wallet): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(14)); background = roundedDrawable(COLOR_CARD, 22)
        addView(TextView(this@createCashoutExplainer).apply { text = t("cashout_explainer"); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(0, 0, 0, dp(8)) })
        val requestDetail = rewardsSnapshot?.withdrawal?.let {
            t("cashout_step_request_dynamic", formatCurrencyAmount(wallet, it.threshold, it.thresholdUsdReference, includeUsdReference = true))
        } ?: t("cashout_step_request")
        val steps = listOf(
            t("earn_gems") to t("cashout_step_earn"),
            t("convert_wallet") to t("cashout_step_convert"),
            t("request_cashout") to requestDetail,
        )
        steps.forEachIndexed { index, step ->
            addView(LinearLayout(this@createCashoutExplainer).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                addView(TextView(this@createCashoutExplainer).apply { text = "${index + 1}"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = roundedDrawable(if (index == 0) COLOR_CORAL else COLOR_AQUA, 14) }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(LinearLayout(this@createCashoutExplainer).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0)
                    addView(TextView(this@createCashoutExplainer).apply { text = step.first; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
                    addView(TextView(this@createCashoutExplainer).apply { text = step.second; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
                })
            })
        }
    }

    /** 服务端余额确认后，宝石由屏幕中心飞入当前余额锚点，最终才刷新锚点文本。 */
internal fun DramaAppActivity.animateClaimedGems(previous: Long, current: Long) {
        val target = gemBalanceAnchor ?: return
        if (!target.isAttachedToWindow || gemFlightInProgress) return
        gemFlightInProgress = true
        gemBalanceLabel?.text = formatGems(previous)
        val rootLocation = IntArray(2)
        val targetLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        target.getLocationOnScreen(targetLocation)
        val fly = ImageView(this).apply {
            setImageResource(R.drawable.wallet_money)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 0f
        }
        val size = dp(42)
        val startX = root.width / 2f - size / 2f
        val startY = root.height * 0.53f - size / 2f
        root.addView(fly, FrameLayout.LayoutParams(size, size).apply { leftMargin = startX.toInt(); topMargin = startY.toInt() })
        val endX = targetLocation[0] - rootLocation[0] + target.width / 2f - startX - size / 2f
        val endY = targetLocation[1] - rootLocation[1] + target.height / 2f - startY - size / 2f
        val animation = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(fly, View.ALPHA, 0f, 1f, 1f, 0f),
                ObjectAnimator.ofFloat(fly, View.TRANSLATION_X, 0f, endX),
                ObjectAnimator.ofFloat(fly, View.TRANSLATION_Y, 0f, endY),
                ObjectAnimator.ofFloat(fly, View.SCALE_X, 1.35f, 0.54f),
                ObjectAnimator.ofFloat(fly, View.SCALE_Y, 1.35f, 0.54f),
            )
            duration = 720L
            interpolator = AccelerateDecelerateInterpolator()
        }
        animation.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                (fly.parent as? ViewGroup)?.removeView(fly)
                gemBalanceLabel?.text = formatGems(current)
                target.animate().scaleX(1.14f).scaleY(1.14f).setDuration(120L).withEndAction {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                }.start()
                gemFlightInProgress = false
            }
        })
        animation.start()
}

/** A half-screen cash celebration resolves into a focused flight toward the active wallet balance. */
internal fun DramaAppActivity.animateClaimedCash(previous: String, current: String) {
        val target = cashBalanceAnchor ?: return
        if (!target.isAttachedToWindow || gemFlightInProgress) return
        gemFlightInProgress = true
        val wallet = rewardsSnapshot?.wallet
        cashBalanceLabel?.text = wallet?.let { formatCurrencyAmount(it, previous, null, includeUsdReference = false) } ?: previous
        val rootLocation = IntArray(2); val targetLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation); target.getLocationOnScreen(targetLocation)
        val cashDelta = (current.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO).subtract(previous.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO).max(java.math.BigDecimal.ZERO)
        val rewardText = wallet?.let { "+${formatCurrencyAmount(it, cashDelta.toPlainString(), null, includeUsdReference = false)}" } ?: "+$current"
        val celebration = FrameLayout(this).apply {
            alpha = 0f
            // Keep the animation layer fully transparent: the coins and amount should sit
            // above the current screen without introducing a grey dimming scrim.
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val size = dp(112)
        val fly = ImageView(this).apply { setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE; alpha = 0f }
        val rewardBadge = TextView(this).apply {
            text = rewardText; textSize = 36f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(COLOR_SUNSET)
            alpha = 0f; setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val halo = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FFB34D); setStroke(dp(2), 0xFFFFB84D.toInt()) }; alpha = 0f
        }
        val celebrationHeight = (root.height * 0.54f).toInt().coerceAtLeast(dp(260))
        root.addView(celebration, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, celebrationHeight, Gravity.CENTER))
        celebration.addView(fly, FrameLayout.LayoutParams(size, size, Gravity.CENTER).apply { topMargin = dp(16) })
        celebration.addView(rewardBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(136) })
        repeat(6) { index ->
            val coin = ImageView(this).apply { setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE; alpha = 0f; tag = "cash-coin-$index" }
            val angle = Math.toRadians((index * 60.0) - 90.0)
            coin.translationX = kotlin.math.cos(angle).toFloat() * dp(28)
            coin.translationY = kotlin.math.sin(angle).toFloat() * dp(28)
            celebration.addView(coin, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
            coin.animate().alpha(0.95f).translationX(kotlin.math.cos(angle).toFloat() * dp(118)).translationY(kotlin.math.sin(angle).toFloat() * dp(94)).setStartDelay((index * 55).toLong()).setDuration(420L).start()
        }
        root.addView(halo, FrameLayout.LayoutParams(dp(84), dp(84)).apply { leftMargin = (targetLocation[0] - rootLocation[0] + target.width / 2 - dp(42)); topMargin = (targetLocation[1] - rootLocation[1] + target.height / 2 - dp(42)) })
        val celebrationTop = (root.height - celebrationHeight) / 2f
        val endX = targetLocation[0] - rootLocation[0] + target.width / 2f - root.width / 2f
        val endY = targetLocation[1] - rootLocation[1] + target.height / 2f - celebrationTop - celebrationHeight / 2f - dp(16)
        celebration.animate().alpha(1f).setDuration(180L).start()
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(fly, View.ALPHA, 0f, 1f, 1f, 0f),
                ObjectAnimator.ofFloat(fly, View.TRANSLATION_X, 0f, 0f, endX),
                ObjectAnimator.ofFloat(fly, View.TRANSLATION_Y, 0f, -dp(18).toFloat(), endY),
                ObjectAnimator.ofFloat(fly, View.SCALE_X, .48f, 1.34f, .36f),
                ObjectAnimator.ofFloat(fly, View.SCALE_Y, .48f, 1.34f, .36f),
                ObjectAnimator.ofFloat(fly, View.ROTATION, -14f, 12f, 0f),
                ObjectAnimator.ofFloat(rewardBadge, View.ALPHA, 0f, 1f, 1f, 0f),
                ObjectAnimator.ofFloat(rewardBadge, View.TRANSLATION_Y, 0f, -dp(14).toFloat(), -dp(48).toFloat()),
            )
            duration = 1_120L; interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (celebration.parent as? ViewGroup)?.removeView(celebration)
                    halo.animate().alpha(.9f).scaleX(.35f).scaleY(.35f).setDuration(1).withEndAction {
                        halo.animate().alpha(0f).scaleX(1.65f).scaleY(1.65f).setDuration(340).withEndAction { (halo.parent as? ViewGroup)?.removeView(halo) }.start()
                    }.start()
                    cashBalanceLabel?.text = wallet?.let { formatCurrencyAmount(it, current, null, includeUsdReference = false) } ?: current
                    target.animate().scaleX(1.20f).scaleY(1.20f).setDuration(130L).withEndAction { target.animate().scaleX(1f).scaleY(1f).setDuration(180L).start() }.start()
                    gemFlightInProgress = false
                }
            })
            start()
        }
}

internal fun DramaAppActivity.createPlayerAssetStrip(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)); background = roundedDrawable(0xA6000000.toInt(), 22); alpha = 0.84f
        val wallet = rewardsSnapshot?.wallet
        val walletIcon = ImageView(this@createPlayerAssetStrip).apply { setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE; isClickable = true; contentDescription = t("wallet"); setOnClickListener { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) } }
        cashBalanceAnchor = walletIcon
        addView(walletIcon, LinearLayout.LayoutParams(dp(38), dp(38)))
        val cash = TextView(this@createPlayerAssetStrip).apply { text = wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(5), 0, 0, 0); isClickable = true; setOnClickListener { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) } }
        cashBalanceLabel = cash
        addView(cash)
        val extraReward = TextView(this@createPlayerAssetStrip).apply {
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 1
            setPadding(dp(5), dp(5), dp(5), dp(5)); contentDescription = t("bonus_cash")
        }
        playerWalletBoostButtons.add(extraReward)
        updatePlayerWalletBoostButton(extraReward)
        val extraSlot = LinearLayout(this@createPlayerAssetStrip).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(extraReward, LinearLayout.LayoutParams(0, dp(34), 1f))
            val cooldown = ChargeCooldownRingView(this@createPlayerAssetStrip)
            walletChargeCooldownRings.add(cooldown); updateWalletChargeCooldownRing(cooldown)
            addView(cooldown, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(2) })
        }
        addView(extraSlot, LinearLayout.LayoutParams(dp(160), dp(38)).apply { marginStart = dp(8) })
    }

internal fun DramaAppActivity.createTopSafePlayerAssetStrip(): View {
        val shell = FrameLayout(this)
        val strip = createPlayerAssetStrip()
        shell.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(26) })
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val params = strip.layoutParams as FrameLayout.LayoutParams
            // Keep the in-player asset strip clear of the video action area and status icons.
            val margin = maxOf(safeTop, dp(18)) + dp(22)
            if (params.topMargin != margin) { params.topMargin = margin; strip.layoutParams = params }
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        return shell
    }

internal fun DramaAppActivity.formatGems(value: Long): String = String.format(Locale.US, "%,d", value)

internal fun DramaAppActivity.createBottomNavigation(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(9), dp(14), dp(10)); background = topRoundedDrawable(COLOR_CARD, 26); elevation = dp(10).toFloat()
        MainTab.entries.forEach { tab ->
            val active = activeTab == tab
            addView(LinearLayout(this@createBottomNavigation).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
                setOnClickListener {
                    if (activeTab != tab) {
                        // The non-immersive Discover preview owns a PSSDK Fragment. Remove it
                        // before rendering another tab so its media surface cannot outlive the page.
                        if (sdkPlayerShown) {
                            releasePlayer()
                            dismissSdkDramaPlayerIfShown()
                            playerShown = false
                        }
                        if (tab == MainTab.DISCOVER) discoverPreviewPresented = false
                        activeTab = tab
                        renderActiveTab()
                    }
                }
                val icon = ImageView(this@createBottomNavigation).apply {
                    setImageResource(tab.iconRes)
                    imageTintList = ColorStateList.valueOf(if (active) COLOR_CORAL else COLOR_TEXT_MUTED)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(TextView(this@createBottomNavigation).apply { text = t(tab.languageKey); textSize = 11f; gravity = Gravity.CENTER; typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; setTextColor(if (active) COLOR_CORAL else COLOR_TEXT_MUTED); setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

internal fun DramaAppActivity.createSectionHeader(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@createSectionHeader).apply { text = title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
        if (subtitle.isNotBlank()) addView(TextView(this@createSectionHeader).apply { text = subtitle; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0) })
    }
