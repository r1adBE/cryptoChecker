pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Nur für das Foojay-Plugin (gibt es nur dort). Fällt Maven Central kurz aus, holte Gradle die
        // Kotlin-Plugin-Marker sonst vom Portal, dessen POM anders aussieht als die geprüfte von Central
        // (gradle/verification-metadata.xml) — die Prüfung schlug dann fehl.
        gradlePluginPortal {
            content { includeGroupByRegex("org\\.gradle\\.toolchains.*") }
        }
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
include(":data")
include(":resources")
include(":coreui")
include(":widget")
include(":app")
// Wear-OS-App
include(":wear")
// Erzeugt das Baseline Profile der App (nur auf Gerät/Emulator, siehe DEVELOPMENT.md)
include(":baselineprofile")
