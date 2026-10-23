import Foundation

/// Ehrlicher Hinweis «Wann Alarme geprüft werden» als EIN Satz je Plattform
/// (statt vier verschieden langer Fassungen): Solange die App offen ist, prüft sie zuverlässig; bei
/// geschlossener App entscheidet iOS, wann (`alarm_timing_hint_ios`). Derselbe Satz steht unter jedem
/// Alarm, ist der ganze Text der Bestätigung nach dem ersten Alarm und erscheint einmal nach dem
/// Speichern — wie `AlarmTimingHint.kt` (Android).
enum AlarmTimingHint {
    /// Höchstlänge des Satzes in jeder Sprache: Er ist der ganze Text der Bestätigung nach dem ersten
    /// Alarm und muss dort ohne Scrollen lesbar bleiben.
    static let maxChars = 160

    /// Jetzt zeigen? Nur einmal und nicht zusätzlich zur Bestätigung des allerersten Alarms — die zeigt
    /// denselben Satz. Nach jedem Speichern gilt der Hinweis als gezeigt (Hinweis oder Bestätigung erschien).
    static func shouldShow(alreadyShown: Bool, firstAlarmDialog: Bool) -> Bool {
        !alreadyShown && !firstAlarmDialog
    }

    /// Trenner im Pfad «Einstellungen › Kurs-Aktualisierung».
    static let pathSeparator = " › "

    /// Pfad zum Schalter aus den übersetzten Namen der Ebenen.
    static func settingsPath(_ parts: [String]) -> String {
        parts.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: pathSeparator)
    }

    /// Der eine Satz mit den Texten der App.
    static var text: String { L("alarm_timing_hint_ios") }

    /// Weg zur Wahl «Wie aktuell?» unter dem Satz im einmaligen Hinweis. «Kurs-Aktualisierung»
    /// steht auf der Hauptseite (Gruppe «Allgemein»).
    static var whereText: String {
        L("alarm_timing_where", settingsPath([L("settings_title"), L("settings_row_updates")]))
    }
}
