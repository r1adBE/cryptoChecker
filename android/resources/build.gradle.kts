// Ressourcen der App (Texte in 75 Sprachen, Layouts der Widgets, Bilder, Schrift, XML-Konfiguration) als eigenes
// Modul: :app und weitere Module (z. B. die Widgets) teilen dieselben Texte. Kein Kotlin-Code; R-Klasse
// com.cryptochecker.resources.R. Geprüft werden die Ressourcen von Lint in :app (checkDependencies).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cryptochecker.resources"
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
        checkReleaseBuilds = true
        // Wie in :app
        disable += setOf(
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi", "Typos",
            "TypographyDashes", "TypographyFractions", "UnusedTranslation",
        )
    }
}

dependencies {
    // Eltern der Themes (Theme.AppCompat…, Theme.SplashScreen); beide nutzt :app ohnehin
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
}
