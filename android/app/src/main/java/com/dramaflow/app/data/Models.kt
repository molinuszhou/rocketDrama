package com.dramaflow.app.data

import androidx.compose.ui.graphics.Color
import com.dramaflow.app.ads.AdConsent

enum class MainTab(val label: String) {
    HOME("首页"),
    DISCOVER("发现"),
    TASKS("任务"),
    PROFILE("我的")
}

enum class AppRoute {
    MAIN,
    SEARCH,
    PLAYER,
    HISTORY,
    FAVORITES,
    CASH_WALLET,
    COIN_WALLET,
    SETTINGS
}

enum class DramaCategory(val label: String) {
    FEATURED("精选"),
    POPULAR("热门"),
    LATEST("最新"),
    MALE("男频"),
    FEMALE("女频")
}

data class Drama(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val episodes: Int,
    val lockedFrom: Int,
    val accent: Color,
    val coverSymbol: String,
    val tags: List<String>
)

data class WatchRecord(
    val dramaId: String,
    val episode: Int,
    val progressPercent: Int,
    val watchedAt: Long = System.currentTimeMillis()
)

enum class TaskStatus { IN_PROGRESS, CLAIMABLE, CLAIMED }

data class RewardTask(
    val id: String,
    val title: String,
    val description: String,
    val progress: Int,
    val target: Int,
    val rewardCoins: Int,
    val rewardCashCents: Int,
    val status: TaskStatus,
    val adEligible: Boolean = false
)

data class RewardMilestone(
    val id: String,
    val requiredPoints: Int,
    val rewardCoins: Int,
    val claimed: Boolean = false
)

data class WithdrawalEligibility(
    val eligible: Boolean,
    val minimumCoins: Int,
    val remainingCoins: Int,
    val unmetRequirements: List<String>,
    val nextAction: String
)

data class WalletTier(
    val id: String,
    val label: String,
    val cashCents: Int,
    val requiredEpisodes: Int,
    val requiredLevel: Int,
    val unlocked: Boolean
)

data class UserProfile(
    val cashCents: Int = 286,
    val coins: Int = 1280,
    val level: Int = 3,
    val levelProgress: Float = 0.68f,
    val watchedEpisodes: Int = 8,
    val checkInStreak: Int = 3,
    val signedInToday: Boolean = false
)

data class AppUiState(
    val initialized: Boolean = false,
    val adConsent: AdConsent = AdConsent.UNKNOWN,
    val adsConfigured: Boolean = false,
    val adsReady: Boolean = false,
    val selectedTab: MainTab = MainTab.DISCOVER,
    val route: AppRoute = AppRoute.MAIN,
    val selectedDramaId: String? = null,
    val searchQuery: String = "",
    val searchHistory: List<String> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val watchHistory: List<WatchRecord> = emptyList(),
    val unlockedUntil: Map<String, Int> = emptyMap(),
    val user: UserProfile = UserProfile(),
    val tasks: List<RewardTask> = emptyList(),
    val cashTiers: List<WalletTier> = emptyList(),
    val dailyBaseCoinLimit: Int = 800,
    val baseCoinsEarnedToday: Int = 320,
    val rewardPoints: Int = 4,
    val milestones: List<RewardMilestone> = emptyList(),
    val withdrawalEligibility: WithdrawalEligibility = WithdrawalEligibility(
        eligible = false,
        minimumCoins = 500,
        remainingCoins = 0,
        unmetRequirements = emptyList(),
        nextAction = "完成资格条件"
    ),
    val snackMessage: String? = null,
    val pendingRewardTaskId: String? = null,
    val showUnlockDialog: Boolean = false,
    val showRewardDialog: Boolean = false,
    val showCheckInDialog: Boolean = false
)
