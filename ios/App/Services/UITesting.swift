import CryptoKit
import SwiftUI
import UniformTypeIdentifiers

#if DEBUG
/// Nahtstellen für die UI-Tests (Ziel `CryptoCheckerUITests`): nur im Debug-Build und nur mit dem Startargument
/// `-uiTesting` aktiv. Ohne Argument ist `isActive` false und alles verhält sich wie sonst; ein Release-Build
/// enthält den Typ gar nicht (Aufrufer stehen in `#if DEBUG`).
///
/// Mit dem Argument, beim Start vor allem anderen (`CryptoCheckerApp.init`):
/// - Speicher leeren wie «App zurücksetzen» (`AppData.clearStoredFiles`) — jeder Start beginnt wie eine
///   frische Installation; Einstellungen: Begrüssung und Gesten-Hinweis gesehen, Portfolio an.
/// - Börsen-Antworten fest (`UITestMarket`), kein Abruf verlässt das Gerät.
/// - Dateiauswahl und Dateiablage ersetzt: «CSV importieren» liest den Text aus der Umgebung `UITEST_CSV`,
///   «Sichern» schreibt nach `tmp/uitest_backup.json`, «Wiederherstellen» liest von dort
///   (`testableFileImporter`, `exportURL`). Die Sicherung bleibt über einen Neustart, wenn
///   `UITEST_KEEP_BACKUP=1` gesetzt ist (Sichern → Neustart = Daten weg → Wiederherstellen); `UITEST_BACKUP`
///   legt dort eine Sicherung des Tests ab.
enum UITesting {
    static let isActive = ProcessInfo.processInfo.arguments.contains(UiTestTags.launchArgument)

    /// Schlüssel des Wiederherstellungs-Journals im UI-Test (`RestoreJournal.journalKey`), nur für diesen Lauf.
    static let journalKey = SymmetricKey(size: .bits256)

    private static var csvURL: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("uitest_import.csv")
    }

    private static var backupURL: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("uitest_backup.json")
    }

    /// Vor dem ersten Zugriff auf `AppData.shared`, `AppRouter.shared` usw. aufrufen.
    static func prepareIfRequested() {
        guard isActive else { return }
        let environment = ProcessInfo.processInfo.environment
        let fm = FileManager.default
        AppData.clearStoredFiles()
        if environment[UiTestTags.keepBackupEnvironment] != "1" { try? fm.removeItem(at: backupURL) }
        if let backup = environment[UiTestTags.backupEnvironment] {
            try? Data(backup.utf8).write(to: backupURL, options: .atomic)
        }
        try? fm.removeItem(at: csvURL)
        if let csv = environment[UiTestTags.csvEnvironment] {
            try? Data(csv.utf8).write(to: csvURL, options: .atomic)
        }
        var settings = AppSettings()
        settings.aboutSeen = true
        settings.gestureHintSeen = true
        // Karte «Ersten Alarm setzen» aus: die Abläufe erwarten die Liste wie bisher
        settings.firstAlarmCardDismissed = true
        settings.portfolioEnabled = true
        SharedStorage.saveSettings(settings)
        UITestMarket.install()
    }

    /// Datei statt der Dateiauswahl; nil = echte Auswahl (kein UI-Test).
    static func importURL(_ file: TestImportFile) -> URL? {
        guard isActive else { return nil }
        switch file {
        case .csv: return csvURL
        case .backup: return backupURL
        }
    }

    /// «Sichern» schreibt hierhin statt die Dateiablage zu öffnen; nil = echte Ablage (kein UI-Test).
    static var exportURL: URL? {
        isActive ? backupURL : nil
    }
}
#endif

extension View {
    /// `.fileImporter` — im UI-Test (Debug-Build mit `-uiTesting`) liefert er statt der Systemauswahl sofort die
    /// Datei des Tests (`UITesting.importURL`), über denselben `onCompletion` wie eine echte Auswahl.
    @ViewBuilder
    func testableFileImporter(
        isPresented: Binding<Bool>,
        allowedContentTypes: [UTType],
        testFile: TestImportFile,
        onCompletion: @escaping (Result<URL, Error>) -> Void
    ) -> some View {
        #if DEBUG
        if let url = UITesting.importURL(testFile) {
            onChange(of: isPresented.wrappedValue) { _, open in
                guard open else { return }
                isPresented.wrappedValue = false
                onCompletion(.success(url))
            }
        } else {
            fileImporter(isPresented: isPresented, allowedContentTypes: allowedContentTypes, onCompletion: onCompletion)
        }
        #else
        fileImporter(isPresented: isPresented, allowedContentTypes: allowedContentTypes, onCompletion: onCompletion)
        #endif
    }
}

/// Welche Testdatei `testableFileImporter` im UI-Test liefert (im Release ohne Wirkung).
enum TestImportFile {
    case csv, backup
}
