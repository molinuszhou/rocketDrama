pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://artifact.bytedance.com/repository/pangle")
        maven(url = "https://artifact.bytedance.com/repository/Volcengine/")
        maven(url = "https://artifact.byteplus.com/repository/public/")
        maven(url = "https://jfrog.anythinktech.com/artifactory/overseas_sdk")
        maven(url = "https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
        // TaurusX Android SDK source for the native dependency paired with its TopOn adapter.
        maven(url = "https://bitbucket.org/sdkcenter/sdkcenter/raw/release")
    }
}

rootProject.name = "DramaFlow"
include(":app")
