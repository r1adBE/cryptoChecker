import XCTest
@testable import CryptoChecker

/// Zuordnung importierter Zeilen zu Transaktionen (`CsvImportMapping`) — wie `CsvImportMappingTest.kt`.
final class CsvImportMappingTests: XCTestCase {

    private let csv = "Date,Transaction Type,BTC Amount,BTC Price,Currency Pair,Fiat Amount (excl. fees),Fiat Currency,Fee,Fee Currency,Destination,Operation ID,Counterparty\r\n"
        + "2024-09-15T14:23:17Z,Sell,-0.02847593,92341.28,BTC/CHF,2629.45,CHF,0.00,CHF,CH00,op-0001,Relai\r\n"
        + "2024-07-22T09:41:06Z,Buy,0.00284516,95287.63,BTC/CHF,271.05,CHF,2.45,CHF,bc1q1,op-0002,Relai\r\n"
        + "2024-06-20T08:00:00Z,Withdrawal,0.00500000,,,,,0.00001000,BTC,bc1q3,op-0004,\r\n"
        + "2024-06-21T10:00:00Z,Referral bonus,0.00010000,60000.00,BTC/CHF,6.00,CHF,,CHF,bc1q4,op-0005,Relai\r\n"
        + "2024-06-22T10:00:00Z,Cancelled,0.00010000,60000.00,BTC/CHF,6.00,CHF,,CHF,,op-0006,Relai\r\n"
        + "2024-06-23T10:00:00Z,Buy,0.001,,BTC/EUR,,EUR,,EUR,,op-0009,Relai\r\n"

