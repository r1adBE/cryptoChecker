import Foundation

/// Feste Adressen der App (Datenschutz usw.) — an einer Stelle, damit Store-Eintrag
/// (`fastlane/metadata/*/privacy_url.txt`) und App nicht auseinanderlaufen.
enum AppLinks {
    // GitHub Pages von r1adBE/cryptoChecker (gleich wie in fastlane/metadata/*/privacy_url.txt).
    static let privacyPolicy = "https://r1adbe.github.io/cryptoChecker/privacy/"

    /// Fragen und Wünsche in «Über» (`about_why`): GitHub-Issues, gleich wie in Android.
    static let feedbackLabel = "github.com/r1adBE/cryptoChecker"
    static let feedback = "https://github.com/r1adBE/cryptoChecker/issues/new/choose"

    /// «Börse wünschen»: GitHub-Issue mit der Vorlage `.github/ISSUE_TEMPLATE/exchange_request.md`.
    static let exchangeRequest = "https://github.com/r1adBE/cryptoChecker/issues/new?template=exchange_request.md"

    /// Quellcode der App, öffentlich unter der MIT-Lizenz (gleich wie Android `SOURCE_CODE_URL`).
    static let sourceCode = "https://github.com/r1adBE/cryptoChecker"

    /// `text` mit `label` als tippbarem Link zu `url` (unterstrichen, Farbe über `.tint`); fehlt `label`, bleibt der
    /// Text schlicht. Für «Über» (`about_why`) und den Beta-Hinweis der Steuer-Vorlagen. Wie Android `linkedText`.
    static func linked(_ text: String, label: String, url: String) -> AttributedString {
        var linked = AttributedString(text)
        if let range = linked.range(of: label) {
            linked[range].link = URL(string: url)
            linked[range].underlineStyle = .single
        }
        return linked
    }
}
