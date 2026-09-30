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

internal fun DramaAppActivity.beginStartupExperience() {
        if (startupContentInitializationStarted) return
        startupCheckinPromptRequested = true
        startupCheckinPromptShown = false
        startupAdFinished = false
        startupCatalogFinished = false
        startupCatalogFailure = null
        startupPosterPreloadFinished = false
        startupRewardsFinished = !rewardsApi.isConfigured
        startupMinimumVisualFinished = false
        startupContentHandoffFinished = false
        startupPosterPreloadPercent = 0
        shortDramaRetryAttempt = 0
        shortDramaRetryScheduled = false
        shortDramaRetryGeneration += 1
        pendingShortDramaDiagnostic = null
        shownShortDramaDiagnostic = null
        shortDramaDiagnosticDialogShowing = false
        shortDramaDiagnosticNotBeforeElapsedMillis = 0L
        shortDramaDiagnosticDialog = null
        renderStartupLoading()
        updateStartupProgress(0, t("startup_preparing"))
        // Keep a real, short loading phase on fast devices. Slow provider work remains background
        // work; the main page uses an inline catalogue loading state instead of holding launch.
        root.postDelayed({
            startupMinimumVisualFinished = true
            completeStartupWhenReady()
        }, MINIMUM_STARTUP_VISUAL_MS)
        // Keep the branded launch surface visible while the first PSSDK feed races to return.
        // This remains bounded, so an unhealthy provider cannot recreate the historic 35% block.
        root.postDelayed({
            startupContentHandoffFinished = true
            completeStartupWhenReady()
        }, STARTUP_CONTENT_HANDOFF_GRACE_MS)
        if (rewardsApi.isConfigured) refreshRewardsSnapshot() else completeStartupWhenReady()
        preloadCatalog()
        // 等待 onPostResume 后再发起开屏请求，确保窗口已就绪；目录和开屏并行预热。
    }

internal fun DramaAppActivity.maybeShowAppOpenAd() {
        if (appOpenAttempted) return
        appOpenAttempted = true
        if (restoringExternalAdReturn) {
            finishStartupAfterAppOpen("external_ad_return")
            return
        }
        if (consentStore.read() != AdConsent.GRANTED) {
            finishStartupAfterAppOpen("ads_disabled")
            return
        }
        // Activity 已进入前台后请求；TopOnAdsManager 会等待 SDK 初始化成功回调再加载开屏。
        topOnAds.showAppOpenIfEligible(this) { result ->
            when (result) {
                TopOnAdsManager.FullscreenResult.Shown -> logStatus("开屏广告已关闭")
                is TopOnAdsManager.FullscreenResult.Failed -> logStatus("开屏广告加载失败：${result.code} ${result.message}")
                is TopOnAdsManager.FullscreenResult.Skipped -> logStatus("开屏广告未展示：${result.reason}")
            }
            runOnUiThread { finishStartupAfterAppOpen(result.javaClass.simpleName) }
        }
    }

internal fun DramaAppActivity.finishStartupAfterAppOpen(reason: String) {
        if (startupContentInitializationStarted || isFinishing || isDestroyed) return
        startupAdFinished = true
        logStatus("开屏流程结束：$reason")
        updateStartupProgress(computeStartupProgress(), t("startup_ad_ready"))
        completeStartupWhenReady()
    }

internal fun DramaAppActivity.preloadCatalog() {
    preloadShortDramaSdkCatalog()
}

