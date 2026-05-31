pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven("https://jitpack.io")
    }
}


rootProject.name = "ARMusic"
include(":app")
include(":common")
include(":component")
include(":crash")

include(":lmedia")
include(":lplayer")
include(":lplayer:lib-decoder-flac")

include(":lplaylist")
include(":lhistory")
include(":lartist")
include(":lalbum")
include(":lfolder")
