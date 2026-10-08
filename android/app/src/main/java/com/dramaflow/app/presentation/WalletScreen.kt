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
import com.dramaflow.app.local.LocalEpisode
import com.dramaflow.app.rewards.RewardsApiClient
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

internal fun DramaAppActivity.showWithdrawalPage(wallet: RewardsApiClient.Wallet) {
        withdrawalPageShown = true
        if (playerShown) releasePlayer()
        if (sdkPlayerShown) dismissSdkDramaPlayerIfShown()
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
                text = if (isWithdrawalAccountLinked()) t("linked") else t("link_account")
                textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(COLOR_SUNSET); setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true
                setOnClickListener { showWithdrawalAccountDialog(this) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
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
            val balance = wallet.cashWallet.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val usdBalance = wallet.availableBalanceUsdReference.toBigDecimalOrNull()
                ?: wallet.cashWalletUsdReference.toBigDecimalOrNull()
                ?: balance
            val withdrawalEnabled = rewardsSnapshot?.withdrawal?.enabled == true
            listOf("200.00", "500.00", "800.00", "1000.00").forEachIndexed { index, rawGoal ->
                val goalUsd = java.math.BigDecimal(rawGoal)
                val goalLabel = "US$${goalUsd.setScale(0).toPlainString()}"
                val goalReached = withdrawalEnabled && usdBalance >= goalUsd
                addView(LinearLayout(this@createWithdrawalPage).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(10), dp(13), dp(10))
                    background = roundedDrawable(if (index == 0) 0x28FFFFFF else 0x18FFFFFF, 15)
                    val goalRow = LinearLayout(this@createWithdrawalPage).apply { gravity = Gravity.CENTER_VERTICAL }
                    goalRow.addView(TextView(this@createWithdrawalPage).apply {
                        text = goalLabel
                        textSize = if (index == 0) 24f else 18f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (index == 0) COLOR_SUNSET else Color.WHITE)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    goalRow.addView(TextView(this@createWithdrawalPage).apply {
                        text = t("withdraw")
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setSingleLine(true)
                        includeFontPadding = false
                        setTextColor(Color.WHITE)
                        background = primaryActionDrawable()
                        isClickable = true
                        setOnClickListener {
                            showWithdrawalProgressDialog(
                                wallet = wallet,
                                rawGoal = rawGoal,
                                goalLabel = goalLabel,
                                balanceReached = goalReached,
                            )
                        }
                    }, LinearLayout.LayoutParams(dp(92), dp(32)))
                    addView(goalRow)
                    addView(ProgressBar(this@createWithdrawalPage, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = usdBalance.multiply(java.math.BigDecimal(100)).divide(goalUsd, 0, java.math.RoundingMode.DOWN).toInt().coerceIn(0, 100)
                        progressTintList = ColorStateList.valueOf(if (index == 0) COLOR_SUNSET else COLOR_ACCENT)
                        backgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8); bottomMargin = dp(2) })
                }, matchParams(bottom = if (index == 3) 0 else 8))
            }
            addView(TextView(this@createWithdrawalPage).apply {
                text = t("cashout_watch_action")
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = primaryActionDrawable()
                isClickable = true
                setOnClickListener { activeTab = MainTab.HOME; renderActiveTab() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        }, matchParams(bottom = 14))
    }

