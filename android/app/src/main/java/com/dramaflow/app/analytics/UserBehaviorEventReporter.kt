package com.dramaflow.app.analytics

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.presentation.INSTALLATION_KEY
import com.dramaflow.app.presentation.PREFERENCES
import com.dramaflow.app.presentation.SESSION_TOKEN_KEY
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Privacy-minimised, durable user-funnel telemetry.
 *
 * The caller only validates and schedules telemetry. JSON construction, SharedPreferences access,
 * queue disk I/O and request construction run on one serial worker and are all best effort, so a
 * storage or analytics failure cannot affect startup, playback, ads or reward settlement.
 */
internal class UserBehaviorEventReporter private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences? by lazy {
        runCatching { appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
            .onFailure { logFailure("open behavior preferences", it) }
            .getOrNull()
    }
    private val httpClient by lazy { OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queueWorker = Executors.newSingleThreadExecutor(BehaviorQueueThreadFactory())
    private val queueDirectory by lazy { File(appContext.filesDir, QUEUE_DIRECTORY) }
    private val queueStateFile by lazy { File(queueDirectory, QUEUE_STATE_FILE_NAME) }
    private val legacyQueueFile by lazy { File(queueDirectory, QUEUE_FILE_NAME) }
    private val sessionId = "session-${UUID.randomUUID()}"
    private val stagedCriticalKeys = mutableSetOf<String>()
    private val stagedCriticalLock = Any()

    // The fields below are accessed only by queueWorker.
    private var queueLoaded = false
    private var queueState = BehaviorQueueState()
    private var sending = false
    private var retryScheduled = false
    private var retryGeneration = 0L
    private var consecutiveFailureCount = 0
    private var lastUnauthorizedToken: String? = null
    private var earlyRanksLoaded = false
    // Publish immutable snapshots. UI callbacks must never wait for rank storage or its lock.
    @Volatile private var earlyRanks: Map<String, Int> = emptyMap()
    private var cachedInstallationId: String? = null

    /**
     * Called after a 401 at most once per distinct persisted token. The integration must request a
     * fresh Rewards bootstrap/session and call [flushPending] after storing the new token.
     */
    @Volatile
    private var sessionRefreshHook: ((String?) -> Unit)? = null

    init {
        runCatching { registerNetworkRecovery() }
            .onFailure { logFailure("network recovery registration", it) }
        dispatch {
            if (loadQueueState()) flushPendingOnWorker()
        }
    }

    fun record(
        eventCategory: String,
        eventName: String,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        safely("record behavior event") {
            recordInternal(eventCategory, eventName, properties)
        }
    }

    /** Records only the first observed start for each of a user's first twenty distinct videos. */
    fun recordEarlyVideoStarted(
        contentKey: String,
        provider: String,
        episodeIndex: Int,
        dramaId: String,
        episodeId: String,
        contentTitle: String,
    ): Int? = recordEarlyVideo(
        contentKey = contentKey,
        deliveryType = "started",
        eventName = "EARLY_VIDEO_STARTED",
        provider = provider,
        episodeIndex = episodeIndex,
        dramaId = dramaId,
        episodeId = episodeId,
        contentTitle = contentTitle,
    )

    /** Records only the first successful end for a previously observed early video. */
    fun recordEarlyVideoCompleted(
        contentKey: String,
        provider: String,
        episodeIndex: Int,
        dramaId: String,
        episodeId: String,
        contentTitle: String,
    ): Int? = recordEarlyVideo(
        contentKey = contentKey,
        deliveryType = "completed",
        eventName = "EARLY_VIDEO_COMPLETED",
        provider = provider,
        episodeIndex = episodeIndex,
        dramaId = dramaId,
        episodeId = episodeId,
        contentTitle = contentTitle,
    )

    fun currentEarlyVideoRank(contentKey: String): Int? = runCatching {
        earlyRanks[contentKey.trim()]
    }.onFailure { logFailure("read early video rank", it) }.getOrNull()

    /** Safe to call on application/Activity startup and after a fresh Rewards session is stored. */
    fun flushPending() {
        dispatch {
            retryGeneration += 1L
            retryScheduled = false
            flushPendingOnWorker()
        }
    }

    /**
     * Optional integration point for the owner of RewardsApiClient. The hook must be non-blocking;
     * it should bootstrap/refresh the session and invoke [flushPending] after persisting a token.
     */
    fun setSessionRefreshHook(hook: ((String?) -> Unit)?) {
        sessionRefreshHook = hook
    }

    private fun recordEarlyVideo(
        contentKey: String,
        deliveryType: String,
        eventName: String,
        provider: String,
        episodeIndex: Int,
        dramaId: String,
        episodeId: String,
        contentTitle: String,
    ): Int? = runCatching {
        val normalizedKey = contentKey.trim().takeIf { it.isNotEmpty() } ?: return@runCatching null
        val occurredAt = System.currentTimeMillis()
        dispatch {
            val rank = earlyVideoRank(normalizedKey, createIfMissing = deliveryType == "started") ?: return@dispatch
            val criticalKey = "$deliveryType:$normalizedKey"
            if (!reserveCriticalKey(criticalKey)) return@dispatch
            val input = BehaviorEventInput(
                eventId = "behavior-${UUID.randomUUID()}",
                category = "PLAYBACK",
                name = eventName,
                properties = normalizeProperties(mapOf(
                    "early_video_rank" to rank,
                    "provider" to provider,
                    "episode_index" to episodeIndex,
                    "drama_id" to dramaId,
                    "episode_id" to episodeId,
                    "content_title" to contentTitle,
                )),
                occurredAtMillis = occurredAt,
                criticalKey = criticalKey,
            )
            enqueueOnWorker(input)
        }
        currentEarlyVideoRank(normalizedKey)
    }.onFailure { logFailure("record early video", it) }.getOrNull()

    private fun recordInternal(
        eventCategory: String,
        eventName: String,
        properties: Map<String, Any?>,
        criticalKey: String? = null,
    ): Boolean {
        val category = eventCategory.trim().uppercase()
        val name = eventName.trim().uppercase()
        if (category !in EVENT_CATEGORIES || !EVENT_NAME.matches(name) || "PROGRESS" in name) return false
        val input = BehaviorEventInput(
            eventId = "behavior-${UUID.randomUUID()}",
            category = category,
            name = name,
            properties = normalizeProperties(properties),
            occurredAtMillis = System.currentTimeMillis(),
            criticalKey = criticalKey,
        )
        return dispatch { enqueueOnWorker(input) }
    }

    private fun enqueueOnWorker(input: BehaviorEventInput) {
        if (!loadQueueState()) {
            input.criticalKey?.let(::releaseCriticalKey)
            return
        }
        val event = runCatching { input.toQueueEvent() }
            .onFailure { logFailure("build behavior event", it) }
            .getOrNull() ?: run {
            input.criticalKey?.let(::releaseCriticalKey)
            return
        }
        val candidate = BehaviorEventQueuePolicy.enqueue(queueState, event, MAX_PENDING_EVENTS)
        if (candidate == queueState) {
            input.criticalKey?.let(::releaseCriticalKey)
            return
        }
        if (!persistQueueState(candidate)) {
            input.criticalKey?.let(::releaseCriticalKey)
            return
        }
        queueState = candidate
        flushPendingOnWorker()
    }

    private fun flushPendingOnWorker() {
        if (sending || retryScheduled || !loadQueueState()) return
        val event = queueState.events.firstOrNull() ?: return
        val baseUrl = BuildConfig.REWARDS_API_BASE_URL.trim().trimEnd('/')
        val token = currentSessionToken()
        if ((!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) || token.isNullOrBlank()) return
        sending = true
        send(event, baseUrl, token)
    }

    private fun send(event: BehaviorQueueEvent, baseUrl: String, token: String) {
        val request = runCatching {
            Request.Builder()
                .url("$baseUrl/v2/analytics/behavior-events")
                .post(event.payload.toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("X-Rewards-Session", "v1")
                .header("Authorization", "Bearer $token")
                .build()
        }.onFailure { logFailure("build behavior request", it) }.getOrNull()
        if (request == null) {
            scheduleRecoveryRetryOnWorker()
            return
        }
        runCatching {
            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    dispatch { scheduleRecoveryRetryOnWorker() }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            DeliveryResult(
                                accepted = it.isSuccessful && runCatching {
                                    JSONObject(it.body?.string().orEmpty()).optInt("code", -1) == 0
                                }.getOrDefault(false),
                                responseCode = it.code,
                            )
                        }
                    }.onFailure { logFailure("read behavior response", it) }
                        .getOrDefault(DeliveryResult(accepted = false, responseCode = null))
                    dispatch { handleDeliveryResult(event, token, result) }
                }
            })
        }.onFailure {
            logFailure("enqueue behavior request", it)
            scheduleRecoveryRetryOnWorker()
        }
    }

    private fun handleDeliveryResult(event: BehaviorQueueEvent, token: String, result: DeliveryResult) {
        if (result.accepted) {
            val candidate = BehaviorEventQueuePolicy.acknowledge(queueState, event.eventId, MAX_PENDING_EVENTS)
            if (candidate != queueState && persistQueueState(candidate)) {
                queueState = candidate
                consecutiveFailureCount = 0
                lastUnauthorizedToken = null
                sending = false
                flushPendingOnWorker()
            } else {
                // The server may already have accepted this event; retain the stable event id until
                // the local ACK snapshot is durable so a retry remains idempotent server-side.
                scheduleRecoveryRetryOnWorker()
            }
            return
        }
        if (result.responseCode == 401) {
            requestSessionRefresh(token)
            scheduleRecoveryRetryOnWorker(AUTH_RETRY_DELAY_MS)
        } else {
            // Retain all non-successful deliveries, including 4xx responses. Analytics must never
            // delete evidence merely because the client cannot currently classify a server error.
            scheduleRecoveryRetryOnWorker()
        }
    }

    private fun requestSessionRefresh(rejectedToken: String) {
        if (lastUnauthorizedToken == rejectedToken) return
        lastUnauthorizedToken = rejectedToken
        runCatching { sessionRefreshHook?.invoke(rejectedToken) }
            .onFailure { logFailure("request Rewards session refresh", it) }
    }

    private fun scheduleRecoveryRetryOnWorker(delayOverride: Long? = null) {
        sending = false
        if (retryScheduled) return
        retryScheduled = true
        val generation = ++retryGeneration
        val delayMillis = delayOverride ?: RETRY_DELAYS_MS[consecutiveFailureCount.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        if (delayOverride == null) consecutiveFailureCount += 1
        val scheduled = runCatching {
            mainHandler.postDelayed({
                dispatch {
                    if (generation != retryGeneration) return@dispatch
                    retryScheduled = false
                    flushPendingOnWorker()
                }
            }, delayMillis)
            true
        }.onFailure { logFailure("schedule behavior retry", it) }.getOrDefault(false)
        if (!scheduled) retryScheduled = false
    }

    private fun registerNetworkRecovery() {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) flushPending()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)
    }

    private fun loadQueueState(): Boolean {
        if (queueLoaded) return true
        val hadStateFile = queueStateFile.exists()
        val loaded = runCatching {
            if (hadStateFile) {
                decodeQueueState(queueStateFile.readText(Charsets.UTF_8))
                    ?: throw IOException("behavior queue state is invalid")
            } else {
                migrateLegacyQueue()
            }
        }.onFailure { logFailure("load behavior queue", it) }.getOrNull() ?: return false
        val normalized = BehaviorEventQueuePolicy.normalize(loaded, MAX_PENDING_EVENTS)
        if (!persistQueueState(normalized)) return false
        queueState = normalized
        queueLoaded = true
        if (!hadStateFile) runCatching { legacyQueueFile.delete() }
        return true
    }

    private fun migrateLegacyQueue(): BehaviorQueueState {
        if (!legacyQueueFile.exists()) return BehaviorQueueState()
        val legacyEvents = legacyQueueFile.useLines(Charsets.UTF_8) { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val payload = JSONObject(line)
                    val eventId = payload.optString("event_id").trim()
                    eventId.takeIf { it.isNotEmpty() }?.let {
                        BehaviorQueueEvent(it, payload.toString(), criticalKeyFor(payload))
                    }
                }.getOrNull()
            }.toList()
        }
        return BehaviorEventQueuePolicy.migrateLegacy(
            legacyEvents = legacyEvents,
            acknowledgedCriticalKeys = emptySet(),
            maxNormalEvents = MAX_PENDING_EVENTS,
        )
    }

    private fun decodeQueueState(raw: String): BehaviorQueueState? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("version", 0) != QUEUE_STATE_VERSION) return@runCatching null
        val events = mutableListOf<BehaviorQueueEvent>()
        val eventArray = root.optJSONArray("events") ?: JSONArray()
        for (index in 0 until eventArray.length()) {
            val event = eventArray.optJSONObject(index) ?: continue
            val eventId = event.optString("event_id").trim()
            val payload = event.optString("payload")
            if (eventId.isNotEmpty() && payload.isNotEmpty()) {
                events += BehaviorQueueEvent(
                    eventId = eventId,
                    payload = payload,
                    criticalKey = event.optString("critical_key").trim().takeIf { it.isNotEmpty() },
                )
            }
        }
        val earlyStates = mutableMapOf<String, EarlyVideoDeliveryState>()
        val stateObject = root.optJSONObject("early_video_delivery") ?: JSONObject()
        val keys = stateObject.keys()
        while (keys.hasNext()) {
            val key = keys.next().trim()
            val value = runCatching { EarlyVideoDeliveryState.valueOf(stateObject.optString(key)) }.getOrNull()
            if (key.isNotEmpty() && value != null) earlyStates[key] = value
        }
        BehaviorQueueState(events, earlyStates)
    }.getOrNull()

    private fun persistQueueState(candidate: BehaviorQueueState): Boolean {
        val temporaryFile = File(queueDirectory, "$QUEUE_STATE_FILE_NAME.tmp")
        return runCatching {
            if (!queueDirectory.exists() && !queueDirectory.mkdirs()) return@runCatching false
            val serialized = encodeQueueState(candidate)
            FileOutputStream(temporaryFile).use { output ->
                output.write(serialized.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporaryFile.toPath(),
                queueStateFile.toPath(),
                REPLACE_EXISTING,
                ATOMIC_MOVE,
            )
            true
        }.onFailure { logFailure("persist behavior queue", it) }
            .getOrDefault(false)
            .also { persisted -> if (!persisted) runCatching { temporaryFile.delete() } }
    }

    private fun encodeQueueState(state: BehaviorQueueState): String = JSONObject().apply {
        put("version", QUEUE_STATE_VERSION)
        put("events", JSONArray().apply {
            state.events.forEach { event ->
                put(JSONObject().apply {
                    put("event_id", event.eventId)
                    put("payload", event.payload)
                    event.criticalKey?.let { put("critical_key", it) }
                })
            }
        })
        put("early_video_delivery", JSONObject().apply {
            state.earlyVideoStates.forEach { (key, value) -> put(key, value.name) }
        })
    }.toString()

    private fun BehaviorEventInput.toQueueEvent(): BehaviorQueueEvent {
        val payload = JSONObject()
            .put("user_id", anonymousInstallationId())
            .put("event_id", eventId)
            .put("session_id", sessionId)
            .put("event_category", category)
            .put("event_name", name)
            .put("properties", JSONObject(properties))
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("occurred_at_ms", occurredAtMillis)
        return BehaviorQueueEvent(eventId, payload.toString(), criticalKey)
    }

    private fun criticalKeyFor(payload: JSONObject): String? {
        val type = when (payload.optString("event_name")) {
            "EARLY_VIDEO_STARTED" -> "started"
            "EARLY_VIDEO_COMPLETED" -> "completed"
            else -> return null
        }
        val properties = payload.optJSONObject("properties") ?: return null
        val dramaId = properties.optString("drama_id").trim()
        val episodeId = properties.optString("episode_id").trim()
        return if (dramaId.isNotEmpty() && episodeId.isNotEmpty()) "$type:$dramaId:$episodeId" else null
    }

    private fun anonymousInstallationId(): String {
        cachedInstallationId?.let { return it }
        val generated = UUID.randomUUID().toString()
        val value = runCatching {
            preferences?.getString(INSTALLATION_KEY, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: generated.also { preferences?.edit()?.putString(INSTALLATION_KEY, it)?.apply() }
        }.onFailure { logFailure("read installation id", it) }.getOrDefault(generated)
        cachedInstallationId = value
        return value
    }

    private fun currentSessionToken(): String? = runCatching {
        preferences?.getString(SESSION_TOKEN_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }.onFailure { logFailure("read Rewards session", it) }.getOrNull()

    private fun earlyVideoRank(contentKey: String, createIfMissing: Boolean): Int? {
        val normalizedKey = contentKey.trim().takeIf { it.isNotEmpty() } ?: return null
        if (!earlyRanksLoaded) {
            earlyRanks = runCatching {
                val json = JSONObject(preferences?.getString(EARLY_VIDEO_RANKS, "{}") ?: "{}")
                buildMap {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        json.optInt(key, 0).takeIf { it in 1..MAX_EARLY_VIDEOS }?.let { put(key, it) }
                    }
                }
            }.onFailure { logFailure("read early video ranks", it) }.getOrDefault(emptyMap())
            earlyRanksLoaded = true
        }
        earlyRanks[normalizedKey]?.let { return it }
        if (!createIfMissing || earlyRanks.size >= MAX_EARLY_VIDEOS) return null
        val rank = (1..MAX_EARLY_VIDEOS).first { candidate -> candidate !in earlyRanks.values }
        earlyRanks = earlyRanks + (normalizedKey to rank)
        runCatching {
            preferences?.edit()?.putString(EARLY_VIDEO_RANKS, JSONObject(earlyRanks).toString())?.apply()
        }.onFailure { logFailure("persist early video rank", it) }
        return rank
    }

    private fun reserveCriticalKey(key: String): Boolean = synchronized(stagedCriticalLock) {
        stagedCriticalKeys.add(key)
    }

    private fun releaseCriticalKey(key: String) {
        synchronized(stagedCriticalLock) { stagedCriticalKeys.remove(key) }
    }

    private fun normalizeProperties(rawProperties: Map<String, Any?>): Map<String, Any> = runCatching {
        buildMap {
            rawProperties.entries.forEach { (rawKey, rawValue) ->
                if (size >= MAX_PROPERTIES) return@forEach
                val key = rawKey.trim().lowercase().take(MAX_PROPERTY_KEY_LENGTH)
                if (key.isEmpty() || key.any { !it.isLetterOrDigit() && it != '_' }) return@forEach
                when (rawValue) {
                    is Boolean, is Int, is Long -> put(key, rawValue)
                    is Float -> if (rawValue.isFinite()) put(key, rawValue.toDouble())
                    is Double -> if (rawValue.isFinite()) put(key, rawValue)
                    is String -> rawValue.trim().take(MAX_PROPERTY_VALUE_LENGTH).takeIf { it.isNotEmpty() }?.let { put(key, it) }
                }
            }
        }
    }.onFailure { logFailure("normalize behavior properties", it) }.getOrDefault(emptyMap())

    private fun dispatch(block: () -> Unit): Boolean = runCatching {
        queueWorker.execute {
            runCatching(block).onFailure { logFailure("behavior queue worker", it) }
        }
        true
    }.onFailure { logFailure("schedule behavior work", it) }.getOrDefault(false)

    private fun safely(operation: String, block: () -> Unit) {
        runCatching(block).onFailure { logFailure(operation, it) }
    }

    private fun logFailure(operation: String, error: Throwable) {
        Log.w(TAG, "Behavior telemetry skipped: $operation", error)
    }

    private data class BehaviorEventInput(
        val eventId: String,
        val category: String,
        val name: String,
        val properties: Map<String, Any>,
        val occurredAtMillis: Long,
        val criticalKey: String?,
    )

    private data class DeliveryResult(
        val accepted: Boolean,
        val responseCode: Int?,
    )

    private class BehaviorQueueThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, "behavior-event-queue").apply {
            isDaemon = true
        }
    }

    companion object {
        private const val TAG = "UserBehaviorReporter"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EVENT_CATEGORIES = setOf("APP", "SDK", "ONBOARDING", "PLAYBACK", "AD", "NETWORK", "REWARDS", "UI")
        val EVENT_NAME = Regex("^[A-Z][A-Z0-9_]{1,79}$")
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 10_000L, 60_000L, 300_000L)
        const val AUTH_RETRY_DELAY_MS = 60_000L
        const val QUEUE_DIRECTORY = "user_behavior_events"
        /** Legacy NDJSON file, imported once into [QUEUE_STATE_FILE_NAME]. */
        const val QUEUE_FILE_NAME = "pending.ndjson"
        const val QUEUE_STATE_FILE_NAME = "pending-state.json"
        const val QUEUE_STATE_VERSION = 2
        /** Ordinary telemetry cap; protected first-video records are retained in addition. */
        const val MAX_PENDING_EVENTS = 500
        const val MAX_PROPERTIES = 16
        const val MAX_PROPERTY_KEY_LENGTH = 40
        const val MAX_PROPERTY_VALUE_LENGTH = 512
        const val MAX_EARLY_VIDEOS = 20
        const val EARLY_VIDEO_RANKS = "behavior_early_video_ranks"

        @Volatile private var instance: UserBehaviorEventReporter? = null

        fun get(context: Context): UserBehaviorEventReporter = instance ?: synchronized(this) {
            instance ?: UserBehaviorEventReporter(context.applicationContext).also { instance = it }
        }
    }
}