    /// CHF: 0.8 je USD; EUR fehlt.
    private func fx(_ c: String, _ day: Int64) -> Decimal? { c == "CHF" ? Decimal(string: "0.8") : nil }

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))! }

    func testPreviewCountsNewDuplicatesSkippedAndMissing() {
        let p = CsvImportMapping.preview(CsvImportParser.parse(csv), existingKeys: ["RELAI|op-0001"], fx: fx)
        XCTAssertEqual(p.duplicates, 1)
        XCTAssertEqual(p.skipped, [CsvImportSkip(line: 6, reason: .UNSUPPORTED_KIND)])
        XCTAssertEqual(p.transactions.map(\.line), [3, 4, 5, 7])
        XCTAssertEqual(p.missingPrice, [7])
        XCTAssertEqual(p.missingFx, [])
    }

    /// Belohnung als Zins (Lending): Art als Notiz (AT: Anschaffungskosten = Wert); Staking ohne Notiz — wie Android.
    func testInterestRewardKeepsKindAsNote() throws {
        let text = "Date,Type,Coin,Amount,Price,Currency\n"
            + "2024-02-01T10:00:00Z,Lending interest,ETH,0.01,2000,USD\n"
            + "2024-02-02T10:00:00Z,Staking Reward,ETH,0.01,2000,USD\n"
        let txs = Dictionary(uniqueKeysWithValues: CsvImportMapping.preview(CsvImportParser.parse(text), existingKeys: [],
                                                                            fx: { _, _ in nil }).transactions.map { ($0.line, $0) })
        XCTAssertEqual(txs[2]?.type, .REWARD)
        XCTAssertEqual(txs[2]?.note, "Lending interest")
        XCTAssertEqual(txs[3]?.type, .REWARD)
        XCTAssertNil(txs[3]?.note)
    }

    func testMapsTypesPricesAndFees() throws {
        let txs = Dictionary(uniqueKeysWithValues: CsvImportMapping.preview(CsvImportParser.parse(csv), existingKeys: [],
                                                                            fx: fx).transactions.map { ($0.line, $0) })
        let sell = try XCTUnwrap(txs[2])
        XCTAssertEqual(sell.type, .SELL)
        XCTAssertEqual(sell.priceUsdt, dec("115426.6"))
        XCTAssertNil(sell.fee)
        let buy = try XCTUnwrap(txs[3])
        XCTAssertEqual(buy.type, .BUY)
        XCTAssertEqual(buy.feeUnit, .QUOTE)
        XCTAssertEqual(buy.fee, dec("3.0625"))
        XCTAssertEqual(buy.importKey, "RELAI|op-0002")
        let withdrawal = try XCTUnwrap(txs[4])
        XCTAssertEqual(withdrawal.type, .WITHDRAWAL)
        XCTAssertNil(withdrawal.priceUsdt)
        XCTAssertEqual(withdrawal.feeUnit, .COIN)
        XCTAssertEqual(withdrawal.fee, dec("0.00001"))
        XCTAssertEqual(txs[5]?.type, .REWARD)
        XCTAssertNil(txs[7]?.priceUsdt)
    }

    func testMissingFxImportsWithoutPriceAndFee() throws {
        let p = CsvImportMapping.preview(CsvImportParser.parse(csv), existingKeys: [], fx: { _, _ in nil })
        XCTAssertEqual(p.missingFx, [2, 3, 5])
        let buy = try XCTUnwrap(p.transactions.first { $0.line == 3 })
        XCTAssertNil(buy.priceUsdt)
        XCTAssertNil(buy.fee)
        XCTAssertNil(buy.feeUnit)
    }

    func testThirdCoinFeeAndFeeRows() throws {
        let row = CsvImportRow(line: 1, kind: .BUY, rawKind: "Buy", coin: "ETH", amount: 1, price: 3000, quote: "USDT",
                               fee: dec("0.01"), feeUnit: "BNB", timeMillis: 5, externalId: nil, key: "k")
        let tx = CsvImportMapping.toTx(try XCTUnwrap(CsvImport.priceInUsdt([row], fx: { _, _ in nil }).first))
        XCTAssertEqual(tx.feeCoin, "BNB")
        XCTAssertEqual(tx.feeCoinAmount, dec("0.01"))
        XCTAssertNil(tx.fee)
        XCTAssertEqual(tx.priceUsdt, 3000)
        let fee = CsvImportRow(line: 2, kind: .FEE, rawKind: " Network fee ", coin: "ETH", amount: 1, price: nil,
                               quote: nil, fee: nil, feeUnit: nil, timeMillis: 5, externalId: nil, key: "f")
        let feeTx = CsvImportMapping.toTx(try XCTUnwrap(CsvImport.priceInUsdt([fee], fx: { _, _ in nil }).first))
        // Gebühr-Zeile: Veräusserung ohne Erlös (Verkauf zum Preis 0)
        XCTAssertEqual(feeTx.type, .SELL)
        XCTAssertEqual(feeTx.priceUsdt, 0)
        XCTAssertEqual(feeTx.note, "Network fee")
    }

    func testFxNeedsPerCurrencyWithDayRange() {
        let needs = CsvImportMapping.fxNeeds(CsvImportParser.parse(csv).rows)
        XCTAssertEqual(Set(needs.keys), ["CHF"])
        XCTAssertEqual(needs["CHF"]?.lowerBound, CsvImport.epochDay(1_718_964_000_000))
    }

    func testWithdrawalsCanBecomeTransfers() {
        let result = CsvImportParser.parse(csv)
        let plain = Dictionary(uniqueKeysWithValues: CsvImportMapping.preview(result, existingKeys: [], fx: fx)
            .transactions.map { ($0.line, $0) })
        let asTransfer = Dictionary(uniqueKeysWithValues: CsvImportMapping.preview(result, existingKeys: [],
                                                                                  withdrawalsAsTransfer: true, fx: fx)
            .transactions.map { ($0.line, $0) })
        XCTAssertEqual(plain[4]?.type, .WITHDRAWAL)
        XCTAssertEqual(asTransfer[4]?.type, .TRANSFER)
        XCTAssertNil(asTransfer[4]?.transferTo) // die Formate liefern kein Ziel
        XCTAssertEqual(asTransfer[4]?.fee, plain[4]?.fee) // Gebühr in Coin bleibt (Netzwerkgebühr der Umbuchung)
        XCTAssertEqual(CsvImportMapping.type(.FEE, withdrawalsAsTransfer: true), .SELL)
        XCTAssertEqual(CsvImportMapping.type(.WITHDRAWAL, withdrawalsAsTransfer: true), .TRANSFER)
    }

    private func address(_ label: String?, _ change: String, count: Bool = true) -> WatchedAddress {
        WatchedAddress(id: 1, chain: .BTC, address: "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", label: label,
                       observations: [AddressObservation(time: 1_718_900_000_000, balance: dec(change))],
                       countInPortfolio: count)
    }

    /// Auszahlung an eine eigene Adresse: Vorschlag «Umbuchung» nur bei passender beobachteter Änderung (± 1 %, Menge oder
    /// Menge − Gebühr im Coin) einer ZÄHLENDEN Adresse des Coins; sonst keine Vorauswahl — wie Android.
    func testWithdrawalProposalNeedsMatchingObservedChange() throws {
        let txs = CsvImportMapping.preview(CsvImportParser.parse(csv), existingKeys: [], fx: fx).transactions
        let w = try XCTUnwrap(txs.first { $0.type == .WITHDRAWAL }) // 0.005 BTC, Gebühr 0.00001 BTC
        // Erhalten 0.00499 (nach Netzwerkgebühr) → Umbuchung zu «Ledger»
        let hit = try XCTUnwrap(CsvImportMapping.proposeWithdrawals(txs, addresses: [address("Ledger", "0.00499")]).first)
        XCTAssertEqual(hit.importKey, w.importKey)
        XCTAssertEqual(hit.choice, .TRANSFER)
        XCTAssertEqual(hit.transferTo, "Ledger")
        // ± 1 %: 0.00504 passt noch, 0.0051 nicht
        XCTAssertEqual(CsvImportMapping.proposeWithdrawals(txs, addresses: [address(nil, "0.00504")]).first?.choice, .TRANSFER)
        XCTAssertNil(CsvImportMapping.proposeWithdrawals(txs, addresses: [address("X", "0.0051")]).first?.choice)
        // Ohne Bezeichnung: gekürzte Adresse als Ziel
        XCTAssertEqual(CsvImportMapping.proposeWithdrawals(txs, addresses: [address(nil, "0.005")]).first?.transferTo,
                       "bc1qar…5mdq")
        // «Nur anzeigen» bzw. keine Adresse: keine Vorauswahl
        let none = try XCTUnwrap(CsvImportMapping.proposeWithdrawals(txs, addresses: [address("Ledger", "0.00499", count: false)]).first)
        XCTAssertNil(none.choice)
        XCTAssertNil(none.transferTo)
        XCTAssertEqual(CsvImportMapping.proposeWithdrawals(txs, addresses: []).count, 1)
        XCTAssertNil(CsvImportMapping.proposeWithdrawals(txs, addresses: []).first?.choice)
        // Erst mit Wahl je Auszahlung entschieden
        XCTAssertFalse(CsvImportMapping.allWithdrawalsDecided(txs, choices: [:]))
        XCTAssertTrue(CsvImportMapping.allWithdrawalsDecided(txs, choices: [w.importKey: .WITHDRAWAL]))
        // Übernehmen: Umbuchung mit Ziel bzw. Auszahlung; andere Zeilen unverändert
        let asTransfer = CsvImportMapping.applyWithdrawalChoices(txs, choices: [w.importKey: .TRANSFER],
                                                                 destinations: [w.importKey: "Ledger"])
        let t = try XCTUnwrap(asTransfer.first { $0.importKey == w.importKey })
        XCTAssertEqual(t.type, .TRANSFER)
        XCTAssertEqual(t.transferTo, "Ledger")
        XCTAssertEqual(t.fee, w.fee)
        XCTAssertEqual(asTransfer.filter { $0.importKey != w.importKey }, txs.filter { $0.importKey != w.importKey })
        XCTAssertEqual(CsvImportMapping.applyWithdrawalChoices(txs, choices: [w.importKey: .WITHDRAWAL]), txs)
    }

    /// Tausch: zwei Seiten (gleiche Auftragsnummer) → EIN Tausch mit Wert aus dem Fiat-Betrag (CHF → USDT).
    func testSwapLegsBecomeOneSwap() throws {
        let sq = "Datum;Auftrag #;Transaktionen;Symbol;Name;ISIN;Anzahl;Stückpreis;Kosten;Nettobetrag;Währung Nettobetrag\n"
            + "07-01-2023 10:00:00;120000010;Tausch;ETH;Ethereum;;-1.0;1300.00;0.00;1300.00;CHF\n"
            + "07-01-2023 10:00:00;120000010;Tausch;BTC;Bitcoin;;0.08;16250.00;0.0004;-1300.00;CHF\n"
        let p = CsvImportMapping.preview(CsvImportParser.parse(sq), existingKeys: [], fx: fx)
        XCTAssertEqual(p.transactions.count, 1)
        let swap = try XCTUnwrap(p.transactions.first)
        XCTAssertEqual(swap.type, .SWAP)
        XCTAssertEqual(swap.coin, "ETH")
        XCTAssertEqual(swap.amount, dec("1"))
        XCTAssertEqual(swap.toCoin, "BTC")
        XCTAssertEqual(swap.toAmount, dec("0.08"))
        XCTAssertEqual(swap.swapValueUsdt, dec("1625")) // 1300 CHF ÷ 0.8
        XCTAssertNil(swap.priceUsdt)
        // Gebühr «Kosten» der erhaltenen Seite in CHF → USDT
        XCTAssertEqual(swap.feeUnit, .QUOTE)
        XCTAssertEqual(swap.fee, dec("0.0005"))
        XCTAssertEqual(CsvImportMapping.type(.SWAP), .SWAP)
        XCTAssertEqual(CsvImportMapping.fxNeeds(CsvImportParser.parse(sq).rows), ["CHF": 19364...19364])
    }

    /// Datei «neueste zuerst»: älteste zuerst speichern (Id folgt der Zeit); aufsteigende/gemischte bleiben — wie Android.
    func testInsertOrderReversesNewestFirstFiles() {
        let desc = "Date,Type,Coin,Amount,Price,Currency\n"
            + "2024-03-02T10:00:00Z,Sell,BTC,0.5,61000,USD\n"
            + "2024-03-02T10:00:00Z,Buy,BTC,1,60000,USD\n"
            + "2024-03-01T10:00:00Z,Deposit,BTC,0.1,,\n"
        let txs = CsvImportMapping.preview(CsvImportParser.parse(desc), existingKeys: []) { _, _ in nil }.transactions
        XCTAssertEqual(txs.map(\.line), [2, 3, 4])
        XCTAssertEqual(CsvImportMapping.insertOrder(txs).map(\.line), [4, 3, 2])
        let asc = Array(txs.reversed())
        XCTAssertEqual(CsvImportMapping.insertOrder(asc).map(\.line), [4, 3, 2])
        XCTAssertTrue(CsvImportMapping.insertOrder([]).isEmpty)
        // Nur gleiche Zeiten: wie in der Datei
        XCTAssertEqual(CsvImportMapping.insertOrder(Array(txs.prefix(2))).map(\.line), [2, 3])
    }
}
