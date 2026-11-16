// Die Widgets der App (Liste, Einzel, Portfolio, Puls): Anbieter, Zeichnen, Einrichten. Paket wie bisher
// (com.cryptochecker.app.widget; Paket = Modul, Regel layers in source_rules.py). Was sie von der App brauchen (Planung,
// Portfolio-Alarme, Uhr, Prüfung nach Rückkehr des Netzes), kommt über Schnittstellen (WidgetHostPorts.kt), verbunden
// in :app (di/WidgetHostModule.kt); die Einträge im Manifest stehen in :app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.cryptochecker.widget"
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
    api(project(":coreui"))
    implementation(project(":marketdata"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.timber)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
