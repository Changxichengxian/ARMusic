plugins {
    id("com.android.application")
}

android {
    namespace = "com.lalilu.lmusic.exporter"
    compileSdk = libs.versions.compile.version.get().toInt()

    defaultConfig {
        applicationId = "com.lalilu.lmusic.alpha"
        minSdk = libs.versions.min.sdk.version.get().toInt()
        targetSdk = 34
        versionCode = 42
        versionName = "1.5.4-ALPHA_EXPORT_BRIDGE"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
}
