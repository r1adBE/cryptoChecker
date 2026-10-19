import XCTest
@testable import CryptoChecker

/// TradFi-Kennzeichen von Aster, BingX Futures und Lighter (Robinhood-Spot) sowie die Börsen für «Nur TradFi» —
/// Werte aus den öffentlichen Paarlisten vom 19.10.2026. Wie `TradFiTest.kt`; die Kennzeichen der übrigen Börsen
/// prüft `MarketsTests.swift`.
final class TradFiTests: XCTestCase {

    func testAster() {
        let tradFi: [[String]] = [
            ["STOCK"], ["STOCK", "Semiconductor"], ["STOCK", "ETF"], ["ETF"],
            ["Commodities"], ["STOCK", "AOS2", "USD1-RWA"], ["pre-launch", "STOCK"], ["stock"],
        ]
        for subTypes in tradFi { XCTAssertTrue(TradFi.aster(subTypes: subTypes), "\(subTypes)") }
        let crypto: [[String]] = [["Top"], ["Meme"], ["AI"], [], ["AOS2"], ["pre-launch"]]
        for subTypes in crypto { XCTAssertFalse(TradFi.aster(subTypes: subTypes), "\(subTypes)") }
    }

    func testBingX() {
        for asset in ["NCSKAAPL2USD", "NCSINIKKEI2252USD", "NCCOGOLD2USD", "NCFXEUR2USD"] {
            XCTAssertTrue(TradFi.bingx(asset: asset), asset)
        }
        for asset in ["BTC", "NEIROCTO", "PAXG", "NC", "NCT", "NCSK", ""] {
            XCTAssertFalse(TradFi.bingx(asset: asset), asset)
        }
        let bases = [
            "AAPL-USDT": "AAPL", "GOLD(XAU)-USDT": "XAU", "SILVER(XAG)-USDT": "XAG",
            "Palladium(XPD)-USDT": "XPD", "Oil Brent-USDT": "OILBRENT", "Nikkei 225-USDT": "NIKKEI225",
            "EURUSD-USDT": "EURUSD", "": "NCSKX2USD",
        ]
        for (name, base) in bases {
            XCTAssertEqual(TradFi.bingxBase(displayName: name, asset: "NCSKX2USD"), base, name)
        }
    }

    func testLighterRobinhood() {
        // nicht in tokenlist: Vorsilbe «rh» + Grossbuchstabe genügt
        XCTAssertTrue(TradFi.lighterRobinhood(base: "rhSPY", restAssetType: nil))
        XCTAssertTrue(TradFi.lighterRobinhood(base: "rhQQQ", restAssetType: ""))
        // Rest in tokenlist: dessen asset_type entscheidet
        XCTAssertTrue(TradFi.lighterRobinhood(base: "rhQQQ", restAssetType: "RWA"))
        XCTAssertFalse(TradFi.lighterRobinhood(base: "rhETH", restAssetType: "CRYPTO"))
        for base in ["rh", "rhx", "RHSPY", "rh1", "SPY", "ETH"] {
            XCTAssertFalse(TradFi.lighterRobinhood(base: base, restAssetType: nil), base)
        }
    }

    /// «Nur TradFi» zeigt die Börsen, deren Paarliste TradFi kennzeichnet — Hyperliquid tut das nicht.
    func testTradFiMarkets() {
        for key in ["Aster", "BingXFutures", "Lighter", "PhemexFutures"] {
            XCTAssertTrue(CoinLogos.tradFiMarkets.contains(key), key)
            XCTAssertTrue(AssetScope.TRADFI.allowsMarket(key), key)
        }
        for key in ["Hyperliquid", "BingX"] { XCTAssertFalse(AssetScope.TRADFI.allowsMarket(key), key) }
    }
}
