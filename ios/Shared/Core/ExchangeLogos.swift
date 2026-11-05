import Foundation

/// Logos der Börsen in den Listen («Paar hinzufügen»): mit der App ausgeliefert (`packName`.`packExtension`, Format wie
/// das Coin-Logo-Paket, `CoinLogos.parseIconPack`; erzeugt mit `android/tools/gen_logo_packs.py`), angezeigt sofort vom
/// Gerät — kein Abruf. Futures zeigen das Logo der Mutterbörse (Binance Futures → Binance). DexScreener und die
/// EZB-Referenzkurse bekommen ein festes Symbol (`Symbol`). Jede registrierte Börse hat genau eins von beiden
/// (Paritätstest). Reine Logik — wie Android `ExchangeLogos` (`testdata/parity/exchange_logos.json`).
enum ExchangeLogos {
    /// Festes Symbol statt Logo.
    enum Symbol: String { case dex = "DEX", forex = "FOREX" }

    /// Mitgeliefertes Paket der Börsen-Logos (`App/Resources`, nur die App; Android `assets/`).
    static let packName = "exchange_logos"
    static let packExtension = "bin"

    /// Börsen-Schlüssel → Name des Logos im Paket; Futures: die Mutterbörse.
    static let logos: [String: String] = [
        "Coinbase": "gdax", "Kraken": "kraken", "GateIo": "gate", "Binance": "binance", "BinanceUs": "binanceus",
        "Kucoin": "kucoin", "Okex": "okex", "OkexFutures": "okex", "BinanceFutures": "binance", "Bybit": "bybit",
        "BybitFutures": "bybit", "Bitget": "bitget", "BitgetFutures": "bitget", "Mexc": "mexc",
        "MexcFutures": "mexc", "Htx": "htx", "HtxFutures": "htx", "Bitfinex": "bitfinex", "Bitstamp": "bitstamp",
        "Bitvavo": "bitvavo", "CryptoCom": "cryptocom", "Gemini": "gemini", "Hyperliquid": "hyperliquid",
        "Aster": "aster", "BingX": "bingx", "BingXFutures": "bingx", "Lighter": "lighter", "Woo": "woo",
        "WooFutures": "woo", "Deribit": "deribit", "Phemex": "phemex", "PhemexFutures": "phemex",
        "BitunixFutures": "bitunix", "Poloniex": "poloniex", "OneTrading": "onetrading", "Upbit": "upbit",
        "Bithumb": "bithumb", "BitFlyer": "bitflyer", "Bitbank": "bitbank", "Coincheck": "coincheck",
        "Btcturk": "btcturk", "Paribu": "paribu", "Icrypex": "icrypex", "Bitso": "bitso",
        "MercadoBitcoin": "mercadobitcoin", "Indodax": "indodax", "ZebPay": "zebpay", "CoinDcx": "coindcx",
        "HashKey": "hashkey", "IndependentReserve": "independentreserve", "BtcMarkets": "btcmarkets",
        "Luno": "luno", "Bitkub": "bitkub", "BinanceTh": "binance", "CoinsPh": "coinsph", "Coinone": "coinone",
        "WhiteBit": "whitebit", "MaxMaicoin": "maxmaicoin", "BitoPro": "bitopro", "BinanceTr": "binance",
        "Foxbit": "foxbit", "Bit2Me": "bit2me", "Valr": "valr", "WazirX": "wazirx", "Buda": "buda",
        "Tokocrypto": "tokocrypto", "UpbitTh": "upbit", "UpbitId": "upbitid", "CoinJar": "coinjar",
        "GmoCoin": "gmocoin", "Latoken": "latoken", "NonKyc": "nonkyc", "Bitpanda": "bitpanda",
        "CoinSpot": "coinspot", "Quidax": "quidax", "Swyftx": "swyftx", "Ndax": "ndax", "BitOasis": "bitoasis",
    ]

    /// Festes Symbol statt Logo.
    static let symbols: [String: Symbol] = [
        "DexScreener": .dex,
        "EcbReference": .forex, "NbuReference": .forex, "NbpReference": .forex,
    ]

    /// Name des Logos im Paket; mehrere Börsen teilen eines. nil = festes Symbol bzw. unbekannte Börse.
    static func logoName(_ exchangeKey: String) -> String? { logos[exchangeKey] }
}
