package com.dramaflow.app.analytics

import android.content.Context
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.ads.AdRevenueAuditValues
import com.dramaflow.app.presentation.INSTALLATION_KEY
import com.dramaflow.app.presentation.PREFERENCES
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Best-effort mirror of reporting delivery outcomes into the Rewards operations ledger.
 *
 * This is deliberately **not** an on-device audit log: it creates no CSV, no files, no
 * export intent, and keeps no durable queue. It only sends privacy-minimised metadata to
 * the configured Rewards API so the protected web console can review reporting outcomes.
 */
internal class ReportingDeliveryEventReporter(context: Context) {
    private val appContext = context.applicationContext
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun record(
        provider: String,
        eventName: String,
        status: String,
        placement: String,
        adUnitId: String,
        errorCode: String,
        revenue: AdRevenueAuditValues,
    ) {
        // Rewards Operations is a separate server ledger and must never be mirrored into
        // the reporting-delivery ledger, avoiding self-generated report loops.
        if (provider !in REMOTE_PROVIDERS) return
        val baseUrl = BuildConfig.REWARDS_API_BASE_URL.trim().trimEnd('/')
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return
        val event = JSONObject()
            .put("client_event_id", "reporting-${UUID.randomUUID()}")
            .put("install_id_hash", sha256(anonymousInstallationId()))
            .put("provider", provider)
            .put("event_name", eventName.take(MAX_EVENT_NAME_LENGTH))
            .put("status", status)
            .put("logical_placement", placement.take(MAX_PLACEMENT_LENGTH))
            .put("ad_unit_id", adUnitId.take(MAX_AD_UNIT_LENGTH))
            .put("error_code", errorCode.take(MAX_ERROR_CODE_LENGTH))
            .put("currency", revenue.currency.take(3))
            .put("occurred_at_ms", System.currentTimeMillis())
        revenue.ecpm.toDoubleOrNull()?.let { event.put("ecpm", it) }
        revenue.value.toDoubleOrNull()?.let { event.put("value", it) }
        val request = Request.Builder()
            .url("$baseUrl/v2/operations/reporting-delivery-events")
            .post(event.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = Unit
            override fun onResponse(call: Call, response: okhttp3.Response) = response.use { Unit }
        })
    }

    private fun anonymousInstallationId(): String =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(INSTALLATION_KEY, null)
            ?: UUID.randomUUID().toString().also { generated ->
                appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(INSTALLATION_KEY, generated)
                    .apply()
            }

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val REMOTE_PROVIDERS = setOf("AppsFlyer", "Firebase", "Xuanyu")
        const val MAX_EVENT_NAME_LENGTH = 120
        const val MAX_PLACEMENT_LENGTH = 80
        const val MAX_AD_UNIT_LENGTH = 160
        const val MAX_ERROR_CODE_LENGTH = 40
    }
}
