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
        startupPosterPreloadPercent = 0
        catalogRetryAttempt = 0
        catalogRetryScheduled = false
        renderStartupLoading()
        updateStartupProgress(0, t("startup_preparing"))
        // Rewards and the PSSDK catalog continue in the background. A slow content SDK must never
        // hold the entire application on its launch screen; welcome/check-in is shown as soon as
        // the rewards snapshot arrives after the shell is visible.
        if (rewardsApi.isConfigured) refreshRewardsSnapshot()
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
            startupCatalogFailure = when (initialization) {
                is ShortDramaSdkManager.Initialization.InvalidConfiguration -> initialization.message
                is ShortDramaSdkManager.Initialization.Failed -> initialization.message
                ShortDramaSdkManager.Initialization.Disabled -> "Short-drama SDK is disabled in this build"
                ShortDramaSdkManager.Initialization.Ready -> error("unreachable")
            }
            completeStartupWhenReady()
            if (startupContentInitializationStarted) handleBackgroundCatalogFailure(startupCatalogFailure.orEmpty())
            return@runOnUiThread
        }
        shortDramaSdk.loadDiscovery(
            onSuccess = { discovery -> runOnUiThread {
                val cards = (discovery.popular + discovery.latest)
                    .distinctBy { it.id }
                    .filter { it.title.isNotBlank() }
                startupCatalogFinished = true
                if (cards.isEmpty()) {
                    startupCatalogFailure = t("catalog_empty")
                    startupPosterPreloadFinished = true
                    completeStartupWhenReady()
                    if (startupContentInitializationStarted) handleBackgroundCatalogFailure(startupCatalogFailure.orEmpty())
                    return@runOnUiThread
                }
                catalogRetryAttempt = 0
                catalogRetryScheduled = false
                sdkDramaCardsByCatalogId.clear()
                cards.forEach { card -> sdkDramaCardsByCatalogId[sdkCatalogId(card.id)] = card }
                catalogItems = cards.map(::sdkCatalogDrama)
                updateStartupProgress(30, t("startup_content_ready"))
                // The catalogue is usable immediately; poster preloading is purely an optional
                // visual optimization and must not delay first interaction.
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
                        completeStartupWhenReady()
                    },
                    onTimedOut = {
                        startupPosterPreloadFinished = true
                        // Coil will continue to resolve any late posters lazily in their cards.
                        // A poster cache miss is not a content failure.
                        completeStartupWhenReady()
                    },
                )
            } },
            onFailure = { message -> runOnUiThread {
                startupCatalogFinished = true
                startupPosterPreloadFinished = true
                startupCatalogFailure = t("catalog_load_failed", message)
                completeStartupWhenReady()
                if (startupContentInitializationStarted) handleBackgroundCatalogFailure(startupCatalogFailure.orEmpty())
            } },
        )
    } }
}

/** Keeps the rest of the app usable when the provider catalogue is temporarily unavailable. */
internal fun DramaAppActivity.handleBackgroundCatalogFailure(message: String) {
    if (!isFinishing && !isDestroyed && catalogItems.isEmpty()) renderCatalogUnavailableShell(message)
    scheduleCatalogRetry()
}

internal fun DramaAppActivity.scheduleCatalogRetry(immediate: Boolean = false) {
    if (catalogRetryScheduled || isFinishing || isDestroyed || catalogItems.isNotEmpty()) return
    catalogRetryScheduled = true
    val retryDelay = if (immediate) 0L else (2_000L shl catalogRetryAttempt.coerceAtMost(3))
    root.postDelayed({
        catalogRetryScheduled = false
        if (!isFinishing && !isDestroyed && catalogItems.isEmpty()) {
            catalogRetryAttempt += 1
            preloadShortDramaSdkCatalog()
        }
    }, retryDelay)
}

    /** Preloads first-screen poster resources after the app shell is already interactive. */
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
        // A terminal error is acceptable: cards fall back to their ordinary image placeholders.
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
        // Only the optional splash owns the launch-screen deadline. PSSDK discovery, poster
        // caching and rewards bootstrap all complete after the regular application shell appears.
        if (startupContentInitializationStarted || !startupAdFinished || isFinishing || isDestroyed) return
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
        // If the rewards response won the startup race this shows immediately; otherwise the
        // response callback retries it. The short posted retry also handles the first layout pass
        // so welcome/check-in is never attached to the outgoing loading view.
        showStartupCheckinWhenReady()
        root.postDelayed({ showStartupCheckinWhenReady() }, 350L)
    }
