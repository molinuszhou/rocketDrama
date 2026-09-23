package com.dramaflow.app.ads

import com.secmtp.sdk.core.api.ATAdInfo

/**
 * Normalizes TopOn ad metadata into one identity shared by local audit records and
 * all external reporting. MAX is a TopOn Custom Network, so the final MAX demand
 * partner is exposed through extInfoMap rather than the parent network field.
 */
data class AdNetworkIdentity(
    val networkName: String,
    val sourceId: String,
    val detail: String,
) {
    companion object {
        fun from(adInfo: ATAdInfo): AdNetworkIdentity {
            val extInfo = adInfo.extInfoMap.orEmpty()
            val maxNetwork = extInfo.valueFor("network_name", "NetworkName")
            val maxAdUnitId = extInfo.valueFor("ad_unit_id", "AdUnitId")
            val maxNetworkPlacementId = extInfo.valueFor("network_placement_id", "NetworkPlacement")
            val topOnNetwork = adInfo.networkName.orEmpty()
                .ifBlank { adInfo.adNetworkType.orEmpty() }
                .ifBlank { "TopOn" }
            val sourceId = when {
                maxNetworkPlacementId.isNotBlank() -> maxNetworkPlacementId
                maxAdUnitId.isNotBlank() -> maxAdUnitId
                else -> adInfo.adsourceId.orEmpty()
            }
            val detail = buildList {
                if (maxAdUnitId.isNotBlank()) add("max_ad_unit_id=$maxAdUnitId")
                if (maxNetworkPlacementId.isNotBlank()) add("max_network_placement_id=$maxNetworkPlacementId")
            }.joinToString("; ")
            return AdNetworkIdentity(
                networkName = if (maxNetwork.isNotBlank()) "MAX > $maxNetwork" else topOnNetwork,
                sourceId = sourceId,
                detail = detail,
            )
        }

        private fun Map<String, Any>.valueFor(vararg keys: String): String =
            keys.firstNotNullOfOrNull { key -> this[key]?.toString()?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
    }
}
