import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Speicher und Netz der App: Room-Datenbanken (Merkliste, Alarme, Portfolio) mit DAOs und
// Verschlüsselung, Einstellungen, Repositories, Börsen-, Kerzen- und Adress-Datenquellen, Sicherung. Jede Datei liegt
// im Paket com.cryptochecker.app.data.* (Paket = Modul, LayerBoundaryTest); Hilt-Module (Datenbanken, OkHttp) bleiben
// in :app. Die Room-Schemas bleiben unter app/schemas (Migrationstests und Schema-Drift-Prüfung lesen sie dort).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.cryptochecker.data"
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
        // Wie in :app, :domain und :marketdata
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// Room schreibt sein Schema in den Ordner der App (app/schemas; Migrationstests, room_schema_drift.py)
room {
    schemaDirectory("$rootDir/app/schemas")
}

// Kotlin ist ab AGP 9 eingebaut; jvmTarget folgt compileOptions (Java 17).

dependencies {
    // Modelle und Regeln; :domain gibt :marketdata und Coroutines weiter
    api(project(":domain"))
    implementation(libs.androidx.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Datenbank: Room-Typen (RoomDatabase, DAOs) stehen in öffentlichen Signaturen
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // SQLCipher wie in :app: nur das AAR ohne Abhängigkeiten; androidx.sqlite liefert room-runtime
    implementation(libs.sqlcipher.android) {
        artifact {
            type = "aar"
            extension = "aar"
        }
        isTransitive = false
    }

    api(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)

    // Tests der Datenschicht (src/test, Audit C-03): dieselbe Umgebung wie die Unit-Tests von :app — echtes org.json
    // (AOSP-Code, vor android.jar; die Codecs und Parser hier lesen damit), virtuelle Zeit (runTest) und ein echter
    // lokaler HTTP-Server für die Netzschicht (ExchangeHttpTest)
    testImplementation(libs.junit)
    testImplementation(libs.android.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
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

// Testabdeckung nur auf Anfrage (wie :domain); der Bericht von :app (koverXmlReportDebug) führt die Tests
// dieses Moduls mit denen von :app zusammen
if (providers.gradleProperty("coverage").orNull == "true") {
    apply(plugin = libs.plugins.kover.get().pluginId)
}
