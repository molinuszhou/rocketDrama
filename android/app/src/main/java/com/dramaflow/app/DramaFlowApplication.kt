package com.dramaflow.app

import android.app.Application
import android.util.Log
import com.dramaflow.app.ads.TopOnSettings
import com.dramaflow.app.analytics.EngagementAnalytics
import com.dramaflow.app.content.ShortDramaSdkManager
import com.dramaflow.app.presentation.INSTALLATION_KEY
import com.dramaflow.app.presentation.PREFERENCES
import com.dramaflow.app.rewards.RewardsApiClient
import java.util.UUID

/**
 * 正式应用级入口。
 *
 * 短剧内容由 Pangle/PSSDK 提供；这里初始化广告、归因和统计 SDK。
 */
class DramaFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Attribution and analytics are reporting services rather than TopOn SDK initialization.
        // Starting them here preserves AppsFlyer install/open attribution while TopOn still waits
        // for an eligible, consented ad request.
        val rewardsApi = RewardsApiClient(baseUrlProvider = { BuildConfig.REWARDS_API_BASE_URL })
        EngagementAnalytics.get(this).apply {
            setAttributionSyncListener { attribution ->
                if (rewardsApi.isConfigured) {
                    rewardsApi.syncAttribution(anonymousInstallationId(), attribution) { error ->
                        Log.w(TAG, "AppsFlyer attribution sync will retry on the next Rewards bootstrap: $error")
                    }
                }
            }
            initialize()
        }
        val settings = TopOnSettings.fromBuildConfig()
        if (settings.isConfigured) {
            val mode = if (settings.isDemoMode) "official-demo" else "configured-network"
            // 真正初始化由 TopOnAdsManager 异步完成；它会等待 SDK 回调成功后才加载开屏广告。
            Log.i(TAG, "TopOn configuration ready for release ${BuildConfig.VERSION_NAME}, mode=$mode")
        } else {
            Log.w(TAG, "TopOn is not configured: TOPON_APP_ID or TOPON_APP_KEY is missing")
        }

        ShortDramaSdkManager.get(this).initializeIfEnabled { result ->
            when (result) {
                ShortDramaSdkManager.Initialization.Disabled -> Log.w(TAG, "Pangle short-drama SDK is disabled")
                ShortDramaSdkManager.Initialization.Ready -> Log.i(TAG, "External short-drama SDK initialization completed")
                is ShortDramaSdkManager.Initialization.InvalidConfiguration -> Log.w(TAG, result.message)
                is ShortDramaSdkManager.Initialization.Failed -> Log.w(TAG, result.message)
            }
        }
    }

    private companion object {
        const val TAG = "DramaFlowApplication"
    }

    private fun anonymousInstallationId(): String =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(INSTALLATION_KEY, null)
            ?: UUID.randomUUID().toString().also {
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(INSTALLATION_KEY, it).apply()
            }
}