private fun DramaAppActivity.showWithdrawalAccountDialog(linkButton: TextView) {
    val providers = listOf(
        Triple("PAYPAL", R.drawable.ic_withdraw_paypal, 0xFF2276D2.toInt()),
        Triple("STRIPE", R.drawable.ic_withdraw_stripe, 0xFF6755D6.toInt()),
        Triple("LINK", R.drawable.ic_withdraw_link, COLOR_ACCENT),
    )
    val selected = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("withdrawal_provider", "PAYPAL") ?: "PAYPAL"
    val dialog = AlertDialog.Builder(this).create()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(22), dp(22), dp(18))
        background = gradientDrawable(intArrayOf(COLOR_CARD, COLOR_SURFACE), 26)
        addView(TextView(this@showWithdrawalAccountDialog).apply {
            text = t("withdrawal_provider_select")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        addView(TextView(this@showWithdrawalAccountDialog).apply {
            text = t("withdrawal_provider_hint")
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(7), 0, dp(14))
        })
    }
    providers.forEachIndexed { index, (provider, iconRes, color) ->
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedDrawable(if (provider == selected) 0x22FF6D4A else COLOR_SURFACE, 16)
            isClickable = true
            addView(ImageView(this@showWithdrawalAccountDialog).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(7), dp(7), dp(7), dp(7))
                contentDescription = provider.replaceFirstChar(Char::titlecase)
                background = roundedDrawable(color, 15)
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(this@showWithdrawalAccountDialog).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@showWithdrawalAccountDialog).apply { text = provider.replaceFirstChar(Char::titlecase); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
                addView(TextView(this@showWithdrawalAccountDialog).apply { text = t("withdrawal_account"); textSize = 11f; setTextColor(COLOR_TEXT_MUTED) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@showWithdrawalAccountDialog).apply { text = if (provider == selected) "✓" else ""; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT) })
            setOnClickListener {
                dialog.dismiss()
                showWithdrawalAccountInput(provider, linkButton)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { if (index > 0) topMargin = dp(8) })
    }
    card.addView(TextView(this).apply {
        text = t("cancel")
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT_PRIMARY)
        background = roundedDrawable(COLOR_SURFACE, 16)
        isClickable = true
        setOnClickListener { dialog.dismiss() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
    dialog.setView(card)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        setLayout((resources.displayMetrics.widthPixels * 0.90f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

private fun DramaAppActivity.showWithdrawalAccountInput(provider: String, linkButton: TextView) {
    val input = EditText(this).apply {
        hint = t("withdrawal_account_hint")
        setSingleLine(true)
        setTextColor(COLOR_TEXT_PRIMARY)
    }
    var dialog: AlertDialog? = null
    dialog = showBrandedInputDialog(
        title = t("withdrawal_account"),
        message = t("withdrawal_account_for", provider.replaceFirstChar(Char::titlecase)),
        input = input,
        primaryLabel = t("save"),
        onPrimary = { account ->
            rewardsApi.saveWithdrawalAccount(anonymousInstallationId(), provider, account, onSuccess = {
                getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("withdrawal_account_linked", true).putString("withdrawal_provider", provider).apply()
                runOnUiThread {
                    dialog?.dismiss()
                    linkButton.text = t("linked")
                    toast(t("withdrawal_account_saved"))
                }
            }, onFailure = { message -> runOnUiThread { toast(message) } })
        },
    )
}

private fun DramaAppActivity.requestWithdrawalForGoal(wallet: RewardsApiClient.Wallet, rawGoal: String) {
    if (!isWithdrawalAccountLinked()) {
        toast(t("link_account_first")); return
    }
    rewardsApi.requestWithdrawal(anonymousInstallationId(), rawGoal, onSuccess = { result ->
        runOnUiThread {
            showWithdrawalProgressDialog(
                wallet = wallet,
                rawGoal = rawGoal,
                goalLabel = result.optString("amount", rawGoal),
                balanceReached = true,
                requestSubmitted = true,
            )
        }
    }, onFailure = { message -> runOnUiThread { toast(message) } })
}

private fun DramaAppActivity.isWithdrawalAccountLinked(): Boolean =
    rewardsSnapshot?.withdrawal?.accountLinked
        ?: getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("withdrawal_account_linked", false)

private fun DramaAppActivity.showCashoutGoalNotice(goalLabel: String, remainingUsd: java.math.BigDecimal) {
    val message = if (remainingUsd.signum() > 0) {
        t("cashout_goal_progress", goalLabel, "US$${remainingUsd.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}")
    } else {
        rewardsSnapshot?.withdrawal?.reason?.takeIf { it.isNotBlank() } ?: t("cashout_threshold_notice", goalLabel)
    }
    showBrandedChoiceDialog(t("withdraw"), message, t("done"))
}

/**
 * Displays the withdrawal route for every goal tap.  An under-threshold balance is a real,
 * actionable state rather than a dead-end message: the final CTA launches one wallet-boost ad
 * and therefore consumes the same server-governed daily quota and cooldown as the wallet card.
 */
private fun DramaAppActivity.showWithdrawalProgressDialog(
    wallet: RewardsApiClient.Wallet,
    rawGoal: String,
    goalLabel: String,
    balanceReached: Boolean,
    requestSubmitted: Boolean = false,
) {
    val dialog = AlertDialog.Builder(this).create()
    val animatedRows = mutableListOf<View>()
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(20))
        background = gradientDrawable(intArrayOf(COLOR_CARD, 0xFF2A2116.toInt()), 28)
        addView(TextView(this@showWithdrawalProgressDialog).apply {
            text = "✓"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedDrawable(COLOR_ACCENT, 22)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(this@showWithdrawalProgressDialog).apply {
            text = t("withdrawal_progress_title")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(0, dp(10), 0, 0)
        })
        addView(TextView(this@showWithdrawalProgressDialog).apply {
            text = goalLabel
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(COLOR_SUNSET)
            setPadding(0, dp(5), 0, dp(16))
        })

        data class ProgressStep(val label: String, val state: Int)
        val steps = listOf(
            ProgressStep(t("withdrawal_progress_submitted"), if (requestSubmitted) 2 else 1),
            ProgressStep(t("withdrawal_progress_review"), if (requestSubmitted) 1 else 0),
            ProgressStep(
                if (balanceReached) t("withdrawal_progress_balance") else t("withdrawal_progress_insufficient"),
                if (balanceReached) 2 else 3,
            ),
            ProgressStep(t("withdrawal_progress_pending"), if (requestSubmitted && balanceReached) 1 else 0),
        )
        steps.forEachIndexed { index, step ->
            val (circleColor, marker, textColor) = when (step.state) {
                2 -> Triple(COLOR_ACCENT, "✓", COLOR_TEXT_PRIMARY)
                3 -> Triple(COLOR_SUNSET, "!", COLOR_TEXT_PRIMARY)
                1 -> Triple(COLOR_SUNSET, "•", COLOR_TEXT_PRIMARY)
                else -> Triple(COLOR_SURFACE, "•", COLOR_TEXT_MUTED)
            }
            val row = LinearLayout(this@showWithdrawalProgressDialog).apply {
                gravity = Gravity.CENTER_VERTICAL
                val timeline = FrameLayout(this@showWithdrawalProgressDialog)
                if (index < steps.lastIndex) {
                    timeline.addView(View(this@showWithdrawalProgressDialog).apply {
                        setBackgroundColor(if (step.state == 2) COLOR_ACCENT else 0xFF67616B.toInt())
                    }, FrameLayout.LayoutParams(dp(3), dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                        topMargin = dp(22)
                    })
                }
                timeline.addView(TextView(this@showWithdrawalProgressDialog).apply {
                    text = marker
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundedDrawable(circleColor, 12)
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                addView(timeline, LinearLayout.LayoutParams(dp(28), dp(46)))
                addView(TextView(this@showWithdrawalProgressDialog).apply {
                    text = step.label
                    textSize = 14f
                    typeface = if (step.state == 2 || step.state == 3) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(textColor)
                    setPadding(dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            row.alpha = 0f
            row.translationY = dp(12).toFloat()
            animatedRows += row
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }
        addView(TextView(this@showWithdrawalProgressDialog).apply {
            text = when {
                requestSubmitted -> t("done")
                !balanceReached -> t("continue_earning")
                !isWithdrawalAccountLinked() -> t("link_account")
                else -> t("request_cashout")
            }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = primaryActionDrawable()
            isClickable = true
            setOnClickListener {
                when {
                    requestSubmitted -> dialog.dismiss()
                    !balanceReached -> {
                        dialog.dismiss()
                        // Directly use the existing wallet-boost action; the server continues to
                        // enforce both the remaining daily count and the charge cooldown.
                        requestWalletReleaseBoost()
                    }
                    !isWithdrawalAccountLinked() -> {
                        dialog.dismiss()
                        toast(t("link_account_first"))
                    }
                    else -> {
                        dialog.dismiss()
                        requestWithdrawalForGoal(wallet, rawGoal)
                    }
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(14) })
    }
    dialog.setView(card)
    dialog.setCancelable(false)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    animatedRows.forEachIndexed { index, row ->
        row.animate()
            .alpha(1f)
            .translationY(0f)
            // The withdrawal route is intentionally legible: each stage pauses before revealing
            // the next, matching the reference timeline instead of flashing all states at once.
            .setStartDelay(650L + index * 850L)
            .setDuration(360L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
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
