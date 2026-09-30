package com.dramaflow.app.content

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bytedance.sdk.shortplay.api.PSSDK
import com.bytedance.sdk.shortplay.api.ShortPlay
import com.bytedance.sdk.shortplay.api.ShortPlayFragment
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.i18n.AppLanguage
import java.util.UUID

/**
 * 合作方短剧 SDK 的可选内容入口。
 *
 * Debug 可保持 [BuildConfig.SHORT_DRAMA_SDK_ENABLED] 为 false，目录和播放仍由本地 Content
 * API + Media3 提供。正式 Release 强制使用该 SDK 的热门、最新、分类和播放页；UI 只消费
 * [DramaCard] 的统一内容模型，避免向用户暴露内容来源或 SDK 名称。
 */
class ShortDramaSdkManager private constructor(private val appContext: Context) {
    sealed interface Initialization {
        data object Disabled : Initialization
        data object Ready : Initialization
        data class InvalidConfiguration(val message: String) : Initialization
        data class Failed(val message: String) : Initialization
    }

    data class DramaCard(
        val id: Long,
        val title: String,
        val totalEpisodes: Int,
        val summary: String,
        val coverUrl: String,
        val language: String,
        val genres: List<String>,
        internal val nativeShortPlay: ShortPlay,
    )

    data class Category(
        val id: Long,
        val name: String,
        val contentCount: Int,
    )

    data class Discovery(
        val popular: List<DramaCard>,
        val latest: List<DramaCard>,
        val categories: List<Category>,
        val hasMore: Boolean = false,
    )

    data class PagedCards(
        val cards: List<DramaCard>,
        val hasMore: Boolean,
    )

    /** PSSDK's server-side availability check: SUPPORT, NOT_SUPPORT or NETWORK_ERROR. */
    data class PlaybackServiceStatus(
        val checked: Boolean,
        val supported: Boolean,
        val reason: String,
    )

    @Volatile
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initializationLock = Any()
    private var initializationAttempt = 0
    private var activeInitializationAttempt = 0
    private val pendingInitializationCallbacks = mutableListOf<(Initialization) -> Unit>()
    @Volatile
    private var playbackServiceStatus = PlaybackServiceStatus(
        checked = false,
        supported = false,
        reason = "PSSDK playback service has not been checked",
    )

