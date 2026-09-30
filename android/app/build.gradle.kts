import java.util.Properties

val integrationProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use(::load)
}

fun integrationValue(name: String): String =
    providers.gradleProperty(name).orNull
        ?: integrationProperties.getProperty(name)
        ?: System.getenv(name)
        ?: ""

fun integrationTimeoutMillis(name: String, defaultValue: Long): Long =
    integrationValue(name).trim().toLongOrNull()
        ?.coerceIn(1_000L, 60_000L)
        ?: defaultValue

fun escapeBuildConfig(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

// TopOn 官方 Android Demo 的公开测试凭据，仅允许 Debug 测试模式使用；不可用于正式投放。
val topOnDemoMode = integrationValue("TOPON_DEMO_MODE").trim().equals("true", ignoreCase = true)
val topOnDemoAppId = "a5aa1f9deda26d"
val topOnDemoAppKey = "4f7b9ac17decb9babec83aac078742c7"
val topOnDemoRewardedPlacementId = "b5b449fb3d89d7"
val topOnDemoInterstitialPlacementId = "b5baca53984692"
val topOnDemoSplashPlacementId = "b5bea7cc9a4497"
val partnerTopOnAppId = integrationValue("TOPON_APP_ID")
val partnerTopOnAppKey = integrationValue("TOPON_APP_KEY")
val partnerTopOnRewardedPlacementId = integrationValue("TOPON_REWARDED_PLACEMENT_ID")
val partnerTopOnInterstitialPlacementId = integrationValue("TOPON_INTERSTITIAL_PLACEMENT_ID")
val partnerTopOnSplashPlacementId = integrationValue("TOPON_SPLASH_PLACEMENT_ID")
val topOnAppId = if (topOnDemoMode) topOnDemoAppId else partnerTopOnAppId
val topOnAppKey = if (topOnDemoMode) topOnDemoAppKey else partnerTopOnAppKey
val topOnRewardedPlacementId = if (topOnDemoMode) topOnDemoRewardedPlacementId else partnerTopOnRewardedPlacementId
val topOnInterstitialPlacementId = if (topOnDemoMode) topOnDemoInterstitialPlacementId else partnerTopOnInterstitialPlacementId
val topOnSplashPlacementId = if (topOnDemoMode) topOnDemoSplashPlacementId else partnerTopOnSplashPlacementId
// Demo 模式与官方 Demo 清单保持一致；合作方模式必须显式注入其对应的 AdMob App ID。
val topOnDemoAdmobAppId = "ca-app-pub-9488501426181082~6354662111"
val partnerAdmobAppId = integrationValue("ADMOB_APP_ID")
val topOnAdmobAppId = if (topOnDemoMode) topOnDemoAdmobAppId else partnerAdmobAppId
// Configurable from local.properties, -P or environment: TOPON_REWARDED_LOAD_TIMEOUT_MS.
// Keep the app responsive while allowing slow mediation waterfalls enough time to return a fill.
val topOnRewardedLoadTimeoutMillis = integrationTimeoutMillis("TOPON_REWARDED_LOAD_TIMEOUT_MS", 10_000L)
val appsFlyerDevKey = integrationValue("APPSFLYER_DEV_KEY")
val appsFlyerSingleRevenueEventName = integrationValue("APPSFLYER_SINGLE_REVENUE_EVENT_NAME").ifBlank { "ad_impression" }
val appsFlyerCumulativeRevenueEventName = integrationValue("APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME").ifBlank { "ad_impression_001" }
val firebaseRewardedEventName = integrationValue("FIREBASE_REWARDED_EVENT_NAME").ifBlank { "rewarded_ad_completed" }
val firebaseCumulativeRevenueEventName = integrationValue("FIREBASE_CUMULATIVE_REVENUE_EVENT_NAME").ifBlank { "ad_impression_001" }
// 炫羽海外事件服务当前测试协议为 HTTP；Android 清单仅对该域名放行明文流量。
val xuanyuEventBaseUrl = integrationValue("XUANYU_EVENT_BASE_URL").ifBlank { "http://api.eventtrack.top" }
val shortDramaSdkEnabled = integrationValue("SHORT_DRAMA_SDK_ENABLED").trim().equals("true", ignoreCase = true)
val shortDramaAppId = integrationValue("SHORT_DRAMA_APP_ID")
val shortDramaVodAppId = integrationValue("SHORT_DRAMA_VOD_APP_ID")
val shortDramaLicenseAsset = integrationValue("SHORT_DRAMA_LICENSE_ASSET")
val shortDramaSecurityKey = integrationValue("SHORT_DRAMA_SECURITY_KEY")
val whiteLabelAppName = integrationValue("WHITE_LABEL_APP_NAME").ifBlank { "JoyeDrama" }
val analyticsDebug = integrationValue("ANALYTICS_DEBUG").trim().equals("true", ignoreCase = true)
// 测试阶段默认关闭 AppsFlyer、Firebase 和炫羽的实际事件上报；SDK 与事件代码保留，正式联调时显式开启。
val externalReportingEnabled = integrationValue("EXTERNAL_REPORTING_ENABLED").trim().equals("true", ignoreCase = true)
val releaseAbRoutingEnabled = integrationValue("RELEASE_AB_ROUTING_ENABLED").trim().equals("true", ignoreCase = true)
val localContentApiBaseUrl = integrationValue("LOCAL_CONTENT_API_BASE_URL").ifBlank { "http://10.0.2.2:8000" }
val rewardsApiBaseUrl = integrationValue("REWARDS_API_BASE_URL").ifBlank { "http://10.0.2.2:8100" }
val commercialReleaseRequested = gradle.startParameter.taskNames.any { task ->
    val normalized = task.lowercase()
    normalized.contains("release") && !normalized.contains("whiterelease")
}
if (commercialReleaseRequested) {
    check(!topOnDemoMode) { "TOPON_DEMO_MODE=true is permitted only for Debug builds, never for a commercial Release build." }
    val missing = listOf(
        "TOPON_APP_ID" to partnerTopOnAppId,
        "TOPON_APP_KEY" to partnerTopOnAppKey,
        "TOPON_REWARDED_PLACEMENT_ID" to partnerTopOnRewardedPlacementId,
        "TOPON_INTERSTITIAL_PLACEMENT_ID" to partnerTopOnInterstitialPlacementId,
        "TOPON_SPLASH_PLACEMENT_ID" to partnerTopOnSplashPlacementId,
        "ADMOB_APP_ID" to partnerAdmobAppId,
        "APPSFLYER_DEV_KEY" to appsFlyerDevKey,
        "SHORT_DRAMA_APP_ID" to shortDramaAppId,
        "SHORT_DRAMA_VOD_APP_ID" to shortDramaVodAppId,
        "SHORT_DRAMA_LICENSE_ASSET" to shortDramaLicenseAsset,
        "SHORT_DRAMA_SECURITY_KEY" to shortDramaSecurityKey,
        "REWARDS_API_BASE_URL" to rewardsApiBaseUrl.takeUnless { it.contains("10.0.2.2") }.orEmpty(),
    ).filter { (_, value) -> value.isBlank() }.map { (name, _) -> name }
    check(missing.isEmpty()) { "Commercial Release requires explicit advertising configuration: ${missing.joinToString()}" }
}

val validateCommercialReleaseAdConfig = tasks.register("validateCommercialReleaseAdConfig") {
    group = "verification"
    description = "Rejects Demo or incomplete advertising credentials for commercial Release tasks."
    inputs.property("toponDemoMode", topOnDemoMode)
    inputs.property("TOPON_APP_ID", partnerTopOnAppId)
    inputs.property("TOPON_APP_KEY", partnerTopOnAppKey)
    inputs.property("TOPON_REWARDED_PLACEMENT_ID", partnerTopOnRewardedPlacementId)
    inputs.property("TOPON_INTERSTITIAL_PLACEMENT_ID", partnerTopOnInterstitialPlacementId)
    inputs.property("TOPON_SPLASH_PLACEMENT_ID", partnerTopOnSplashPlacementId)
    inputs.property("ADMOB_APP_ID", partnerAdmobAppId)
    doLast {
        check(inputs.properties["toponDemoMode"].toString().toBoolean().not()) { "TOPON_DEMO_MODE=true is permitted only for Debug builds, never for a commercial Release build." }
        val required = listOf("TOPON_APP_ID", "TOPON_APP_KEY", "TOPON_REWARDED_PLACEMENT_ID", "TOPON_INTERSTITIAL_PLACEMENT_ID", "TOPON_SPLASH_PLACEMENT_ID", "ADMOB_APP_ID")
        val missing = required.filter { key -> inputs.properties[key].toString().isBlank() }
        check(missing.isEmpty()) { "Commercial Release requires explicit advertising configuration: ${missing.joinToString()}" }
    }
}
tasks.configureEach {
    val normalized = name.lowercase()
    if (name != validateCommercialReleaseAdConfig.name && normalized.contains("release") && !normalized.contains("whiterelease")) {
        dependsOn(validateCommercialReleaseAdConfig)
    }
}
// Firebase configuration is an environment credential, never committed. Do not apply the Google
// Services plugin to a stale config registered for the former test package: it would make a
// formal build look valid while emitting the wrong Firebase resources.
val formalApplicationId = "com.feather.joyelledrama"
val googleServicesConfigPresent = listOf(
    file("google-services.json"),
    file("src/debug/google-services.json"),
    file("src/release/google-services.json"),
).any { candidate ->
    candidate.exists() && candidate.readText().contains("\"package_name\": \"$formalApplicationId\"")
}
if (commercialReleaseRequested) {
    check(googleServicesConfigPresent) {
        "Commercial Release requires google-services.json registered for $formalApplicationId."
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

if (googleServicesConfigPresent) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    // Source namespace remains stable for existing Activity and resource references. Google Play,
    // Firebase, AppsFlyer and TopOn identify the shipped app by applicationId below.
    namespace = "com.dramaflow.app"
    compileSdk = 35

    defaultConfig {
        // Formal Play distribution package. Firebase, AppsFlyer and TopOn production settings must
        // all be registered against this same identifier.
        applicationId = formalApplicationId
        minSdk = 26
        targetSdk = 35
        // Android 客户端每次代码改动递增补丁版本；服务端/文档单独改动不变更此版本。
        versionCode = 54
        versionName = "1.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "TOPON_APP_ID", "\"${escapeBuildConfig(topOnAppId)}\"")
        buildConfigField("String", "TOPON_APP_KEY", "\"${escapeBuildConfig(topOnAppKey)}\"")
        buildConfigField("String", "TOPON_REWARDED_PLACEMENT_ID", "\"${escapeBuildConfig(topOnRewardedPlacementId)}\"")
        buildConfigField("String", "TOPON_INTERSTITIAL_PLACEMENT_ID", "\"${escapeBuildConfig(topOnInterstitialPlacementId)}\"")
        buildConfigField("String", "TOPON_SPLASH_PLACEMENT_ID", "\"${escapeBuildConfig(topOnSplashPlacementId)}\"")
        buildConfigField("long", "TOPON_REWARDED_LOAD_TIMEOUT_MS", "${topOnRewardedLoadTimeoutMillis}L")
        buildConfigField("boolean", "TOPON_DEMO_MODE", topOnDemoMode.toString())
        buildConfigField("String", "APPSFLYER_DEV_KEY", "\"${escapeBuildConfig(appsFlyerDevKey)}\"")
        buildConfigField("String", "APPSFLYER_SINGLE_REVENUE_EVENT_NAME", "\"${escapeBuildConfig(appsFlyerSingleRevenueEventName)}\"")
        buildConfigField("String", "APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME", "\"${escapeBuildConfig(appsFlyerCumulativeRevenueEventName)}\"")
        buildConfigField("String", "FIREBASE_REWARDED_EVENT_NAME", "\"${escapeBuildConfig(firebaseRewardedEventName)}\"")
        buildConfigField("String", "FIREBASE_CUMULATIVE_REVENUE_EVENT_NAME", "\"${escapeBuildConfig(firebaseCumulativeRevenueEventName)}\"")
        buildConfigField("String", "XUANYU_EVENT_BASE_URL", "\"${escapeBuildConfig(xuanyuEventBaseUrl)}\"")
        buildConfigField("boolean", "SHORT_DRAMA_SDK_ENABLED", shortDramaSdkEnabled.toString())
        buildConfigField("String", "SHORT_DRAMA_APP_ID", "\"${escapeBuildConfig(shortDramaAppId)}\"")
        buildConfigField("String", "SHORT_DRAMA_VOD_APP_ID", "\"${escapeBuildConfig(shortDramaVodAppId)}\"")
        buildConfigField("String", "SHORT_DRAMA_LICENSE_ASSET", "\"${escapeBuildConfig(shortDramaLicenseAsset)}\"")
        buildConfigField("String", "SHORT_DRAMA_SECURITY_KEY", "\"${escapeBuildConfig(shortDramaSecurityKey)}\"")
        buildConfigField("boolean", "ANALYTICS_DEBUG", analyticsDebug.toString())
        buildConfigField("boolean", "EXTERNAL_REPORTING_ENABLED", externalReportingEnabled.toString())
        buildConfigField("boolean", "RELEASE_AB_ROUTING_ENABLED", releaseAbRoutingEnabled.toString())
        buildConfigField("boolean", "WHITE_PACK", "false")
        buildConfigField("boolean", "ADS_ENABLED", "true")
        buildConfigField("boolean", "REWARDS_ENABLED", "true")
        buildConfigField("boolean", "CONTENT_UNLOCK_ENABLED", "true")
        resValue("string", "app_name", whiteLabelAppName)
        buildConfigField("String", "LOCAL_CONTENT_API_BASE_URL", "\"${escapeBuildConfig(localContentApiBaseUrl)}\"")
        buildConfigField("String", "REWARDS_API_BASE_URL", "\"${escapeBuildConfig(rewardsApiBaseUrl)}\"")
        manifestPlaceholders["ADMOB_APP_ID"] = topOnAdmobAppId
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SCREENSHOT_PROTECTION_ENABLED", "false")
            // Debug 使用 TOPON_DEMO_MODE 的构建参数；默认 false，显式开启后自动使用官方 Demo 凭据。
        }
        release {
            // Release activity enables FLAG_SECURE; debug remains screenshot-friendly for device verification.
            buildConfigField("boolean", "SCREENSHOT_PROTECTION_ENABLED", "true")
            // A commercial release never inherits Demo IDs, even if a developer has enabled Demo mode locally.
            buildConfigField("String", "TOPON_APP_ID", "\"${escapeBuildConfig(partnerTopOnAppId)}\"")
            buildConfigField("String", "TOPON_APP_KEY", "\"${escapeBuildConfig(partnerTopOnAppKey)}\"")
            buildConfigField("String", "TOPON_REWARDED_PLACEMENT_ID", "\"${escapeBuildConfig(partnerTopOnRewardedPlacementId)}\"")
            buildConfigField("String", "TOPON_INTERSTITIAL_PLACEMENT_ID", "\"${escapeBuildConfig(partnerTopOnInterstitialPlacementId)}\"")
            buildConfigField("String", "TOPON_SPLASH_PLACEMENT_ID", "\"${escapeBuildConfig(partnerTopOnSplashPlacementId)}\"")
            buildConfigField("boolean", "TOPON_DEMO_MODE", "false")
            // Formal release always consumes Pangle short-drama SDK content and the AppsFlyer
            // organic/non-organic monetization split; local content remains Debug-only.
            buildConfigField("boolean", "SHORT_DRAMA_SDK_ENABLED", "true")
            buildConfigField("boolean", "RELEASE_AB_ROUTING_ENABLED", "true")
            manifestPlaceholders["ADMOB_APP_ID"] = partnerAdmobAppId
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Partner white-label build: content browsing/playback remains available,
        // while ads, rewards, wallet and episode locks are disabled at build time.
        create("whiteRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-white"
            // Partner testing build: sign with the standard local debug keystore
            // so adb can install it. Replace this with the partner's release
            // keystore before any public distribution.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "WHITE_PACK", "true")
            buildConfigField("boolean", "ADS_ENABLED", "false")
            buildConfigField("boolean", "REWARDS_ENABLED", "false")
            buildConfigField("boolean", "CONTENT_UNLOCK_ENABLED", "false")
            // White release is content-only; never start attribution or send analytics side-channel events.
            buildConfigField("boolean", "EXTERNAL_REPORTING_ENABLED", "false")
            buildConfigField("boolean", "RELEASE_AB_ROUTING_ENABLED", "false")
            resValue("string", "app_name", "JoyeDrama White")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // TopOn mediation core. In the current 6.5.73.3 distribution it already bundles the
    // interstitial, rewarded and splash APIs; adding the older standalone format AARs would
    // create duplicate classes during release dexing.
    implementation("com.anythink.sdk:core-tpn:6.5.73.3")
    implementation("com.anythink.sdk:adapter-tpn-sdm:6.5.68.1.1")
    implementation("com.smartdigimkttech.sdk:smartdigimkttech-sdk:6.5.68")
    implementation("com.anythink.sdk:adapter-tpn-admob:6.5.36")
    implementation("com.google.android.gms:play-services-ads:24.6.0")

    // MAX is configured as a Custom Network inside TopOn. The Alex adapter bridges TopOn requests
    // to MAX, while these MAX adapters bring the SDKs for the enabled MAX demand partners.
    // MAX's AppLovin SDK key and each MAX ad-unit ID remain TopOn dashboard server extras; no MAX
    // credential is embedded in the client. MAX does not provide the Splash format in this bridge.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.applovin:applovin-sdk:13.6.0")
    implementation("io.github.alex-only:max_adapter_tpn:1.3.1")
    implementation("com.applovin.mediation:google-adapter:24.6.0.0")
    implementation("com.applovin.mediation:vungle-adapter:7.7.8.1")
    implementation("com.applovin.mediation:facebook-adapter:6.20.0.0")
    implementation("com.applovin.mediation:bytedance-adapter:8.3.0.4.1")
    implementation("com.applovin.mediation:unityads-adapter:4.20.1.0")
    implementation("com.applovin.mediation:mintegral-adapter:17.1.81.0")

    // Attribution and analytics.
    implementation("com.appsflyer:af-android-sdk:6.17.5")
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-analytics")

    // 合作方短剧 SDK：默认由 SHORT_DRAMA_SDK_ENABLED=false 保持本地视频测试链路；
    // 需要外部短剧目录和鉴权时再填入合作方参数并开启该开关。
    implementation("com.bytedance.dramaverse:pssdk:1.8.0.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
