package com.dramaflow.app

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dramaflow.app.ads.AdConsent
import com.dramaflow.app.ads.AdConsentStore
import com.dramaflow.app.ads.TopOnAdsManager
import com.dramaflow.app.ads.TopOnSettings
import com.dramaflow.app.data.AppRoute
import com.dramaflow.app.data.AppUiState
import com.dramaflow.app.data.CacheFirstDramaRepository
import com.dramaflow.app.data.Drama
import com.dramaflow.app.data.MainTab
import com.dramaflow.app.data.RewardMilestone
import com.dramaflow.app.data.TaskStatus
import com.dramaflow.app.data.UserProfile
import com.dramaflow.app.data.WatchRecord
import com.dramaflow.app.data.WithdrawalEligibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CacheFirstDramaRepository(application)
    private val searchHistoryStore = com.dramaflow.app.data.SearchHistoryStore(application)
    private val pangleSettings = TopOnSettings.fromBuildConfig()
    private val adConsentStore = AdConsentStore(application)
    private val pangleAdsManager = TopOnAdsManager(application, pangleSettings)

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    var dramas: List<Drama> = repository.allDramas()
        private set

    init {
        val user = UserProfile()
        val consent = adConsentStore.read()
        _uiState.value = AppUiState(
            initialized = true,
            selectedTab = MainTab.DISCOVER,
            searchHistory = searchHistoryStore.load(),
            tasks = repository.tasks(),
            user = user,
            cashTiers = repository.cashTiers(user),
            pangleConsent = consent,
            pangleConfigured = pangleSettings.isConfigured,
            pangleAdsReady = consent == AdConsent.GRANTED && pangleSettings.isConfigured,
            milestones = defaultMilestones(),
            withdrawalEligibility = withdrawalEligibilityFor(user)
        )
    }

    /** 在正式短剧内容服务完成拉取后调用，页面层会继续复用同一 Drama 模型。 */
    fun replaceCatalogFromSource(remoteCatalog: List<Drama>) {
        repository.replaceCatalog(remoteCatalog)
        dramas = repository.allDramas()
        _uiState.update { it.copy(snackMessage = "短剧目录已更新：${dramas.size} 部内容可用") }
    }

    fun recordAdConsent(granted: Boolean) {
        val consent = if (granted) AdConsent.GRANTED else AdConsent.DECLINED
        adConsentStore.save(consent)
        _uiState.update {
            it.copy(
                pangleConsent = consent,
                pangleAdsReady = granted && pangleSettings.isConfigured,
                snackMessage = when {
                    granted && pangleSettings.isConfigured -> "已同意广告隐私说明；开屏、插屏和激励广告会在对应场景按需加载"
                    granted -> "已记录同意；请先配置 TopOn App ID 与广告位 ID"
                    else -> "已拒绝广告隐私说明；开屏、插屏、激励广告和广告解锁将不可用"
                }
            )
        }
    }

    /** 旧 Compose 页面兼容入口；主运行链路使用通用广告同意状态。 */
    fun recordPangleConsent(granted: Boolean) = recordAdConsent(granted)

    /** 旧 Compose 页面兼容入口。 */
    fun requestPangleConsentChoice() = requestAdConsentChoice()

    fun requestAdConsentChoice() {
        adConsentStore.clear()
        _uiState.update {
            it.copy(
                pangleConsent = AdConsent.UNKNOWN,
                pangleAdsReady = false,
                snackMessage = "请重新选择广告隐私偏好"
            )
        }
    }

    fun selectTab(tab: MainTab) = _uiState.update { it.copy(selectedTab = tab, route = AppRoute.MAIN) }
    fun navigate(route: AppRoute) = _uiState.update { it.copy(route = route) }

    fun navigateBack() = _uiState.update { state ->
        if (state.route == AppRoute.MAIN) state else state.copy(route = AppRoute.MAIN, selectedDramaId = null)
    }

    fun openDrama(dramaId: String) = _uiState.update { it.copy(route = AppRoute.PLAYER, selectedDramaId = dramaId) }
    fun selectedDrama(): Drama? = dramas.firstOrNull { it.id == _uiState.value.selectedDramaId }
    fun updateQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun submitSearch() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isBlank()) return
        _uiState.update { it.copy(searchHistory = searchHistoryStore.add(query), snackMessage = "已找到与“$query”相关的短剧") }
    }

    fun useSearchHistory(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        submitSearch()
    }

    fun clearSearchHistory() {
        searchHistoryStore.clear()
        _uiState.update { it.copy(searchHistory = emptyList(), snackMessage = "搜索历史已清空") }
    }

    fun toggleFavorite(dramaId: String) = _uiState.update { state ->
        val isFavorite = dramaId in state.favorites
        state.copy(
            favorites = state.favorites.toMutableSet().apply { if (isFavorite) remove(dramaId) else add(dramaId) },
            snackMessage = if (isFavorite) "已取消收藏" else "已收藏，稍后可在我的收藏中继续观看"
        )
    }

    fun selectEpisode(drama: Drama, episode: Int) {
        val highestUnlocked = _uiState.value.unlockedUntil[drama.id] ?: drama.lockedFrom - 1
        if (episode >= drama.lockedFrom && episode > highestUnlocked) {
            _uiState.update { it.copy(showUnlockDialog = true) }
        } else {
            recordWatch(drama.id, episode, 0)
        }
    }

    fun watchAdToUnlock(activity: Activity?) {
        val drama = selectedDrama() ?: return
        if (!canRequestAd(activity)) return
        _uiState.update { it.copy(showUnlockDialog = false, snackMessage = "正在加载 TopOn 激励广告…") }
        pangleAdsManager.showRewarded(requireNotNull(activity)) { result ->
            when (result) {
                TopOnAdsManager.RewardedResult.RewardEarned -> unlockEpisodeAfterReward(drama)
                TopOnAdsManager.RewardedResult.Cancelled -> postAdMessage("广告未完整观看，未解锁剧集")
                is TopOnAdsManager.RewardedResult.Unavailable -> postAdMessage(result.reason)
                is TopOnAdsManager.RewardedResult.Failed -> postAdMessage("广告加载失败（${result.code}）：${result.message.ifBlank { "请稍后重试" }}")
            }
        }
    }

    private fun unlockEpisodeAfterReward(drama: Drama) {
        val targetEpisode = drama.lockedFrom
        _uiState.update { state ->
            state.copy(
                unlockedUntil = state.unlockedUntil + (drama.id to targetEpisode),
                snackMessage = "广告奖励已确认：第 $targetEpisode 集已解锁"
            )
        }
        recordWatch(drama.id, targetEpisode, 0)
    }

    fun dismissUnlock() = _uiState.update { it.copy(showUnlockDialog = false) }

    fun recordWatch(dramaId: String, episode: Int, progress: Int) = _uiState.update { state ->
        val updatedHistory = listOf(WatchRecord(dramaId, episode, progress)) + state.watchHistory.filterNot { it.dramaId == dramaId }
        val updatedUser = state.user.copy(watchedEpisodes = state.user.watchedEpisodes + 1)
        state.copy(
            watchHistory = updatedHistory.take(20),
            user = updatedUser,
            cashTiers = repository.cashTiers(updatedUser),
            withdrawalEligibility = withdrawalEligibilityFor(updatedUser)
        )
    }

    fun completeCurrentEpisode() {
        val drama = selectedDrama() ?: return
        val previous = _uiState.value.watchHistory.firstOrNull { it.dramaId == drama.id }
        val nextEpisode = ((previous?.episode ?: 0) + 1).coerceAtMost(drama.episodes)
        selectEpisode(drama, nextEpisode)
    }

    fun signIn() = _uiState.update { state ->
        if (state.user.signedInToday) return@update state.copy(snackMessage = "今天已经签到，明天再来吧")
        val grantedCoins = cappedBaseCoins(state, 30)
        val updatedUser = state.user.copy(
            signedInToday = true,
            checkInStreak = state.user.checkInStreak + 1,
            coins = state.user.coins + grantedCoins,
            cashCents = state.user.cashCents + 5
        )
        state.copy(
            user = updatedUser,
            baseCoinsEarnedToday = state.baseCoinsEarnedToday + grantedCoins,
            rewardPoints = state.rewardPoints + 1,
            tasks = state.tasks.map { if (it.id == "checkin") it.copy(status = TaskStatus.CLAIMED) else it },
            cashTiers = repository.cashTiers(updatedUser),
            withdrawalEligibility = withdrawalEligibilityFor(updatedUser),
            showCheckInDialog = true,
            snackMessage = if (grantedCoins == 30) null else "基础奖励已接近每日上限，本次签到获得 $grantedCoins 金币"
        )
    }

    fun dismissCheckIn() = _uiState.update { it.copy(showCheckInDialog = false) }

    fun claimTask(taskId: String, doubled: Boolean = false) = _uiState.update { state ->
        val task = state.tasks.firstOrNull { it.id == taskId } ?: return@update state
        if (task.status != TaskStatus.CLAIMABLE) return@update state.copy(snackMessage = "该任务暂不可领取")

        val multiplier = if (doubled) 2 else 1
        val expectedCoins = task.rewardCoins * multiplier
        val grantedCoins = cappedBaseCoins(state, expectedCoins)
        if (grantedCoins <= 0) {
            return@update state.copy(
                pendingRewardTaskId = null,
                showRewardDialog = false,
                snackMessage = "今日基础金币已达 ${state.dailyBaseCoinLimit} 上限，明日重置后可继续领取"
            )
        }
        val updatedUser = state.user.copy(
            coins = state.user.coins + grantedCoins,
            cashCents = state.user.cashCents + task.rewardCashCents * multiplier
        )
        state.copy(
            user = updatedUser,
            baseCoinsEarnedToday = state.baseCoinsEarnedToday + grantedCoins,
            rewardPoints = state.rewardPoints + 1,
            tasks = state.tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.CLAIMED) else it },
            cashTiers = repository.cashTiers(updatedUser),
            withdrawalEligibility = withdrawalEligibilityFor(updatedUser),
            pendingRewardTaskId = null,
            showRewardDialog = false,
            snackMessage = "已领取${if (doubled) "双倍" else ""}奖励：+$grantedCoins 金币；阶段点 +1"
        )
    }

    fun claimMilestone(milestoneId: String) = _uiState.update { state ->
        val milestone = state.milestones.firstOrNull { it.id == milestoneId } ?: return@update state
        when {
            milestone.claimed -> state.copy(snackMessage = "该阶段宝箱已经领取")
            state.rewardPoints < milestone.requiredPoints -> state.copy(snackMessage = "还差 ${milestone.requiredPoints - state.rewardPoints} 个阶段点可领取")
            else -> {
                val updatedUser = state.user.copy(coins = state.user.coins + milestone.rewardCoins)
                state.copy(
                    user = updatedUser,
                    milestones = state.milestones.map { if (it.id == milestoneId) it.copy(claimed = true) else it },
                    cashTiers = repository.cashTiers(updatedUser),
                    withdrawalEligibility = withdrawalEligibilityFor(updatedUser),
                    snackMessage = "阶段宝箱已领取：+${milestone.rewardCoins} 金币"
                )
            }
        }
    }

    fun openTaskReward(taskId: String) {
        if (_uiState.value.tasks.any { it.id == taskId && it.status == TaskStatus.CLAIMABLE }) {
            _uiState.update { it.copy(showRewardDialog = true, pendingRewardTaskId = taskId) }
        }
    }

    fun watchAdForDoubleTaskReward(activity: Activity?, taskId: String) {
        if (!canRequestAd(activity)) return
        _uiState.update { it.copy(showRewardDialog = false, snackMessage = "正在加载 TopOn 双倍奖励广告…") }
        pangleAdsManager.showRewarded(requireNotNull(activity)) { result ->
            when (result) {
                TopOnAdsManager.RewardedResult.RewardEarned -> claimTask(taskId, doubled = true)
                TopOnAdsManager.RewardedResult.Cancelled -> postAdMessage("广告未完整观看，未领取双倍奖励")
                is TopOnAdsManager.RewardedResult.Unavailable -> postAdMessage(result.reason)
                is TopOnAdsManager.RewardedResult.Failed -> postAdMessage("广告加载失败（${result.code}）：${result.message.ifBlank { "请稍后重试" }}")
            }
        }
    }

    fun dismissReward() = _uiState.update { it.copy(showRewardDialog = false, pendingRewardTaskId = null) }

    fun requestDemoWithdrawal(label: String) = _uiState.update { state ->
        val eligibility = state.withdrawalEligibility
        if (eligibility.eligible) {
            state.copy(snackMessage = "资格已满足，可进入 $label 的申请流程")
        } else {
            state.copy(snackMessage = "尚未满足资格：${eligibility.unmetRequirements.joinToString("；")}")
        }
    }

    fun consumeSnack() = _uiState.update { it.copy(snackMessage = null) }
    fun findDrama(dramaId: String): Drama? = dramas.firstOrNull { it.id == dramaId }

    fun filteredDramas(query: String): List<Drama> {
        if (query.isBlank()) return dramas
        return dramas.filter { drama ->
            drama.title.contains(query, true) || drama.subtitle.contains(query, true) || drama.tags.any { it.contains(query, true) }
        }
    }

    fun favoriteDramas(): List<Drama> = dramas.filter { it.id in _uiState.value.favorites }
    fun historyDramas(): List<Pair<Drama, WatchRecord>> = _uiState.value.watchHistory.mapNotNull { record -> findDrama(record.dramaId)?.let { it to record } }

    private fun cappedBaseCoins(state: AppUiState, requestedCoins: Int): Int =
        requestedCoins.coerceAtMost((state.dailyBaseCoinLimit - state.baseCoinsEarnedToday).coerceAtLeast(0))

    private fun defaultMilestones(): List<RewardMilestone> = listOf(
        RewardMilestone("stage_1", requiredPoints = 3, rewardCoins = 60),
        RewardMilestone("stage_2", requiredPoints = 6, rewardCoins = 120),
        RewardMilestone("stage_3", requiredPoints = 10, rewardCoins = 220)
    )

    private fun withdrawalEligibilityFor(user: UserProfile): WithdrawalEligibility {
        val minimumCoins = 500
        val unmet = buildList {
            if (user.coins < minimumCoins) add("还差 ${minimumCoins - user.coins} 金币达到最低兑换门槛")
            if (user.watchedEpisodes < 3) add("还需完成 ${3 - user.watchedEpisodes} 集有效观看")
            if (!user.signedInToday) add("完成今日签到")
        }
        return WithdrawalEligibility(
            eligible = unmet.isEmpty(),
            minimumCoins = minimumCoins,
            remainingCoins = (minimumCoins - user.coins).coerceAtLeast(0),
            unmetRequirements = unmet,
            nextAction = if (unmet.isEmpty()) "已满足公开资格条件，可提交申请" else unmet.first()
        )
    }

    private fun canRequestAd(activity: Activity?): Boolean {
        val state = _uiState.value
        val message = when {
            activity == null -> "无法获取当前页面，暂不能展示广告"
            state.pangleConsent != AdConsent.GRANTED -> "请先同意广告隐私说明后再使用广告解锁"
            !pangleSettings.isConfigured -> "TopOn 尚未配置 App ID 或激励广告位 ID"
            else -> null
        }
        if (message != null) {
            postAdMessage(message)
            return false
        }
        return true
    }

    private fun postAdMessage(message: String) = _uiState.update { it.copy(snackMessage = message) }
}
