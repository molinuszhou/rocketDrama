package com.dramaflow.app

import android.app.Application
import android.util.Log
import com.dramaflow.app.ads.TopOnSettings
import com.dramaflow.app.analytics.PartnerAnalytics
import com.dramaflow.app.content.ShortDramaSdkManager
import com.secmtp.sdk.core.api.ATSDK

/**
 * 白包应用级入口。
 *
 * 短剧内容仍由 content service 与现有播放器链路处理；这里统一初始化第三方
 * 广告、归因和统计 SDK。初始化失败不阻塞本地内容浏览，但会使广告入口安全降级。
 */
class DramaFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PartnerAnalytics.get(this).initialize()

        val settings = TopOnSettings.fromBuildConfig()
        if (settings.isConfigured) {
            ATSDK.setNetworkLogDebug(settings.enableNetworkDebug)
            ATSDK.init(this, settings.appId, settings.appKey)
            Log.i(TAG, "TopOn initialization requested for white-label build ${BuildConfig.VERSION_NAME}")
        } else {
            Log.w(TAG, "TopOn is not initialized: TOPON_APP_ID or TOPON_APP_KEY is missing")
        }

        ShortDramaSdkManager.get(this).initializeIfEnabled { result ->
            when (result) {
                ShortDramaSdkManager.Initialization.Disabled -> Log.i(TAG, "External short-drama SDK disabled; local content mode is active")
                ShortDramaSdkManager.Initialization.Ready -> Log.i(TAG, "External short-drama SDK initialization completed")
                is ShortDramaSdkManager.Initialization.InvalidConfiguration -> Log.w(TAG, result.message)
                is ShortDramaSdkManager.Initialization.Failed -> Log.w(TAG, result.message)
            }
        }
    }

    private companion object {
        const val TAG = "DramaFlowApplication"
    }
}
