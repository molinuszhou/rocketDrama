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
                gravity = Gravity.CENTER_VERTICAL; background = roundedDrawable(0xF2FFFFFF.toInt(), 20); setPadding(dp(5), dp(4), dp(5), dp(4)); clipToOutline = true
                addView(createAssetPill(icon = R.drawable.wallet_money, label = t("wallet_balance"), value = wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—", isGem = false) { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            }
            addView(balanceBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        shell.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP).apply { topMargin = dp(30) })
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

internal fun DramaAppActivity.createAssetPill(icon: Int, label: String, value: String, isGem: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(7), dp(4), dp(7), dp(4))
        elevation = 0f
        isClickable = true
        contentDescription = "$label $value"
        setOnClickListener { onClick() }
        val image = ImageView(this@createAssetPill).apply { setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        if (isGem) gemBalanceAnchor = image else cashBalanceAnchor = image
        addView(image, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(this@createAssetPill).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), 0, 0, 0)
            val valueView = TextView(this@createAssetPill).apply { text = value; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 1; includeFontPadding = false; letterSpacing = -0.02f; setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
        if (isGem) gemBalanceLabel = valueView else cashBalanceLabel = valueView
            addView(valueView)
            addView(TextView(this@createAssetPill).apply { text = label; textSize = 9f; setTextColor(COLOR_TEXT_MUTED); maxLines = 1 })
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
            if (index < items.lastIndex) addView(View(this@createCashoutSummary).apply { setBackgroundColor(0x151C1C1C) }, LinearLayout.LayoutParams(dp(1), dp(55)))
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

/** The v1.6.4 direct-cash wallet uses the same restrained confirmation motion without a Gem balance. */
internal fun DramaAppActivity.animateClaimedCash(previous: String, current: String) {
        val target = cashBalanceAnchor ?: return
        if (!target.isAttachedToWindow || gemFlightInProgress) return
        gemFlightInProgress = true
        val wallet = rewardsSnapshot?.wallet
        cashBalanceLabel?.text = wallet?.let { formatCurrencyAmount(it, previous, null, includeUsdReference = false) } ?: previous
        val rootLocation = IntArray(2); val targetLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation); target.getLocationOnScreen(targetLocation)
        val fly = ImageView(this).apply { setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE; alpha = 0f }
        val size = dp(42); val startX = root.width / 2f - size / 2f; val startY = root.height * 0.53f - size / 2f
        root.addView(fly, FrameLayout.LayoutParams(size, size).apply { leftMargin = startX.toInt(); topMargin = startY.toInt() })
        val endX = targetLocation[0] - rootLocation[0] + target.width / 2f - startX - size / 2f
        val endY = targetLocation[1] - rootLocation[1] + target.height / 2f - startY - size / 2f
        AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(fly, View.ALPHA, 0f, 1f, 1f, 0f), ObjectAnimator.ofFloat(fly, View.TRANSLATION_X, 0f, endX), ObjectAnimator.ofFloat(fly, View.TRANSLATION_Y, 0f, endY), ObjectAnimator.ofFloat(fly, View.SCALE_X, 1.25f, .55f), ObjectAnimator.ofFloat(fly, View.SCALE_Y, 1.25f, .55f))
            duration = 680L; interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (fly.parent as? ViewGroup)?.removeView(fly)
                    cashBalanceLabel?.text = wallet?.let { formatCurrencyAmount(it, current, null, includeUsdReference = false) } ?: current
                    target.animate().scaleX(1.12f).scaleY(1.12f).setDuration(110L).withEndAction { target.animate().scaleX(1f).scaleY(1f).setDuration(140L).start() }.start()
                    gemFlightInProgress = false
                }
            })
            start()
        }
}

internal fun DramaAppActivity.createPlayerAssetStrip(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(5), dp(9), dp(5)); background = roundedDrawable(0xD9141720.toInt(), 18)
        val wallet = rewardsSnapshot?.wallet
        val walletIcon = ImageView(this@createPlayerAssetStrip).apply { setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE; isClickable = true; contentDescription = t("wallet"); setOnClickListener { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) } }
        cashBalanceAnchor = walletIcon
        addView(walletIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
        val cash = TextView(this@createPlayerAssetStrip).apply { text = wallet?.let { formatWalletTotal(it, includeUsdReference = false) } ?: "—"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(3), 0, 0, 0); isClickable = true; setOnClickListener { wallet?.let(::showWithdrawalPage) ?: toast(t("wallet_syncing")) } }
        cashBalanceLabel = cash
        addView(cash)
        val extraReward = TextView(this@createPlayerAssetStrip).apply {
            textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 1
            setPadding(dp(8), dp(5), dp(8), dp(5)); contentDescription = t("extra_reward")
        }
        playerWalletBoostButtons.add(extraReward)
        updatePlayerWalletBoostButton(extraReward)
        addView(extraReward, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginStart = dp(7) })
    }

internal fun DramaAppActivity.createTopSafePlayerAssetStrip(): View {
        val shell = FrameLayout(this)
        val strip = createPlayerAssetStrip()
        shell.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(26) })
        ViewCompat.setOnApplyWindowInsetsListener(shell) { _, insets ->
            val safeTop = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val params = strip.layoutParams as FrameLayout.LayoutParams
            val margin = maxOf(safeTop, dp(18)) + dp(10)
            if (params.topMargin != margin) { params.topMargin = margin; strip.layoutParams = params }
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        return shell
    }

internal fun DramaAppActivity.formatGems(value: Long): String = String.format(Locale.US, "%,d", value)

internal fun DramaAppActivity.createBottomNavigation(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(9), dp(14), dp(10)); background = topRoundedDrawable(0xFCFFFFFF.toInt(), 26); elevation = dp(10).toFloat()
        MainTab.entries.forEach { tab ->
            val active = activeTab == tab
            addView(LinearLayout(this@createBottomNavigation).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
                setOnClickListener { if (activeTab != tab) { activeTab = tab; renderActiveTab() } }
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