private fun DramaAppActivity.preloadShortDramaSdkCatalog() {
    shortDramaSdk.initializeIfEnabled { initialization -> runOnUiThread {
        if (initialization != ShortDramaSdkManager.Initialization.Ready) {
            startupCatalogFinished = true
            startupPosterPreloadFinished = true
            val retryAllowed = initialization is ShortDramaSdkManager.Initialization.Failed
            startupCatalogFailure = when (initialization) {
                is ShortDramaSdkManager.Initialization.InvalidConfiguration -> initialization.message
                is ShortDramaSdkManager.Initialization.Failed -> initialization.message
                ShortDramaSdkManager.Initialization.Disabled -> "Short-drama SDK is disabled in this build"
                ShortDramaSdkManager.Initialization.Ready -> error("unreachable")
            }
            if (startupContentInitializationStarted) renderActiveTab() else completeStartupWhenReady()
            queueShortDramaCatalogDiagnostic(startupCatalogFailure.orEmpty())
            if (retryAllowed) scheduleShortDramaCatalogRetry(startupCatalogFailure.orEmpty())
            return@runOnUiThread
        }
        shortDramaSdk.loadDiscovery(
            onSuccess = { discovery -> runOnUiThread {
                val cards = (discovery.popular + discovery.latest)
                    .distinctBy { it.id }
                    .filter { it.title.isNotBlank() }
                startupCatalogFinished = true
                startupContentHandoffFinished = true
                if (cards.isEmpty()) {
                    startupCatalogFailure = t("catalog_empty")
                    startupPosterPreloadFinished = true
                    if (startupContentInitializationStarted) renderActiveTab() else completeStartupWhenReady()
                    queueShortDramaCatalogDiagnostic(startupCatalogFailure.orEmpty())
                    scheduleShortDramaCatalogRetry(startupCatalogFailure.orEmpty())
                    return@runOnUiThread
                }
                shortDramaRetryAttempt = 0
                shortDramaRetryScheduled = false
                clearShortDramaCatalogDiagnostic()
                sdkDramaCardsByCatalogId.clear()
                cards.forEach { card -> sdkDramaCardsByCatalogId[sdkCatalogId(card.id)] = card }
                catalogItems = cards.map(::sdkCatalogDrama)
                catalogNextPage = 2
                catalogHasMorePages = discovery.hasMore
                catalogPageLoadInFlight = false
                updateStartupProgress(30, t("startup_content_ready"))
                if (startupContentInitializationStarted) renderActiveTab()
                preloadCatalogCovers(
                    items = catalogItems,
                    onProgress = { percent ->
                        startupPosterPreloadPercent = percent
                        updateStartupProgress(computeStartupProgress(), t("startup_content_ready"))
                    },
                    onFinished = {
                        startupPosterPreloadFinished = true
                        startupPosterPreloadPercent = 100
                        if (startupContentInitializationStarted) renderActiveTab() else completeStartupWhenReady()
                    },
                    onTimedOut = {
                        startupCatalogFailure = t("startup_resources_timeout")
                        startupPosterPreloadFinished = true
                        if (startupContentInitializationStarted) renderActiveTab() else completeStartupWhenReady()
                    },
                )
            } },
            onFailure = { message -> runOnUiThread {
                startupCatalogFinished = true
                startupContentHandoffFinished = true
                startupPosterPreloadFinished = true
                startupCatalogFailure = t("catalog_load_failed", message)
                if (startupContentInitializationStarted) renderActiveTab() else completeStartupWhenReady()
                queueShortDramaCatalogDiagnostic(message)
                scheduleShortDramaCatalogRetry(message)
            } },
        )
    } }
}

/** Delays transient provider failures so a late PSSDK success does not interrupt the first experience. */
internal fun DramaAppActivity.queueShortDramaCatalogDiagnostic(message: String) {
    if (message.isBlank()) return
    pendingShortDramaDiagnostic = message.take(MAX_SHORT_DRAMA_DIAGNOSTIC_LENGTH)
    shortDramaDiagnosticNotBeforeElapsedMillis = android.os.SystemClock.elapsedRealtime() + SHORT_DRAMA_DIAGNOSTIC_GRACE_MS
    root.postDelayed(::showPendingShortDramaCatalogDiagnostic, SHORT_DRAMA_DIAGNOSTIC_GRACE_MS)
}

internal fun DramaAppActivity.showPendingShortDramaCatalogDiagnostic() {
    val message = pendingShortDramaDiagnostic ?: return
    if (catalogItems.isNotEmpty() || isFinishing || isDestroyed) return
    val remainingGrace = shortDramaDiagnosticNotBeforeElapsedMillis - android.os.SystemClock.elapsedRealtime()
    if (remainingGrace > 0L) {
        root.postDelayed(::showPendingShortDramaCatalogDiagnostic, remainingGrace)
        return
    }
    if (!startupCheckinPromptShown || shortDramaDiagnosticDialogShowing || shownShortDramaDiagnostic == message) return
    shortDramaDiagnosticDialogShowing = true
    val dialog = showBrandedChoiceDialog(
        title = t("short_drama_diagnostic_title"),
        message = message,
        primaryLabel = t("retry"),
        onPrimary = {
            shownShortDramaDiagnostic = message
            pendingShortDramaDiagnostic = null
            shortDramaDiagnosticDialogShowing = false
            shortDramaRetryGeneration += 1
            shortDramaRetryScheduled = false
            shortDramaRetryAttempt = 0
            preloadShortDramaSdkCatalog()
        },
        secondaryLabel = t("close"),
        onSecondary = {
            shownShortDramaDiagnostic = message
            pendingShortDramaDiagnostic = null
            shortDramaDiagnosticDialogShowing = false
        },
    )
    shortDramaDiagnosticDialog = dialog
    dialog.setOnDismissListener {
        if (shortDramaDiagnosticDialog === dialog) {
            shortDramaDiagnosticDialog = null
            shortDramaDiagnosticDialogShowing = false
        }
    }
}

/** Clears a pending or visible transient diagnostic as soon as usable catalogue data arrives. */
internal fun DramaAppActivity.clearShortDramaCatalogDiagnostic() {
    pendingShortDramaDiagnostic = null
    shownShortDramaDiagnostic = null
    shortDramaDiagnosticNotBeforeElapsedMillis = 0L
    shortDramaDiagnosticDialog?.dismiss()
    shortDramaDiagnosticDialog = null
    shortDramaDiagnosticDialogShowing = false
}

