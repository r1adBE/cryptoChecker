package com.cryptochecker.marketdata.model.exchange

/**
 * Tokocrypto (Indonesien), öffentliche «open API» wie Binance TR ([BinanceCloudBase]):
 * https://www.tokocrypto.com/apidocs/ — ausserhalb Indonesiens antwortet die Börse mit HTTP 451 (Standort gesperrt).
 * Paare des Typs 1 führt das Binance-Orderbuch (`api.binance.com`, wie ccxt «tokocrypto»).
 */
class Tokocrypto : BinanceCloudBase(
    name = "Tokocrypto",
    host = "https://www.tokocrypto.com",
    binanceApi = "https://api.binance.com/api/v3",
) {
    override val key = "Tokocrypto"
}
