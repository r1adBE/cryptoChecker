// Erzeugt das Baseline Profile der App: startet sie, scrollt die Merkliste und öffnet ein
// Aktionsblatt (BaselineProfileGenerator). Läuft nur auf einem Gerät/Emulator (API 28+):
//   ./gradlew :app:generateBaselineProfile
// Ergebnis: app/src/release/generated/baselineProfiles/ (einchecken). Siehe DEVELOPMENT.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.cryptochecker.baselineprofile"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Angeschlossenes Gerät bzw. laufender Emulator (keine verwalteten Geräte)
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
