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

    /// Runde 15: Quellcode der App, öffentlich unter der MIT-Lizenz (gleich wie Android `SOURCE_CODE_URL`).
    static let sourceCode = "https://github.com/r1adBE/cryptoChecker"

    static var privacyPolicyURL: URL? { URL(string: privacyPolicy) }
}
