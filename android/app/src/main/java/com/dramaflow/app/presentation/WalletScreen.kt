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

internal fun DramaAppActivity.showWithdrawalPage(wallet: RewardsApiClient.Wallet) {
        withdrawalPageShown = true
        if (playerShown) releasePlayer()
        playerShown = false
        window.statusBarColor = COLOR_BACKGROUND
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        gemBalanceAnchor = null
        gemBalanceLabel = null
        cashBalanceLabel = null
        exitImmersivePlayerMode()
        val page = ScrollView(this).apply {
            background = pageCanvasDrawable()
            isFillViewport = true
            addView(createWithdrawalPage(wallet))
        }
        root.removeAllViews()
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

internal fun DramaAppActivity.createWithdrawalPage(wallet: RewardsApiClient.Wallet): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(56), dp(18), dp(34))
        addView(LinearLayout(this@createWithdrawalPage).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@createWithdrawalPage).apply {
                setImageResource(R.drawable.ic_arrow_back); imageTintList = ColorStateList.valueOf(COLOR_TEXT_PRIMARY); scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(0, 0, dp(14), 0); isClickable = true; contentDescription = t("back")
                setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(this@createWithdrawalPage).apply {
                text = t("wallet"); textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@createWithdrawalPage).apply {
                text = t("rules"); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT)
                setPadding(dp(10), dp(8), 0, dp(8)); isClickable = true; setOnClickListener { showWalletRulesDialog(wallet) }
            })
        }, matchParams(bottom = 16))
        addView(LinearLayout(this@createWithdrawalPage).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(20))
            background = gradientDrawable(intArrayOf(COLOR_INK, COLOR_PLUM, 0xFF24304A.toInt()), 24)
            val balanceRow = LinearLayout(this@createWithdrawalPage).apply { gravity = Gravity.CENTER_VERTICAL }
            balanceRow.addView(LinearLayout(this@createWithdrawalPage).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@createWithdrawalPage).apply { text = t("wallet_balance"); textSize = 14f; setTextColor(0xCCFFFFFF.toInt()) })
                addView(TextView(this@createWithdrawalPage).apply { text = formatWalletTotal(wallet, includeUsdReference = false); textSize = 38f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(5), 0, 0); maxLines = 2 })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            balanceRow.addView(ImageView(this@createWithdrawalPage).apply {
                setImageResource(R.drawable.wallet_money); scaleType = ImageView.ScaleType.CENTER_INSIDE; contentDescription = t("wallet")
            }, LinearLayout.LayoutParams(dp(86), dp(86)))
            addView(balanceRow)
            addView(LinearLayout(this@createWithdrawalPage).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(13)); background = roundedDrawable(0x26FFFFFF, 18)
                val titleRow = LinearLayout(this@createWithdrawalPage).apply { gravity = Gravity.CENTER_VERTICAL }
                titleRow.addView(TextView(this@createWithdrawalPage).apply { text = t("ad_boost"); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val quota = TextView(this@createWithdrawalPage).apply {
                    textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xDDFFFFFF.toInt())
                }
                walletChargeQuotaLabels.add(quota); updateWalletChargeQuotaLabel(quota)
                titleRow.addView(quota)
                addView(titleRow)
                addView(TextView(this@createWithdrawalPage).apply {
                    text = t("wallet_boost_description")
                    textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, dp(5), 0, dp(10))
                })
                val boostAction = TextView(this@createWithdrawalPage).apply {
                    textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(38), dp(10))
                }
                walletChargeButtons.add(boostAction); updateWalletChargeButton(boostAction)
                val boostSlot = FrameLayout(this@createWithdrawalPage).apply {
                    addView(boostAction, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), Gravity.CENTER))
                    val cooldown = ChargeCooldownRingView(this@createWithdrawalPage)
                    walletChargeCooldownRings.add(cooldown); updateWalletChargeCooldownRing(cooldown)
                    addView(cooldown, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })
                }
                addView(boostSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            }, matchParams(top = 16, bottom = 18))
            val balance = wallet.cashWallet.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val usdBalance = wallet.availableBalanceUsdReference.toBigDecimalOrNull()
                ?: wallet.cashWalletUsdReference.toBigDecimalOrNull()
                ?: balance
            // The reward model has a single US$300 ceiling. Showing additional $750/$1,200
            // milestones contradicted that model and diluted the primary goal.
            val goalUsd = java.math.BigDecimal("300.00")
            val remainingUsd = goalUsd.subtract(usdBalance).coerceAtLeast(java.math.BigDecimal.ZERO)
            addView(TextView(this@createWithdrawalPage).apply {
                text = t("cashout_goal_cap")
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xCCFFFFFF.toInt())
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(TextView(this@createWithdrawalPage).apply {
                text = "US$300"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_SUNSET)
                includeFontPadding = false
            })
            addView(ProgressBar(this@createWithdrawalPage, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = usdBalance.multiply(java.math.BigDecimal(100)).divide(goalUsd, 0, java.math.RoundingMode.DOWN).toInt().coerceIn(0, 100)
                progressTintList = ColorStateList.valueOf(COLOR_SUNSET)
                backgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(8); bottomMargin = dp(7) })
            addView(TextView(this@createWithdrawalPage).apply {
                text = t("cashout_goal_progress", "US$300", "US$${remainingUsd.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}")
                textSize = 12f
                setTextColor(0xCCFFFFFF.toInt())
            })
            addView(TextView(this@createWithdrawalPage).apply {
                text = t("cashout_watch_action")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT_PRIMARY)
                background = roundedDrawable(COLOR_SURFACE, 15)
                isClickable = true
                setOnClickListener { activeTab = MainTab.HOME; renderActiveTab() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        }, matchParams(bottom = 14))
    }

