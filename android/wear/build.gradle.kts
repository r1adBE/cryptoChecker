// Wear-OS-App (Entscheide Inhaber 27.10.): fragt die Kurse selbst bei den Börsen ab; Liste «Wie Handy»
// (Favoriten) oder «Eigene Liste» — nur diese Wahl kommt über die Wear-Daten-Schicht vom/zum Handy. Liste und
// Komplikation. Gleiches Paket wie die Handy-App (eine Store-Eintragung, Wear-Formfaktor); Versionscode 90 000 +
// Build-Nummer der Handy-App.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Build-Nummer der Handy-App (versionCode in app/build.gradle.kts), damit sie nur an einer Stelle steht. */
val phoneBuild: Int = Regex("""versionCode = (\d+)""").find(rootProject.file("app/build.gradle.kts").readText())
    ?.groupValues?.get(1)?.toInt() ?: error("versionCode der Handy-App nicht gefunden")

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
        versionName = "16.2.2"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
