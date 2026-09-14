-keep class androidx.startup.** { *; }
-keep class androidx.datastore.preferences.core.PreferenceDataStoreFactory { *; }

# TopOn SDK consumer rules cover most adapters. Preserve public callback/reflection entry points
# used by the white-label mediation, revenue reporting and test debugger paths.
-keep class com.secmtp.sdk.** { *; }
-dontwarn com.secmtp.sdk.**
-keep class com.smartdigimkttech.sdk.** { *; }
-dontwarn com.smartdigimkttech.sdk.**

# AppsFlyer receives lifecycle and attribution callbacks through its SDK manifest components.
-keep class com.appsflyer.** { *; }
-dontwarn com.appsflyer.**

# 合作方短剧 SDK：目录、分类、鉴权与播放器存在回调和反射入口。默认本地视频模式下
# 不会初始化它；开启 SHORT_DRAMA_SDK_ENABLED 后必须保留其公开 API。
-keep class com.bytedance.sdk.shortplay.** { *; }
-dontwarn com.bytedance.sdk.shortplay.**
-keep class com.ss.ttvideoengine.** { *; }
-dontwarn com.ss.ttvideoengine.**
# PSSDK 的超分、AI、崩溃上报与兼容播放器模块在基础短剧包中为可选能力；当前不调用
# 对应功能，按官方 R8 缺失规则忽略，不影响目录、分类、鉴权或视频播放。
-dontwarn android.os.SystemProperties
-dontwarn com.bytedance.bmf_mods_api.**
-dontwarn com.bytedance.bmf_mods_lite_api.**
-dontwarn com.bytedance.bpea.basics.**
-dontwarn com.bytedance.crash.**
-dontwarn com.google.android.exoplayer2.**
-dontwarn com.ss.ttm.utils.**
-dontwarn com.ss.ugc.clientai.core.api.**

# Media3/ExoPlayer consumer rules provide the base configuration; preserve the local content
# models that are JSON-mapped by the owned-content test service.
-keep class com.dramaflow.app.local.** { *; }

# Firebase Analytics ships consumer rules. Compose navigation routes are defined in app source
# and can be optimized safely.
