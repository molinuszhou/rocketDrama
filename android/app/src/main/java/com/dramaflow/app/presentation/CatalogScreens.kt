package com.dramaflow.app.presentation

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.webkit.WebView
import android.webkit.WebViewClient
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

internal fun DramaAppActivity.createHomePage(): View = scrollPage { content ->
    val filtered = filteredCatalogItems()
    val recommended = recommendedCatalogItems()
    val primaryItems = if (selectedCatalogGenre == null) recommended else filtered
    content.addView(createPageTitle(t("home"), t("home_greeting")), matchParams(bottom = 12))
    content.addView(createSearchPrompt(), matchParams(bottom = 12))
    content.addView(createCategoryTabs(), matchParams(bottom = 14))
    if (catalogItems.isEmpty()) {
        content.addView(createCatalogLoadingSkeleton(), matchParams(bottom = 18))
        return@scrollPage
    }
    val featured = primaryItems.firstOrNull()
    featured?.let { content.addView(createHeroBanner(it), matchParams(bottom = 18)) }
    val shelf = primaryItems.filter { it.id != featured?.id }.take(SHELF_ITEM_LIMIT)
    if (shelf.isNotEmpty()) {
        content.addView(createSectionHeader(
            if (hasCatalogPreferenceSignals()) t("continue_watching") else t("popular_this_week"),
            t("recommended_for_you"),
        ), matchParams(bottom = 10))
        content.addView(createHorizontalDramaShelf(shelf), matchParams(bottom = 20))
    }
    content.addView(createSectionHeader(t("all_dramas"), t("discover_subtitle")), matchParams(bottom = 10))
    addDramaGrid(content, filtered)
}

/** Avoids an empty home page while PSSDK finishes the first remote catalogue request. */
internal fun DramaAppActivity.createCatalogLoadingSkeleton(): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(20), dp(16), dp(22))
    background = roundedDrawable(COLOR_CARD, 20)
    addView(ProgressBar(this@createCatalogLoadingSkeleton).apply {
        indeterminateTintList = ColorStateList.valueOf(COLOR_CORAL)
    }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
    addView(TextView(this@createCatalogLoadingSkeleton).apply {
        text = t("catalog_loading")
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT_PRIMARY)
        setPadding(0, dp(12), 0, dp(5))
    })
    addView(TextView(this@createCatalogLoadingSkeleton).apply {
        text = t("catalog_loading_hint")
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT_MUTED)
    })
}

internal fun DramaAppActivity.createDiscoverPage(): View = scrollPage { content ->
        val recommended = recommendedCatalogItems()
        content.addView(createPageTitle(t("discover"), t("discover_subtitle")), matchParams(bottom = 16))
        content.addView(createCategoryTabs(), matchParams(bottom = 16))
        if (catalogItems.isEmpty()) {
            content.addView(createCatalogLoadingSkeleton(), matchParams(bottom = 18))
            return@scrollPage
        }
        primaryFeaturedDrama(recommended)?.let { content.addView(createHeroBanner(it), matchParams(bottom = 22)) }
        content.addView(createSectionHeader(t("popular_this_week"), t("recommended_for_you")), matchParams(bottom = 12))
        if (recommended.isNotEmpty()) content.addView(createHorizontalDramaShelf(recommended.take(SHELF_ITEM_LIMIT)), matchParams(bottom = 22))
        catalogGenreGroups().forEach { (genre, dramas) ->
            content.addView(createSectionHeader("$genre ${t("discover")}", t("episodes_count", dramas.size)), matchParams(bottom = 12))
            content.addView(createHorizontalDramaShelf(dramas.take(SHELF_ITEM_LIMIT)), matchParams(bottom = 20))
        }
    }

internal fun DramaAppActivity.primaryFeaturedDrama(recommended: List<LocalDrama>): LocalDrama? = recommended.firstOrNull() ?: catalogItems.firstOrNull()


