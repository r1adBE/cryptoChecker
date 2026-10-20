import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Typsichere Navigationsziele (ScreenRoute)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
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

android {
    namespace = "com.cryptochecker.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.cryptochecker.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        versionCode = 87
        versionName = "16.2.2"

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // UI-Tests (androidTest): gemeinsame Testdateien beider Plattformen als Assets des Test-APK
    // (die App selbst enthält sie nicht); iOS bekommt dieselben über tools/gen_xcodeproj.py.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$rootDir/testdata/uitest")
        }
    }

    lint {
        abortOnError = true
        enable += "LogNotTimber"
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

// Room schreibt sein Schema hierhin; unter KSP geht das nur über das Room-Plugin.
room {
    schemaDirectory("$projectDir/schemas")
}

// Kotlin ist ab AGP 9 eingebaut; jvmTarget folgt compileOptions (Java 17).

dependencies {
    implementation(project(":marketdata"))

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
    ksp(libs.androidx.room.compiler)
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
    // für die Netzschicht (MarketHttpTest: 429/Retry-After, Grössengrenze, callTimeout) — ohne Robolectric
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
