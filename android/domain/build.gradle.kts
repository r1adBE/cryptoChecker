import org.gradle.api.tasks.testing.logging.TestExceptionFormat

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

    // Tests der Fachlogik (src/test, Audit C-03): dieselbe Umgebung wie die Unit-Tests von :app — echtes org.json
    // (AOSP-Code, vor android.jar: JsonCompatibilityTest vergleicht das eigene JSON damit, die Börsenklassen aus
    // :marketdata lesen damit) und virtuelle Zeit (runTest)
    testImplementation(libs.junit)
    testImplementation(libs.android.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

// JVM-Unit-Tests mit fester Sprache und Zeitzone wie in :app (app/build.gradle.kts, testOptions); fehlgeschlagene
// Tests mit voller Meldung im Log
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Testabdeckung nur auf Anfrage (wie :marketdata); der Bericht von :app (koverXmlReportDebug) führt die Tests
// dieses Moduls mit denen von :app zusammen
if (providers.gradleProperty("coverage").orNull == "true") {
    apply(plugin = libs.plugins.kover.get().pluginId)
}
