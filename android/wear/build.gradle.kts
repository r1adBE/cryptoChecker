// Wear-OS-App: fragt die Kurse selbst bei den Börsen ab; Liste «Wie Handy»
// (Favoriten) oder «Eigene Liste» — nur diese Wahl kommt über die Wear-Daten-Schicht vom/zum Handy. Liste und
// Komplikation. Gleiches Paket wie die Handy-App (eine Store-Eintragung, Wear-Formfaktor); Versionscode 90 000 +
// Build-Nummer der Handy-App.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Build-Nummer und Version der Handy-App (app/build.gradle.kts), damit sie nur an einer Stelle stehen. */
val phoneGradle: String = rootProject.file("app/build.gradle.kts").readText()
val phoneBuild: Int = Regex("""versionCode = (\d+)""").find(phoneGradle)
    ?.groupValues?.get(1)?.toInt() ?: error("versionCode der Handy-App nicht gefunden")
val phoneVersion: String = Regex("""versionName = "([^"]+)"""").find(phoneGradle)
    ?.groupValues?.get(1) ?: error("versionName der Handy-App nicht gefunden")

// Gleicher Upload-Schlüssel wie :app (gleiches Paket, eine Store-Eintragung): keystore.properties im
// Projektwurzelverzeichnis (Vorlage keystore.properties.example). «-PrequireReleaseSigning=true» bricht ohne die Datei
// ab — der Store-Build (bundleRelease für alle Module) baut sonst still ein unsigniertes Uhr-Bundle.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val keystore: Map<String, String> =
    keystoreProperties.stringPropertyNames().associateWith { keystoreProperties.getProperty(it) }
if (providers.gradleProperty("requireReleaseSigning").orNull == "true" && !keystorePropertiesFile.exists()) {
    throw GradleException(
        "requireReleaseSigning=true, aber ${keystorePropertiesFile.path} fehlt (Vorlage: keystore.properties.example)",
    )
}

// Wie :app – keine Compose-Zuordnung in proguard.map (wechselnde Reihenfolge, reproduzierbarer Build)
composeCompiler {
    includeComposeMappingFile.set(false)
}

android {
    namespace = "com.cryptochecker.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.cryptochecker.app"
        minSdk = 30
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 90_000 + phoneBuild
        versionName = phoneVersion
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                // Relativ wie in :app (dort vom Ordner app/ aus); empfohlen ist ein absoluter Pfad
                storeFile = rootProject.file("app").resolve(keystore.getValue("storeFile"))
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Ohne keystore.properties bleibt der Build unsigniert, statt zu scheitern (wie :app)
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        getByName("debug") {
            // Wie :app: Die Debug-Uhr hat dasselbe Paket wie das Debug-Handy (com.cryptochecker.app.debug), sonst
            // finden sich die beiden über die Wear-Daten-Schicht nicht; neben der Release-Fassung installierbar
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME für den User-Agent (WatchPriceSource)
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Wie in :app und :marketdata
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

dependencies {
    // Coin-Liste und Wahl der Uhr (gemeinsam mit der Handy-App)
    implementation(project(":marketdata"))
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.complications.datasource)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // Echtes org.json für die Parser-Tests (wie :app)
    testImplementation(libs.android.json)
}