    /**
     * 不在 SDK 开关关闭时做任何网络或播放器初始化，确保本地视频测试完全独立于外部短剧服务。
     */
    fun initializeIfEnabled(onResult: (Initialization) -> Unit) {
        if (!BuildConfig.SHORT_DRAMA_SDK_ENABLED) {
            onResult(Initialization.Disabled)
            return
        }
        if (initialized || PSSDK.hasInitialized()) {
            initialized = true
            onResult(Initialization.Ready)
            return
        }

        val appId = BuildConfig.SHORT_DRAMA_APP_ID.trim()
        val vodAppId = BuildConfig.SHORT_DRAMA_VOD_APP_ID.trim()
        val licenseAsset = normalizeAssetPath(BuildConfig.SHORT_DRAMA_LICENSE_ASSET)
        val securityKey = BuildConfig.SHORT_DRAMA_SECURITY_KEY.trim()
        when {
            appId.isBlank() || vodAppId.isBlank() || securityKey.isBlank() -> {
                onResult(Initialization.InvalidConfiguration("短剧 SDK 的 App ID、VOD App ID 或安全密钥未配置"))
                return
            }
            licenseAsset.isBlank() || !assetExists(licenseAsset) -> {
                onResult(Initialization.InvalidConfiguration("短剧 SDK 的 License 文件未配置或不存在"))
                return
            }
        }

        val attempt = synchronized(initializationLock) {
            // Startup, a manual Retry and the automatic recovery path may overlap. PSSDK.init is
            // process-wide; concurrent calls can strand the first callback and produce the
            // observed "first attempt times out, Retry succeeds" pattern.
            pendingInitializationCallbacks += onResult
            if (activeInitializationAttempt != 0) {
                null
            } else {
                ++initializationAttempt
                initializationAttempt.also { activeInitializationAttempt = it }
            }
        } ?: return

        val config = PSSDK.Config.Builder()
            .appId(appId)
            .vodAppId(vodAppId)
            .licenseAssertPath(licenseAsset)
            .securityKey(securityKey)
            .userId(installationId())
            // PSSDK's official recommendation for weak networks is 15 seconds. The prior
            // client-specific 10-second cutoff was shorter and caused false first-launch errors.
            .requestTimeout(PSSDK_REQUEST_TIMEOUT_MS)
            .autoInitVideoPlayer(true)
            .debug(BuildConfig.DEBUG)
            .build()
        var terminalResultDelivered = false
        lateinit var initializationTimeout: Runnable
        fun deliverFailure(result: Initialization) {
            if (terminalResultDelivered) return
            terminalResultDelivered = true
            mainHandler.removeCallbacks(initializationTimeout)
            val callbacks = synchronized(initializationLock) {
                if (activeInitializationAttempt != attempt) {
                    emptyList()
                } else {
                    activeInitializationAttempt = 0
                    pendingInitializationCallbacks.toList().also { pendingInitializationCallbacks.clear() }
                }
            }
            callbacks.forEach { callback -> callback(result) }
        }
        fun deliverReady() {
            initialized = true
            terminalResultDelivered = true
            mainHandler.removeCallbacks(initializationTimeout)
            val callbacks = synchronized(initializationLock) {
                activeInitializationAttempt = 0
                pendingInitializationCallbacks.toList().also { pendingInitializationCallbacks.clear() }
            }
            callbacks.forEach { callback -> callback(Initialization.Ready) }
        }
        initializationTimeout = Runnable {
            if (terminalResultDelivered) return@Runnable
            Log.w(TAG, "短剧 SDK 初始化超时：${SDK_INITIALIZATION_TIMEOUT_MS}ms")
            deliverFailure(Initialization.Failed("短剧 SDK 初始化超时，请稍后重试"))
        }
        mainHandler.postDelayed(initializationTimeout, SDK_INITIALIZATION_TIMEOUT_MS)
        // SDK 内容语言与界面语言独立：UI 使用六语词典；SDK 请求使用内容方支持的标准语言码。
        runCatching {
            PSSDK.setContentLanguage(contentLanguageFor(AppLanguage.tag(appContext)))
            PSSDK.init(appContext, config, object : PSSDK.PSSDKInitListener {
                override fun onInitFinished(success: Boolean, error: PSSDK.ErrorInfo?) {
                    initialized = success
                    if (success) {
                        PSSDK.setEligibleAudience(true)
                        checkPlaybackService { status ->
                            Log.i(TAG, "短剧 SDK 播放服务检测：${status.reason}")
                            // A provider service outage must not prevent catalogue browsing. Playback
                            // is checked again immediately before a provider fragment is opened.
                            deliverReady()
                        }
                    } else {
                        deliverFailure(Initialization.Failed("短剧 SDK 初始化失败：${error?.code ?: -1} ${error?.msg.orEmpty()}"))
                    }
                }
            })
        }.onFailure {
            Log.w(TAG, "短剧 SDK 初始化调用异常", it)
            deliverFailure(Initialization.Failed("短剧 SDK 初始化调用失败：${it.message.orEmpty()}"))
        }
    }

    /** 读取 SDK 的热门、最新和分类数据，供首页推荐、发现页分类和后续搜索模块复用。 */
    fun loadDiscovery(
        pageSize: Int = DEFAULT_PAGE_SIZE,
        onSuccess: (Discovery) -> Unit,
        onFailure: (String) -> Unit,
    ) = loadDiscoveryPage(FIRST_PAGE, pageSize, onSuccess, onFailure)

    /** Loads a subsequent provider page; the host app owns merging and scroll-trigger timing. */
    fun loadDiscoveryPage(
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        onSuccess: (Discovery) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!initialized && !PSSDK.hasInitialized()) {
            onFailure("短剧 SDK 尚未初始化")
            return
        }

        var popular: List<DramaCard>? = null
        var latest: List<DramaCard>? = null
        var popularHasMore = false
        var latestHasMore = false
        var categories: List<Category>? = null
        var popularFailure: String? = null
        var latestFailure: String? = null
        var delivered = false
        lateinit var discoveryTimeout: Runnable

        fun deliverFailure(message: String) {
            if (delivered) return
            delivered = true
            mainHandler.removeCallbacks(discoveryTimeout)
            onFailure(message)
        }

