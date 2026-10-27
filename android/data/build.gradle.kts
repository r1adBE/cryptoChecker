// Speicher und Netz der App (R, Build 142): Room-Datenbanken (Merkliste, Alarme, Portfolio) mit DAOs und
// Verschlüsselung, Repositories, Börsen-, Kerzen- und Adress-Datenquellen, Sicherung. Die Pakete bleiben
// com.cryptochecker.app.*; Hilt-Module (Datenbanken, OkHttp) bleiben in :app. Die Room-Schemas bleiben unter
// app/schemas (Migrationstests und Schema-Drift-Prüfung lesen sie dort).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.cryptochecker.data"
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
        checkDependencies = true
        checkReleaseBuilds = true
        // Wie in :app, :domain und :marketdata
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// Room schreibt sein Schema in den bisherigen Ordner der App (Migrationstests, room_schema_drift.py)
room {
    schemaDirectory("$rootDir/app/schemas")
}

// Kotlin ist ab AGP 9 eingebaut; jvmTarget folgt compileOptions (Java 17).

dependencies {
    // Modelle und Regeln; :domain gibt :marketdata, Coroutines und Room-Runtime weiter
    api(project(":domain"))
    implementation(libs.androidx.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Datenbank: Room-Typen (RoomDatabase, DAOs) stehen in öffentlichen Signaturen
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // SQLCipher wie bisher in :app: nur das AAR ohne Abhängigkeiten; androidx.sqlite liefert room-runtime
    implementation(libs.sqlcipher.android) {
        artifact {
            type = "aar"
            extension = "aar"
        }
        isTransitive = false
    }

    api(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
}

// Testabdeckung nur auf Anfrage (wie :domain); die Tests der Datenschicht laufen vorerst in :app, dessen Bericht
// :data mitmisst
if (providers.gradleProperty("coverage").orNull == "true") {
    apply(plugin = libs.plugins.kover.get().pluginId)
}