/** Retries only transient PSSDK bootstrap/catalog failures; the application shell stays interactive. */
private fun DramaAppActivity.scheduleShortDramaCatalogRetry(reason: String) {
    if (catalogItems.isNotEmpty() || shortDramaRetryScheduled || shortDramaRetryAttempt >= MAX_SHORT_DRAMA_RETRY_ATTEMPTS || isFinishing || isDestroyed) return
    shortDramaRetryScheduled = true
    val nextAttempt = shortDramaRetryAttempt + 1
    val delayMillis = SHORT_DRAMA_RETRY_DELAYS_MS[shortDramaRetryAttempt]
    val generation = shortDramaRetryGeneration
    logStatus("短剧目录加载未完成，${delayMillis / 1_000} 秒后进行第 $nextAttempt 次后台重试：$reason")
    root.postDelayed({
        if (generation != shortDramaRetryGeneration) return@postDelayed
        shortDramaRetryScheduled = false
        if (!isFinishing && !isDestroyed && catalogItems.isEmpty()) {
            shortDramaRetryAttempt = nextAttempt
            preloadShortDramaSdkCatalog()
        }
    }, delayMillis)
}

    /** Preloads the first screen’s real poster resources. Main UI never opens until all requests settle. */
internal fun DramaAppActivity.preloadCatalogCovers(
    items: List<LocalDrama>,
    onProgress: (Int) -> Unit,
    onFinished: () -> Unit,
    onTimedOut: () -> Unit,
) {
        val urls = items.asSequence().map { it.coverImage.trim() }.filter { it.isNotBlank() }.distinct().take(12).toList()
        if (urls.isEmpty()) { onProgress(100); onFinished(); return }
        var remaining = urls.size
        var complete = false
        lateinit var timeout: Runnable
        fun requestSettled() {
            if (complete) return
            remaining -= 1
            val settled = urls.size - remaining
            onProgress(settled * 100 / urls.size)
            if (remaining == 0) {
                complete = true
                root.removeCallbacks(timeout)
                onFinished()
            }
        }
        timeout = Runnable {
            if (!complete) {
                complete = true
                onTimedOut()
            }
        }
        // A terminal error is an acceptable settled resource; a hung request is a startup failure, never false readiness.
        root.postDelayed(timeout, 12_000L)
        urls.forEach { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(applicationContext)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .listener(
                        onSuccess = { _, _ -> root.post(::requestSettled) },
                        onError = { _, _ -> root.post(::requestSettled) },
                    )
                    .build(),
            )
        }
    }

internal fun DramaAppActivity.computeStartupProgress(): Int {
        val catalogWeight = if (startupCatalogFinished) 30 else 0
        val posterWeight = startupPosterPreloadPercent * 35 / 100
        val rewardsWeight = if (startupRewardsFinished) 20 else 0
        val adWeight = if (startupAdFinished) 15 else 0
        return (catalogWeight + posterWeight + rewardsWeight + adWeight).coerceIn(0, 100)
    }

internal fun DramaAppActivity.completeStartupWhenReady() {
        // PSSDK catalogue and Rewards data are provider/network work, not launch prerequisites.
        // Requiring them here is valid for main's local content service but leaves formal builds
        // permanently at 35% when the provider's first catalogue callback is delayed.
        if (startupContentInitializationStarted || !startupAdFinished || !startupMinimumVisualFinished || !startupContentHandoffFinished || isFinishing || isDestroyed) return
        startupContentInitializationStarted = true
        updateStartupProgress(100, t("startup_ready"))
        if (restorePlayerAfterCatalog) {
            val drama = catalogItems.firstOrNull { it.id == restoredDramaId } ?: catalogItems.firstOrNull()
            if (drama != null) {
                restorePlayerAfterCatalog = false
                clearExternalAdReturnCheckpoint()
                restoringExternalAdReturn = false
                showPlayer(drama, restoredEpisodeIndex, restoredPositionSeconds)
                return
            }
        }
        renderActiveTab()
        showStartupCheckinWhenReady()
    }

private const val MAX_SHORT_DRAMA_RETRY_ATTEMPTS = 3
private const val MINIMUM_STARTUP_VISUAL_MS = 1_500L
private const val STARTUP_CONTENT_HANDOFF_GRACE_MS = 4_500L
private val SHORT_DRAMA_RETRY_DELAYS_MS = longArrayOf(3_000L, 6_000L, 12_000L)
private const val MAX_SHORT_DRAMA_DIAGNOSTIC_LENGTH = 1_000
private const val SHORT_DRAMA_DIAGNOSTIC_GRACE_MS = 8_000L