        fun tryDeliverContent() {
            if (delivered) return
            // Categories are not a first-screen prerequisite. Both feeds are awaited so the
            // app can reliably honour the provider's hasMore signal and continue paging at the end.
            val popularDone = popular != null || popularFailure != null
            val latestDone = latest != null || latestFailure != null
            if (popularDone && latestDone) {
                if (popular.orEmpty().isNotEmpty() || latest.orEmpty().isNotEmpty()) {
                    delivered = true
                    mainHandler.removeCallbacks(discoveryTimeout)
                    onSuccess(Discovery(popular.orEmpty(), latest.orEmpty(), categories.orEmpty(), popularHasMore || latestHasMore))
                } else {
                    deliverFailure("短剧内容读取失败：${popularFailure.orEmpty()}; ${latestFailure.orEmpty()}")
                }
            }
        }

        discoveryTimeout = Runnable {
            if (delivered) return@Runnable
            Log.w(TAG, "短剧目录请求超时：${DISCOVERY_TIMEOUT_MS}ms")
            deliverFailure("短剧目录加载超时，请检查网络后重试")
        }
        mainHandler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS)

        PSSDK.requestPopularDrama(page.coerceAtLeast(FIRST_PAGE), pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) {
                popular = result?.dataList.orEmpty().map(::toCard)
                popularHasMore = result?.hasMore == true
                tryDeliverContent()
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                popularFailure = "热门 ${error?.code ?: -1} ${error?.msg.orEmpty()}"
                tryDeliverContent()
            }
        })

        PSSDK.requestNewDrama(page.coerceAtLeast(FIRST_PAGE), pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) {
                latest = result?.dataList.orEmpty().map(::toCard)
                latestHasMore = result?.hasMore == true
                tryDeliverContent()
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                latestFailure = "最新 ${error?.code ?: -1} ${error?.msg.orEmpty()}"
                tryDeliverContent()
            }
        })

        // Categories are stable across feed pages; only request them with the first page.
        if (page == FIRST_PAGE) PSSDK.requestCategoryList("", object : PSSDK.CategoryListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay.ShortPlayCategory>?) {
                categories = result?.dataList.orEmpty().map { category ->
                    Category(category.id, category.name.orEmpty(), category.count)
                }.filter { it.name.isNotBlank() }
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                Log.w(TAG, "短剧分类读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
            }
        })
    }

    /** Reads provider category metadata separately; feed delivery intentionally never waits on this optional call. */
    fun loadCategories(onSuccess: (List<Category>) -> Unit, onFailure: (String) -> Unit) {
        if (!initialized && !PSSDK.hasInitialized()) { onFailure("Short-drama SDK is not initialized"); return }
        PSSDK.requestCategoryList("", object : PSSDK.CategoryListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay.ShortPlayCategory>?) =
                onSuccess(result?.dataList.orEmpty().map { Category(it.id, it.name.orEmpty(), it.count) }.filter { it.name.isNotBlank() })
            override fun onFail(error: PSSDK.ErrorInfo?) = onFailure("Short-drama category list failed: ${error?.code ?: -1} ${error?.msg.orEmpty()}")
        })
    }

    /** Provider category paging is kept distinct from the all-feed cursor. */
    fun loadCategoryPage(
        categoryId: Long,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        onSuccess: (PagedCards) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!initialized && !PSSDK.hasInitialized()) { onFailure("Short-drama SDK is not initialized"); return }
        PSSDK.requestFeedListByCategoryIds(listOf(categoryId), emptyList(), page.coerceAtLeast(FIRST_PAGE), pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) =
                onSuccess(PagedCards(result?.dataList.orEmpty().map(::toCard), result?.hasMore == true))
            override fun onFail(error: PSSDK.ErrorInfo?) = onFailure("Short-drama category content failed: ${error?.code ?: -1} ${error?.msg.orEmpty()}")
        })
    }

    /** Backward-compatible first-page category helper. */
    fun loadCategory(categoryId: Long, pageSize: Int = DEFAULT_PAGE_SIZE, onSuccess: (List<DramaCard>) -> Unit, onFailure: (String) -> Unit) =
        loadCategoryPage(categoryId, FIRST_PAGE, pageSize, { onSuccess(it.cards) }, onFailure)

    /** Paged provider search adapter. The UI owns dedupe and bottom-scroll timing. */
    fun searchPage(
        keyword: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        onSuccess: (PagedCards) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!initialized && !PSSDK.hasInitialized()) { onFailure("Short-drama SDK is not initialized"); return }
        PSSDK.searchDrama(keyword.trim(), false, page.coerceAtLeast(FIRST_PAGE), pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) =
                onSuccess(PagedCards(result?.dataList.orEmpty().map(::toCard), result?.hasMore == true))
            override fun onFail(error: PSSDK.ErrorInfo?) = onFailure("Short-drama search failed: ${error?.code ?: -1} ${error?.msg.orEmpty()}")
        })
    }

    /** Backward-compatible first-page search helper. */
    fun search(keyword: String, pageSize: Int = DEFAULT_PAGE_SIZE, onSuccess: (List<DramaCard>) -> Unit, onFailure: (String) -> Unit) =
        searchPage(keyword, FIRST_PAGE, pageSize, { onSuccess(it.cards) }, onFailure)

    fun playbackService(): PlaybackServiceStatus = playbackServiceStatus

    /** Verifies playback availability before opening a provider-owned player. */
    fun checkPlaybackService(onResult: (PlaybackServiceStatus) -> Unit) {
        if (!initialized && !PSSDK.hasInitialized()) {
            onResult(PlaybackServiceStatus(false, false, "PSSDK is not initialized"))
            return
        }
        PSSDK.checkPlayStatus(object : PSSDK.ServiceCheckResultListener {
            override fun onCheckResult(result: PSSDK.ServiceAvailableResult?) {
                val status = when (result) {
                    PSSDK.ServiceAvailableResult.SUPPORT -> PlaybackServiceStatus(true, true, "SUPPORT")
                    PSSDK.ServiceAvailableResult.NOT_SUPPORT -> PlaybackServiceStatus(true, false, "NOT_SUPPORT")
                    PSSDK.ServiceAvailableResult.NETWORK_ERROR -> PlaybackServiceStatus(true, false, "NETWORK_ERROR")
                    null -> PlaybackServiceStatus(true, false, "UNKNOWN")
                }
                playbackServiceStatus = status
                onResult(status)
            }
        })
    }

    /** Creates the provider player only for a production SDK card; the caller owns its Fragment transaction. */
    fun createDetailFragment(
        card: DramaCard,
        startEpisodeIndex: Int,
        startPositionSeconds: Int,
        onClose: () -> Unit,
        needsEpisodeUnlock: (episodeIndex: Int) -> Boolean,
        requestEpisodeUnlock: (episodeIndex: Int, listener: PSSDK.ShortPlayBlockResultListener?) -> Unit,
        onEpisodeStarted: (episodeIndex: Int, episode: com.bytedance.sdk.shortplay.api.EpisodeData?) -> Unit,
        onEpisodeCompleted: (episodeIndex: Int) -> Unit,
        onPlaybackProgress: (episodeIndex: Int, progress: Int, duration: Int) -> Unit,
        onOverScroll: (direction: Int) -> Unit,
        onVideoTap: () -> Unit,
        onPlaybackFailure: (String) -> Boolean,
        onImmersiveChanged: (Boolean) -> Unit,
        onVideoInfoFetched: (episodeIndex: Int, summary: String) -> Unit,
    ): ShortPlayFragment {
        return PSSDK.createDetailFragment(
            card.nativeShortPlay,
            PSSDK.DetailPageConfig.Builder()
                .hideLeftTopCloseAndTitle(true, object : PSSDK.ShortPlayDetailPageCloseListener {
                    override fun onCloseClicked(): Boolean { onClose(); return true }
                })
                .startPlayIndex(startEpisodeIndex.coerceAtLeast(0))
                .startPlayAtTimeSeconds(startPositionSeconds.coerceAtLeast(0))
                // The SDK preloads and advances naturally. Completion callbacks remain wired
                // to JoyeDrama's reward ledger but must not race a second host-side transition.
                .enableAutoPlayNext(true)
                // PSSDK signals idle immersive-mode changes through the listener below; the
                // host mirrors them into its system-bar and control-chrome policy.
                .enableImmersiveMode(2_500L)
                // PSSDK owns the media view; intercepting a direct video tap lets the host map
                // it to the same public pause/play action as the local player without an overlay.
                .setVideoClickInterceptor(object : PSSDK.IClickInterceptor {
                    override fun onInterceptClick(): Boolean {
                        onVideoTap()
                        return true
                    }
                })
                .displayBottomExtraView(false)
                .build(),
            object : PSSDK.ShortPlayDetailPageListener {
                override fun onOverScroll(direction: Int) = onOverScroll(direction)
                override fun onProgressChange(shortPlay: ShortPlay?, episodeIndex: Int, progress: Int, duration: Int) =
                    onPlaybackProgress(episodeIndex, progress, duration)
                override fun onPlayFailed(error: PSSDK.ErrorInfo?): Boolean =
                    onPlaybackFailure("${error?.code ?: -1} ${error?.msg.orEmpty()}")
                override fun onShortPlayPlayed(shortPlay: ShortPlay?, episodeIndex: Int, episode: com.bytedance.sdk.shortplay.api.EpisodeData?) {
                    onEpisodeStarted(episodeIndex, episode)
                }
                override fun onItemSelected(position: Int, itemType: PSSDK.ShortPlayDetailPageListener.ItemType?, episodeIndex: Int) = Unit
                override fun onVideoPlayStateChanged(shortPlay: ShortPlay?, episodeIndex: Int, state: Int) = Unit
                override fun onVideoPlayCompleted(shortPlay: ShortPlay?, episodeIndex: Int) {
                    onEpisodeCompleted(episodeIndex)
                }
                override fun onEnterImmersiveMode() = onImmersiveChanged(true)
                override fun onExitImmersiveMode() = onImmersiveChanged(false)
                override fun isNeedBlock(shortPlay: ShortPlay?, episodeIndex: Int): Boolean = needsEpisodeUnlock(episodeIndex)
                override fun showAdIfNeed(shortPlay: ShortPlay?, episodeIndex: Int, listener: PSSDK.ShortPlayBlockResultListener?) {
                    requestEpisodeUnlock(episodeIndex, listener)
                }
                override fun onVideoInfoFetched(shortPlay: ShortPlay?, episodeIndex: Int, info: PSSDK.VideoPlayInfo?) {
                    onVideoInfoFetched(episodeIndex, info?.toString().orEmpty())
                }
                override fun onObtainPlayerControlViews(): MutableList<android.view.View> = mutableListOf()
            },
        ) ?: error("PSSDK did not create a drama detail page")
    }

    private fun toCard(shortPlay: ShortPlay): DramaCard {
        val genres = buildList {
            shortPlay.categories.orEmpty().mapNotNullTo(this) { it.name?.trim()?.takeIf(String::isNotBlank) }
            shortPlay.tags.orEmpty().mapNotNullTo(this) { it.name?.trim()?.takeIf(String::isNotBlank) }
        }.distinct()
        return DramaCard(
            id = shortPlay.id,
            title = shortPlay.title.orEmpty(),
            totalEpisodes = shortPlay.total.coerceAtLeast(0),
            summary = shortPlay.desc.orEmpty(),
            coverUrl = shortPlay.coverImage.orEmpty(),
            language = shortPlay.language.orEmpty(),
            genres = genres,
            nativeShortPlay = shortPlay,
        )
    }

    /** 用户切换应用语言后，外部目录刷新前调用；不改变本地视频内容的标题或字幕。 */
    fun setContentLanguageForUi(uiLanguage: String) {
        if (BuildConfig.SHORT_DRAMA_SDK_ENABLED && (initialized || PSSDK.hasInitialized())) {
            PSSDK.setContentLanguage(contentLanguageFor(uiLanguage))
        }
    }

    private fun contentLanguageFor(uiLanguage: String): String = when (uiLanguage) {
        "pt-BR" -> "pt"
        "zh-TW" -> "zh_hant"
        "zh-CN" -> "zh_hans"
        else -> uiLanguage.substringBefore('-')
    }

    private fun normalizeAssetPath(rawPath: String): String = rawPath.trim()
        .removePrefix("assets:///")
        .removePrefix("assets://")
        .trimStart('/')

    private fun assetExists(path: String): Boolean = runCatching {
        appContext.assets.open(path).use { }
        true
    }.getOrElse { false }

    private fun installationId(): String {
        val prefs = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return prefs.getString(INSTALLATION_ID_KEY, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(INSTALLATION_ID_KEY, it).apply() }
    }

    companion object {
        private const val TAG = "ShortDramaSdkManager"
        private const val FIRST_PAGE = 1
        private const val DEFAULT_PAGE_SIZE = 20
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val PSSDK_REQUEST_TIMEOUT_MS = 15_000L
        private const val SDK_INITIALIZATION_TIMEOUT_MS = 18_000L
        private const val PREFERENCES = "joyedrama.short_drama_sdk"
        private const val INSTALLATION_ID_KEY = "installation_id"

        @Volatile
        private var instance: ShortDramaSdkManager? = null

        fun get(context: Context): ShortDramaSdkManager = instance ?: synchronized(this) {
            instance ?: ShortDramaSdkManager(context.applicationContext).also { instance = it }
        }
    }
}
