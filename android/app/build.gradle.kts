import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Typsichere Navigationsziele (ScreenRoute)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Baseline Profile: generiert von :baselineprofile, sonst gilt src/main/baseline-prof.txt
    alias(libs.plugins.baselineprofile)
}

// Signierschlüssel liegt ausserhalb der Versionsverwaltung.
// Vorlage: keystore.properties.example im Projektwurzelverzeichnis.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
// Als Map<String, String>: Zugriff ohne Typumwandlung (keystore["…"])
val keystore: Map<String, String> =
    keystoreProperties.stringPropertyNames().associateWith { keystoreProperties.getProperty(it) }

// Store-Build: «./gradlew -PrequireReleaseSigning=true :app:bundleRelease» bricht ohne keystore.properties ab,
// statt still ein unsigniertes Bundle zu bauen. Ohne die Eigenschaft (CI, lokale Builds) bleibt alles wie bisher.
if (providers.gradleProperty("requireReleaseSigning").orNull == "true" && !keystorePropertiesFile.exists()) {
    throw GradleException(
        "requireReleaseSigning=true, aber ${keystorePropertiesFile.path} fehlt (Vorlage: keystore.properties.example)",
    )
}

// Quell-Commit für «Diagnose teilen». Store-Build: -PgitCommit=<Hash aus dem public-Klon>, CI: GITHUB_SHA,
// sonst «local». Kein git-Aufruf beim Konfigurieren (Konfigurations-Cache). REL3-R1: Nur ein voller
// Commit-Hash (40 Zeichen 0-9a-f) zählt, alles andere wird «local» (und der Guard unten bricht beim Store-Build ab).
val gitCommitRaw: String = providers.gradleProperty("gitCommit").orElse(providers.environmentVariable("GITHUB_SHA"))
    .getOrElse("").trim()
val gitCommit: String = gitCommitRaw.takeIf { Regex("^[0-9a-f]{40}$").matches(it) } ?: "local"

// Store-Build (requireReleaseSigning=true) ohne Commit bricht ab, statt «local» in die Diagnose zu
// schreiben. Geprüft erst im fertigen Task-Graph und nur, wenn ein Release-Bundle/-APK gebaut wird (Debug-Builds,
// Tests und CI ohne die Eigenschaft bleiben unberührt); whenReady läuft vor dem Speichern des Konfigurations-Caches.
val requireReleaseCommit = providers.gradleProperty("requireReleaseSigning").orNull == "true" && gitCommit == "local"
if (requireReleaseCommit) {
    val appPath = project.path
    val received = gitCommitRaw
    gradle.taskGraph.whenReady {
        val releaseTasks = listOf("bundleRelease", "assembleRelease", "packageReleaseBundle", "packageRelease")
        if (releaseTasks.any { hasTask("$appPath:$it") }) {
            throw GradleException(
                "requireReleaseSigning=true, aber gitCommit fehlt oder ist kein voller Commit-Hash (40 Zeichen 0-9a-f, " +
                    "erhalten: «$received»). Commit des Tags aus dem öffentlichen Klon übergeben: -PgitCommit=<Ausgabe " +
                    "von «git -C <public-Klon> rev-parse \"v<versionName>-<versionCode>^{commit}\"»> (siehe docs/PLAY_STORE.md §3)",
            )
        }
    }
}

// Reproduzierbarer Build: der Compose-Compiler legt sonst eine Zuordnung der Gruppen-Schlüssel für R8 an
// (Abschnitt «ComposeStackTrace» in proguard.map), deren Reihenfolge von Lauf zu Lauf wechselt; die APK bleibt gleich,
// aber die Mapping-Datei im AAB (BUNDLE-METADATA) nicht. Ohne Absturzberichte wird sie nicht gebraucht.
composeCompiler {
    includeComposeMappingFile.set(false)
}

