package com.dramaflow.app.ads

import android.content.Context
import com.dramaflow.app.BuildConfig
import com.secmtp.sdk.core.api.ATAdInfo
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Persists a compact, shareable record of the TopOn mediation chain observed by
 * this device. It intentionally excludes App Key, device identifiers, user IDs,
 * creative URLs and reward values; the file is safe to share with the mediation
 * partner when reconciling the configured and actually-returned ad networks.
 */
class AdNetworkAuditLog private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val auditDirectory = File(appContext.filesDir, DIRECTORY_NAME)
    private val auditFile = File(auditDirectory, FILE_NAME)
    private var configurationRecorded = false

    data class Summary(
        val eventCount: Int,
        val lastRecordedAtUtc: String?,
    )

    /** Writes one configuration row for each in-use TopOn placement per process. */
    fun recordConfiguration(settings: TopOnSettings) {
        synchronized(lock) {
            if (configurationRecorded) return
            configurationRecorded = true
        }
        record(
            event = "CONFIGURED",
            logicalPlacement = "splash",
            topOnPlacementId = settings.splashPlacementId,
            adFormat = "splash",
            result = if (settings.canShowSplash) "READY" else "MISSING_CONFIGURATION",
        )
        record(
            event = "CONFIGURED",
            logicalPlacement = "interstitial",
            topOnPlacementId = settings.interstitialPlacementId,
            adFormat = "interstitial",
            result = if (settings.canShowInterstitial) "READY" else "MISSING_CONFIGURATION",
        )
        record(
            event = "CONFIGURED",
            logicalPlacement = "rewarded",
            topOnPlacementId = settings.rewardedPlacementId,
            adFormat = "rewarded",
            result = if (settings.canShowRewarded) "READY" else "MISSING_CONFIGURATION",
        )
    }

    fun record(
        event: String,
        logicalPlacement: String,
        topOnPlacementId: String,
        adFormat: String,
        adNetwork: String = "",
        adSourceId: String = "",
        result: String = "",
        errorCode: Int? = null,
        revenue: AdRevenueAuditValues = AdRevenueAuditValues(),
        detail: String = "",
    ) {
        synchronized(lock) {
            ensureHeaderLocked()
            val row = listOf(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                BuildConfig.VERSION_NAME,
                event,
                logicalPlacement,
                topOnPlacementId,
                adFormat,
                adNetwork,
                adSourceId,
                result,
                errorCode?.toString().orEmpty(),
                revenue.currency,
                revenue.ecpm,
                revenue.value,
                detail.take(MAX_DETAIL_LENGTH),
            ).joinToString(",") { csvCell(it) }
            auditFile.appendText("$row\n", Charsets.UTF_8)
            trimIfNeededLocked()
        }
    }

    /** Records TopOn's paid-impression callback separately from show/close lifecycle events. */
    fun recordPaidRevenue(
        logicalPlacement: String,
        topOnPlacementId: String,
        adFormat: String,
        adInfo: ATAdInfo,
    ) {
        val identity = AdNetworkIdentity.from(adInfo)
        record(
            event = "PAID_REVENUE",
            logicalPlacement = logicalPlacement,
            topOnPlacementId = topOnPlacementId,
            adFormat = adFormat,
            adNetwork = identity.networkName,
            adSourceId = identity.sourceId,
            result = "REPORTED",
            revenue = AdRevenueAuditValues.fromPaidImpression(adInfo.currency, adInfo.ecpm),
            detail = identity.detail,
        )
    }

    fun summary(): Summary = synchronized(lock) {
        if (!auditFile.exists()) return@synchronized Summary(0, null)
        var eventCount = 0
        var lastTimestamp: String? = null
        auditFile.useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isNotBlank()) {
                    eventCount += 1
                    lastTimestamp = line.substringBefore(',').trim('"')
                }
            }
        }
        Summary(eventCount, lastTimestamp)
    }

    /** Returns a FileProvider-safe CSV file, creating its header if no ad call has occurred yet. */
    fun exportFile(): File = synchronized(lock) {
        ensureHeaderLocked()
        auditFile
    }

    fun clear() {
        synchronized(lock) {
            if (auditFile.exists()) auditFile.delete()
            configurationRecorded = false
        }
    }

    private fun ensureHeaderLocked() {
        if (!auditDirectory.exists()) auditDirectory.mkdirs()
        if (!auditFile.exists() || auditFile.length() == 0L) {
            auditFile.writeText("recorded_at_utc,app_version,event,logical_placement,topon_placement_id,ad_format,ad_network,ad_source_id,result,error_code,currency,ecpm,value,detail\n", Charsets.UTF_8)
        }
    }

    /** Keep the current report small enough to share while retaining recent debugging history. */
    private fun trimIfNeededLocked() {
        if (auditFile.length() <= MAX_FILE_BYTES) return
        val rows = auditFile.readLines(Charsets.UTF_8)
        val header = rows.firstOrNull().orEmpty()
        val retained = rows.drop(1).takeLast(MAX_EVENTS)
        auditFile.writeText((listOf(header) + retained).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""

    companion object {
        private const val DIRECTORY_NAME = "ad_audit"
        // The old CSV is left in place for review. A versioned file prevents a mixed-column export
        // after an app update while adding paid-impression eCPM and per-impression value fields.
        private const val FILE_NAME = "joyedrama_topon_adn_audit_v2.csv"
        private const val MAX_FILE_BYTES = 1_000_000L
        private const val MAX_EVENTS = 2_000
        private const val MAX_DETAIL_LENGTH = 300

        @Volatile private var instance: AdNetworkAuditLog? = null

        fun get(context: Context): AdNetworkAuditLog = instance ?: synchronized(this) {
            instance ?: AdNetworkAuditLog(context).also { instance = it }
        }
    }
}
