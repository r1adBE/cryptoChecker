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
    }
}

// Kotlin ist ab AGP 9 eingebaut; jvmTarget folgt compileOptions (Java 17).
