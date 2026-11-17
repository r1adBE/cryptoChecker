// Wurzel-Buildskript. Alle Versionen stehen im Versionskatalog gradle/libs.versions.toml.
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    // AGP 9 bringt Kotlin eingebaut mit (ohne Plugin org.jetbrains.kotlin.android).
    // Die Kotlin-Version kommt über diesen Klassenpfad; Hilt 2.60 braucht >= 2.3.21.
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        // Build-Werkzeuge (AGP, Lint, Plugins) ziehen ältere Bibliotheken mit bekannten Lücken: Mindestversionen
        // (Liste und Gründe im Versionskatalog, Abschnitt «buildtool»)
        constraints {
            listOf(
                libs.buildtool.bcprov,
                libs.buildtool.bcpkix,
                libs.buildtool.bcutil,
                libs.buildtool.guava,
                libs.buildtool.jose4j,
                libs.buildtool.jdom2,
                libs.buildtool.commons.lang3,
                libs.buildtool.freemarker,
            ).forEach { classpath(it) }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    // Nur auf dem Klassenpfad; angewendet in app/ und marketdata/ erst mit -Pcoverage=true
    alias(libs.plugins.kover) apply false
}

// Lieferkette: Abhängigkeiten je Modul gesperrt (gradle.lockfile, erzeugt vom Workflow «Supply chain update»).
// Ohne Lockfile LENIENT (keine Prüfung), mit Lockfile DEFAULT: eine Abweichung von der gesperrten Version bricht
// ab, Konfigurationen ohne Eintrag (z. B. nur auf dem Gerät aufgelöste) bleiben erlaubt. Die Prüfsummen
// (gradle/verification-metadata.xml) prüft Gradle von selbst, sobald die Datei existiert. Siehe DEVELOPMENT.md.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(if (file("gradle.lockfile").exists()) LockMode.DEFAULT else LockMode.LENIENT)
    }
}

// Kotlin-Warnungen brechen den Build in den App- und Bibliotheksquellen ab (main, debug, withoutHttpLog), auch wenn
// ein Modul aus dem Gradle-Build-Cache kommt. Testquellen (Aufgaben *UnitTest*/*AndroidTest*) zählen nicht. :data
// fehlt: dort kompiliert dieselbe Aufgabe den von Room erzeugten Kotlin-Code, dessen Warnungen der Schritt
// «Kotlin warnings» in android.yml ausfiltert (er zählt nur Zeilen aus src/main).
val warningsAsErrorsModules = setOf("app", "coreui", "domain", "marketdata", "wear", "widget")
subprojects {
    if (name in warningsAsErrorsModules) {
        tasks.withType<KotlinCompile>().configureEach {
            if (!name.contains("Test")) compilerOptions.allWarningsAsErrors.set(true)
        }
    }
}

// Jede Test-Aufgabe kennt die Dateien, die die Tests ausserhalb ihres Klassenpfads lesen (gemeinsame Testfälle,
// Swift-Quellen, Nachbarmodule …): eine Änderung dort lässt die Tests neu laufen und ändert den Cache-Schlüssel.
subprojects {
    apply(from = rootProject.file("gradle/test-inputs.gradle.kts"))
}

// KSP (Room, Hilt) immer vollständig und nacheinander: Die CI baute mehrmals ein Debug-APK ohne die von Room
// erzeugten Klassen (Absturz «AppDatabase_Impl does not exist», :data ohne erzeugte Java-Dateien), lokal kam eine
// unvollständige KSP-Ausgabe aus dem Build-Cache. Darum: KSP-Ausgaben nie aus dem Cache, nie inkrementell
// (gradle.properties `ksp.incremental=false`), höchstens eine KSP-Aufgabe gleichzeitig; :data prüft danach, dass die
// Room-Klassen da sind (data/build.gradle.kts).
abstract class KspSerial : BuildService<BuildServiceParameters.None>

val kspSerial = gradle.sharedServices.registerIfAbsent("kspSerial", KspSerial::class) { maxParallelUsages.set(1) }
subprojects {
    tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach {
        usesService(kspSerial)
        outputs.cacheIf("KSP-Ausgabe immer neu erzeugen") { false }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
