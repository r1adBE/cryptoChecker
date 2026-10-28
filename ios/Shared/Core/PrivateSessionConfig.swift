import Foundation

/// Gemeinsame Grundlage aller eigenen URLSessions: keine Cookies annehmen oder senden, kein Antwort-Cache auf der
/// Platte. Die Datenschutzerklärung verspricht «keine Kennungen» — ein Server könnte sonst über ein Set-Cookie eine
/// Kennung setzen, die bei jedem weiteren Abruf zurückginge (Android: OkHttp ohne CookieJar, also ebenfalls nichts).
enum PrivateSessionConfig {
    /// Neue Konfiguration auf Basis von `.default` (UI-Tests tauschen genau diese aus, um ihr Protokoll einzuhängen);
    /// Zeitgrenzen und weitere Einstellungen setzt jede Aufrufstelle wie bisher selbst.
    static func make(timeout: TimeInterval, resource: TimeInterval? = nil) -> URLSessionConfiguration {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = timeout
        if let resource { c.timeoutIntervalForResource = resource }
        c.httpCookieAcceptPolicy = .never
        c.httpShouldSetCookies = false
        c.httpCookieStorage = nil
        c.urlCache = nil
        return c
    }

    /// Einmal beim Start: Cookies und Cache-Antworten der gemeinsamen Speicher (`URLSession.shared`)
    /// entfernen. Billig, darum ohne Merker bei jedem Start.
    static func clearSharedStores() {
        HTTPCookieStorage.shared.removeCookies(since: .distantPast)
        URLCache.shared.removeAllCachedResponses()
    }
}
