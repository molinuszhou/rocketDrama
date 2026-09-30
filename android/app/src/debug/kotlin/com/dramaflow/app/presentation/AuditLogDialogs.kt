package com.dramaflow.app.presentation

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.dramaflow.app.BuildConfig
import com.dramaflow.app.ads.AdNetworkAuditLog
import com.dramaflow.app.analytics.ReportingAuditLog

/** Debug-only helpers for internal TopOn/MAX and reporting reconciliation. */
internal fun DramaAppActivity.showAdNetworkAuditLog() {
    val audit = AdNetworkAuditLog.get(this)
    val summary = audit.summary()
    val message = if (summary.eventCount == 0) {
        t("ad_network_audit_empty")
    } else {
        t("ad_network_audit_message", summary.eventCount, summary.lastRecordedAtUtc.orEmpty())
    }
    showBrandedChoiceDialog(
        title = t("ad_network_audit"),
        message = message,
        primaryLabel = t("ad_network_audit_share"),
        onPrimary = {
            runCatching {
                val file = audit.exportFile()
                val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.ad-audit-files", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("joyedrama_topon_adn_audit", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, t("ad_network_audit_share")))
            }.onFailure {
                toast(t("ad_network_audit_share_failed"))
            }
        },
        secondaryLabel = t("ad_network_audit_clear"),
        onSecondary = {
            showBrandedChoiceDialog(
                title = t("ad_network_audit_clear"),
                message = t("ad_network_audit_clear_message"),
                primaryLabel = t("ad_network_audit_clear"),
                onPrimary = {
                    audit.clear()
                    toast(t("ad_network_audit_cleared"))
                },
                secondaryLabel = t("cancel"),
            )
        },
    )
}

/** Debug-only helper for internal AppsFlyer/Firebase/Xuanyu reconciliation. */
internal fun DramaAppActivity.showReportingAuditLog() {
    val audit = ReportingAuditLog.get(this)
    val summary = audit.summary()
    val message = if (summary.eventCount == 0) t("reporting_audit_empty")
    else t("reporting_audit_message", summary.eventCount, summary.lastRecordedAtUtc.orEmpty())
    showBrandedChoiceDialog(
        title = t("reporting_audit"),
        message = message,
        primaryLabel = t("reporting_audit_share"),
        onPrimary = {
            runCatching {
                val file = audit.exportFile()
                val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.ad-audit-files", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("joyedrama_reporting_audit", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, t("reporting_audit_share")))
            }.onFailure { toast(t("reporting_audit_share_failed")) }
        },
        secondaryLabel = t("reporting_audit_clear"),
        onSecondary = {
            showBrandedChoiceDialog(
                title = t("reporting_audit_clear"),
                message = t("reporting_audit_clear_message"),
                primaryLabel = t("reporting_audit_clear"),
                onPrimary = { audit.clear(); toast(t("reporting_audit_cleared")) },
                secondaryLabel = t("cancel"),
            )
        },
    )
}