internal fun DramaAppActivity.formatCurrencyAmount(wallet: RewardsApiClient.Wallet, amount: String, usdReference: String? = null, includeUsdReference: Boolean = false): String {
        val fractionDigits = wallet.currency.fractionDigits.coerceIn(0, 6)
        val locale = when (wallet.currency.code) {
            "BRL" -> Locale("pt", "BR")
            "IDR" -> Locale("id", "ID")
            else -> Locale.US
        }
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }
        val numeric = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val local = "${wallet.currency.symbol}${formatter.format(numeric)}"
        // The server assigns a fixed market policy: USD by default, local currency only
        // for explicitly configured markets. Do not show approximate public-FX references.
        return local
    }

internal fun DramaAppActivity.formatWalletTotal(wallet: RewardsApiClient.Wallet, includeUsdReference: Boolean = false): String {
        if (wallet.cashMode) {
            return formatCurrencyAmount(wallet, wallet.cashWallet, wallet.cashWalletUsdReference, includeUsdReference)
        }
        val total = (wallet.cashWallet.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) +
            (wallet.availableBalance.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        val usdTotal = (wallet.cashWalletUsdReference.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) +
            (wallet.availableBalanceUsdReference.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        return formatCurrencyAmount(wallet, total.toPlainString(), usdTotal.toPlainString(), includeUsdReference)
    }

internal fun DramaAppActivity.showCashoutEligibilityNotice() {
        val wallet = rewardsSnapshot?.wallet
        val withdrawal = rewardsSnapshot?.withdrawal
        val threshold = withdrawal?.threshold?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val balance = wallet?.cashWallet?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val dynamicReason = if (wallet != null && threshold > java.math.BigDecimal.ZERO && balance < threshold) {
            t("cashout_threshold_notice", formatCurrencyAmount(wallet, threshold.toPlainString(), null, false))
        } else {
            withdrawal?.reason?.takeIf { it.isNotBlank() } ?: t("cashout_minimum_notice")
        }
        showBrandedChoiceDialog(t("request_cashout"), dynamicReason, t("done"))
    }
