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
fun isAdmobAndroidAppId(value: String): Boolean {
    val normalized = value.trim()
    return normalized.startsWith("ca-app-pub-") && normalized.contains('~') && !normalized.contains('/')
}
// Configurable from local.properties, -P or environment: TOPON_REWARDED_LOAD_TIMEOUT_MS.
// Keep the app responsive while allowing slow mediation waterfalls enough time to return a fill.
val topOnRewardedLoadTimeoutMillis = integrationTimeoutMillis("TOPON_REWARDED_LOAD_TIMEOUT_MS", 10_000L)
val appsFlyerDevKey = integrationValue("APPSFLYER_DEV_KEY")
val appsFlyerSingleRevenueEventName = integrationValue("APPSFLYER_SINGLE_REVENUE_EVENT_NAME").ifBlank { "ad_impression" }
val appsFlyerCumulativeRevenueEventName = integrationValue("APPSFLYER_CUMULATIVE_REVENUE_EVENT_NAME").ifBlank { "ad_impression_001" }
val firebaseRewardedEventName = integrationValue("FIREBASE_REWARDED_EVENT_NAME").ifBlank { "rewarded_ad_completed" }
val firebaseCumulativeRevenueEventName = integrationValue("FIREBASE_CUMULATIVE_REVENUE_EVENT_NAME").ifBlank { "ad_impression_001" }
// 炫羽海外事件服务当前正式接口仍为 HTTP；Android 清单仅对该域名放行明文流量。
val xuanyuEventBaseUrl = integrationValue("XUANYU_EVENT_BASE_URL").ifBlank { "http://api.eventtrack.top" }
val shortDramaAppId = integrationValue("SHORT_DRAMA_APP_ID")
val shortDramaVodAppId = integrationValue("SHORT_DRAMA_VOD_APP_ID")
val shortDramaLicenseAsset = integrationValue("SHORT_DRAMA_LICENSE_ASSET")
val shortDramaSecurityKey = integrationValue("SHORT_DRAMA_SECURITY_KEY")
val analyticsDebug = integrationValue("ANALYTICS_DEBUG").trim().equals("true", ignoreCase = true)
// 默认关闭 AppsFlyer、Firebase 和炫羽的实际事件上报；由受控构建参数显式开启。
val externalReportingEnabled = integrationValue("EXTERNAL_REPORTING_ENABLED").trim().equals("true", ignoreCase = true)
val releaseAbRoutingEnabled = integrationValue("RELEASE_AB_ROUTING_ENABLED").trim().equals("true", ignoreCase = true)
// Rewards 服务地址由 local.properties、-P 或 CI Secret 注入。不要把生产域名硬编码在客户端；
// 服务迁移或路径调整时，只需更新受控构建参数并重新构建。
val rewardsApiBaseUrl = integrationValue("REWARDS_API_BASE_URL").trim().trimEnd('/')
fun isProductionRewardsApiBaseUrl(value: String): Boolean {
    val normalized = value.trim().trimEnd('/')
    return normalized.startsWith("https://") &&
        !normalized.contains("10.0.2.2") &&
        !normalized.contains("localhost") &&
        !normalized.contains("127.0.0.1")
}
val commercialReleaseRequested = gradle.startParameter.taskNames.any { task ->
    val normalized = task.lowercase()
    // `:app:assemble` / `:app:bundle` expand to Release variants too. Treat these aggregate
    // entry points as commercial builds so empty production credentials cannot bypass checks.
    normalized.contains("release") ||
        normalized.substringAfterLast(':') in setOf("assemble", "bundle", "build")
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
        "REWARDS_API_BASE_URL" to rewardsApiBaseUrl,
    ).filter { (_, value) -> value.isBlank() }.map { (name, _) -> name }
    check(missing.isEmpty()) { "Commercial Release requires explicit advertising configuration: ${missing.joinToString()}" }
    check(isProductionRewardsApiBaseUrl(rewardsApiBaseUrl)) {
        "REWARDS_API_BASE_URL must be an explicit non-local HTTPS URL for a commercial Release build."
    }
    check(isAdmobAndroidAppId(partnerAdmobAppId)) {
        "ADMOB_APP_ID must be the one Android AdMob App ID in ca-app-pub-...~... format, not a ca-app-pub-.../... ad unit ID."
    }
    check(externalReportingEnabled) {
        "Commercial Release requires EXTERNAL_REPORTING_ENABLED=true so AppsFlyer, Firebase, Xuanyu and the Rewards delivery ledger remain enabled."
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
}

