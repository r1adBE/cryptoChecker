import XCTest
@testable import CryptoChecker

/// Erstes ungültiges Feld beim Speichern (`PortfolioTxFirstInvalid`) — wie `PortfolioTxFirstInvalidTest.kt` (Android).
final class PortfolioTxFirstInvalidTests: XCTestCase {

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) ?? .nan }

    private func buy() -> PortfolioTxForm.Entry { PortfolioTxForm.Entry(type: .BUY, coinQuery: "btc", amount: dec("1")) }

    private func swap() -> PortfolioTxForm.Entry {
        var e = PortfolioTxForm.Entry(type: .SWAP, coinQuery: "BTC", amount: dec("1"))
        e.toCoinQuery = "ETH"
        e.toAmount = dec("2")
        return e
    }

    private func first(_ e: PortfolioTxForm.Entry) -> PortfolioTxFirstInvalid.Field? {
        PortfolioTxFirstInvalid.of(PortfolioTxForm.check(e), amountValid: e.amount != nil, isSwap: e.type == .SWAP)
    }

    func testGueltigeEingabeHatKeinFehlerfeld() {
        XCTAssertNil(first(buy()))
        XCTAssertNil(first(swap()))
    }

    func testReihenfolgeWieImBlatt() {
        // Coin und Menge falsch: zuerst der Coin (steht oben)
        var e = buy()
        e.coinQuery = ""
        e.amount = nil
        XCTAssertEqual(first(e), .coin)
        e = buy()
        e.amount = nil
        e.priceBlank = false
        XCTAssertEqual(first(e), .amount)
        // Preis unlesbar, Gebühr unlesbar: zuerst der Preis
        e = buy()
        e.priceBlank = false
        e.feeBlank = false
        XCTAssertEqual(first(e), .price)
        e.priceBlank = true
        XCTAssertEqual(first(e), .fee)
    }

    func testTauschFelder() {
        var e = swap()
        e.toCoinQuery = "btc"
        e.toAmount = nil
        XCTAssertEqual(first(e), .toCoin)
        e = swap()
        e.toAmount = nil
        XCTAssertEqual(first(e), .toAmount)
        e = swap()
        e.swapValueBlank = false
        e.swapValue = dec("-1")
        XCTAssertEqual(first(e), .swapValue)
    }

    func testGebuehrImDrittenCoinUndVerbrauchteMenge() {
        // Dritter Coin gleich dem Coin der Transaktion: ungültig
        var e = buy()
        e.feeCoin = "BTC"
        e.feeCoinAmountBlank = false
        e.feeCoinAmount = dec("1")
        XCTAssertEqual(first(e), .feeCoin)
        // Gebühr im Coin ≥ Menge: am Gebührenfeld
        e = buy()
        e.feeBlank = false
        e.fee = dec("1")
        e.feeUnit = .COIN
        XCTAssertEqual(first(e), .fee)
        // Tausch: Gebühr im erhaltenen Coin ≥ erhaltene Menge — an der Gebühr im erhaltenen Coin
        e = swap()
        e.feeCoin = "ETH"
        e.feeCoinAmountBlank = false
        e.feeCoinAmount = dec("2")
        XCTAssertEqual(first(e), .feeCoin)
    }

    func testNurGebuehrenfelderLiegenImAbschnitt() {
        XCTAssertTrue(PortfolioTxFirstInvalid.Field.fee.inLedger)
        XCTAssertTrue(PortfolioTxFirstInvalid.Field.feeCoin.inLedger)
        XCTAssertFalse(PortfolioTxFirstInvalid.Field.price.inLedger)
        XCTAssertFalse(PortfolioTxFirstInvalid.Field.coin.inLedger)
    }

    func testKeinFehlerfeldGenauDannWennSpeicherbar() {
        var noCoin = buy(); noCoin.coinQuery = ""
        var noAmount = buy(); noAmount.amount = nil
        var badPrice = buy(); badPrice.priceBlank = false
        var badFee = buy(); badFee.feeBlank = false
        var noTo = swap(); noTo.toAmount = nil
        var consumes = swap(); consumes.feeCoin = "ETH"; consumes.feeCoinAmountBlank = false; consumes.feeCoinAmount = dec("2")
        for e in [buy(), noCoin, noAmount, badPrice, badFee, swap(), noTo, consumes] {
            XCTAssertEqual(PortfolioTxForm.check(e).valid, first(e) == nil, "\(e)")
        }
    }
}