android {
    namespace = "com.cryptochecker.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.cryptochecker.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        versionCode = 160
        versionName = "16.2.2"

        // Quell-Commit für «Diagnose teilen» (Berechnung siehe gitCommit oben).
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")

        // Instrumentierte Tests mit Hilt (HiltTestApplication); betrifft nur androidTest
        testInstrumentationRunner = "com.cryptochecker.app.HiltTestRunner"
    }

    buildFeatures {
        compose = true
        // BuildConfig ist seit AGP 8 nicht mehr automatisch aktiv.
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystore.getValue("storeFile"))
                storePassword = keystore["storePassword"]
                keyAlias = keystore["keyAlias"]
                keyPassword = keystore["keyPassword"]
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Symboltabellen der nativen Bibliotheken (SQLCipher) ins AAB: Play Console zeigt native Abstürze
            // damit mit Funktionsnamen. Fehlt das NDK lokal, meldet AGP das nur als Warnung.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }

            // Ohne keystore.properties bleibt der Build unsigniert, statt zu scheitern.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            // Debug- und Release-App lassen sich so nebeneinander installieren.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // Fremd-Datei aus kotlinx-serialization (Prüfwerte des Herstellers, für die App ohne Nutzen): nicht ins APK/AAB —
    // Geheimnis-Scanner melden sie sonst als Token (Audit F-23)
    packaging {
        resources {
            excludes += "META-INF/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/verification.properties"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // UI-Tests (androidTest): gemeinsame Testdateien beider Plattformen als Assets des Test-APK
    // (die App selbst enthält sie nicht); iOS bekommt dieselben über tools/gen_xcodeproj.py.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$rootDir/testdata/uitest")
            // Room-Schemas für die Migrationstests (RoomSchemaMigrationTest, MigrationTestHelper); exportiert von
            // :data (Room-Plugin) in diesen Ordner — vorher legte das Room-Plugin in :app sie selbst bei
            assets.directories.add("$projectDir/schemas")
        }
    }

    lint {
        abortOnError = true
        enable += "LogNotTimber"
        // Versions-Updates laufen bewusst über den Workflow «Supply chain update» und OSV (dazu muss
        // gradle/verification-metadata.xml passen), nicht über Lint-Hinweise.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // Der Wechsel des targetSdk ist eine bewusste, getestete Entscheidung.
        disable += "OldTargetApi"
        // Die englische Wörterliste schlägt in übersetzten Texten falsch an (de «seit dem», Schweizer «ss»,
        // Fachwörter wie «gas» in pt/tr).
        disable += "Typos"
        // Datumsangaben (1-3-2021, 1/3/2021), Aktenzeichen (Rev. Proc. 2024-28) und Quoten (1/2) in den Steuertexten
        // stehen bewusst so; iOS übernimmt dieselben Texte (ios/tools/convert_strings.py).
        disable += setOf("TypographyDashes", "TypographyFractions")
        // Ordner values-in/values-iw (alte Codes) und id/he in res/xml/locales_config.xml sind dieselben Sprachen.
        disable += "UnusedTranslation"
    }

    // JVM-Unit-Tests mit fester Sprache und Zeitzone: Erwartete Datums- und Zahlentexte sollen auf jedem Rechner gleich
    // ausfallen (lokal etwa de-CH/Europe/Zurich, im CI en-US/UTC). Tests, die eine andere Locale brauchen, setzen sie
    // selbst und stellen sie danach zurück.
    // Leistungstests (Paket performance, Zeitmessungen) laufen nicht im normalen Unit-Test-Lauf: Parallel zu Lint
    // oder anderen Tests schwanken die Zeiten. Eigener Lauf: -PperformanceTests=only (CI-Schritt «Performance tests»,
    // allein); -PperformanceTests=true nimmt sie in den normalen Lauf auf.
    val performanceTests = providers.gradleProperty("performanceTests").orNull
    testOptions {
        unitTests.all {
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
            it.systemProperty("user.timezone", "UTC")
            when (performanceTests) {
                "only" -> it.filter.includeTestsMatching("com.cryptochecker.app.performance.*")
                "true" -> Unit
                else -> it.exclude("**/performance/**")
            }
        }
    }

    // Nur die 31 App-Sprachen (res/xml/locales_config.xml) ins APK — Übersetzungen der Bibliotheken in weiteren Sprachen
    // fallen weg. Ordnernamen wie in res/ (iw, in, nb, pt-rBR) und dazu die neuen Codes «he»/«id», damit keine Sprache je
    // nach Schreibweise wegfällt; LocaleFiltersTest (androidTest) prüft das auf dem Gerät.
    androidResources {
        localeFilters += listOf(
            "en", "de", "fr", "it", "es", "pt", "pt-rBR", "nl", "pl", "cs", "hu", "ro", "sq", "el", "tr", "sv", "da",
            "nb", "fi", "ru", "uk", "ar", "iw", "he", "fa", "hi", "th", "vi", "in", "id", "zh", "ja", "ko",
        )
    }

    // Die App hat eine eigene Sprachwahl (AppCompatDelegate.setApplicationLocales). Mit
    // Sprach-Splits liefert Play aus dem AAB nur die Gerätesprachen aus; eine andere in der App
    // gewählte Sprache fiele dann auf Englisch zurück. Darum alle Sprachen ins Basis-APK.
    bundle {
        language {
            enableSplit = false
        }
    }

}

// HttpLogInterceptorProvider: Debug hat ihn in src/debug (mit logging-interceptor), alle anderen
// Build-Typen — Release und die vom Baseline-Profile-Plugin abgeleiteten (nonMinifiedRelease,
// benchmarkRelease) — die leere Fassung ohne Mitschnitt.
androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType != "debug") {
            variant.sources.java?.addStaticSourceDirectory("src/withoutHttpLog/java")
        }
    }
}