internal fun DramaAppActivity.createProfilePage(): View = scrollPage { content ->
        content.addView(createProfileHero(), matchParams(bottom = 16))
        content.addView(createSectionHeader(t("continue_watching"), t("home_subtitle")), matchParams(bottom = 12))
        val watchedItems = catalogItems.filter { playbackHistory.contains(resumePreferenceKey(it.id, "episode")) }.take(4)
        if (watchedItems.isEmpty()) content.addView(createInlineNotice(t("watch_history_empty")), matchParams(bottom = 10))
        else watchedItems.forEach { content.addView(createHistoryRow(it), matchParams(bottom = 10)) }
        content.addView(createSectionHeader(t("my_services"), t("profile_subtitle")), matchParams(top = 10, bottom = 10))
        content.addView(createSettingsRow(t("favorites"), t("continue_watching"), "›") { toast(t("favorites")) }, matchParams(bottom = 8))
        content.addView(createSettingsRow(t("reward_ledger"), t("ledger_note"), "›") { showRewardLedgerHint() }, matchParams(bottom = 8))
        addDebugAuditRows(content)
        content.addView(createSettingsRow(t("settings"), "${t("ad_settings")} · ${t("privacy_terms")}", "›") { showSettingsCenter() }, matchParams(bottom = 8))
        content.addView(createSettingsRow(t("language"), AppLanguage.label(this), "›") { showLanguageSettings() }, matchParams(bottom = 16))
    }

internal fun DramaAppActivity.createProfileHero(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(4))
        addView(TextView(this@createProfileHero).apply {
            text = "J"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = gradientDrawable(intArrayOf(COLOR_AQUA, COLOR_CORAL), 30)
        }, LinearLayout.LayoutParams(dp(62), dp(62)))
        addView(LinearLayout(this@createProfileHero).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, 0, 0)
            addView(TextView(this@createProfileHero).apply { text = t("profile"); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createProfileHero).apply { text = t("profile_subtitle"); textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

internal fun DramaAppActivity.scrollPage(build: (LinearLayout) -> Unit): View {
        // 常规页面为固定资产栏预留 126dp 高度；奖励页使用自己的首屏 hero，因此从顶部开始。
        val topInset = if (activeTab == MainTab.TASKS) 0 else 128
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(topInset), dp(16), dp(94))
        }
        build(content)
        return ScrollView(this).apply {
            isFillViewport = true
            background = pageCanvasDrawable()
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

internal fun DramaAppActivity.matchParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

internal fun DramaAppActivity.createPageTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@createPageTitle).apply {
            text = title
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        addView(TextView(this@createPageTitle).apply {
            text = subtitle
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        })
    }

internal fun DramaAppActivity.createBalanceMissionCard(): View = createInlineNotice(t("ledger_note"))

internal fun DramaAppActivity.createCategoryTabs(): View = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this@createCategoryTabs).apply { orientation = LinearLayout.HORIZONTAL }
        val allLabel = t("all_dramas")
        (listOf(allLabel) + catalogGenreGroups().map { it.first }).forEachIndexed { index, label ->
            val selected = if (label == allLabel) selectedCatalogGenre == null else label == selectedCatalogGenre
            row.addView(TextView(this@createCategoryTabs).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else COLOR_TEXT_MUTED)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundedDrawable(if (selected) COLOR_CORAL else COLOR_SURFACE, 16)
                isClickable = true
                setOnClickListener { selectedCatalogGenre = label.takeUnless { it == allLabel }; renderActiveTab() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = dp(8) })
        }
        addView(row)
    }

    /** 分类、推荐与排序均读取统一内容模型；未来 PSSDK 接通后仅替换 catalogItems 的数据来源。 */
internal fun DramaAppActivity.catalogGenreGroups(): List<Pair<String, List<LocalDrama>>> = catalogItems
        .flatMap { drama -> drama.genres.mapNotNull { genre -> genre.trim().takeIf(String::isNotBlank)?.let { it to drama } } }
        .groupBy({ it.first }, { it.second })
        .map { (genre, dramas) -> genre to dramas.distinctBy { it.id } }
        .sortedByDescending { it.second.size }
        .take(MAX_CATALOG_GENRES)

internal fun DramaAppActivity.filteredCatalogItems(): List<LocalDrama> {
        val genre = selectedCatalogGenre ?: return catalogItems
        return catalogItems.filter { drama -> drama.genres.any { it.equals(genre, ignoreCase = true) } }
    }

internal fun DramaAppActivity.hasCatalogPreferenceSignals(): Boolean = catalogItems.any { drama ->
        favorites.getBoolean("favorite_${drama.id}", false) || playbackHistory.contains(resumePreferenceKey(drama.id, "episode"))
    }

internal fun DramaAppActivity.recommendedCatalogItems(): List<LocalDrama> {
        val preferredGenres = catalogItems
            .filter { drama -> favorites.getBoolean("favorite_${drama.id}", false) || playbackHistory.contains(resumePreferenceKey(drama.id, "episode")) }
            .flatMap { it.genres }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()
        return catalogItems.sortedBy { drama ->
            when {
                favorites.getBoolean("favorite_${drama.id}", false) -> 0
                playbackHistory.contains(resumePreferenceKey(drama.id, "episode")) -> 1
                drama.genres.any { it.trim().lowercase(Locale.ROOT) in preferredGenres } -> 2
                else -> 3
            }
        }
    }

