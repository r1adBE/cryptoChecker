pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // Steht vor dem Versionskatalog fest (libs gibt es hier noch nicht), darum mit Version im Klartext
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Versionskatalog: gradle/libs.versions.toml (Gradle liest ihn ohne weitere Angabe als «libs»)
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cryptoChecker"

include(":marketdata")
include(":domain")
include(":app")
// Erzeugt das Baseline Profile der App (nur auf Gerät/Emulator, siehe DEVELOPMENT.md)
include(":baselineprofile")
