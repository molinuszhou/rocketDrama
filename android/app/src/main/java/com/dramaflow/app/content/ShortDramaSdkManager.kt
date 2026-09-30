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
    )

    @Volatile
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

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

        val config = PSSDK.Config.Builder()
            .appId(appId)
            .vodAppId(vodAppId)
            .licenseAssertPath(licenseAsset)
            .securityKey(securityKey)
            .userId(installationId())
            .requestTimeout(10_000L)
            .autoInitVideoPlayer(true)
            .debug(BuildConfig.DEBUG)
            .build()
        var resultDelivered = false
        lateinit var initializationTimeout: Runnable
        fun deliver(result: Initialization) {
            if (resultDelivered) return
            resultDelivered = true
            mainHandler.removeCallbacks(initializationTimeout)
            onResult(result)
        }
        initializationTimeout = Runnable {
            if (resultDelivered) return@Runnable
            // PSSDK can complete its first remote bootstrap after the request timeout supplied to
            // the SDK. This is only diagnostic: do not consume the callback or force the user to
            // tap retry, because the original initialization may still become ready moments later.
            Log.w(TAG, "短剧 SDK 初始化较慢：已等待 ${SDK_INITIALIZATION_TIMEOUT_MS}ms，继续等待 SDK 回调")
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
                        PSSDK.checkPlayStatus(object : PSSDK.ServiceCheckResultListener {
                            override fun onCheckResult(result: PSSDK.ServiceAvailableResult?) {
                                Log.i(TAG, "短剧 SDK 播放服务检测：${result?.name ?: "UNKNOWN"}")
                            }
                        })
                        deliver(Initialization.Ready)
                    } else {
                        deliver(Initialization.Failed("短剧 SDK 初始化失败：${error?.code ?: -1} ${error?.msg.orEmpty()}"))
                    }
                }
            })
        }.onFailure {
            Log.w(TAG, "短剧 SDK 初始化调用异常", it)
            deliver(Initialization.Failed("短剧 SDK 初始化调用失败：${it.message.orEmpty()}"))
        }
    }

    /** 读取 SDK 的热门、最新和分类数据，供首页推荐、发现页分类和后续搜索模块复用。 */
    fun loadDiscovery(
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
        var categories: List<Category>? = null
        var delivered = false
        lateinit var discoveryTimeout: Runnable

        fun deliverFailure(message: String) {
            if (delivered) return
            delivered = true
            mainHandler.removeCallbacks(discoveryTimeout)
            onFailure(message)
        }

        fun tryDeliver() {
            if (!delivered && popular != null && latest != null && categories != null) {
                delivered = true
                mainHandler.removeCallbacks(discoveryTimeout)
                onSuccess(Discovery(popular.orEmpty(), latest.orEmpty(), categories.orEmpty()))
            }
        }

        discoveryTimeout = Runnable {
            if (delivered) return@Runnable
            Log.w(TAG, "短剧目录请求超时：${DISCOVERY_TIMEOUT_MS}ms")
            deliverFailure("短剧目录加载超时，请检查网络后重试")
        }
        mainHandler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS)

        PSSDK.requestPopularDrama(FIRST_PAGE, pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) {
                popular = result?.dataList.orEmpty().map(::toCard)
                tryDeliver()
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                deliverFailure("短剧热门内容读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
            }
        })

        PSSDK.requestNewDrama(FIRST_PAGE, pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) {
                latest = result?.dataList.orEmpty().map(::toCard)
                tryDeliver()
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                deliverFailure("短剧最新内容读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
            }
        })

        // 空字符串沿用历史接入的默认语言策略；SDK 本身将依据 setContentLanguage 的设置返回内容。
        PSSDK.requestCategoryList("", object : PSSDK.CategoryListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay.ShortPlayCategory>?) {
                categories = result?.dataList.orEmpty().map { category ->
                    Category(category.id, category.name.orEmpty(), category.count)
                }.filter { it.name.isNotBlank() }
                tryDeliver()
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                deliverFailure("短剧分类读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
            }
        })
    }

    /** 根据 SDK 分类 ID 读取该分类下的内容，供点击首页/发现页分类标签后调用。 */
    fun loadCategory(
        categoryId: Long,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        onSuccess: (List<DramaCard>) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (!initialized && !PSSDK.hasInitialized()) {
            onFailure("短剧 SDK 尚未初始化")
            return
        }
        PSSDK.requestFeedListByCategoryIds(listOf(categoryId), emptyList(), FIRST_PAGE, pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) = onSuccess(result?.dataList.orEmpty().map(::toCard))
            override fun onFail(error: PSSDK.ErrorInfo?) = onFailure("短剧分类内容读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
        })
    }

    /** Production search adapter. The test build uses local catalog filtering while the SDK switch is disabled. */
    fun search(keyword: String, pageSize: Int = DEFAULT_PAGE_SIZE, onSuccess: (List<DramaCard>) -> Unit, onFailure: (String) -> Unit) {
        if (!initialized && !PSSDK.hasInitialized()) { onFailure("Short-drama SDK is not initialized"); return }
        PSSDK.searchDrama(keyword.trim(), false, FIRST_PAGE, pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) = onSuccess(result?.dataList.orEmpty().map(::toCard))
            override fun onFail(error: PSSDK.ErrorInfo?) = onFailure("Short-drama search failed: ${error?.code ?: -1} ${error?.msg.orEmpty()}")
        })
    }

    /** Creates the provider player only for a production SDK card; the caller owns its Fragment transaction. */
    fun createDetailFragment(
        card: DramaCard,
        onClose: () -> Unit,
        needsEpisodeUnlock: (episodeIndex: Int) -> Boolean,
        requestEpisodeUnlock: (episodeIndex: Int, listener: PSSDK.ShortPlayBlockResultListener?) -> Unit,
        onEpisodeStarted: (episodeIndex: Int, episode: com.bytedance.sdk.shortplay.api.EpisodeData?) -> Unit,
        onEpisodeCompleted: (episodeIndex: Int) -> Unit,
        onPlaybackProgress: (episodeIndex: Int, progress: Int, duration: Int) -> Unit,
        onOverScroll: (direction: Int) -> Unit,
    ): ShortPlayFragment {
        return PSSDK.createDetailFragment(
            card.nativeShortPlay,
            PSSDK.DetailPageConfig.Builder()
                .hideLeftTopCloseAndTitle(true, object : PSSDK.ShortPlayDetailPageCloseListener {
                    override fun onCloseClicked(): Boolean { onClose(); return true }
                })
                // JoyeDrama advances the next provider episode after its completion callback so
                // natural-break ads and rewards are applied before the new episode starts.
                .enableAutoPlayNext(false)
                .displayBottomExtraView(false)
                .build(),
            object : PSSDK.ShortPlayDetailPageListener {
                override fun onOverScroll(direction: Int) = onOverScroll(direction)
                override fun onProgressChange(shortPlay: ShortPlay?, episodeIndex: Int, progress: Int, duration: Int) =
                    onPlaybackProgress(episodeIndex, progress, duration)
                override fun onPlayFailed(error: PSSDK.ErrorInfo?): Boolean = false
                override fun onShortPlayPlayed(shortPlay: ShortPlay?, episodeIndex: Int, episode: com.bytedance.sdk.shortplay.api.EpisodeData?) {
                    onEpisodeStarted(episodeIndex, episode)
                }
                override fun onItemSelected(position: Int, itemType: PSSDK.ShortPlayDetailPageListener.ItemType?, episodeIndex: Int) = Unit
                override fun onVideoPlayStateChanged(shortPlay: ShortPlay?, episodeIndex: Int, state: Int) = Unit
                override fun onVideoPlayCompleted(shortPlay: ShortPlay?, episodeIndex: Int) {
                    onEpisodeCompleted(episodeIndex)
                }
                override fun onEnterImmersiveMode() = Unit
                override fun onExitImmersiveMode() = Unit
                override fun isNeedBlock(shortPlay: ShortPlay?, episodeIndex: Int): Boolean = needsEpisodeUnlock(episodeIndex)
                override fun showAdIfNeed(shortPlay: ShortPlay?, episodeIndex: Int, listener: PSSDK.ShortPlayBlockResultListener?) {
                    requestEpisodeUnlock(episodeIndex, listener)
                }
                override fun onVideoInfoFetched(shortPlay: ShortPlay?, episodeIndex: Int, info: PSSDK.VideoPlayInfo?) = Unit
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
        private const val SDK_INITIALIZATION_TIMEOUT_MS = 12_000L
        private const val DISCOVERY_TIMEOUT_MS = 12_000L
        private const val PREFERENCES = "joyedrama.short_drama_sdk"
        private const val INSTALLATION_ID_KEY = "installation_id"

        @Volatile
        private var instance: ShortDramaSdkManager? = null

        fun get(context: Context): ShortDramaSdkManager = instance ?: synchronized(this) {
            instance ?: ShortDramaSdkManager(context.applicationContext).also { instance = it }
        }
    }
}
