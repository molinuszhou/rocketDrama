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
 * 该客户端不会在本地计算宝石、签到或广告奖励。所有宝石账本、每日频控、轮次与提现资格均由
 * Rewards Service 的数据库事务结算；内容服务仅负责自有剧目录、播放会话和媒体权益。
 */
class RewardsApiClient(
    private val baseUrlProvider: () -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    data class RewardTask(
        val id: String,
        val title: String,
        val description: String,
        val progress: Int,
        val target: Int,
        val rewardCoins: Int,
        val rewardCashCents: Int = 0,
        val adDoubleEnabled: Boolean = false,
        val status: String,
        val cycleNumber: Int = 0,
    )

    data class Wallet(
        val coins: Long,
        val cashWallet: String,
        val availableBalance: String,
        val totalGemsEarned: Long,
        val exchangeRateGems: Long,
        val watchedEpisodes: Int,
        val signedInToday: Boolean,
        val checkinStreak: Int,
    )

    data class Ring(
        val enabled: Boolean = false,
        val progressSeconds: Int = 0,
        val targetSeconds: Int = 1,
        val claimable: Boolean = false,
        val rewardCoins: Int = 0,
        val adDoubleEnabled: Boolean = false,
    )

    data class UnlockRule(
        val freeEpisodesDefault: Int,
        val episodesPerReward: Int = 1,
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
        val ring: Ring,
        val unlock: UnlockRule,
        val unlockedEpisodeTargets: Set<String> = emptySet(),
        val checkinSignedToday: Boolean,
        val checkinStreak: Int,
        val checkinNextRewardCoins: Int,
        val checkinRewardTiers: List<Int>,
        val checkinAdDoubleEnabled: Boolean,
        val walletEligibilityCopy: String,
        val userCopy: Map<String, String> = emptyMap(),
        val chest: Chest,
        val interstitial: InterstitialPolicy,
        val withdrawal: Withdrawal,
        val achievements: List<Achievement>,
        val rvCountToday: Int,
        val rvDailyLimit: Int,
    ) {
        val rewardsEnabled: Boolean get() = true
        val ruleVersion: String get() = "postgresql-v2"
    }

    data class RewardAction(
        val idempotent: Boolean,
        val action: String,
        val gemsAdded: Long,
        val roundNumber: Int? = null,
        val canDouble: Boolean = false,
        val snapshot: Snapshot,
    )

    val isConfigured: Boolean
        get() = normalisedBaseUrl()?.let { it.startsWith("http://") || it.startsWith("https://") } == true

    fun bootstrap(userId: String, region: String, onSuccess: (Snapshot) -> Unit, onFailure: (String) -> Unit) {
        post("/v2/bootstrap", JSONObject().put("user_id", userId), onSuccess = { data -> onSuccess(parseSnapshot(data)) }, onFailure = onFailure)
    }

    fun recordWatchEvent(
        userId: String,
        dramaId: String,
        episodeId: String,
        validWatchSeconds: Int,
        onSuccess: (Snapshot) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        post(
            "/v2/watch-events",
            JSONObject()
                .put("user_id", userId)
                .put("event_id", "watch-${UUID.randomUUID()}")
                .put("drama_id", dramaId)
                .put("episode_id", episodeId)
                .put("watched_seconds", validWatchSeconds),
            onSuccess = { data -> onSuccess(parseSnapshot(data.getJSONObject("snapshot"))) },
            onFailure = onFailure,
        )
    }

    fun completeRewarded(
        userId: String,
        placementId: String,
        targetId: String? = null,
        episodeIndex: Int? = null,
        proof: String,
        onSuccess: (RewardAction) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val payload = JSONObject()
            .put("user_id", userId)
            .put("ad_event_id", "pangle-${UUID.randomUUID()}")
            .put("placement_id", placementId)
            .put("proof", proof)
            .put("timestamp_ms", System.currentTimeMillis())
        if (!targetId.isNullOrBlank()) payload.put("target_id", targetId)
        if (episodeIndex != null) payload.put("episode_index", episodeIndex)
        post("/v2/ads/rewarded/complete", payload, onSuccess = { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(
                RewardAction(
                    idempotent = data.optBoolean("idempotent"),
                    action = result.optString("action"),
                    gemsAdded = result.optLong("gems_added"),
                    roundNumber = result.optInt("round_number").takeIf { it > 0 },
                    canDouble = result.optBoolean("can_double"),
                    snapshot = parseSnapshot(data.getJSONObject("snapshot")),
                )
            )
        }, onFailure = onFailure)
    }

    fun checkin(userId: String, onSuccess: (RewardAction) -> Unit, onFailure: (String) -> Unit) {
        post("/v2/checkins", JSONObject().put("user_id", userId), onSuccess = { data ->
            val result = data.optJSONObject("result") ?: JSONObject()
            onSuccess(
                RewardAction(
                    idempotent = data.optBoolean("idempotent"),
                    action = "SIGN_IN",
                    gemsAdded = result.optLong("gems_added"),
                    snapshot = parseSnapshot(data.getJSONObject("snapshot")),
                )
            )
        }, onFailure = onFailure)
    }

    fun recordInterstitial(
        userId: String,
        placementId: String,
        onSuccess: (Snapshot) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        post(
            "/v2/interstitials/shown",
            JSONObject()
                .put("user_id", userId)
                .put("event_id", "int-${UUID.randomUUID()}")
                .put("placement_id", placementId),
            onSuccess = { data -> onSuccess(parseSnapshot(data.getJSONObject("snapshot"))) },
            onFailure = onFailure,
        )
    }

    private fun post(path: String, body: JSONObject, onSuccess: (JSONObject) -> Unit, onFailure: (String) -> Unit) {
        val baseUrl = normalisedBaseUrl()
        if (baseUrl == null) {
            onFailure("独立积分服务尚未配置")
            return
        }
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = onFailure("积分服务网络错误：${error.message ?: "无法连接"}")
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val bodyText = it.body?.string().orEmpty()
                    val json = runCatching { JSONObject(bodyText) }.getOrNull()
                    if (!it.isSuccessful) {
                        onFailure(json?.optString("msg")?.takeIf(String::isNotBlank) ?: "积分服务请求失败（HTTP ${it.code}）")
                        return
                    }
                    val data = json?.optJSONObject("data")
                    if (json?.optInt("code", -1) != 0 || data == null) {
                        onFailure(json?.optString("msg")?.takeIf(String::isNotBlank) ?: "积分服务响应无效")
                        return
                    }
                    runCatching { onSuccess(data) }.onFailure { error -> onFailure("积分服务响应解析失败：${error.message}") }
                }
            }
        })
    }

    private fun normalisedBaseUrl(): String? = baseUrlProvider().trim().trimEnd('/').takeIf {
        it.startsWith("http://") || it.startsWith("https://")
    }

    private fun parseSnapshot(json: JSONObject): Snapshot {
        val walletJson = json.getJSONObject("wallet")
        val today = json.getJSONObject("today")
        val checkin = json.getJSONObject("checkin")
        val unlock = json.getJSONObject("episode_unlock")
        val chest = json.getJSONObject("chest")
        val interstitial = json.getJSONObject("interstitial")
        val withdrawal = json.getJSONObject("withdrawal")
        val tiersArray = checkin.optJSONArray("tiers") ?: JSONArray()
        val tiers = buildList {
            for (index in 0 until tiersArray.length()) add(tiersArray.getJSONObject(index).optLong("gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        val pending = json.optJSONArray("pending_claims") ?: JSONArray()
        val taskList = buildList {
            for (index in 0 until pending.length()) {
                val item = pending.getJSONObject(index)
                val state = item.optString("state", "PENDING")
                val status = when (state) {
                    "PENDING" -> "CLAIMABLE"
                    "CLAIMED" -> "DOUBLE_READY"
                    else -> "CLAIMED"
                }
                add(
                    RewardTask(
                        id = "cycle-${item.optInt("cycle_number")}",
                        title = "${item.optInt("round_to")} 集追剧奖励",
                        description = "完成第 ${item.optInt("round_from")}-${item.optInt("round_to")} 轮后可领取宝石",
                        progress = item.optInt("round_to"),
                        target = item.optInt("round_to"),
                        rewardCoins = item.optLong("base_reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        adDoubleEnabled = state == "CLAIMED",
                        status = status,
                        cycleNumber = item.optInt("cycle_number"),
                    )
                )
            }
        }
        val achievementsArray = json.optJSONArray("achievements") ?: JSONArray()
        val achievements = buildList {
            for (index in 0 until achievementsArray.length()) {
                val item = achievementsArray.getJSONObject(index)
                add(Achievement(item.optString("code"), item.optLong("gems"), item.optString("virtual_entitlement")))
            }
        }
        val signedToday = checkin.optBoolean("signed_today")
        return Snapshot(
            businessDate = json.optString("business_date"),
            wallet = Wallet(
                coins = walletJson.optLong("gems_balance"),
                cashWallet = walletJson.optString("cash_wallet", "0.00"),
                availableBalance = walletJson.optString("available_balance", "0.00"),
                totalGemsEarned = walletJson.optLong("total_gems_earned"),
                exchangeRateGems = walletJson.optLong("exchange_rate_gems", 150000L),
                watchedEpisodes = today.optInt("episode_count"),
                signedInToday = signedToday,
                checkinStreak = checkin.optInt("streak_day", 1),
            ),
            tasks = taskList,
            ring = Ring(),
            unlock = UnlockRule(
                freeEpisodesDefault = unlock.optInt("free_episodes_count", 3),
                dailyRewardedUnlockLimit = today.optInt("rv_daily_limit", 20),
                title = "观看广告解锁本集",
                description = "完整观看广告后继续追剧并计入宝石奖励轮次",
                validWatchSeconds = unlock.optInt("valid_watch_seconds", 60),
            ),
            checkinSignedToday = signedToday,
            checkinStreak = checkin.optInt("streak_day", 1),
            checkinNextRewardCoins = checkin.optLong("today_base_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            checkinRewardTiers = tiers,
            checkinAdDoubleEnabled = signedToday && checkin.optLong("today_double_gems") == 0L,
            walletEligibilityCopy = withdrawal.optString("reason"),
            chest = Chest(
                claimable = chest.optBoolean("claimable"),
                claimed = chest.optBoolean("claimed"),
                requiresCheckin = chest.optBoolean("requires_checkin", true),
                rewardCoins = chest.optLong("reward_gems").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ),
            interstitial = InterstitialPolicy(
                cooldownSeconds = interstitial.optInt("cooldown_seconds", 90),
                hourlyLimit = interstitial.optInt("hourly_limit", 3),
                lastRvAt = interstitial.optString("last_rv_at").takeIf { it.isNotBlank() && it != "null" },
                lastInterstitialAt = interstitial.optString("last_interstitial_at").takeIf { it.isNotBlank() && it != "null" },
            ),
            withdrawal = Withdrawal(
                enabled = withdrawal.optBoolean("enabled"),
                eligible = withdrawal.optBoolean("eligible"),
                reason = withdrawal.optString("reason"),
                threshold = withdrawal.optString("threshold", "0.00"),
            ),
            achievements = achievements,
            rvCountToday = today.optInt("rv_count"),
            rvDailyLimit = today.optInt("rv_daily_limit", 20),
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
