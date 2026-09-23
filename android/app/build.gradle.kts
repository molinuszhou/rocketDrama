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

fun escapeBuildConfig(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

// TopOn 官方 Android Demo 的公开测试凭据，仅允许 Debug 测试模式使用；不可用于正式投放。
val topOnDemoMode = integrationValue("TOPON_DEMO_MODE").trim().equals("true", ignoreCase = true)
val topOnDemoAppId = "a5aa1f9deda26d"
val topOnDemoAppKey = "4f7b9ac17decb9babec83aac078742c7"
val topOnDemoRewardedPlacementId = "b5b449fb3d89d7"
val topOnDemoInterstitialPlacementId = "b5baca53984692"
val topOnDemoSplashPlacementId = "b5bea7cc9a4497"
val topOnAppId = if (topOnDemoMode) topOnDemoAppId else integrationValue("TOPON_APP_ID").ifBlank { "h6a880d76bf897" }
val topOnAppKey = if (topOnDemoMode) topOnDemoAppKey else integrationValue("TOPON_APP_KEY")
val topOnRewardedPlacementId = if (topOnDemoMode) topOnDemoRewardedPlacementId else integrationValue("TOPON_REWARDED_PLACEMENT_ID").ifBlank { "n6a880eaaa1a1e" }
val topOnInterstitialPlacementId = if (topOnDemoMode) topOnDemoInterstitialPlacementId else integrationValue("TOPON_INTERSTITIAL_PLACEMENT_ID").ifBlank { "n6a880e948c456" }
val topOnSplashPlacementId = if (topOnDemoMode) topOnDemoSplashPlacementId else integrationValue("TOPON_SPLASH_PLACEMENT_ID").ifBlank { "n6a880ebb7fb00" }
// Demo 模式与官方 Demo 清单保持一致；合作方模式继续使用参数表提供的 AdMob App ID。
val topOnDemoAdmobAppId = "ca-app-pub-9488501426181082~6354662111"
val topOnAdmobAppId = if (topOnDemoMode) topOnDemoAdmobAppId else integrationValue("ADMOB_APP_ID").ifBlank { "ca-app-pub-4636975460860893~1434961000" }
val appsFlyerDevKey = integrationValue("APPSFLYER_DEV_KEY").ifBlank { "DdWbxT9VRELdEsZiAcnGea" }
val appsFlyerSingleRevenueEventName = integrationValue("APPSFLYER_SINGLE_REVENUE_EVENT_NAME").ifBlank { "ad_impression" }
val appsFlyerCumulativeRevenueEventName = integrationValue("APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME").ifBlank { "ad_impression_001" }
val firebaseRewardedEventName = integrationValue("FIREBASE_REWARDED_EVENT_NAME").ifBlank { "rewarded_ad_completed" }
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
val localContentApiBaseUrl = integrationValue("LOCAL_CONTENT_API_BASE_URL").ifBlank { "http://10.0.2.2:8000" }
val rewardsApiBaseUrl = integrationValue("REWARDS_API_BASE_URL").ifBlank { "http://10.0.2.2:8100" }
// Firebase 的应用配置属于环境凭据，仓库中不提交。仅在配置文件存在时启用 Google
// Services 插件；Analytics SDK 与其受开关控制的代码仍可在本地/CI 中正常编译。
val googleServicesConfigPresent = file("google-services.json").exists() ||
    file("src/debug/google-services.json").exists() ||
    file("src/release/google-services.json").exists()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

if (googleServicesConfigPresent) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.dramaflow.app"
    compileSdk = 35

    defaultConfig {
        // 与新 Firebase 配置及计划上架的正式 Android 应用标识保持一致。
        // namespace 与 applicationId 统一，避免上架、深链和第三方 SDK 注册信息出现歧义。
        applicationId = "com.dramaflow.app"
        minSdk = 26
        targetSdk = 35
        // Android 客户端每次代码改动递增补丁版本；服务端/文档单独改动不变更此版本。
        versionCode = 35
        versionName = "1.6.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "TOPON_APP_ID", "\"${escapeBuildConfig(topOnAppId)}\"")
        buildConfigField("String", "TOPON_APP_KEY", "\"${escapeBuildConfig(topOnAppKey)}\"")
        buildConfigField("String", "TOPON_REWARDED_PLACEMENT_ID", "\"${escapeBuildConfig(topOnRewardedPlacementId)}\"")
        buildConfigField("String", "TOPON_INTERSTITIAL_PLACEMENT_ID", "\"${escapeBuildConfig(topOnInterstitialPlacementId)}\"")
        buildConfigField("String", "TOPON_SPLASH_PLACEMENT_ID", "\"${escapeBuildConfig(topOnSplashPlacementId)}\"")
        buildConfigField("boolean", "TOPON_DEMO_MODE", topOnDemoMode.toString())
        buildConfigField("String", "APPSFLYER_DEV_KEY", "\"${escapeBuildConfig(appsFlyerDevKey)}\"")
        buildConfigField("String", "APPSFLYER_SINGLE_REVENUE_EVENT_NAME", "\"${escapeBuildConfig(appsFlyerSingleRevenueEventName)}\"")
        buildConfigField("String", "APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME", "\"${escapeBuildConfig(appsFlyerCumulativeRevenueEventName)}\"")
        buildConfigField("String", "FIREBASE_REWARDED_EVENT_NAME", "\"${escapeBuildConfig(firebaseRewardedEventName)}\"")
        buildConfigField("String", "XUANYU_EVENT_BASE_URL", "\"${escapeBuildConfig(xuanyuEventBaseUrl)}\"")
        buildConfigField("boolean", "SHORT_DRAMA_SDK_ENABLED", shortDramaSdkEnabled.toString())
        buildConfigField("String", "SHORT_DRAMA_APP_ID", "\"${escapeBuildConfig(shortDramaAppId)}\"")
        buildConfigField("String", "SHORT_DRAMA_VOD_APP_ID", "\"${escapeBuildConfig(shortDramaVodAppId)}\"")
        buildConfigField("String", "SHORT_DRAMA_LICENSE_ASSET", "\"${escapeBuildConfig(shortDramaLicenseAsset)}\"")
        buildConfigField("String", "SHORT_DRAMA_SECURITY_KEY", "\"${escapeBuildConfig(shortDramaSecurityKey)}\"")
        buildConfigField("boolean", "ANALYTICS_DEBUG", analyticsDebug.toString())
        buildConfigField("boolean", "EXTERNAL_REPORTING_ENABLED", externalReportingEnabled.toString())
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
            // 防止错误把官方 Demo 凭据带入正式白包。
            buildConfigField("boolean", "TOPON_DEMO_MODE", "false")
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

    // White-label test stack: TopOn mediation, AppsFlyer attribution and Firebase Analytics.
    implementation("com.anythink.sdk:core-tpn:6.5.73.3")
    implementation("com.anythink.sdk:adapter-tpn-sdm:6.5.68.1.1")
    implementation("com.smartdigimkttech.sdk:smartdigimkttech-sdk:6.5.68")
    implementation("com.anythink.sdk:adapter-tpn-admob:6.5.36")
    implementation("com.google.android.gms:play-services-ads:24.6.0")
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
