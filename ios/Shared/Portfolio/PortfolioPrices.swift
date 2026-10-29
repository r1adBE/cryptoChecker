import Foundation

/// Führt Abläufe nacheinander aus (wie ein `Mutex.withLock` in Kotlin):
/// Ein zweiter Aufruf wartet, bis der vorherige fertig ist.
actor PortfolioSerialGate {
    private var tail: Task<Void, Never>?

    func run<T: Sendable>(_ operation: @escaping @Sendable () async -> T) async -> T {
        let previous = tail
        let task = Task<T, Never> {
            _ = await previous?.value
            return await operation()
        }
        tail = Task { _ = await task.value }
        return await task.value
    }
}

/// Aktuelle USDT-Kurse je Coin und der Zeitpunkt der ältesten verwendeten Abfrage.
struct PortfolioPrices: Sendable, Equatable {
    var prices: [String: Double] = [:]
    /// 0 = noch keine Kurse.
    var updatedAt: Int64 = 0
    /// Coins OHNE Kurs, weil nur die letzte Stundenkerze einen hatte und er mangels unabhängigem Bezug
    /// nicht geprüft werden konnte (`PricePlausibility.candleUnchecked`): Sie stehen nicht in `prices`,
    /// zählen also nirgends als Geld; die Zeile nennt sie «ungeprüft».
    var unchecked: Set<String> = []
    /// Abfragezeit je Coin (ms) — für die Frische je Coin (`PortfolioPriceFallback.freshShare`).
    var times: [String: Int64] = [:]
    /// Coin → Börse, deren Kurs gilt (eingetragene Börse, `PortfolioExchangeRoute`); fehlt = Binance.
    var sources: [String: String] = [:]
    /// Coin → eingetragene Börse, deren Kurs diesmal nicht ging (dann Binance).
    var exchangeFailed: [String: String] = [:]
    /// Coins mit mehreren eingetragenen Börsen — bewertet mit Binance.
    var mixedExchange: Set<String> = []
    /// Binance-Kurs der Coins mit eigener Börse — zum Skalieren der Tageskurse.
    var binance: [String: Double] = [:]
    /// Coins OHNE Kurs, weil die Abfrage gerade scheiterte (Netz, Sperre/Backoff) — nicht, weil es keinen Kurs gibt
    /// (`PortfolioPriceIssue`). Zeile: «Kurs gerade nicht abrufbar» statt «kein Kurs». Wie Android.
    var unavailable: Set<String> = []
    /// Gekapselte/gebrückte Coins ohne eigenen Börsenkurs, bewertet mit dem Kurs ihrer Basis (`ChainAssets.priceViaBase`):
    /// Coin → Basis. Die Zeile zeigt dann «≈ BTC-Kurs (gebrückt/verpackt)» — nie als eigener Kurs. Wie Android.
    var viaBase: [String: String] = [:]
    /// Coins MIT Binance-Kurs, dessen Token nicht bestätigt ist: kein Name bekannt und unter dem Kürzel
    /// führen andere Listen klar verschiedene Token (`PortfolioPriceIdentity.BinanceCheck.unverified`). Sie zählen mit
    /// dem Kurs (stehen in `prices`); die Zeile nennt sie «Kurs ungeprüft», Portfolio-Alarme behandeln sie wie einen
    /// alten Kurs (`PortfolioWidgetSnapshot.unverifiedCoins`). Wie Android.
    var unverified: Set<String> = []
    /// Coins OHNE Kurs, die Binance mangels prüfbarem Namen ausliess (`PortfolioPriceIdentity.BinanceCheck.unchecked`)
    /// und für die auch sonst keine Quelle einen Kurs führt (`PortfolioPriceIssue.noPrice`, nicht gescheitert): Die Zeile
    /// nennt sie «Kein Markt» statt «Kurs ungeprüft». Wie `unchecked` ohne Kurs: zählen nirgends als Geld. Wie Android.
    var noMarket: Set<String> = []
}

/// Warum ein Coin keinen Kurs hat: `noPrice` = keine Quelle führt ihn; `unavailable` = Abfrage gerade gescheitert.
/// Wie `PortfolioPriceIssue` (Android).
enum PortfolioPriceIssue: Sendable, Equatable {
    case noPrice, unavailable

    /// Kerzen-Kette ohne Kurs: `unavailable`, wenn die Abfrage scheiterte (`failed`), alle Kerzen-Quellen gesperrt/
    /// pausiert sind (`allSourcesBlocked`) oder schon die Sammelabfrage am Netz scheiterte (`networkFailed`) — dann
    /// ist «kein Kurs» nicht belegt. Sonst `noPrice`.
    static func of(failed: Bool, allSourcesBlocked: Bool, networkFailed: Bool) -> PortfolioPriceIssue {
        failed || allSourcesBlocked || networkFailed ? .unavailable : .noPrice
    }

    /// Coins, die Binance ohne prüfbaren Namen ausliess (`skipped`), ohne die verworfenen Kerzenkurse (`rejected`): keine
    /// Quelle mit Kurs (`noPrice`, `.noPrice`) → `noMarket` («Kein Markt»), sonst `unchecked` («Kurs ungeprüft»).
    /// Wie Android `PortfolioPriceIssue.nameUnchecked`.
    /// Scheiterte die Abfrage (`unavailable`): keiner von beiden, die Zeile sagt «Kurs gerade nicht abrufbar».
    static func nameUnchecked(_ skipped: Set<String>, rejected: Set<String>, noPrice: Set<String>,
                              unavailable: Set<String>) -> (unchecked: Set<String>, noMarket: Set<String>) {
        let open = skipped.subtracting(rejected).subtracting(unavailable)
        return (open.subtracting(noPrice), open.intersection(noPrice))
    }
}
