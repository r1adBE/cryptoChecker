package com.cryptochecker.app.ui

/**
 * Stabile Kennungen für UI-Tests (`Modifier.testTag`) — nur dort, wo Text zu brüchig wäre (Sprache,
 * gleiche Beschriftung mehrfach, Felder ohne Text). Reine Semantik, kein Verhalten. Gleiche Namen wie
 * `UiTestTags.swift` (iOS: `accessibilityIdentifier`), damit beide UI-Tests dieselben Abläufe gleich
 * ansprechen. `watchlist`, `watch_row` und `starter_add` nutzt auch der Baseline-Profile-Generator.
 */
object UiTestTags {
    // ── Tableiste ──
    const val TAB_WATCHLIST = "tab_watchlist"
    const val TAB_CYCLE = "tab_cycle"
    const val TAB_PORTFOLIO = "tab_portfolio"
    const val TAB_SETTINGS = "tab_settings"

    // ── Markt ──
    /** Inhalt des Tabs «Markt», sobald Crypto Pulse geladen ist und alles Sichtbare auf «Jetzt» steht. */
    const val MARKET_LOADED = "market_loaded"

    // ── Merkliste ──
    const val WATCHLIST = "watchlist"
    const val WATCH_ROW = "watch_row"
    const val STARTER_ADD = "starter_add"
    const val WATCHLIST_ADD_PAIR = "watchlist_add_pair"
    const val WATCH_ACTION_ALARM = "watch_action_alarm"

    /** Glocke im Kopf der Merkliste (mit Alarmen: Alarm-Übersicht). */
    const val WATCH_BELL = "watch_bell"

    // ── Mitteilungen ──
    /** «Weiter» der Erklärung vor der System-Abfrage (`NotificationPrePrompt`). */
    const val NOTIFICATION_PREPROMPT_CONTINUE = "notification_preprompt_continue"

    // ── Paar hinzufügen (Coin-Suche) ──
    const val EXPLORER_SEARCH = "explorer_search"
    const val EXPLORER_LISTS_LOAD = "explorer_lists_load"
    const val EXPLORER_SUGGESTED_ADD = "explorer_suggested_add"
    private const val EXPLORER_COIN = "explorer_coin_"

    /** Treffer der Coin-Suche («explorer_coin_BTC»; TradFi-Kürzel mit «_tradfi»). */
    fun explorerCoin(symbol: String, tradFi: Boolean = false): String =
        EXPLORER_COIN + symbol + if (tradFi) "_tradfi" else ""

    // ── Alarme ──
    const val ALARM_THRESHOLD = "alarm_threshold"
    const val ALARM_SAVE = "alarm_save"
    const val ALARM_MORE = "alarm_more"
    const val ALARM_CARD = "alarm_card"

    /** Liste der Alarm-Übersicht («Alle Alarme»). */
    const val ALARMS_OVERVIEW = "alarms_overview"

    // ── Portfolio und CSV-Import ──
    const val PORTFOLIO_MENU = "portfolio_menu"
    const val PORTFOLIO_IMPORT_CSV = "portfolio_import_csv"
    const val PORTFOLIO_IMPORTS = "portfolio_imports"
    const val CSV_IMPORT_CONFIRM = "csv_import_confirm"
    const val IMPORT_UNDO = "import_undo"
    const val IMPORT_UNDO_CONFIRM = "import_undo_confirm"
    private const val PORTFOLIO_COIN = "portfolio_coin_"

    /** Zeile einer offenen Position im Portfolio («portfolio_coin_BTC»). */
    fun portfolioCoin(coin: String): String = PORTFOLIO_COIN + coin

    // ── Stichtag-Export und Steuer-Arbeitsunterlage ──
    const val PORTFOLIO_CUTOFF_EXPORT = "portfolio_cutoff_export"
    const val PORTFOLIO_TAX_EXPORT = "portfolio_tax_export"
    const val TAX_WORKSHEET_SCREEN = "tax_worksheet_screen"
    const val TAX_WORKSHEET_TEMPLATE = "tax_worksheet_template"
    const val TAX_WORKSHEET_GAINS = "tax_worksheet_gains"
    const val TAX_WORKSHEET_INCOME = "tax_worksheet_income"
    const val TAX_WORKSHEET_HOLDINGS = "tax_worksheet_holdings"
    const val TAX_WORKSHEET_SUMMARY = "tax_worksheet_summary"
    /** «Abbrechen» eines laufenden Exports bzw. «Erneut versuchen» nach einem Fehler. */
    const val EXPORT_CANCEL = "export_cancel"
    const val EXPORT_RETRY = "export_retry"
    private const val TAX_TEMPLATE_OPTION = "tax_template_option_"

    /** Eintrag der Vorlagen-Liste der Arbeitsunterlage («tax_template_option_DE»; Name aus `TaxTemplate`). */
    fun taxTemplateOption(name: String): String = TAX_TEMPLATE_OPTION + name

    // ── Sichern und Wiederherstellen ──
    const val SETTINGS_BACKUP = "settings_backup"
    const val BACKUP_EXPORT = "backup_export"
    const val BACKUP_IMPORT = "backup_import"
    const val BACKUP_PASSWORD_FIELD = "backup_password"
    const val BACKUP_PASSWORD_REPEAT = "backup_password_repeat"
    const val BACKUP_EXPORT_CONFIRM = "backup_export_confirm"
    const val BACKUP_RESTORE_PASSWORD_FIELD = "backup_restore_password"
    const val BACKUP_RESTORE_SUBMIT = "backup_restore_submit"
    const val BACKUP_RESTORE_CONFIRM = "backup_restore_confirm"

    /** Schliessen-✕ einer Meldung mit Aktion (Snackbar bzw. Banner). */
    const val SNACKBAR_DISMISS = "snackbar_dismiss"

    /** Rückfrage nach «Rückgängig» der Sammelaufnahme: «Behalten» bzw. «Entfernen». */
    const val BULK_UNDO_KEEP = "bulk_undo_keep"
    const val BULK_UNDO_REMOVE = "bulk_undo_remove"

    /** Hinweis unter einem Passwortfeld (z. B. «falsches Passwort»): Kennung des Felds + «_hint». */
    fun hint(fieldTag: String): String = fieldTag + "_hint"
}
