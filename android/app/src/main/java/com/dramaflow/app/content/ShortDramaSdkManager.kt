package com.dramaflow.app.content

import android.content.Context
import android.util.Log
import com.bytedance.sdk.shortplay.api.PSSDK
import com.bytedance.sdk.shortplay.api.ShortPlay
import com.dramaflow.app.BuildConfig
import java.util.UUID

/**
 * 合作方短剧 SDK 的可选内容入口。
 *
 * 当前测试包保持 [BuildConfig.SHORT_DRAMA_SDK_ENABLED] 为 false，目录和播放仍由本地 Content
 * API + Media3 提供。待填写短剧 SDK 测试参数并开启开关后，本类可读取 SDK 的热门、最新、分类
 * 和分类推荐列表；UI 只消费 [DramaCard] 的统一内容模型，避免向用户暴露内容来源或 SDK 名称。
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
        PSSDK.init(appContext, config, object : PSSDK.PSSDKInitListener {
            override fun onInitFinished(success: Boolean, error: PSSDK.ErrorInfo?) {
                initialized = success
                if (success) {
                    PSSDK.setEligibleAudience(true)
                    onResult(Initialization.Ready)
                } else {
                    onResult(Initialization.Failed("短剧 SDK 初始化失败：${error?.code ?: -1} ${error?.msg.orEmpty()}"))
                }
            }
        })
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

        fun tryDeliver() {
            if (!delivered && popular != null && latest != null && categories != null) {
                delivered = true
                onSuccess(Discovery(popular.orEmpty(), latest.orEmpty(), categories.orEmpty()))
            }
        }

        PSSDK.requestPopularDrama(FIRST_PAGE, pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) {
                popular = result?.dataList.orEmpty().map(::toCard)
                tryDeliver()
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                if (!delivered) {
                    delivered = true
                    onFailure("短剧热门内容读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
                }
            }
        })

        PSSDK.requestNewDrama(FIRST_PAGE, pageSize, object : PSSDK.FeedListResultListener {
            override fun onSuccess(result: PSSDK.FeedListLoadResult<ShortPlay>?) {
                latest = result?.dataList.orEmpty().map(::toCard)
                tryDeliver()
            }

            override fun onFail(error: PSSDK.ErrorInfo?) {
                if (!delivered) {
                    delivered = true
                    onFailure("短剧最新内容读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
                }
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
                if (!delivered) {
                    delivered = true
                    onFailure("短剧分类读取失败：${error?.code ?: -1} ${error?.msg.orEmpty()}")
                }
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
        )
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
        private const val PREFERENCES = "joyedrama.short_drama_sdk"
        private const val INSTALLATION_ID_KEY = "installation_id"

        @Volatile
        private var instance: ShortDramaSdkManager? = null

        fun get(context: Context): ShortDramaSdkManager = instance ?: synchronized(this) {
            instance ?: ShortDramaSdkManager(context.applicationContext).also { instance = it }
        }
    }
}
