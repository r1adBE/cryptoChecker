import Foundation

/// iOS spricht Kurse nur bei offener App (keine Ansage im Hintergrund): Die Einstellungsseite und die Meldung «Kurs
/// vorlesen» sagen das immer dazu (`alarm_speak_ios_open_only`).
enum SpeechHints {
    /// Untertitel der Einstellungsseite «Sprachausgabe».
    static var settingsSubtitle: String { L("settings_tts_hint_silent") + " " + L("alarm_speak_ios_open_only") }
    /// Meldung nach «Kurs vorlesen» an einem Paar.
    static var speechOnToast: String { L("watchlist_tts_speech_on") + " " + L("alarm_speak_ios_open_only") }
}
