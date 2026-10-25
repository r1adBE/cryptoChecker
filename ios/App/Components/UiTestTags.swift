import Foundation

/// Stabile Kennungen für UI-Tests (`accessibilityIdentifier`) — nur dort, wo Text zu brüchig wäre (Sprache,
/// gleiche Beschriftung mehrfach, Felder ohne Text). Reine Kennung, kein Verhalten; VoiceOver liest sie nicht.
/// Gleiche Namen wie `UiTestTags.kt` (Android: `Modifier.testTag`). Diese Datei wird auch ins Ziel
/// `CryptoCheckerUITests` kompiliert (tools/gen_xcodeproj.py), darum nur Text-Konstanten ohne App-Typen.
enum UiTestTags {
    // MARK: Start der UI-Tests (nur iOS; gelesen von `UITesting` im Debug-Build)
    /// Startargument: leerer Speicher, feste Börsen-Antworten, Testdateien statt Dateiauswahl.
    static let launchArgument = "-uiTesting"
    /// Umgebung: Text der CSV-Datei, die «CSV importieren» statt der Dateiauswahl bekommt.
    static let csvEnvironment = "UITEST_CSV"
    /// Umgebung «1»: Sicherungsdatei des vorigen Starts behalten (Sichern → Neustart → Wiederherstellen).
    static let keepBackupEnvironment = "UITEST_KEEP_BACKUP"
    /// Umgebung: Text der Sicherungsdatei, die «Wiederherstellen» statt der Dateiauswahl bekommt.
    static let backupEnvironment = "UITEST_BACKUP"
    /// Umgebung «1»: «Sichern» kürzt die Datei nach dem Schreiben, die Prüfung scheitert («nicht bestätigt»).
    static let failVerifyEnvironment = "UITEST_FAIL_VERIFY"

    // MARK: Tableiste
    static let tabWatchlist = "tab_watchlist"
    static let tabCycle = "tab_cycle"
    static let tabPortfolio = "tab_portfolio"
    static let tabSettings = "tab_settings"

    // MARK: Merkliste
    static let watchlist = "watchlist"
    static let watchRow = "watch_row"
    static let starterAdd = "starter_add"
    static let watchlistAddPair = "watchlist_add_pair"
    static let watchActionAlarm = "watch_action_alarm"

    // MARK: Mitteilungen
    /// «Weiter» der Erklärung vor der System-Abfrage (`NotificationPrePrompt`).
    static let notificationPrepromptContinue = "notification_preprompt_continue"

    // MARK: Paar hinzufügen (Coin-Suche)
    static let explorerSearch = "explorer_search"
    static let explorerListsLoad = "explorer_lists_load"
    static let explorerSuggestedAdd = "explorer_suggested_add"

    /// Treffer der Coin-Suche («explorer_coin_BTC»; TradFi-Kürzel mit «_tradfi»).
    static func explorerCoin(_ symbol: String, tradFi: Bool = false) -> String {
        "explorer_coin_" + symbol + (tradFi ? "_tradfi" : "")
    }

    // MARK: Alarme
    static let alarmThreshold = "alarm_threshold"
    static let alarmSave = "alarm_save"
    static let alarmMore = "alarm_more"
    static let alarmCard = "alarm_card"

    // MARK: Portfolio und CSV-Import
    static let portfolioMenu = "portfolio_menu"
    static let portfolioImportCsv = "portfolio_import_csv"
    static let portfolioImports = "portfolio_imports"
    static let csvImportConfirm = "csv_import_confirm"
    static let importUndo = "import_undo"
    static let importUndoConfirm = "import_undo_confirm"

    /// Zeile einer offenen Position im Portfolio («portfolio_coin_BTC»).
    static func portfolioCoin(_ coin: String) -> String { "portfolio_coin_" + coin }

    // Stichtag-Export und Steuer-Arbeitsunterlage
    static let portfolioCutoffExport = "portfolio_cutoff_export"
    static let portfolioTaxExport = "portfolio_tax_export"
    static let taxWorksheetScreen = "tax_worksheet_screen"
    static let taxWorksheetTemplate = "tax_worksheet_template"
    static let taxWorksheetGains = "tax_worksheet_gains"
    static let taxWorksheetIncome = "tax_worksheet_income"
    static let taxWorksheetHoldings = "tax_worksheet_holdings"
    static let taxWorksheetSummary = "tax_worksheet_summary"
    /// «Abbrechen» eines laufenden Exports bzw. «Erneut versuchen» nach einem Fehler.
    static let exportCancel = "export_cancel"
    static let exportRetry = "export_retry"

    /// Eintrag der Vorlagen-Liste der Arbeitsunterlage («tax_template_option_DE»; `TaxTemplate.rawValue`).
    static func taxTemplateOption(_ name: String) -> String { "tax_template_option_" + name }

    // MARK: Sichern und Wiederherstellen
    static let settingsBackup = "settings_backup"
    static let backupExport = "backup_export"
    static let backupImport = "backup_import"
    static let backupPassword = "backup_password"
    static let backupPasswordRepeat = "backup_password_repeat"
    static let backupExportConfirm = "backup_export_confirm"
    static let backupRestorePassword = "backup_restore_password"
    static let backupRestoreSubmit = "backup_restore_submit"
    static let backupRestoreConfirm = "backup_restore_confirm"

    /// Kurze Rückmeldung unten (z. B. «Gesichert»). Android zeigt dort einen System-Toast ohne Kennung
    /// (der Android-Test prüft stattdessen die geschriebene Datei).
    static let toast = "toast"

    /// Schliessen-✕ einer Meldung mit Aktion (Snackbar bzw. Banner).
    static let snackbarDismiss = "snackbar_dismiss"

    /// Hinweis «Arbeitshilfe, keine Steuer- oder Anlageberatung» unten im Portfolio (`PortfolioDisclaimer`).
    static let portfolioDisclaimer = "portfolio_disclaimer"

    /// «Schliessen» des Speicher-Hinweises der Merkliste (`WatchlistStorageNotice`). Im UI-Test steht er immer (der
    /// unsignierte Simulator hat keinen Schlüsselbund der App Group); das Audit schliesst ihn vor der Prüfung.
    static let storageNoticeClose = "storage_notice_close"

    /// Rückfrage nach «Rückgängig» der Sammelaufnahme: «Behalten» bzw. «Entfernen».
    static let bulkUndoKeep = "bulk_undo_keep"
    static let bulkUndoRemove = "bulk_undo_remove"

    /// Hinweis unter einem Passwortfeld (z. B. «falsches Passwort»): Kennung des Felds + «_hint».
    static func hint(_ fieldTag: String) -> String { fieldTag + "_hint" }
}
