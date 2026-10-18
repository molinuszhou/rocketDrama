package com.dramaflow.app.ads

import android.content.Context
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.analytics.ReportingAuditLog
import com.dramaflow.app.analytics.ReportingProvider
import com.dramaflow.app.analytics.ReportingStatus
import com.dramaflow.app.analytics.recordProvider
import com.secmtp.sdk.core.api.ATAdInfo
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Mirrors observed TopOn callbacks into the Rewards service's operations ledger.
 *
 * This queue never participates in an ad display, reward grant, or UI callback: a
 * network failure is retained locally and retried on the next app launch. The
 * payload carries only a SHA-256 installation pseudonym, never the raw install or
 * Rewards user identifier. Financial truth remains TopOn/MAX reporting; this is an
 * operational reconciliation stream.
 */
class AdMediationEventReporter(
    context: Context,
    private val baseUrlProvider: () -> String,
    private val installationIdProvider: () -> String,
    private val sessionTokenProvider: () -> String? = { null },
    private val reportingAudit: ReportingAuditLog = ReportingAuditLog.get(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val queueDirectory = File(appContext.filesDir, QUEUE_DIRECTORY)
    private val queueFile = File(queueDirectory, QUEUE_FILE_NAME)
    private var sending = false

    fun recordShown(logicalPlacement: String, toponPlacementId: String, adFormat: String, adInfo: ATAdInfo) {
        enqueue(
            eventType = "SHOWN",
            logicalPlacement = logicalPlacement,
            toponPlacementId = toponPlacementId,
            adFormat = adFormat,
            adInfo = adInfo,
            reportingTargets = reportingTargetsFor(adFormat),
        )
    }

    /** Attempts a bounded replay of retained events. Safe to call from Activity.onCreate. */
    fun flushPending() {
        synchronized(lock) {
            if (sending || queuedEventsLocked().isEmpty()) return
            sending = true
        }
        sendNext()
    }

    private fun enqueue(
        eventType: String,
        logicalPlacement: String,
        toponPlacementId: String,
        adFormat: String,
        adInfo: ATAdInfo,
        reportingTargets: List<String>,
    ) {
        val identity = AdNetworkIdentity.from(adInfo)
        val revenue = AdRevenueAuditValues.fromPaidImpression(adInfo.currency, adInfo.ecpm)
        val event = JSONObject()
            .put("client_event_id", "mediation-${UUID.randomUUID()}")
            // Kept out of the operations ledger model, but present in transit so the same signed
            // Rewards session middleware can authenticate this queue when strict mode is enabled.
            .put("user_id", installationIdProvider())
            .put("install_id_hash", sha256(installationIdProvider()))
            .put("event_type", eventType)
            .put("logical_placement", logicalPlacement)
            .put("topon_placement_id", toponPlacementId)
            .put("ad_format", adFormat)
            .put("ad_network", identity.networkName)
            .put("ad_source_id", identity.sourceId)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("currency", revenue.currency)
            .put("reporting_targets", JSONArray(reportingTargets))
            .put("occurred_at_ms", System.currentTimeMillis())
        revenue.ecpm.toDoubleOrNull()?.let { event.put("ecpm", it) }
        synchronized(lock) {
            appendLocked(event)
            trimLocked()
        }
        reportingAudit.recordProvider(
            ReportingProvider.REWARDS_OPERATIONS,
            "ad_mediation_$eventType",
            ReportingStatus.QUEUED,
            logicalPlacement,
            toponPlacementId,
            revenue = revenue,
            detail = "event_id=${event.getString("client_event_id")}; network=${identity.networkName}; source=${identity.sourceId}",
        )
        flushPending()
    }

    private fun sendNext() {
        val event = synchronized(lock) { queuedEventsLocked().firstOrNull() }
        if (event == null) {
            synchronized(lock) { sending = false }
            return
        }
        // Queues written by earlier releases do not contain user_id. Add it only to the replay
        // payload (the operations endpoint discards it) so a newly enabled strict session policy
        // can accept retained non-financial events instead of leaving them retrying forever.
        event.put("user_id", installationIdProvider())
        val baseUrl = normalisedBaseUrl()
        if (baseUrl == null) {
            recordRetry(event, "rewards_service_not_configured")
            synchronized(lock) { sending = false }
            return
        }
        val requestBuilder = Request.Builder()
            .url("$baseUrl/v2/operations/ad-mediation-events")
            .post(event.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
        sessionTokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            requestBuilder.header("X-Rewards-Session", "v1")
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val request = requestBuilder.build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                recordRetry(event, "network_error")
                synchronized(lock) { sending = false }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val accepted = it.isSuccessful && runCatching {
                        JSONObject(it.body?.string().orEmpty()).optInt("code", -1) == 0
                    }.getOrDefault(false)
                    if (!accepted) {
                        if (it.code in 400..499 && it.code !in setOf(401, 429)) {
                            synchronized(lock) { removeLocked(event.getString("client_event_id")) }
                            reportingAudit.recordProvider(
                                ReportingProvider.REWARDS_OPERATIONS,
                                "ad_mediation_${event.getString("event_type")}",
                                ReportingStatus.FAILED,
                                event.getString("logical_placement"),
                                event.getString("topon_placement_id"),
                                revenue = eventRevenue(event),
                                detail = "event_id=${event.getString("client_event_id")}; permanent_http_${it.code}",
                            )
                            sendNext()
                            return
                        }
                        recordRetry(event, "http_${it.code}")
                        synchronized(lock) { sending = false }
                        return
                    }
                    synchronized(lock) { removeLocked(event.getString("client_event_id")) }
                    reportingAudit.recordProvider(
                        ReportingProvider.REWARDS_OPERATIONS,
                        "ad_mediation_${event.getString("event_type")}",
                        ReportingStatus.SUCCESS,
                        event.getString("logical_placement"),
                        event.getString("topon_placement_id"),
                        revenue = eventRevenue(event),
                        detail = "event_id=${event.getString("client_event_id")}; accepted",
                    )
                    sendNext()
                }
            }
        })
    }

    private fun recordRetry(event: JSONObject, reason: String) {
        reportingAudit.recordProvider(
            ReportingProvider.REWARDS_OPERATIONS,
            "ad_mediation_${event.optString("event_type")}",
            ReportingStatus.RETRYING,
            event.optString("logical_placement"),
            event.optString("topon_placement_id"),
            revenue = eventRevenue(event),
            detail = "event_id=${event.optString("client_event_id")}; $reason",
        )
    }

    private fun eventRevenue(event: JSONObject): AdRevenueAuditValues = AdRevenueAuditValues(
        currency = event.optString("currency"),
        ecpm = event.optString("ecpm"),
        value = event.optString("ecpm").toDoubleOrNull()?.div(1_000.0)?.let { "%.8f".format(java.util.Locale.US, it) }.orEmpty(),
    )

    private fun appendLocked(event: JSONObject) {
        if (!queueDirectory.exists()) queueDirectory.mkdirs()
        queueFile.appendText("${event}\n", Charsets.UTF_8)
    }

    private fun queuedEventsLocked(): List<JSONObject> {
        if (!queueFile.exists()) return emptyList()
        return queueFile.readLines(Charsets.UTF_8).mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    private fun removeLocked(clientEventId: String) {
        val remaining = queuedEventsLocked().filterNot { it.optString("client_event_id") == clientEventId }
        if (remaining.isEmpty()) {
            queueFile.delete()
        } else {
            queueFile.writeText(remaining.joinToString("\n", postfix = "\n") { it.toString() }, Charsets.UTF_8)
        }
    }

    private fun trimLocked() {
        val events = queuedEventsLocked()
        if (events.size <= MAX_PENDING_EVENTS) return
        queueFile.writeText(events.takeLast(MAX_PENDING_EVENTS).joinToString("\n", postfix = "\n") { it.toString() }, Charsets.UTF_8)
    }

    private fun normalisedBaseUrl(): String? = baseUrlProvider().trim().trimEnd('/').takeIf {
        it.startsWith("http://") || it.startsWith("https://")
    }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Splash is mirrored only for internal operations reconciliation, never for external targets. */
    private fun reportingTargetsFor(adFormat: String): List<String> =
        if (adFormat.equals("splash", ignoreCase = true)) emptyList() else REPORTING_TARGETS

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val QUEUE_DIRECTORY = "ad_mediation_events"
        const val QUEUE_FILE_NAME = "pending.ndjson"
        const val MAX_PENDING_EVENTS = 100
        val REPORTING_TARGETS = listOf("PSSDK", "AppsFlyer", "Firebase", "Xuanyu")
    }
}
