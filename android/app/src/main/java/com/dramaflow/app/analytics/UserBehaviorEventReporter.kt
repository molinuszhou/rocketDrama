package com.dramaflow.app.analytics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.presentation.INSTALLATION_KEY
import com.dramaflow.app.presentation.PREFERENCES
import com.dramaflow.app.presentation.SESSION_TOKEN_KEY
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Privacy-minimised, durable user-funnel telemetry.
 *
 * Events are stored locally before delivery and are replayed after a Rewards session token is
 * issued or Android announces a validated network. This queue is deliberately independent of
 * reward settlement: losing telemetry must never block playback, ads or a financial reward.
 */
internal class UserBehaviorEventReporter private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queueDirectory = File(appContext.filesDir, QUEUE_DIRECTORY)
    private val queueFile = File(queueDirectory, QUEUE_FILE_NAME)
    private val sessionId = "session-${UUID.randomUUID()}"
    private var sending = false
    private var retryScheduled = false
    private var consecutiveFailureCount = 0

    init {
        registerNetworkRecovery()
    }

    fun record(
        eventCategory: String,
        eventName: String,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        val category = eventCategory.trim().uppercase()
        val name = eventName.trim().uppercase()
        if (category !in EVENT_CATEGORIES || !EVENT_NAME.matches(name)) return
        val event = JSONObject()
            .put("user_id", anonymousInstallationId())
            .put("event_id", "behavior-${UUID.randomUUID()}")
            .put("session_id", sessionId)
            .put("event_category", category)
            .put("event_name", name)
            .put("properties", JSONObject(normalizeProperties(properties)))
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("occurred_at_ms", System.currentTimeMillis())
        synchronized(lock) {
            appendLocked(event)
            trimLocked()
        }
        flushPending()
    }

    /** Records only the first observed start for each of a user's first five distinct videos. */
    fun recordEarlyVideoStarted(
        contentKey: String,
        provider: String,
        episodeIndex: Int,
        dramaId: String,
        episodeId: String,
        contentTitle: String,
    ): Int? {
        val rank = earlyVideoRank(contentKey, createIfMissing = true) ?: return null
        val started = readKeySet(EARLY_VIDEO_STARTED_KEYS).contains(contentKey)
        if (!started) {
            record(
                "PLAYBACK",
                "EARLY_VIDEO_STARTED",
                mapOf(
                    "early_video_rank" to rank,
                    "provider" to provider,
                    "episode_index" to episodeIndex,
                    "drama_id" to dramaId,
                    "episode_id" to episodeId,
                    "content_title" to contentTitle,
                ),
            )
            updateKeySet(EARLY_VIDEO_STARTED_KEYS) { it += contentKey }
        }
        return rank
    }

    /** Records only the first successful end for a previously observed early video. */
    fun recordEarlyVideoCompleted(
        contentKey: String,
        provider: String,
        episodeIndex: Int,
        dramaId: String,
        episodeId: String,
        contentTitle: String,
    ): Int? {
        val rank = earlyVideoRank(contentKey, createIfMissing = false) ?: return null
        val completed = readKeySet(EARLY_VIDEO_COMPLETED_KEYS).contains(contentKey)
        if (!completed) {
            record(
                "PLAYBACK",
                "EARLY_VIDEO_COMPLETED",
                mapOf(
                    "early_video_rank" to rank,
                    "provider" to provider,
                    "episode_index" to episodeIndex,
                    "drama_id" to dramaId,
                    "episode_id" to episodeId,
                    "content_title" to contentTitle,
                ),
            )
            updateKeySet(EARLY_VIDEO_COMPLETED_KEYS) { it += contentKey }
        }
        return rank
    }

    fun currentEarlyVideoRank(contentKey: String): Int? = earlyVideoRank(contentKey, createIfMissing = false)

    /** Safe to call on application/Activity startup and after a fresh Rewards session is stored. */
    fun flushPending() {
        val event = synchronized(lock) {
            if (sending || retryScheduled || currentSessionToken().isNullOrBlank()) return
            queuedEventsLocked().firstOrNull()?.also { sending = true }
        } ?: return
        send(event)
    }

    private fun send(event: JSONObject) {
        val baseUrl = BuildConfig.REWARDS_API_BASE_URL.trim().trimEnd('/')
        val token = currentSessionToken()
        if ((!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) || token.isNullOrBlank()) {
            synchronized(lock) { sending = false }
            return
        }
        val request = Request.Builder()
            .url("$baseUrl/v2/analytics/behavior-events")
            .post(event.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("X-Rewards-Session", "v1")
            .header("Authorization", "Bearer $token")
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                scheduleRecoveryRetry()
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val accepted = it.isSuccessful && runCatching {
                        JSONObject(it.body?.string().orEmpty()).optInt("code", -1) == 0
                    }.getOrDefault(false)
                    when {
                        accepted -> {
                            synchronized(lock) {
                                removeLocked(event.optString("event_id"))
                                consecutiveFailureCount = 0
                            }
                            sendNext()
                        }
                        // A fresh bootstrap replaces a stale token. Retain the event but do not
                        // busy-loop an authorization failure before that bootstrap completes.
                        it.code == 401 -> synchronized(lock) { sending = false }
                        it.code == 429 || it.code >= 500 -> scheduleRecoveryRetry()
                        it.code in 400..499 -> {
                            synchronized(lock) { removeLocked(event.optString("event_id")) }
                            sendNext()
                        }
                        else -> scheduleRecoveryRetry()
                    }
                }
            }
        })
    }

    private fun sendNext() {
        val event = synchronized(lock) {
            queuedEventsLocked().firstOrNull().also { if (it == null) sending = false }
        } ?: return
        send(event)
    }

    private fun scheduleRecoveryRetry() {
        val delayMillis = synchronized(lock) {
            sending = false
            if (retryScheduled) return
            retryScheduled = true
            val index = consecutiveFailureCount.coerceAtMost(RETRY_DELAYS_MS.lastIndex)
            consecutiveFailureCount += 1
            RETRY_DELAYS_MS[index]
        }
        mainHandler.postDelayed({
            synchronized(lock) { retryScheduled = false }
            flushPending()
        }, delayMillis)
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
        runCatching { manager.registerNetworkCallback(request, callback) }
    }

    private fun anonymousInstallationId(): String = preferences.getString(INSTALLATION_KEY, null)
        ?: UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(INSTALLATION_KEY, generated).apply()
        }

    private fun currentSessionToken(): String? = preferences.getString(SESSION_TOKEN_KEY, null)
        ?.takeIf { it.isNotBlank() }

    private fun earlyVideoRank(contentKey: String, createIfMissing: Boolean): Int? {
        val normalizedKey = contentKey.trim().takeIf { it.isNotEmpty() } ?: return null
        synchronized(lock) {
            val ranks = runCatching { JSONObject(preferences.getString(EARLY_VIDEO_RANKS, "{}")) }.getOrDefault(JSONObject())
            ranks.optInt(normalizedKey, 0).takeIf { it in 1..MAX_EARLY_VIDEOS }?.let { return it }
            if (!createIfMissing || ranks.length() >= MAX_EARLY_VIDEOS) return null
            val rank = (1..MAX_EARLY_VIDEOS).first { candidate ->
                (0 until ranks.length()).none { index -> ranks.optInt(ranks.names()?.optString(index), 0) == candidate }
            }
            ranks.put(normalizedKey, rank)
            preferences.edit().putString(EARLY_VIDEO_RANKS, ranks.toString()).apply()
            return rank
        }
    }

    private fun readKeySet(key: String): Set<String> = preferences.getStringSet(key, emptySet()).orEmpty()

    private fun updateKeySet(key: String, transform: (MutableSet<String>) -> Unit) {
        synchronized(lock) {
            val values = readKeySet(key).toMutableSet()
            transform(values)
            preferences.edit().putStringSet(key, values).apply()
        }
    }

    private fun normalizeProperties(rawProperties: Map<String, Any?>): Map<String, Any> = buildMap {
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

    private fun appendLocked(event: JSONObject) {
        if (!queueDirectory.exists()) queueDirectory.mkdirs()
        queueFile.appendText("$event\n", Charsets.UTF_8)
    }

    private fun queuedEventsLocked(): List<JSONObject> {
        if (!queueFile.exists()) return emptyList()
        return queueFile.readLines(Charsets.UTF_8).mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }

    private fun removeLocked(eventId: String) {
        val remaining = queuedEventsLocked().filterNot { it.optString("event_id") == eventId }
        if (remaining.isEmpty()) queueFile.delete()
        else queueFile.writeText(remaining.joinToString("\n", postfix = "\n") { it.toString() }, Charsets.UTF_8)
    }

    private fun trimLocked() {
        val events = queuedEventsLocked()
        if (events.size <= MAX_PENDING_EVENTS) return
        queueFile.writeText(events.takeLast(MAX_PENDING_EVENTS).joinToString("\n", postfix = "\n") { it.toString() }, Charsets.UTF_8)
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EVENT_CATEGORIES = setOf("APP", "SDK", "ONBOARDING", "PLAYBACK", "AD", "NETWORK", "REWARDS")
        val EVENT_NAME = Regex("^[A-Z][A-Z0-9_]{1,79}$")
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 10_000L, 60_000L, 300_000L)
        const val QUEUE_DIRECTORY = "user_behavior_events"
        const val QUEUE_FILE_NAME = "pending.ndjson"
        const val MAX_PENDING_EVENTS = 500
        const val MAX_PROPERTIES = 16
        const val MAX_PROPERTY_KEY_LENGTH = 40
        const val MAX_PROPERTY_VALUE_LENGTH = 512
        const val MAX_EARLY_VIDEOS = 5
        const val EARLY_VIDEO_RANKS = "behavior_early_video_ranks"
        const val EARLY_VIDEO_STARTED_KEYS = "behavior_early_video_started_keys"
        const val EARLY_VIDEO_COMPLETED_KEYS = "behavior_early_video_completed_keys"

        @Volatile private var instance: UserBehaviorEventReporter? = null

        fun get(context: Context): UserBehaviorEventReporter = instance ?: synchronized(this) {
            instance ?: UserBehaviorEventReporter(context.applicationContext).also { instance = it }
        }
    }
}