internal fun DramaAppActivity.createHeroBanner(drama: LocalDrama): View = FrameLayout(this).apply {
        minimumHeight = dp(282)
        clipToOutline = true
        background = roundedDrawable(COLOR_INK, 26)
        isClickable = true
        setOnClickListener { showPlayer(drama, 0) }
        addView(ImageView(this@createHeroBanner).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(drama.coverImage) { crossfade(true); placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(View(this@createHeroBanner).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xBD10131A.toInt(), 0x2410131A.toInt(), 0xDF10131A.toInt()))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val copy = LinearLayout(this@createHeroBanner).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(createPill(t("featured_this_week"), COLOR_CORAL))
        copy.addView(TextView(this@createHeroBanner).apply { text = drama.title.ifBlank { t("watch_now") }; textSize = 27f; maxLines = 2; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(12), 0, dp(4)) })
        copy.addView(TextView(this@createHeroBanner).apply { text = t("episodes_count", drama.total.coerceAtLeast(1)); textSize = 13f; setTextColor(0xE6FFFFFF.toInt()) })
        addView(copy, FrameLayout.LayoutParams(dp(235), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(18); bottomMargin = dp(18) })
        val play = LinearLayout(this@createHeroBanner).apply {
            gravity = Gravity.CENTER_VERTICAL; background = roundedDrawable(0xE81D1D22.toInt(), 24); setPadding(dp(4), dp(4), dp(12), dp(4))
            addView(ImageView(this@createHeroBanner).apply { setImageResource(R.drawable.ic_play_coral); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(TextView(this@createHeroBanner).apply { text = t("watch_now"); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(5), 0, 0, 0) })
        }
        addView(play, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(14); bottomMargin = dp(14) })
    }

internal fun DramaAppActivity.createSearchPrompt(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15), dp(12), dp(15), dp(12)); background = roundedDrawable(COLOR_CARD, 19); elevation = dp(2).toFloat(); isClickable = true
        setOnClickListener { showSearchPage() }
        addView(ImageView(this@createSearchPrompt).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(COLOR_TEXT_MUTED); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(23), dp(23)))
        addView(TextView(this@createSearchPrompt).apply { text = t("search_hint"); textSize = 14f; setTextColor(COLOR_TEXT_MUTED); setPadding(dp(10), 0, 0, 0) })
    }

