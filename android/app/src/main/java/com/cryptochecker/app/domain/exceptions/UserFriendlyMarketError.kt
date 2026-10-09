package com.cryptochecker.app.domain.exceptions

class UserFriendlyMarketError(message: String) : MarketError(message) {
    companion object {
        /**
         * Feste Kennungen statt Text: Die Meldung landet als Zeichenkette in der
         * Datenbank (WatchEntity.lastError) und wird erst in der Oberfläche
         * übersetzt (friendlyError, wie NOT_TRADED_MARKER).
         */
        const val EMPTY_RESPONSE = "Response data is empty"

        /** Antwort ohne Kurs (siehe MarketRemoteDataSource). */
        const val NO_TICKER_DATA = "Parsed ticker has no data"

        /** Die Börse meldete einen Fehler ohne Text. */
        const val UNKNOWN_EMPTY = "Unknown error (empty)"

        /** Börse ist in dieser App-Version nicht (mehr) vorhanden. */
        const val MARKET_UNAVAILABLE = "Market unavailable"
    }
}