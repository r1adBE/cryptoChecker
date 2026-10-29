import Foundation

/// Weiterleitungen der Adressabfragen (beobachtete Adressen, xpub, eigener Server): Die Adresse steht im Pfad oder im
/// Inhalt der Anfrage. Folgte die Sitzung einer Weiterleitung auf einen anderen Host, ginge sie samt IP-Adresse an einen
/// Dritten, den der Nutzer nie gewählt hat. Darum nur auf denselben Host (Gross-/Kleinschreibung egal) und nie von
/// HTTPS auf HTTP; sonst gilt die Weiterleitung als Antwort (HTTP-Fehler, nächster Host). Gleich wie Android
/// `AddressRedirect.allowed`; Fälle in `Parity/address_privacy.json` («redirectCases»).
enum AddressRedirect {
    static func allowed(fromScheme: String?, fromHost: String?, toScheme: String?, toHost: String?) -> Bool {
        guard let fromHost, !fromHost.isEmpty, let toHost, !toHost.isEmpty else { return false }
        guard fromHost.lowercased() == toHost.lowercased() else { return false }
        let from = fromScheme?.lowercased()
        let to = toScheme?.lowercased()
        guard to == "https" || to == "http" else { return false }
        return !(from == "https" && to == "http")
    }
}

/// Folgt Weiterleitungen der Adressabfragen nur auf denselben Host (`AddressRedirect`); eine andere kommt unverändert
/// als Antwort zurück. Wie Android `SameHostRedirectInterceptor`. Ohne Zustand.
final class SameHostRedirectDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        let from = task.originalRequest?.url
        let allowed = AddressRedirect.allowed(fromScheme: from?.scheme, fromHost: from?.host,
                                              toScheme: request.url?.scheme, toHost: request.url?.host)
        return allowed ? request : nil
    }
}
