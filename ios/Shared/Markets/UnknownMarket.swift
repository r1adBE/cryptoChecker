import Foundation

/// Ersatz für unbekannte Schlüssel (z. B. aus einer Sicherung mit einer Börse,
/// die es nicht mehr gibt). Android zeigt dazu den Hinweis
/// `R.string.market_caution_unknown` (`cautionResId`) — die UI muss das hier
/// selbst über `market is UnknownMarket` lösen.
final class UnknownMarket: Market {
    init() {
        super.init(
            key: "UnknownMarket",
            name: "UNKNOWN",
            ttsName: "UNKNOWN",
            currencyPairs: [VirtualCurrency.BTC: [VirtualCurrency.BTC]]
        )
    }

    override func url(requestId: Int, info: CheckerInfo) -> String { "" }
}
