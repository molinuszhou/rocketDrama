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
    )

    data class Wallet(
        val coins: Long,
        val cashWallet: String,
        val lockedBalance: String,
        val availableBalance: String,
        val totalGemsEarned: Long,
        val exchangeRateGems: Long,
        val minimumExchangeGems: Long,
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
    )

    data class UnlockRule(
        val freeEpisodesDefault: Int,
        val dailyRewardedUnlockLimit: Int,
        val title: String,
        val description: String,
        val validWatchSeconds: Int,
    )

    data class Chest(
        val claimable: Boolean,
        val claimed: Boolean,
        val requiresCheckin: Boolean,
        val rewardCoins: Int,
    )

    data class InterstitialPolicy(
        val cooldownSeconds: Int,
        val hourlyLimit: Int,
        val lastRvAt: String?,
        val lastInterstitialAt: String?,
    )

    data class Withdrawal(
        val enabled: Boolean,
        val eligible: Boolean,
        val reason: String,
        val threshold: String,
    )

    data class Achievement(val code: String, val gems: Long, val entitlement: String)

    data class Snapshot(
        val businessDate: String,
        val wallet: Wallet,
        val tasks: List<RewardTask>,
        val pendingEpisodeRewards: List<EpisodeReward>,
        val tenRoundSize: Int,
        val episodesToNextTenRound: Int,
        val nextTenRoundPreviewCoins: Int,
        val ring: Ring,
        val welcome: WelcomeReward,
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
        val withdrawal: Withdrawal,
        val achievements: List<Achievement>,
        val crownProgress: List<CrownProgress>,
        val rvCountToday: Int,
        val rvDailyLimit: Int,
        val lifecycleDay: Int,
        val geoTier: String,
    ) {
        val rewardsEnabled: Boolean get() = true
        val ruleVersion: String get() = "postgresql-v4"
    }

    data class WatchReward(
        val snapshot: Snapshot,
        val episodeRewardCoins: Int,
        val tenRoundRewardCoins: Int,
    )

    data class RewardAction(
        val idempotent: Boolean,
        val action: String,
        val gemsAdded: Long,
        val snapshot: Snapshot,
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
        val payload = JSONObject().put("user_id", userId).put("ad_event_id", "pangle-${UUID.randomUUID()}")
            .put("placement_id", placementId).put("proof", proof).put("timestamp_ms", System.currentTimeMillis())
        if (!targetId.isNullOrBlank()) payload.put("target_id", targetId)
        if (episodeIndex != null) payload.put("episode_index", episodeIndex)
        post("/v2/ads/rewarded/complete", payload, { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(RewardAction(data.optBoolean("idempotent"), result.optString("action"), result.optLong("gems_added"), parseSnapshot(data.getJSONObject("snapshot"))))
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

    fun exchangeGems(userId: String, gems: Long, onSuccess: (Snapshot) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/wallet/exchange",
        JSONObject().put("user_id", userId).put("gems", gems).put("reference_id", "exchange-${UUID.randomUUID()}"),
        { data -> onSuccess(parseSnapshot(data.getJSONObject("snapshot"))) }, onFailure,
    )

    fun recordInterstitial(userId: String, placementId: String, onSuccess: (Snapshot) -> Unit, onFailure: (String) -> Unit) = post(
        "/v2/interstitials/shown",
        JSONObject().put("user_id", userId).put("event_id", "int-${UUID.randomUUID()}").put("placement_id", placementId),
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
        val chest = json.getJSONObject("chest")
        val interstitial = json.getJSONObject("interstitial")
        val withdrawal = json.getJSONObject("withdrawal")
        val user = json.optJSONObject("user") ?: JSONObject()
        val release = walletJson.optJSONObject("release") ?: JSONObject()
        val tiers = parseTiers(checkin.optJSONArray("tiers"))
        val signedToday = checkin.optBoolean("signed_today")
        val todayIndex = checkin.optInt("today_streak", if (signedToday) checkin.optInt("completed_streak", 1) else 1)
        val taskList = parseTasks(json.optJSONArray("pending_claims"))
        val pendingEpisodeRewards = parseEpisodeRewards(json.optJSONArray("episode_reward_claims"))
        val achievements = parseAchievements(json.optJSONArray("achievements"))
        val crowns = parseCrowns(json.optJSONArray("crown_progress"))
        return Snapshot(
            businessDate = json.optString("business_date"),
            wallet = Wallet(
                coins = walletJson.optLong("gems_balance"), cashWallet = walletJson.optString("cash_wallet", "0.00"),
                lockedBalance = walletJson.optString("wallet_locked", walletJson.optString("cash_wallet", "0.00")),
                availableBalance = walletJson.optString("available_balance", "0.00"),
                totalGemsEarned = walletJson.optLong("total_gems_earned"), exchangeRateGems = walletJson.optLong("exchange_rate_gems", 1_500_000L),
                minimumExchangeGems = walletJson.optLong("minimum_exchange_gems", 10_000L), watchedEpisodes = today.optInt("episode_count"),
                signedInToday = signedToday, checkinStreak = checkin.optInt("completed_streak"),
                release = WalletRelease(release.optBoolean("released_today"), release.optString("daily_release", "0.00"),
                    release.optInt("boost_used"), release.optInt("boost_cap", 3), release.optString("boost_amount", "0.00"),
                    release.optInt("estimated_days_remaining"), release.optInt("pending_days")),
            ),
            tasks = taskList,
            pendingEpisodeRewards = pendingEpisodeRewards,
            tenRoundSize = today.optInt("ten_round_size", 10).coerceAtLeast(1),
            episodesToNextTenRound = today.optInt("episodes_to_next_ten_round", 10).coerceAtLeast(0),
            nextTenRoundPreviewCoins = today.optLong("next_ten_round_preview_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ring = (json.optJSONObject("ring") ?: JSONObject()).let { Ring(it.optBoolean("enabled", true), it.optInt("progress"), it.optInt("target", 1).coerceAtLeast(1), it.optBoolean("claimable"), it.optLong("reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), it.optString("label", "攒币")) },
            welcome = (json.optJSONObject("welcome") ?: JSONObject()).let { WelcomeReward(it.optBoolean("available"), it.optBoolean("claimed"), it.optLong("gems")) },
            unlock = UnlockRule(unlock.optInt("free_episodes_count", 3), today.optInt("rv_daily_limit", 20), "看广告解锁", "完整观看广告后继续追剧", unlock.optInt("valid_watch_seconds", 60)),
            checkinSignedToday = signedToday, checkinStreak = checkin.optInt("completed_streak"), checkinTodayIndex = todayIndex,
            checkinNextRewardCoins = checkin.optLong("today_base_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), checkinRewardTiers = tiers,
            checkinAdDoubleEnabled = signedToday && checkin.optLong("today_double_gems") == 0L,
            walletEligibilityCopy = withdrawal.optString("reason"), chest = Chest(chest.optBoolean("claimable"), chest.optBoolean("claimed"), chest.optBoolean("requires_checkin", true), chest.optLong("reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
            interstitial = InterstitialPolicy(interstitial.optInt("cooldown_seconds", 90), interstitial.optInt("hourly_limit", 3), nullableString(interstitial, "last_rv_at"), nullableString(interstitial, "last_interstitial_at")),
            withdrawal = Withdrawal(withdrawal.optBoolean("enabled"), withdrawal.optBoolean("eligible"), withdrawal.optString("reason"), withdrawal.optString("threshold", "0.00")),
            achievements = achievements, crownProgress = crowns, rvCountToday = today.optInt("rv_count"), rvDailyLimit = today.optInt("rv_daily_limit", 20), lifecycleDay = user.optInt("lifecycle_day"), geoTier = user.optString("geo_tier", "t3"),
        )
    }

    private fun parseTiers(array: JSONArray?): List<Int> = buildList {
        for (index in 0 until (array?.length() ?: 0)) add(array!!.getJSONObject(index).optLong("gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun parseEpisodeRewards(array: JSONArray?): List<EpisodeReward> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array!!.getJSONObject(index)
            if (item.optString("state", "PENDING") == "PENDING") {
                add(EpisodeReward(item.optString("episode_id"), item.optLong("base_reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "PENDING"))
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

    private fun parseAchievements(array: JSONArray?): List<Achievement> = buildList {
        for (index in 0 until (array?.length() ?: 0)) { val item = array!!.getJSONObject(index); add(Achievement(item.optString("code"), item.optLong("gems"), item.optString("virtual_entitlement"))) }
    }

    private fun parseCrowns(array: JSONArray?): List<CrownProgress> = buildList {
        for (index in 0 until (array?.length() ?: 0)) {
            val item = array!!.getJSONObject(index)
            fun metric(key: String): CrownMetric { val value = item.optJSONObject(key) ?: JSONObject(); return CrownMetric(value.optInt("current"), value.optInt("target")) }
            add(CrownProgress(item.optString("code"), item.optString("title"), item.optInt("level_rank", index + 1), item.optBoolean("earned"), item.optLong("gems"), item.optString("entitlement"), item.optInt("window_hours"), metric("rounds"), metric("signins"), metric("rewarded_ads"), item.optDouble("progress").toFloat()))
        }
    }

    private fun nullableString(json: JSONObject, key: String): String? = json.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
