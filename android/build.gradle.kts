// Wurzel-Buildskript. Alle Versionen stehen im Versionskatalog gradle/libs.versions.toml.
buildscript {
    // AGP 9 bringt Kotlin eingebaut mit (ohne Plugin org.jetbrains.kotlin.android).
    // Die Kotlin-Version kommt über diesen Klassenpfad; Hilt 2.60 braucht >= 2.3.21.
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
