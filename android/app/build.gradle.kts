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

val pangleAppId = integrationValue("PANGLE_APP_ID")
val pangleRewardedSlotId = integrationValue("PANGLE_REWARDED_SLOT_ID")
val pangleInterstitialSlotId = integrationValue("PANGLE_INTERSTITIAL_SLOT_ID")
val pangleAppOpenSlotId = integrationValue("PANGLE_APP_OPEN_SLOT_ID")
val panglePauseNativeSlotId = integrationValue("PANGLE_PAUSE_NATIVE_SLOT_ID")
val localContentApiBaseUrl = integrationValue("LOCAL_CONTENT_API_BASE_URL").ifBlank { "http://10.0.2.2:8000" }
val rewardsApiBaseUrl = integrationValue("REWARDS_API_BASE_URL").ifBlank { "http://10.0.2.2:8100" }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "PANGLE_APP_ID", "\"${escapeBuildConfig(pangleAppId)}\"")
        buildConfigField("String", "PANGLE_REWARDED_SLOT_ID", "\"${escapeBuildConfig(pangleRewardedSlotId)}\"")
        buildConfigField("String", "PANGLE_INTERSTITIAL_SLOT_ID", "\"${escapeBuildConfig(pangleInterstitialSlotId)}\"")
        buildConfigField("String", "PANGLE_APP_OPEN_SLOT_ID", "\"${escapeBuildConfig(pangleAppOpenSlotId)}\"")
        buildConfigField("String", "PANGLE_PAUSE_NATIVE_SLOT_ID", "\"${escapeBuildConfig(panglePauseNativeSlotId)}\"")
        buildConfigField("String", "LOCAL_CONTENT_API_BASE_URL", "\"${escapeBuildConfig(localContentApiBaseUrl)}\"")
        buildConfigField("String", "REWARDS_API_BASE_URL", "\"${escapeBuildConfig(rewardsApiBaseUrl)}\"")
    }

    buildTypes {
        release {
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
    implementation("com.pangle.global:ads-sdk:6.5.1.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