internal fun DramaAppActivity.showSearchPage() {
        playerShown = false
        withdrawalPageShown = false
        gemBalanceAnchor = null; gemBalanceLabel = null; cashBalanceLabel = null
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(128), dp(16), dp(36)) }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ImageView(this@showSearchPage).apply { setImageResource(R.drawable.ic_arrow_back); imageTintList = ColorStateList.valueOf(COLOR_TEXT_PRIMARY); scaleType = ImageView.ScaleType.CENTER_INSIDE; contentDescription = t("back"); isClickable = true; setOnClickListener { renderActiveTab() } }, LinearLayout.LayoutParams(dp(40), dp(40)))
        titleRow.addView(TextView(this@showSearchPage).apply { text = t("search"); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); setPadding(dp(5), 0, 0, 0) })
        content.addView(titleRow, matchParams(bottom = 12))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply {
            hint = t("search_hint"); textSize = 15f; setSingleLine(true); setTextColor(COLOR_TEXT_PRIMARY); setHintTextColor(COLOR_TEXT_MUTED)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0); compoundDrawablePadding = dp(10)
            background = roundedDrawable(COLOR_CARD, 19); setPadding(dp(15), dp(7), dp(15), dp(7))
        }
        fun renderResults(query: String) {
            results.removeAllViews()
            val normalized = query.trim()
            val filtered = if (normalized.isBlank()) catalogItems else catalogItems.filter { drama ->
                listOf(drama.title, drama.desc).plus(drama.genres).any { it.contains(normalized, ignoreCase = true) }
            }
            if (filtered.isEmpty()) results.addView(createInlineNotice(t("search_empty")), matchParams(top = 16))
            else filtered.forEach { drama -> results.addView(createDiscoverDramaCard(drama), matchParams(top = 10)) }
            if (normalized.isNotBlank()) {
                val requestedQuery = normalized
                ShortDramaSdkManager.get(this).search(normalized, onSuccess = { cards -> runOnUiThread {
                    if (input.text.toString().trim() != requestedQuery) return@runOnUiThread
                    if (cards.isNotEmpty()) {
                        results.addView(createSectionHeader(t("sdk_search_results"), t("sdk_search_loaded", cards.size)), matchParams(top = 18, bottom = 4))
                        cards.forEach { card -> results.addView(createSdkSearchDramaCard(card), matchParams(top = 10)) }
                    }
                } }, onFailure = { message -> logStatus("PSSDK search unavailable: $message") })
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderResults(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        content.addView(results, matchParams(top = 6))
        renderResults("")
        root.removeAllViews()
        root.addView(ScrollView(this).apply { background = pageCanvasDrawable(); addView(content) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(createPersistentAssetHeader(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP))
    }

internal fun DramaAppActivity.createSdkSearchDramaCard(card: ShortDramaSdkManager.DramaCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(10), dp(12), dp(10)); background = roundedDrawable(COLOR_CARD, 18); isClickable = true
        setOnClickListener { showSdkDramaPlayer(card) }
        addView(ImageView(this@createSdkSearchDramaCard).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = posterFallbackDrawable(); load(card.coverUrl) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) } }, LinearLayout.LayoutParams(dp(92), dp(122)))
        addView(LinearLayout(this@createSdkSearchDramaCard).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), 0, dp(4))
            addView(TextView(this@createSdkSearchDramaCard).apply { text = card.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2 })
            addView(TextView(this@createSdkSearchDramaCard).apply { text = t("episodes_count", card.totalEpisodes.coerceAtLeast(1)); textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), 0, 0) })
            addView(TextView(this@createSdkSearchDramaCard).apply { text = t("watch_now"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT); setPadding(0, dp(18), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

internal fun DramaAppActivity.showSdkDramaPlayer(card: ShortDramaSdkManager.DramaCard) {
        val catalogId = sdkCatalogId(card.id)
        sdkDramaCardsByCatalogId[catalogId] = card
        showSdkDramaPlayer(sdkCatalogDrama(card), card, startIndex = 0)
    }

internal fun DramaAppActivity.createHorizontalDramaShelf(items: List<LocalDrama>): View = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this@createHorizontalDramaShelf).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { index, drama ->
            row.addView(createCompactDramaCard(drama), LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (index > 0) marginStart = dp(10) })
        }
        addView(row)
    }

internal fun DramaAppActivity.addDramaGrid(parent: LinearLayout, dramas: List<LocalDrama>) {
        dramas.chunked(2).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { index, drama ->
                row.addView(createDramaCard(drama), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) marginEnd = dp(8) else marginStart = dp(8)
                })
            }
            if (rowItems.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            parent.addView(row, matchParams(bottom = 16))
        }
    }

internal fun DramaAppActivity.createCompactDramaCard(drama: LocalDrama): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setOnClickListener { showPlayer(drama, 0) }
        val poster = FrameLayout(this@createCompactDramaCard).apply {
            clipToOutline = true
            background = roundedDrawable(COLOR_INK, 17)
            addView(ImageView(this@createCompactDramaCard).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = posterFallbackDrawable()
                load(drama.coverImage) { crossfade(true); placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(this@createCompactDramaCard).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xA90D1118.toInt(), Color.TRANSPARENT))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(createPill(t("episodes_count", drama.total.coerceAtLeast(1)), COLOR_CORAL), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(8); bottomMargin = dp(8) })
        }
        addView(poster, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(184)))
        addView(TextView(this@createCompactDramaCard).apply {
            text = drama.title.ifBlank { t("drama_fallback") }
            maxLines = 2
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(dp(2), dp(8), dp(2), 0)
        })
    }

internal fun DramaAppActivity.createDiscoverDramaCard(drama: LocalDrama): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(10), dp(10), dp(12), dp(10))
        background = roundedDrawable(COLOR_CARD, 18)
        isClickable = true
        setOnClickListener { showPlayer(drama, 0) }
        addView(ImageView(this@createDiscoverDramaCard).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = posterFallbackDrawable()
            load(drama.coverImage) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
        }, LinearLayout.LayoutParams(dp(92), dp(122)))
        addView(LinearLayout(this@createDiscoverDramaCard).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), 0, dp(4))
            addView(TextView(this@createDiscoverDramaCard).apply { text = drama.title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2 })
            addView(TextView(this@createDiscoverDramaCard).apply { text = "${t("episodes_count", drama.total.coerceAtLeast(1))} · ${t("portrait_drama")}"; textSize = 13f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(8), 0, 0) })
            addView(TextView(this@createDiscoverDramaCard).apply { text = "${t("continue_watching")} ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_ACCENT); setPadding(0, dp(18), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

internal fun DramaAppActivity.createWalletCard(): View = createRewardsWalletSummary()

internal fun DramaAppActivity.createHistoryRow(drama: LocalDrama): View = LinearLayout(this).apply {
        val resumeEpisode = savedResumeEpisodeIndex(drama)
        val resumeSecond = savedResumePositionSeconds(drama)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(12), dp(10))
        background = roundedDrawable(COLOR_CARD, 16)
        isClickable = true
        setOnClickListener { showPlayer(drama, resumeEpisode, resumeSecond) }
        addView(ImageView(this@createHistoryRow).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = posterFallbackDrawable(); load(drama.coverImage) { placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) } }, LinearLayout.LayoutParams(dp(48), dp(64)))
        addView(TextView(this@createHistoryRow).apply { text = "${drama.title}\n${t("watch_progress", resumeEpisode + 1, formatPlaybackTime(resumeSecond))}"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY); maxLines = 2; setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@createHistoryRow).apply { text = "${t("continue_label")} ›"; textSize = 13f; setTextColor(COLOR_ACCENT) })
    }