if (googleServicesConfigPresent) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    // Source namespace remains stable for existing Activity and resource references. Google Play,
    // Firebase, AppsFlyer and TopOn identify the shipped app by applicationId below.
    namespace = "com.dramaflow.app"
    compileSdk = 36

    defaultConfig {
        // Formal Play distribution package. Firebase, AppsFlyer and TopOn production settings must
        // all be registered against this same identifier.
        applicationId = formalApplicationId
        minSdk = 26
        targetSdk = 36
        // Android 客户端每次代码改动递增补丁版本；服务端/文档单独改动不变更此版本。
        versionCode = 93
        versionName = "1.7.2.6"

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
        // Formal content always comes from Pangle/PSSDK; local Content API builds were removed.
        buildConfigField("boolean", "SHORT_DRAMA_SDK_ENABLED", "true")
        buildConfigField("String", "SHORT_DRAMA_APP_ID", "\"${escapeBuildConfig(shortDramaAppId)}\"")
        buildConfigField("String", "SHORT_DRAMA_VOD_APP_ID", "\"${escapeBuildConfig(shortDramaVodAppId)}\"")
        buildConfigField("String", "SHORT_DRAMA_LICENSE_ASSET", "\"${escapeBuildConfig(shortDramaLicenseAsset)}\"")
        buildConfigField("String", "SHORT_DRAMA_SECURITY_KEY", "\"${escapeBuildConfig(shortDramaSecurityKey)}\"")
        buildConfigField("boolean", "ANALYTICS_DEBUG", analyticsDebug.toString())
        buildConfigField("boolean", "EXTERNAL_REPORTING_ENABLED", externalReportingEnabled.toString())
        buildConfigField("boolean", "RELEASE_AB_ROUTING_ENABLED", releaseAbRoutingEnabled.toString())
        // Local ADN/reporting reconciliation is an internal Debug-only tool. Production packages
        // never create its files, expose its UI, or include its FileProvider.
        buildConfigField("boolean", "LOCAL_AUDIT_ENABLED", "false")
        resValue("string", "app_name", "JoyeDrama")
        buildConfigField("String", "REWARDS_API_BASE_URL", "\"${escapeBuildConfig(rewardsApiBaseUrl)}\"")
        manifestPlaceholders["ADMOB_APP_ID"] = topOnAdmobAppId
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SCREENSHOT_PROTECTION_ENABLED", "false")
            buildConfigField("boolean", "LOCAL_AUDIT_ENABLED", "true")
            // Debug 使用 TOPON_DEMO_MODE 的构建参数；默认 false，显式开启后自动使用官方 Demo 凭据。
        }
        release {
            // Release activity enables FLAG_SECURE; debug remains screenshot-friendly for device verification.
            buildConfigField("boolean", "SCREENSHOT_PROTECTION_ENABLED", "true")
            buildConfigField("boolean", "LOCAL_AUDIT_ENABLED", "false")
            // A commercial release never inherits Demo IDs, even if a developer has enabled Demo mode locally.
            buildConfigField("String", "TOPON_APP_ID", "\"${escapeBuildConfig(partnerTopOnAppId)}\"")
            buildConfigField("String", "TOPON_APP_KEY", "\"${escapeBuildConfig(partnerTopOnAppKey)}\"")
            buildConfigField("String", "TOPON_REWARDED_PLACEMENT_ID", "\"${escapeBuildConfig(partnerTopOnRewardedPlacementId)}\"")
            buildConfigField("String", "TOPON_INTERSTITIAL_PLACEMENT_ID", "\"${escapeBuildConfig(partnerTopOnInterstitialPlacementId)}\"")
            buildConfigField("String", "TOPON_SPLASH_PLACEMENT_ID", "\"${escapeBuildConfig(partnerTopOnSplashPlacementId)}\"")
            buildConfigField("boolean", "TOPON_DEMO_MODE", "false")
            buildConfigField("boolean", "ANALYTICS_DEBUG", "false")
            // Formal release always uses the Pangle short-drama SDK and AppsFlyer attribution split.
            buildConfigField("boolean", "RELEASE_AB_ROUTING_ENABLED", "true")
            buildConfigField("String", "REWARDS_API_BASE_URL", "\"${escapeBuildConfig(rewardsApiBaseUrl)}\"")
            manifestPlaceholders["ADMOB_APP_ID"] = partnerAdmobAppId
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }
    // This legacy project keeps shared Kotlin under src/main/java. Register the variant-only
    // Kotlin directories explicitly so Debug owns the audit implementation and Release owns
    // the no-op implementation without packaging both.
    sourceSets {
        getByName("debug").java.srcDir("src/debug/kotlin")
        getByName("release").java.srcDir("src/release/kotlin")
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    // Android 12+ system splash attributes referenced by the formal launcher theme.
    implementation("androidx.core:core-splashscreen:1.0.1")

    // TopOn mediation core. In the current 6.5.73.3 distribution it already bundles the
    // interstitial, rewarded and splash APIs; adding the older standalone format AARs would
    // create duplicate classes during release dexing.
    implementation("com.anythink.sdk:core-tpn:6.5.73.3")
    // TopOn direct demand: AdMob, Unity Ads, Pangle, TaurusX, Kwai, Liftoff/Vungle,
    // Mintegral and TopOn ADX. Keep shared AdMob, Unity, Pangle, Vungle and Mintegral SDK
    // versions aligned with MAX below; direct adapters are packaged now so those networks can be
    // enabled in the TopOn console without another client release.
    implementation("com.anythink.sdk:adapter-tpn-sdm:6.5.68.1.1")
    implementation("com.smartdigimkttech.sdk:smartdigimkttech-sdk:6.5.68")
    implementation("com.anythink.sdk:adapter-tpn-admob:6.5.40")
    implementation("com.anythink.sdk:adapter-tpn-unityads:4.20.0.1.0")
    implementation("com.anythink.sdk:adapter-tpn-pangle:8.1.0.3.1.0")
    implementation("com.anythink.sdk:adapter-tpn-taurusx:1.18.4.1.0")
    implementation("com.anythink.sdk:adapter-tpn-kwai:1.2.26.1.0")
    implementation("com.anythink.sdk:adapter-tpn-vungle:7.7.7.1.0")
    implementation("com.anythink.sdk:adapter-tpn-mintegral:17.1.71.1.0")
    // TopOn adapters do not declare these native network SDKs transitively. Keep the versions
    // exactly aligned with the adapter versions verified by the direct-source diagnostic build.
    implementation("com.taurusx.tax:ads:1.18.4")
    implementation("io.github.kwainetwork:adApi:1.2.26")
    implementation("io.github.kwainetwork:adImpl:1.2.26")
    implementation("com.google.android.material:material:1.2.1")
    implementation("androidx.annotation:annotation:1.2.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.4.10")
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.1")

    // Full production matrix: MAX is a TopOn Custom Network ad source and retains its bridge.
    // MAX's network SDKs/adapters below are also shared by TopOn's direct source adapters.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.applovin:applovin-sdk:13.6.0")
    implementation("io.github.alex-only:max_adapter_tpn:1.3.1")
    implementation("com.applovin.mediation:google-adapter:24.6.0.0")
    implementation("com.applovin.mediation:bytedance-adapter:8.1.0.3.0")
    implementation("com.applovin.mediation:vungle-adapter:7.7.7.1")
    implementation("com.applovin.mediation:unityads-adapter:4.20.1.0")
    implementation("com.applovin.mediation:mintegral-adapter:17.1.71.0")

    // Attribution and analytics.
    implementation("com.appsflyer:af-android-sdk:6.17.5")
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-analytics")

    // 正式短剧目录与播放器 SDK。
    implementation("com.bytedance.dramaverse:pssdk:1.8.0.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
