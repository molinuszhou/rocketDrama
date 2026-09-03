# Keep AndroidX Startup initializers and DataStore serializers used by the app.
-keep class androidx.startup.** { *; }
-keep class androidx.datastore.preferences.core.PreferenceDataStoreFactory { *; }

# Pangle SDK consumer rules 会随依赖引入；额外保护通过回调和反射进入的公开 API。
-keep class com.bytedance.sdk.openadsdk.** { *; }
-dontwarn com.bytedance.sdk.openadsdk.**

# Media3/ExoPlayer 由 AndroidX consumer rules 提供基础配置；保留自有播放器数据模型供 JSON 映射。
-keep class com.dramaflow.app.local.** { *; }

# Compose navigation routes are defined in app source and can be optimized safely.