internal fun DramaAppActivity.createSettingsRow(title: String, subtitle: String, trailing: String, action: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedDrawable(COLOR_CARD, 16)
        isClickable = true
        setOnClickListener { action() }
        addView(LinearLayout(this@createSettingsRow).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@createSettingsRow).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(COLOR_TEXT_PRIMARY) })
            addView(TextView(this@createSettingsRow).apply { text = subtitle; textSize = 12f; setTextColor(COLOR_TEXT_MUTED); setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing == "›") {
            addView(ImageView(this@createSettingsRow).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(COLOR_TEXT_MUTED); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(22), dp(22)))
        } else {
            addView(TextView(this@createSettingsRow).apply { text = trailing; textSize = 14f; setTextColor(COLOR_TEXT_MUTED) })
        }
    }

/** A release-facing settings surface: privacy controls, version and anonymous support identifier. */
internal fun DramaAppActivity.showSettingsCenter() {
    val details = "${t("app_version")} ${BuildConfig.VERSION_NAME}\n${t("user_uid")}: ${anonymousInstallationId()}"
    showBrandedChoiceDialog(
        title = t("settings"),
        message = details,
        primaryLabel = t("ad_settings"),
        onPrimary = { showAdPrivacySettings() },
        secondaryLabel = t("privacy_terms"),
        onSecondary = { showPrivacyPolicyPage() },
    )
}

/** Shows the remotely maintained privacy policy without leaving the app. */
internal fun DramaAppActivity.showPrivacyPolicyPage() {
    val dialog = Dialog(this)
    val page = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
    }
    val toolbar = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(10), dp(10))
        setBackgroundColor(COLOR_CARD)
    }
    toolbar.addView(TextView(this).apply {
        text = t("privacy_terms")
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT_PRIMARY)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    toolbar.addView(TextView(this).apply {
        text = "×"
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT_PRIMARY)
        setPadding(dp(12), 0, dp(8), 0)
        isClickable = true
        setOnClickListener { dialog.dismiss() }
    }, LinearLayout.LayoutParams(dp(52), dp(48)))
    page.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
    page.addView(WebView(this).apply {
        webViewClient = WebViewClient()
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        loadUrl("https://xuanfeather.com/joyedrama/privacy-policy.html")
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    dialog.setContentView(page)
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}


internal fun DramaAppActivity.createDramaCard(drama: LocalDrama): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(COLOR_CARD, 18)
        clipToOutline = true
        setPadding(dp(7), dp(7), dp(7), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { showPlayer(drama, 0) }
        val poster = FrameLayout(this@createDramaCard).apply {
            clipToOutline = true
            background = roundedDrawable(COLOR_INK, 14)
            addView(ImageView(this@createDramaCard).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = posterFallbackDrawable()
                contentDescription = drama.title
                load(drama.coverImage) { crossfade(true); placeholder(posterFallbackDrawable()); error(posterFallbackDrawable()) }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(this@createDramaCard).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x8F111722.toInt(), Color.TRANSPARENT))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(createPill(t("episodes_count", drama.total.coerceAtLeast(1)), COLOR_CORAL), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(7); bottomMargin = dp(7) })
        }
        addView(poster, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(195)))
        addView(TextView(this@createDramaCard).apply {
            text = drama.title.ifBlank { t("drama_fallback") }
            textSize = 15f
            maxLines = 2
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            setPadding(dp(3), dp(9), dp(3), 0)
        })
    }

internal fun DramaAppActivity.createPill(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = roundedDrawable(color, 12)
    }
