// Gemeinsame Grundlage der Oberflächen (App und Widgets): Farben, Schriften und Theme (ui.theme), Zahlen-, Zeit- und
// Screenreader-Texte (util), Darstellung und Sprache (settings). Pakete wie bisher (com.cryptochecker.app.ui.theme,
// …util, …settings; Paket = Modul, Regel layers in source_rules.py).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.cryptochecker.coreui"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
        // Wie in :app
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// Wie in :app: Gruppen-Schlüssel nicht in die Mapping-Datei (reproduzierbarer Build), stabile Modelle aus der Datei
composeCompiler {
    includeComposeMappingFile.set(false)
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("gradle/compose-stability.conf"))
}

dependencies {
    api(project(":domain"))
    api(project(":resources"))
    implementation(project(":marketdata"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.timber)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    api(libs.androidx.compose.material3)
}
