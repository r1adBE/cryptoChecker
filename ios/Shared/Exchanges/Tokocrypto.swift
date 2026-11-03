import Foundation

/// Tokocrypto (Indonesien), öffentliche «open API» wie Binance TR (`BinanceCloudBase`):
/// https://www.tokocrypto.com/apidocs/ — ausserhalb Indonesiens antwortet die Börse mit HTTP 451 (Standort gesperrt).
/// Paare des Typs 1 führt das Binance-Orderbuch (`api.binance.com`, wie ccxt «tokocrypto»).
struct Tokocrypto: BinanceCloudBase {
    let key: String = "Tokocrypto"
    let name: String = "Tokocrypto"
    let host: String = "https://www.tokocrypto.com"
    let binanceAPI: String = "https://api.binance.com/api/v3"
}