// Kotlin ist ab AGP 9 eingebaut; jvmTarget folgt compileOptions (Java 17).

dependencies {
    implementation(project(":marketdata"))
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)

    // Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Jetpack Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.util)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.core)

    // hiltViewModel() liegt seit androidx.hilt 1.3.0 in einem eigenen Artefakt.
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.constraintlayout.compose)

    // Datenbank
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // SQLCipher: beide Room-Datenbanken verschlüsselt (EncryptingOpenHelperFactory); bringt libsqlcipher.so je ABI mit.
    // Nur das AAR ohne Abhängigkeiten (wie «…@aar»): androidx.sqlite (SupportSQLiteOpenHelper, sqlite-framework)
    // liefert room-runtime als api
    implementation(libs.sqlcipher.android) {
        artifact {
            type = "aar"
            extension = "aar"
        }
        isTransitive = false
    }

    // Netzwerk
    implementation(libs.okhttp)
    // HTTP-Mitschnitt (Entwickler-Protokoll) nur im Debug-Build; die übrigen Build-Typen bekommen
    // stattdessen src/withoutHttpLog (siehe androidComponents oben)
    debugImplementation(libs.okhttp.logging.interceptor)

    // Hintergrund-Aktualisierung (Kurse, Alarme, Widgets)
    implementation(libs.androidx.work.runtime.ktx)
    // Uhr-App: Coin-Liste über die Wear-Daten-Schicht
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Einstellungen
    implementation(libs.androidx.datastore.preferences)

    // App-Sperre
    implementation(libs.androidx.biometric)

    // Logging
    implementation(libs.timber)

    // Baseline Profile beim Installieren anwenden (auch ohne Play Store) — schnellerer Start
    implementation(libs.androidx.profileinstaller)
    // Konfiguration über ihren Namen: «baselineProfile» heisst auch die Erweiterung des Plugins
    "baselineProfile"(project(":baselineprofile"))

    testImplementation(libs.junit)
    // Echtes org.json (AOSP-Code) für Unit-Tests: die Börsen-Parser (ExchangeContractTest) laufen sonst
    // gegen die Android-Stubs («Method … not mocked»). Muss vor android.jar im Test-Classpath liegen.
    testImplementation(libs.android.json)
    // Virtuelle Zeit (runTest, StandardTestDispatcher), Flows prüfen (Turbine) und ein echter lokaler HTTP-Server
    // für die Netzschicht (ExchangeHttpTest: 429/Retry-After, Grössengrenze, callTimeout) — ohne Robolectric
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver3)

    // Instrumentierte Tests (Kernablauf, CoreFlowTest): nicht Teil der App, nur androidTest
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    androidTestImplementation(libs.androidx.work.testing)
    // WatchlistViewModelTest: Zustand mit Turbine über runTest beobachten
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    // Migrationen aus app/schemas prüfen (RoomSchemaMigrationTest); die Schemas liegen als Assets im Test-APK
    // (sourceSets oben), exportiert von :data
    androidTestImplementation(libs.androidx.room.testing)

    // Gleiche Version in App und androidTest (sonst: «conflicting requirements … from lock file»
    // bei :app:assembleDebugAndroidTest und in Android Studio «Failed to resolve androidx.concurrent»).
    constraints {
        implementation(libs.androidx.concurrent.futures)
        implementation(libs.androidx.concurrent.futures.ktx)
    }
}

// Fehlgeschlagene Unit-Tests mit voller Meldung im Log (CI zeigt sonst nur «AssertionError at …»)
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Testabdeckung nur auf Anfrage (CI-Schritt «Coverage (logic gate)»: -Pcoverage=true, Bericht
// :app:koverXmlReportDebug), sonst laufen die Unit-Tests ohne Instrumentierung. Die CI prüft daraus eine
// Mindestabdeckung der Logik (MIN_LOGIC in android.yml). Der Bericht von :app misst :marketdata
// mit: die Börsen-Parser laufen vor allem in den Vertragstests von :app (contract/), nicht in :marketdata selbst.
if (providers.gradleProperty("coverage").orNull == "true") {
    apply(plugin = libs.plugins.kover.get().pluginId)
    dependencies {
        add("kover", project(":marketdata"))
        add("kover", project(":domain"))
        add("kover", project(":data"))
    }
}

// Lieferkette: XProcessing des Hilt-Compilers in allen Konfigurationen auf die Room-Version (sonst 2.8.4
// neben 2.8.5 in :data; Inhaber 27.10.: beim nächsten «Supply chain update» überall 2.8.5)
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.room" && requested.name == "room-compiler-processing") {
            useVersion(libs.versions.room.get())
            because("eine Version von room-compiler-processing in allen Modulen")
        }
    }
}
