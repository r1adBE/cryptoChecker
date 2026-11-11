// Fachlogik ohne Oberfläche, Datenbank-Zugriff und Netz-Abläufe: Regeln, Berechnungen und Modelle, die :data und
// :app nutzen. Jede Datei liegt im Paket com.cryptochecker.app.domain.* (Paket = Modul, LayerBoundaryTest); kein
// Room, kein OkHttp. Nächste Schritte: Abläufe (Aktualisierung, Alarme) über Schnittstellen hierher,
// Android-Abhängigkeiten ablösen (für Kotlin Multiplatform).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.cryptochecker.domain"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        // Wie in :app und :marketdata
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// Kotlin ist ab AGP 9 eingebaut; jvmTarget folgt compileOptions (Java 17).

dependencies {
    // Typen von :marketdata und Flows stehen in öffentlichen Signaturen
    api(project(":marketdata"))
    api(libs.kotlinx.coroutines.android)
    // @Inject/@Singleton der Regeln mit Zustand (Dagger erzeugt die Fabriken hier, Hilt verbindet sie in :app)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.junit)
}

// Testabdeckung nur auf Anfrage (wie :marketdata); die Tests der Fachlogik laufen vorerst in :app, dessen Bericht
// :domain mitmisst
if (providers.gradleProperty("coverage").orNull == "true") {
    apply(plugin = libs.plugins.kover.get().pluginId)
}
