package com.dramaflow.app.rewards

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 独立 PostgreSQL 积分服务客户端。
 *
 * Android 仅展示快照与上报完成的广告/观看行为；宝石、现金钱包、释放、欢迎奖励与皇冠权益
 * 均以服务端账本为唯一事实来源。为支持离线错误提示，所有未识别的新增字段都会采用保守默认值。
 */
class RewardsApiClient(
    private val baseUrlProvider: () -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    data class EpisodeReward(
        val episodeId: String,
        val rewardCoins: Int,
        val status: String,
        val boosted: Boolean = false,
    )

    data class RewardTask(
        val id: String,
        val title: String,
        val description: String,
        val progress: Int,
        val target: Int,
        val rewardCoins: Int,
        val adDoubleEnabled: Boolean = false,
        val status: String,
        val cycleNumber: Int = 0,
    )

    data class WalletRelease(
        val releasedToday: Boolean,
        val dailyRelease: String,
        val boostUsed: Int,
        val boostCap: Int,
        val boostAmount: String,
        val estimatedDaysRemaining: Int,
        val pendingDays: Int,
        val expectedTomorrowTransfer: String = "0.00",
        val expectedBoostTransfer: String = "0.00",
        val expectedTomorrowTransferUsdReference: String = "0.00",
        val expectedBoostTransferUsdReference: String = "0.00",
        val boostAvailable: Boolean = false,
        val boostPercent: Float = 0.2f,
    )

    data class Currency(
        val marketCode: String = "DEFAULT",
        val code: String = "USD",
        val symbol: String = "$",
        val fractionDigits: Int = 2,
    )

    data class Wallet(
        val coins: Long,
        val cashWallet: String,
        val lockedBalance: String,
        val availableBalance: String,
        val cashWalletUsdReference: String = "0.00",
        val availableBalanceUsdReference: String = "0.00",
        val currency: Currency = Currency(),
        val totalGemsEarned: Long,
        val exchangeRateGems: Long,
        /** Current server-issued conversion value for the exchange rate, used in reward explanations. */
        val exchangeValuePer10000Gems: String = "0.00",
        val exchangeValuePer10000GemsUsdReference: String = "0.00",
        val minimumExchangeGems: Long = 10_000L,
        val watchedEpisodes: Int,
        val signedInToday: Boolean,
        val checkinStreak: Int,
        val release: WalletRelease,
    )

    data class Ring(
        val enabled: Boolean = false,
        val progress: Int = 0,
        val target: Int = 1,
        val claimable: Boolean = false,
        val rewardCoins: Int = 0,
        val label: String = "攒币",
    )

    data class WelcomeReward(
        val available: Boolean,
        val claimed: Boolean,
        val gems: Long,
    )

    data class ReviewReward(
        val available: Boolean = false,
        val claimed: Boolean = false,
        val gems: Long = 0L,
    )

    data class CrownMetric(val current: Int, val target: Int)

    data class CrownProgress(
        val code: String,
        val title: String,
        val levelRank: Int,
        val earned: Boolean,
        val gems: Long,
        val entitlement: String,
        val windowHours: Int,
        val rounds: CrownMetric,
        val signins: CrownMetric,
        val rewardedAds: CrownMetric,
        val progress: Float,
        val episodeRewardMultiplier: Float = 1f,
        val watchedRounds: CrownMetric = CrownMetric(0, 0),
        val adEquivalentRounds: Int = 0,
        val adsPerEpisode: Int = 2,
    )

    data class UnlockRule(
        val freeEpisodesDefault: Int,
        val dailyRewardedUnlockLimit: Int,
        val title: String,
        val description: String,
        val nextEpisodeInterstitialEnabled: Boolean = false,
        val nextEpisodeRewardedEnabled: Boolean = true,
        val extraEpisodeUnlockEnabled: Boolean = false,
        val validWatchSeconds: Int,
    )

    data class Chest(
        val claimable: Boolean,
        val claimed: Boolean,
        val requiresCheckin: Boolean,
        val rewardCoins: Int,
        val availableCount: Int = 0,
        val dailyAvailableCount: Int = 0,
        val watchAvailableCount: Int = 0,
        val minCoins: Int = 0,
        val maxCoins: Int = 0,
        val episodesPerChest: Int = 3,
        val episodesToNextChest: Int = 0,
    )

    data class InterstitialPolicy(
        val cooldownSeconds: Int,
        val hourlyLimit: Int,
        val nextEpisodeEnabled: Boolean = false,
        val nextEpisodeHourlyLimit: Int = 0,
        val pauseAdEnabled: Boolean = false,
        val pauseAdMinPlaySeconds: Int = 90,
        val pauseAdCooldownSeconds: Int = 300,
        val pauseAdHourlyLimit: Int = 0,
        val rewardClaimEnabled: Boolean = false,
        val midEpisodeEnabled: Boolean = false,
        val midEpisodeTriggerSeconds: Int = 45,
        val midEpisodeHourlyLimit: Int = 0,
        val seekEnabled: Boolean = false,
        val seekHourlyLimit: Int = 0,
        val speedEnabled: Boolean = false,
        val speedHourlyLimit: Int = 0,
        val actionRewardThresholdGems: Int = 3000,
        val episodeInterstitialLimit: Int = 1,
        val dramaSwitchEnabled: Boolean = true,
        val dramaSwitchEveryCount: Int = 3,
        val dramaSwitchHourlyLimit: Int = 6,
        val rewardedFallbackEnabled: Boolean = true,
        val lastRvAt: String?,
        val lastInterstitialAt: String?,
    )

    data class GemDamping(
        val totalGemsEarned: Long = 0L,
        val cap: Long = 0L,
        val tier1Threshold: Long = 0L,
        val tier2Threshold: Long = 0L,
        val tier3Threshold: Long = 0L,
        val tier1Multiplier: Float = 1f,
        val tier2Multiplier: Float = 1f,
        val tier3Multiplier: Float = 1f,
        val tier4Multiplier: Float = 1f,
    )

    data class Withdrawal(
        val enabled: Boolean,
        val eligible: Boolean,
        val reason: String,
        val threshold: String,
        val thresholdUsdReference: String = "0.00",
    )

    data class Achievement(val code: String, val gems: Long, val entitlement: String)
    data class LedgerEntry(val type: String, val gemsDelta: Long, val cashWalletDelta: String, val availableBalanceDelta: String, val createdAt: String)

    data class Snapshot(
        val businessDate: String,
        val wallet: Wallet,
        val tasks: List<RewardTask>,
        val pendingEpisodeRewards: List<EpisodeReward>,
        val preboostedEpisodeIds: Set<String> = emptySet(),
        val tenRoundSize: Int,
        val episodesToNextTenRound: Int,
        val nextTenRoundPreviewCoins: Int,
        val episodesPerRandomChest: Int = 3,
        val episodesToNextRandomChest: Int = 0,
        val ring: Ring,
        val welcome: WelcomeReward,
        val review: ReviewReward = ReviewReward(),
        val unlock: UnlockRule,
        val unlockedEpisodeTargets: Set<String> = emptySet(),
        val checkinSignedToday: Boolean,
        val checkinStreak: Int,
        val checkinTodayIndex: Int,
        val checkinNextRewardCoins: Int,
        val checkinRewardTiers: List<Int>,
        val checkinAdDoubleEnabled: Boolean,
        val walletEligibilityCopy: String,
        val chest: Chest,
        val interstitial: InterstitialPolicy,
        val gemDamping: GemDamping = GemDamping(),
        val withdrawal: Withdrawal,
        val achievements: List<Achievement>,
        val crownProgress: List<CrownProgress>,
        val rvCountToday: Int,
        val rvDailyLimit: Int,
        val lifecycleDay: Int,
        val geoTier: String,
        val ledger: List<LedgerEntry> = emptyList(),
    ) {
        val rewardsEnabled: Boolean get() = true
        val ruleVersion: String get() = "postgresql-v8"
    }

    data class WatchReward(
        val snapshot: Snapshot,
        val episodeRewardCoins: Int,
        val tenRoundRewardCoins: Int,
        val randomChestsCreated: Int = 0,
    )

    data class RewardAction(
        val idempotent: Boolean,
        val action: String,
        val gemsAdded: Long,
        val snapshot: Snapshot,
        val adEventId: String? = null,
        val releasedMicros: Long = 0L,
    )

    val isConfigured: Boolean
        get() = normalisedBaseUrl()?.let { it.startsWith("http://") || it.startsWith("https://") } == true

    fun bootstrap(userId: String, region: String, onSuccess: (Snapshot) -> Unit, onFailure: (String) -> Unit) {
        post("/v2/bootstrap", JSONObject().put("user_id", userId).put("region", region), { onSuccess(parseSnapshot(it)) }, onFailure)
    }

    fun recordWatchEvent(
        userId: String,
        dramaId: String,
        episodeId: String,
        validWatchSeconds: Int,
        onSuccess: (WatchReward) -> Unit,
        onFailure: (String) -> Unit,
    ) = post(
        "/v2/watch-events",
        JSONObject().put("user_id", userId).put("event_id", "watch-${UUID.randomUUID()}")
            .put("drama_id", dramaId).put("episode_id", episodeId).put("watched_seconds", validWatchSeconds),
        { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            val episodeReward = result.optJSONObject("episode_reward")
            val tenRound = result.optJSONObject("ten_round_claim")
            onSuccess(WatchReward(
                snapshot = parseSnapshot(data.getJSONObject("snapshot")),
                episodeRewardCoins = episodeReward?.optLong("gems")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                tenRoundRewardCoins = tenRound?.optLong("gems")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                randomChestsCreated = result.optJSONArray("random_chests_created")?.optJSONObject(0)?.optInt("created_count", 0) ?: 0,
            ))
        }, onFailure,
    )

    fun claimEpisodeReward(userId: String, episodeId: String, onSuccess: (RewardAction) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/episode-rewards/claim",
        JSONObject().put("user_id", userId).put("episode_id", episodeId).put("reference_id", "episode-claim-${UUID.randomUUID()}"),
        { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(data.optBoolean("idempotent"), result.optString("action"), result.optLong("gems_added"), parseSnapshot(data.getJSONObject("snapshot"))))
        }, onFailure,
    )

    fun claimAllEpisodeRewards(userId: String, adEventId: String, onSuccess: (RewardAction) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/episode-rewards/claim-all",
        JSONObject().put("user_id", userId).put("reference_id", "episode-claim-all-${UUID.randomUUID()}").put("ad_event_id", adEventId),
        { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(data.optBoolean("idempotent"), result.optString("action"), result.optLong("gems_added"), parseSnapshot(data.getJSONObject("snapshot")), adEventId))
        }, onFailure,
    )

    fun claimTenRoundReward(userId: String, cycleNumber: Int, onSuccess: (RewardAction) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/ten-round-rewards/claim",
        JSONObject().put("user_id", userId).put("cycle_number", cycleNumber).put("reference_id", "ten-round-claim-${UUID.randomUUID()}"),
        { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(data.optBoolean("idempotent"), result.optString("action"), result.optLong("gems_added"), parseSnapshot(data.getJSONObject("snapshot"))))
        }, onFailure,
    )

    fun completeRewarded(
        userId: String,
        placementId: String,
        targetId: String? = null,
        episodeIndex: Int? = null,
        proof: String,
        onSuccess: (RewardAction) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val adEventId = "topon-${UUID.randomUUID()}"
        val payload = JSONObject().put("user_id", userId).put("ad_event_id", adEventId)
            .put("placement_id", placementId).put("proof", proof).put("timestamp_ms", System.currentTimeMillis())
        if (!targetId.isNullOrBlank()) payload.put("target_id", targetId)
        if (episodeIndex != null) payload.put("episode_index", episodeIndex)
        post("/v2/ads/rewarded/complete", payload, { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(
                data.optBoolean("idempotent"), result.optString("action"), result.optLong("gems_added"),
                parseSnapshot(data.getJSONObject("snapshot")), adEventId, result.optLong("released_micros"),
            ))
        }, onFailure)
    }

    fun checkin(userId: String, onSuccess: (RewardAction) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/checkins", JSONObject().put("user_id", userId), { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(data.optBoolean("idempotent"), "SIGN_IN", result.optLong("gems_added"), parseSnapshot(data.getJSONObject("snapshot"))))
        }, onFailure,
    )

    fun claimWelcomeBonus(userId: String, onSuccess: (RewardAction) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/welcome/claim", JSONObject().put("user_id", userId), { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(data.optBoolean("idempotent"), result.optString("action", "WELCOME_BONUS"), result.optLong("gems_added"), parseSnapshot(data.getJSONObject("snapshot"))))
        }, onFailure,
    )

    fun claimReviewReward(userId: String, onSuccess: (RewardAction) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/reviews/claim", JSONObject().put("user_id", userId).put("reference_id", "review-${UUID.randomUUID()}"), { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(data.optBoolean("idempotent"), result.optString("action", "APP_REVIEW"), result.optLong("gems_added"), parseSnapshot(data.getJSONObject("snapshot"))))
        }, onFailure,
    )

    fun exchangeGems(userId: String, gems: Long, onSuccess: (Snapshot) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/wallet/exchange",
        JSONObject().put("user_id", userId).put("gems", gems).put("reference_id", "exchange-${UUID.randomUUID()}"),
        { data -> onSuccess(parseSnapshot(data.getJSONObject("snapshot"))) }, onFailure,
    )

    fun recordInterstitial(userId: String, placementId: String, episodeId: String? = null, onSuccess: (Snapshot) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/interstitials/shown",
        JSONObject().put("user_id", userId).put("event_id", "int-${UUID.randomUUID()}").put("placement_id", placementId).also { payload ->
            if (!episodeId.isNullOrBlank()) payload.put("episode_id", episodeId)
        },
        { data -> onSuccess(parseSnapshot(data.getJSONObject("snapshot"))) }, onFailure,
    )

    private fun post(path: String, body: JSONObject, onSuccess: (JSONObject) -> Unit, onFailure: (String) -> Unit) {
        val baseUrl = normalisedBaseUrl()
        if (baseUrl == null) { onFailure("独立积分服务尚未配置"); return }
        val request = Request.Builder().url(baseUrl + path)
            .post(body.toString().toRequestBody(JSON)).header("Content-Type", "application/json").build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = onFailure("积分服务网络错误：${error.message ?: "无法连接"}")
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                    if (!it.isSuccessful) { onFailure(json?.optString("msg")?.takeIf(String::isNotBlank) ?: "积分服务请求失败（HTTP ${it.code}）"); return }
                    val data = json?.optJSONObject("data")
                    if (json?.optInt("code", -1) != 0 || data == null) { onFailure(json?.optString("msg")?.takeIf(String::isNotBlank) ?: "积分服务响应无效"); return }
                    runCatching { onSuccess(data) }.onFailure { onFailure("积分服务响应解析失败：${it.message}") }
                }
            }
        })
    }

    private fun normalisedBaseUrl(): String? = baseUrlProvider().trim().trimEnd('/').takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun parseSnapshot(json: JSONObject): Snapshot {
        val walletJson = json.getJSONObject("wallet")
        val today = json.getJSONObject("today")
        val checkin = json.getJSONObject("checkin")
        val unlock = json.getJSONObject("episode_unlock")
        val review = json.optJSONObject("review") ?: JSONObject()
        val chest = json.getJSONObject("chest")
        val interstitial = json.getJSONObject("interstitial")
        val withdrawal = json.getJSONObject("withdrawal")
        val damping = json.optJSONObject("gem_damping") ?: JSONObject()
        val user = json.optJSONObject("user") ?: JSONObject()
        val release = walletJson.optJSONObject("release") ?: JSONObject()
        val currencyJson = walletJson.optJSONObject("currency") ?: JSONObject()
        val currency = Currency(
            marketCode = currencyJson.optString("market_code", user.optString("market_code", "DEFAULT")),
            code = currencyJson.optString("code", "USD"),
            symbol = currencyJson.optString("symbol", "$"),
            fractionDigits = currencyJson.optInt("fraction_digits", 2).coerceIn(0, 6),
        )
        val tiers = parseTiers(checkin.optJSONArray("tiers"))
        val signedToday = checkin.optBoolean("signed_today")
        val todayIndex = checkin.optInt("today_streak", if (signedToday) checkin.optInt("completed_streak", 1) else 1)
        val taskList = parseTasks(json.optJSONArray("pending_claims"))
        val pendingEpisodeRewards = parseEpisodeRewards(json.optJSONArray("episode_reward_claims"))
        val achievements = parseAchievements(json.optJSONArray("achievements"))
        val crowns = parseCrowns(json.optJSONArray("crown_progress"))
        val ledger = parseLedger(json.optJSONArray("ledger"))
        return Snapshot(
            businessDate = json.optString("business_date"),
            wallet = Wallet(
                coins = walletJson.optLong("gems_balance"), cashWallet = walletJson.optString("cash_wallet", "0.00"),
                lockedBalance = walletJson.optString("wallet_locked", walletJson.optString("cash_wallet", "0.00")),
                availableBalance = walletJson.optString("available_balance", "0.00"),
                cashWalletUsdReference = walletJson.optString("cash_wallet_usd_reference", walletJson.optString("cash_wallet", "0.00")),
                availableBalanceUsdReference = walletJson.optString("available_balance_usd_reference", walletJson.optString("available_balance", "0.00")),
                currency = currency,
                totalGemsEarned = walletJson.optLong("total_gems_earned"), exchangeRateGems = walletJson.optLong("exchange_rate_gems", 10_000L),
                exchangeValuePer10000Gems = walletJson.optString("exchange_value_per_10000_gems", "0.00"),
                exchangeValuePer10000GemsUsdReference = walletJson.optString("exchange_value_per_10000_gems_usd_reference", "0.00"),
                minimumExchangeGems = walletJson.optLong("minimum_exchange_gems", 10_000L), watchedEpisodes = today.optInt("episode_count"),
                signedInToday = signedToday, checkinStreak = checkin.optInt("completed_streak"),
                release = WalletRelease(release.optBoolean("released_today"), release.optString("daily_release", "0.00"),
                    release.optInt("boost_used"), release.optInt("boost_cap", 3), release.optString("boost_amount", "0.00"),
                    release.optInt("estimated_days_remaining"), release.optInt("pending_days"),
                    release.optString("expected_tomorrow_transfer", "0.00"), release.optString("expected_boost_transfer", "0.00"),
                    release.optString("expected_tomorrow_transfer_usd_reference", release.optString("expected_tomorrow_transfer", "0.00")),
                    release.optString("expected_boost_transfer_usd_reference", release.optString("expected_boost_transfer", "0.00")),
                    release.optBoolean("boost_available"), release.optDouble("boost_percent", 0.2).toFloat()),
            ),
            tasks = taskList,
            pendingEpisodeRewards = pendingEpisodeRewards,
            preboostedEpisodeIds = parseStringSet(json.optJSONArray("episode_reward_preboosted_ids")),
            tenRoundSize = today.optInt("ten_round_size", 10).coerceAtLeast(1),
            episodesToNextTenRound = today.optInt("episodes_to_next_ten_round", 10).coerceAtLeast(0),
            nextTenRoundPreviewCoins = today.optLong("next_ten_round_preview_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            episodesPerRandomChest = today.optInt("episodes_per_random_chest", 3).coerceAtLeast(1),
            episodesToNextRandomChest = today.optInt("episodes_to_next_random_chest", 0).coerceAtLeast(0),
            ring = (json.optJSONObject("ring") ?: JSONObject()).let { Ring(it.optBoolean("enabled", true), it.optInt("progress"), it.optInt("target", 1).coerceAtLeast(1), it.optBoolean("claimable"), it.optLong("reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it.optString("label", "攒币")) },
            welcome = (json.optJSONObject("welcome") ?: JSONObject()).let { WelcomeReward(it.optBoolean("available"), it.optBoolean("claimed"), it.optLong("gems")) },
            review = ReviewReward(review.optBoolean("available"), review.optBoolean("claimed"), review.optLong("gems")),
            unlock = UnlockRule(
                unlock.optInt("free_episodes_count", 3),
                today.optInt("rv_daily_limit", 20),
                "广告解锁",
                "免费集数用完后，由你确认观看广告解锁下一集",
                unlock.optBoolean("next_episode_interstitial_enabled", false),
                unlock.optBoolean("next_episode_rewarded_enabled", true),
                unlock.optBoolean("extra_episode_unlock_enabled", false),
                unlock.optInt("valid_watch_seconds", 60),
            ),
            checkinSignedToday = signedToday, checkinStreak = checkin.optInt("completed_streak"), checkinTodayIndex = todayIndex,
            checkinNextRewardCoins = checkin.optLong("today_base_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), checkinRewardTiers = tiers,
            checkinAdDoubleEnabled = signedToday && checkin.optLong("today_double_gems") == 0L,
            walletEligibilityCopy = withdrawal.optString("reason"), chest = Chest(
                chest.optBoolean("claimable"), chest.optBoolean("claimed"), chest.optBoolean("requires_checkin", false), chest.optLong("reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                chest.optInt("available_count"), chest.optInt("daily_available_count"), chest.optInt("watch_available_count"),
                chest.optLong("min_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), chest.optLong("max_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                chest.optInt("episodes_per_chest", 3).coerceAtLeast(1), chest.optInt("episodes_to_next_chest", 0).coerceAtLeast(0),
            ),
            interstitial = InterstitialPolicy(
                interstitial.optInt("cooldown_seconds", 90),
                interstitial.optInt("hourly_limit", 3),
                interstitial.optBoolean("next_episode_enabled", false),
                interstitial.optInt("next_episode_hourly_limit", 0),
                interstitial.optBoolean("pause_ad_enabled", false),
                interstitial.optInt("pause_ad_min_play_seconds", 90),
                interstitial.optInt("pause_ad_cooldown_seconds", 300),
                interstitial.optInt("pause_ad_hourly_limit", 0),
                interstitial.optBoolean("reward_claim_enabled", false),
                interstitial.optBoolean("mid_episode_enabled", false),
                interstitial.optInt("mid_episode_trigger_seconds", 45),
                interstitial.optInt("mid_episode_hourly_limit", 0),
                interstitial.optBoolean("seek_enabled", false),
                interstitial.optInt("seek_hourly_limit", 0),
                interstitial.optBoolean("speed_enabled", false),
                interstitial.optInt("speed_hourly_limit", 0),
                interstitial.optInt("action_reward_threshold_gems", 3000),
                interstitial.optInt("episode_limit", 1),
                interstitial.optBoolean("drama_switch_enabled", true),
                interstitial.optInt("drama_switch_every_count", 3).coerceAtLeast(1),
                interstitial.optInt("drama_switch_hourly_limit", 6),
                interstitial.optBoolean("rewarded_fallback_enabled", true),
                nullableString(interstitial, "last_rv_at"),
                nullableString(interstitial, "last_interstitial_at"),
            ),
            gemDamping = GemDamping(
                damping.optLong("total_gems_earned", walletJson.optLong("total_gems_earned")),
                damping.optLong("cap"), damping.optLong("tier1_threshold"), damping.optLong("tier2_threshold"), damping.optLong("tier3_threshold"),
                damping.optDouble("tier1_multiplier", 1.0).toFloat(), damping.optDouble("tier2_multiplier", 1.0).toFloat(), damping.optDouble("tier3_multiplier", 1.0).toFloat(), damping.optDouble("tier4_multiplier", 1.0).toFloat(),
            ),
            withdrawal = Withdrawal(withdrawal.optBoolean("enabled"), withdrawal.optBoolean("eligible"), withdrawal.optString("reason"), withdrawal.optString("threshold", "0.00"), withdrawal.optString("threshold_usd_reference", withdrawal.optString("threshold", "0.00"))),
            achievements = achievements, crownProgress = crowns, rvCountToday = today.optInt("rv_count"), rvDailyLimit = today.optInt("rv_daily_limit", 20), lifecycleDay = user.optInt("lifecycle_day"), geoTier = user.optString("geo_tier", "t3"), ledger = ledger,
        )
    }

    private fun parseStringSet(array: JSONArray?): Set<String> = buildSet {
        for (index in 0 until (array?.length() ?: 0)) {
            array?.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun parseTiers(array: JSONArray?): List<Int> = buildList {
        for (index in 0 until (array?.length() ?: 0)) add(array!!.getJSONObject(index).optLong("gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun parseEpisodeRewards(array: JSONArray?): List<EpisodeReward> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array!!.getJSONObject(index)
            if (item.optString("state", "PENDING") == "PENDING") {
                add(EpisodeReward(item.optString("episode_id"), item.optLong("base_reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "PENDING", item.optBoolean("boosted")))
            }
        }
    }

    private fun parseTasks(array: JSONArray?): List<RewardTask> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array!!.getJSONObject(index)
            val state = item.optString("state", "PENDING")
            val status = when (state) { "PENDING" -> "CLAIMABLE"; "CLAIMED" -> "DOUBLE_READY"; else -> "CLAIMED" }
            add(RewardTask("cycle-${item.optInt("cycle_number")}", "十集追剧加奖 · 第 ${item.optInt("cycle_number")} 轮", "完成第 ${item.optInt("round_from")}-${item.optInt("round_to")} 集有效观看获得额外宝石", item.optInt("round_to"), item.optInt("round_to"), item.optLong("base_reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), state == "CLAIMED", status, item.optInt("cycle_number")))
        }
    }

    private fun parseLedger(array: JSONArray?): List<LedgerEntry> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array!!.getJSONObject(index)
            add(LedgerEntry(item.optString("type"), item.optLong("gems_delta"), item.optString("cash_wallet_delta", "0.00"), item.optString("available_balance_delta", "0.00"), item.optString("created_at")))
        }
    }

    private fun parseAchievements(array: JSONArray?): List<Achievement> = buildList {
        for (index in 0 until (array?.length() ?: 0)) { val item = array!!.getJSONObject(index); add(Achievement(item.optString("code"), item.optLong("gems"), item.optString("virtual_entitlement"))) }
    }

    private fun parseCrowns(array: JSONArray?): List<CrownProgress> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array!!.getJSONObject(index)
            fun metric(key: String): CrownMetric { val value = item.optJSONObject(key) ?: JSONObject(); return CrownMetric(value.optInt("current"), value.optInt("target")) }
            add(CrownProgress(
                item.optString("code"), item.optString("title"), item.optInt("level_rank", index + 1), item.optBoolean("earned"),
                item.optLong("gems"), item.optString("entitlement"), item.optInt("window_hours"), metric("rounds"),
                metric("signins"), metric("rewarded_ads"), item.optDouble("progress").toFloat(), item.optDouble("episode_reward_multiplier", 1.0).toFloat(),
                metric("watched_rounds"), item.optInt("ad_equivalent_rounds"), item.optInt("ads_per_episode", 2).coerceAtLeast(1),
            ))
        }
    }

    private fun nullableString(json: JSONObject, key: String): String? = json.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
