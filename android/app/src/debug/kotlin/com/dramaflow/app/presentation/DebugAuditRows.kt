package com.dramaflow.app.presentation

import android.widget.LinearLayout
import com.dramaflow.app.BuildConfig

/** Adds internal reconciliation tools only to Debug builds. */
internal fun DramaAppActivity.addDebugAuditRows(content: LinearLayout) {
    if (!BuildConfig.LOCAL_AUDIT_ENABLED) return
    content.addView(
        createSettingsRow(t("ad_network_audit"), t("ad_network_audit_subtitle"), "›") { showAdNetworkAuditLog() },
        matchParams(bottom = 8),
    )
    content.addView(
        createSettingsRow(t("reporting_audit"), t("reporting_audit_subtitle"), "›") { showReportingAuditLog() },
        matchParams(bottom = 8),
    )
}
