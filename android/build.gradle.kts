plugins {
    // AGP 8.13.2 bundles R8 8.13.19, which officially supports Kotlin 2.3 metadata
    // emitted by Google Mobile Ads SDK 25.4 and the matching TopOn AdMob adapter.
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
