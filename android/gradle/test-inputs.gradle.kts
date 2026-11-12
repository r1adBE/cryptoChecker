// Was die JVM-Unit-Tests ausserhalb ihres Klassenpfads lesen, als Eingabe jeder Test-Aufgabe (alle Module, angewendet
// im Wurzel-Buildskript). Viele Tests sind Quelltext-Wächter und Paritätstests: Sie suchen ab dem Arbeitsverzeichnis
// (`user.dir`) Dateien ausserhalb des Moduls — gemeinsame Testfälle, Swift-Quellen, Quellen und Tests der
// Nachbarmodule, Room-Schemas, Store-Texte, Datenschutzseite. Ohne diese Angabe hält Gradle die Aufgabe für
// «UP-TO-DATE» bzw. holt das Ergebnis aus dem Build-Cache, obwohl sich genau diese Dateien geändert haben.
//
// Pfade relativ zu `android/`. Beide Ablagen gelten: privater Baum (`public/.github`) und veröffentlichtes Repository
// (`.github` neben `android/`). Was im Checkout fehlt (reiner Android-Checkout ohne `ios/`), ist ein leerer Dateibaum
// und stört die Konfiguration nicht. Relative Pfade: der Cache-Schlüssel hängt nicht vom Ort des Checkouts ab.
//
// Neuer Leseort in einem Test → hier eintragen. `TestInputsGuardTest` (:app) liest die Liste zwischen den beiden
// Markierungen und schlägt fehl, wenn ein Test einen Ort im Baum nennt, der nicht darunter liegt.
import org.gradle.api.tasks.PathSensitivity

// testInputRoots:begin
val testInputRoots: List<String> = listOf(
    "LICENSE",
    "build.gradle.kts",
    "gradle/test-inputs.gradle.kts",
    "gradle/compose-stability.conf",
    "app/build.gradle.kts",
    "domain/build.gradle.kts",
    "data/build.gradle.kts",
    "testdata",
    "test-shared",
    "docs/privacy",
    "fastlane/metadata/android",
    "app/schemas",
    "app/src/main",
    "app/src/debug",
    "app/src/withoutHttpLog",
    "app/src/test",
    "app/src/androidTest",
    "domain/src/main",
    "domain/src/test",
    "data/src/main",
    "data/src/test",
    "marketdata/src/main",
    "marketdata/src/test",
    "wear/src/main",
    "wear/src/test",
    "../ios",
    "../.github/workflows",
    "../.github/scripts",
    "../public/.github/workflows",
    "../public/.github/scripts",
)
// testInputRoots:end

// Nur für die Ordner neben `android/` (beginnen mit `../`). Nie Quelltext: Build-Ergebnisse von Xcode/SwiftPM,
// Nutzerdaten von Xcode, Python-Zwischenspeicher und die Angaben für die App-Prüfung (liest kein Test).
val testInputExcludes: List<String> = listOf(
    "**/build/**", "**/.build/**", "**/DerivedData/**", "**/xcuserdata/**", "**/__pycache__/**", "**/.DS_Store",
    "**/review_information/**",
)

// `SourceGuard.ios`: mit REQUIRE_IOS_SOURCES=true ist eine fehlende Swift-Datei ein Fehler statt «übersprungen»
val requireIosSources = providers.environmentVariable("REQUIRE_IOS_SOURCES").orElse("")
val androidDir: File = rootDir

tasks.withType<Test>().configureEach {
    testInputRoots.forEachIndexed { index, relative ->
        inputs.files(fileTree(File(androidDir, relative)) { if (relative.startsWith("../")) exclude(testInputExcludes) })
            .withPropertyName("readByTests$index")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .ignoreEmptyDirectories()
    }
    inputs.property("requireIosSources", requireIosSources)
}
