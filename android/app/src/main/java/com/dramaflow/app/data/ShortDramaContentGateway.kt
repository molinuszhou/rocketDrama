package com.dramaflow.app.data

/**
 * 授权短剧服务的接入边界。
 *
 * Pangle Ads SDK 只提供广告变现能力，不提供公开的剧集目录和播放内容接口。
 * 当业务方提供正式的短剧 SDK 或 API 后，实现本接口并在 AppViewModel 中替换
 * DemoDramaRepository，即可复用现有首页、发现、搜索和播放器页面。
 */
interface ShortDramaContentGateway {
    suspend fun homeSections(language: String, page: Int): ContentPage
    suspend fun discoverFeed(language: String, cursor: String?): FeedPage
    suspend fun search(keyword: String, page: Int): ContentPage
    suspend fun dramaDetail(dramaId: String): DramaPlaybackDetail
}

data class ContentPage(
    val items: List<Drama>,
    val hasMore: Boolean,
    val nextPage: Int?
)

data class FeedPage(
    val items: List<Drama>,
    val nextCursor: String?
)

data class DramaPlaybackDetail(
    val drama: Drama,
    val episodeUrls: List<String>,
    val entitlement: EpisodeEntitlement
)

data class EpisodeEntitlement(
    val lockedFromEpisode: Int,
    val unlockType: UnlockType
)

enum class UnlockType { REWARDED_AD, SUBSCRIPTION, CASH, UNKNOWN }
