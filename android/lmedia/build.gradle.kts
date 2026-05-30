plugins {
    id("com.android.library")
    kotlin("android")
    alias(libs.plugins.kotlin.serialization)
}

android {
    compileSdk = libs.versions.compile.version.get().toIntOrNull()
    namespace = "com.lalilu.lmedia"
    ndkVersion = "21.0.6113669"

    defaultConfig {
        minSdk = 21
    }
    buildTypes {
        release {
            consumerProguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    lint {
        disable += "FlowOperatorInvokedInComposition"
        disable += "CoroutineCreationDuringComposition"
    }
    externalNativeBuild {
        cmake {
            path = File("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.startup.runtime)

    implementation(libs.kotlin.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xmlutil.core)
    implementation(libs.xmlutil.serialization)

    api("androidx.media3:media3-common:1.5.1")

    // https://github.com/sachiotomita/kanhira
    // https://github.com/cy745/kanhira
    // 汉字转平假名库
    implementation("com.github.cy745:kanhira:2de73b1f0a")
}
