// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.application) apply false
    alias(libs.plugins.library) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.flyjingfish.aop) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.krouter.plugin)
    alias(libs.plugins.lumo)
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.gradle.BaseExtension>("android") {
            buildToolsVersion = "36.1.0"
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.gradle.BaseExtension>("android") {
            buildToolsVersion = "36.1.0"
        }
    }
}

// 配置注入遍历的起点项目
ext { set("targetInjectProjectName", "app") }
