import XCTest
@testable import CryptoChecker

/// Prüfung des Erfassen-Blatts (`PortfolioTxForm.check`) ohne Oberfläche — wie `PortfolioTxFormCheckTest.kt` (Android).
final class PortfolioTxFormCheckTests: XCTestCase {

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) ?? .nan }

    private func buy(_ amount: String? = "1") -> PortfolioTxForm.Entry {
        PortfolioTxForm.Entry(type: .BUY, coinQuery: "btc", amount: amount.map(dec))
    }

    private func check(_ e: PortfolioTxForm.Entry) -> PortfolioTxForm.Check { PortfolioTxForm.check(e) }

    func testMinimalBuyIsValidWithUnknownPrice() {
        let c = check(buy())
        XCTAssertEqual(c.coin, "BTC")
        XCTAssertTrue(c.coinValid)
        XCTAssertTrue(c.priceValid)
        XCTAssertEqual(c.thirdFee, PortfolioTxForm.ThirdFee.none)
        XCTAssertTrue(c.valid)
    }

    func testCoinAndAmountRequired() {
        var e = buy()
        e.coinQuery = ""
        XCTAssertFalse(check(e).valid)
        e.coinQuery = "BT-C"
        XCTAssertFalse(check(e).coinValid)
        e.coinQuery = String(repeating: "A", count: 16)
        XCTAssertFalse(check(e).coinValid)
        XCTAssertFalse(check(buy(nil)).valid)
    }

    func testUnreadablePriceBlocksButTransferHasNoPrice() {
        var e = buy()
        e.priceBlank = false
        XCTAssertFalse(check(e).priceValid)
        XCTAssertFalse(check(e).valid)
        // Umbuchung: kein Preisfeld — ein Rest im Text zählt nicht
        var transfer = e
        transfer.type = .TRANSFER
        XCTAssertTrue(check(transfer).priceValid)
        // Anzeigewährung ohne Kurs: gelesen, aber kein USDT-Preis → ungültig
        e.priceEntered = dec("100")
        XCTAssertFalse(check(e).priceValid)
    }

    func testPriceZeroOnlyBlockedForImportedTransactions() {
        var e = buy()
        e.priceBlank = false
        e.priceEntered = 0
        e.price = 0
        XCTAssertFalse(check(e).priceZero)
        XCTAssertTrue(check(e).valid)
        e.imported = true
        let c = check(e)
        XCTAssertTrue(c.priceZero)
        XCTAssertFalse(c.priceValid)
        XCTAssertFalse(c.valid)
    }

    func testFeeOnlyForTypesWithFee() {
        // Belohnung hat keine Gebühr: ein getippter Wert zählt nicht, unlesbar sperrt nicht
        var e = PortfolioTxForm.Entry(type: .REWARD, coinQuery: "ETH", amount: dec("1"))
        e.feeBlank = false
        let r = check(e)
        XCTAssertFalse(r.hasFee)
        XCTAssertNil(r.fee)
        XCTAssertTrue(r.feeValid)
        XCTAssertTrue(r.valid)
        // Verkauf: unlesbare Gebühr sperrt
        e.type = .SELL
        XCTAssertFalse(check(e).feeValid)
        XCTAssertFalse(check(e).valid)
        e.fee = dec("0.1")
        XCTAssertEqual(check(e).fee, dec("0.1"))
        // Auszahlung: Gebühr immer im Coin
        e.type = .WITHDRAWAL
        e.feeUnit = .QUOTE
        XCTAssertEqual(check(e).feeUnit, .COIN)
    }

    func testThirdCoinFeeStates() {
        var e = buy()
        e.feeCoin = "bnb"
        e.feeCoinAmountBlank = false
        e.feeCoinAmount = dec("0.01")
        XCTAssertEqual(check(e).thirdFee, .incomplete)
        XCTAssertTrue(check(e).valid)
        var complete = e
        complete.feeCoinPriceBlank = false
        complete.feeCoinPrice = dec("600")
        XCTAssertEqual(check(complete).thirdFee, .complete)
        // Gleicher Coin wie die Transaktion: nicht speicherbar
        e.feeCoin = "BTC"
        XCTAssertEqual(check(e).thirdFee, .invalid)
        XCTAssertFalse(check(e).valid)
        // Belohnung kennt keine Gebühr im dritten Coin
        e.type = .REWARD
        XCTAssertEqual(check(e).thirdFee, PortfolioTxForm.ThirdFee.none)
    }

    func testSwapChecksReceivedSide() {
        var swap = PortfolioTxForm.Entry(type: .SWAP, coinQuery: "BTC", amount: dec("1"))
        swap.toCoinQuery = "eth"
        swap.toAmount = dec("20")
        let ok = check(swap)
        XCTAssertEqual(ok.toCoin, "ETH")
        XCTAssertTrue(ok.valid)
        var same = swap
        same.toCoinQuery = "btc"
        XCTAssertFalse(check(same).toCoinValid)
        var noAmount = swap
        noAmount.toAmount = nil
        XCTAssertFalse(check(noAmount).valid)
        // Wert leer = unbekannt (gültig), negativ bzw. unlesbar: nicht speicherbar
        var value = swap
        value.swapValueBlank = false
        value.swapValue = 0
        XCTAssertTrue(check(value).swapValueValid)
        value.swapValue = dec("-1")
        XCTAssertFalse(check(value).swapValueValid)
        value.swapValue = nil
        XCTAssertFalse(check(value).valid)
        // Gebühr im erhaltenen Coin: ohne Preis vollständig
        var inReceived = swap
        inReceived.feeCoin = "ETH"
        inReceived.feeCoinAmountBlank = false
        inReceived.feeCoinAmount = dec("0.5")
        let r = check(inReceived)
        XCTAssertTrue(r.feeInReceived)
        XCTAssertEqual(r.thirdFee, .complete)
        XCTAssertTrue(r.valid)
    }

    func testFeeConsumingTheReceivedAmountBlocksSaving() {
        var e = buy()
        e.feeBlank = false
        e.fee = dec("1")
        e.feeUnit = .COIN
        XCTAssertTrue(check(e).feeConsumes)
        XCTAssertFalse(check(e).valid)
        e.fee = dec("0.5")
        XCTAssertFalse(check(e).feeConsumes)
        var swap = PortfolioTxForm.Entry(type: .SWAP, coinQuery: "BTC", amount: dec("1"))
        swap.toCoinQuery = "ETH"
        swap.toAmount = dec("2")
        swap.feeCoin = "ETH"
        swap.feeCoinAmountBlank = false
        swap.feeCoinAmount = dec("2")
        XCTAssertTrue(check(swap).feeConsumes)
        XCTAssertFalse(check(swap).valid)
    }
}
