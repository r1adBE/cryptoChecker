plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cryptochecker.marketdata"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Regeln für jede App, die das Modul einbindet und verkleinert (Börsenklassen, Fehlerklassen)
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
        // Wie im App-Modul: Versions-Updates laufen über «Supply chain update» und OSV, der targetSdk-Wechsel ist
        // eine bewusste Entscheidung.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// Kotlin ist ab AGP 9 eingebaut; jvmTarget folgt compileOptions (Java 17).

dependencies {
    // Unit-Tests der reinen Logik (src/test; ohne org.json, das läuft gegen die Android-Stubs)
    testImplementation(libs.junit)
}

// Testabdeckung nur auf Anfrage (CI-Schritt «Coverage (logic gate)»: -Pcoverage=true), sonst laufen die Tests ohne
// Instrumentierung; die Schwelle prüft die CI über den zusammengeführten Bericht von :app
if (providers.gradleProperty("coverage").orNull == "true") {
    apply(plugin = libs.plugins.kover.get().pluginId)
}
