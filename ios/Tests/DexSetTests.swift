import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei (R, Build 147); jede Klasse unverändert.

// ---- aus DexFailureTests

/// Einordnung einer gescheiterten DEX-Suche (`DexFailure`) — wie `DexFailureTest.kt` (Android).
final class DexFailureTests: XCTestCase {

    private func kind(_ error: Error) -> DexFailure {
        DexFailure.of(RefreshReportLogic.classify(MarketFailure.of(error), ConnectionErrors.describe(error)))
    }

    func testUrsachenWieInDerMerkliste() {
        // Zeitüberschreitung zählt wie in der Merkliste als «Keine Verbindung»
        XCTAssertEqual(DexFailure.of(.OFFLINE), .offline)
        XCTAssertEqual(DexFailure.of(.TIMEOUT), .offline)
        XCTAssertEqual(DexFailure.of(.SERVER), .unreachable)
        XCTAssertEqual(DexFailure.of(.RATE_LIMIT), .unreachable)
        XCTAssertEqual(DexFailure.of(.NO_DATA), .other)
        XCTAssertEqual(DexFailure.of(.UNAVAILABLE), .other)
        XCTAssertEqual(DexFailure.of(.OTHER), .other)
    }

    func testFehlerWerdenEingeordnet() {
        XCTAssertEqual(kind(URLError(.notConnectedToInternet)), .offline)
        XCTAssertEqual(kind(URLError(.timedOut)), .offline)
        XCTAssertEqual(kind(NSError(domain: "Test", code: 1)), .other)
    }

    func testTexteDerMerkliste() {
        XCTAssertEqual(DexFailure.offline.messageKey, "watch_error_offline")
        XCTAssertEqual(DexFailure.unreachable.messageKey, "error_market_unreachable_short")
        XCTAssertEqual(DexFailure.other.messageKey, "something_went_wrong")
    }
}

// ---- aus DexLiquidityTests

/// Alarm-Sperre für dünne DEX-Pools (unter 10’000 USD Liquidität oder unbekannt). Wie `DexLiquidityTest.kt`.
final class DexLiquidityTests: XCTestCase {
    func testThresholdIsTenThousandUsd() {
        XCTAssertEqual(DexScreener.minAlarmLiquidityUsd, 10_000)
        XCTAssertTrue(DexScreener.alarmCapable(10_000))
        XCTAssertTrue(DexScreener.alarmCapable(45_678_901.23))
        XCTAssertFalse(DexScreener.alarmCapable(9_999.99))
        XCTAssertFalse(DexScreener.alarmCapable(0))
        // Unbekannt zählt wie zu wenig
        XCTAssertFalse(DexScreener.alarmCapable(nil))
        XCTAssertFalse(DexScreener.alarmCapable(.nan))
        XCTAssertFalse(DexScreener.alarmCapable(.infinity))
    }

    func testOnlyDexPoolsAreBlocked() {
        XCTAssertTrue(DexLiquidity.blocksAlarms(marketKey: "DexScreener", liquidityUsd: 5_000))
        XCTAssertTrue(DexLiquidity.blocksAlarms(marketKey: "DexScreener", liquidityUsd: nil))
        XCTAssertFalse(DexLiquidity.blocksAlarms(marketKey: "DexScreener", liquidityUsd: 25_000))
        // Börsen-Paare: nie gesperrt, auch ohne Liquidität
        XCTAssertFalse(DexLiquidity.blocksAlarms(marketKey: "Binance", liquidityUsd: nil))
        XCTAssertFalse(DexLiquidity.blocksAlarms(marketKey: "Kraken", liquidityUsd: 1))
    }

    func testKeyIgnoresCaseAndSpaces() {
        XCTAssertEqual(DexLiquidity.key(" ethereum/0xABC "), "ethereum/0xabc")
        XCTAssertNil(DexLiquidity.key("  "))
        XCTAssertNil(DexLiquidity.key(nil))
    }

    func testSearchKeepsSortingAndMarksThinPools() throws {
        let body = """
        {"pairs":[
            {"chainId":"bsc","dexId":"pancake","pairAddress":"0x1","baseToken":{"symbol":"THIN"},"quoteToken":{"symbol":"WBNB"},"priceUsd":"0.1","liquidity":{"usd":2500.5}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0x2","baseToken":{"symbol":"BIG"},"quoteToken":{"symbol":"WETH"},"priceUsd":"1.5","liquidity":{"usd":250000}},
            {"chainId":"solana","dexId":"raydium","pairAddress":"0x3","baseToken":{"symbol":"NOLIQ"},"quoteToken":{"symbol":"SOL"},"priceUsd":"2"}
        ]}
        """
        let pools = try DexScreener.parseSearch(body)
        XCTAssertEqual(pools.map(\.baseSymbol), ["BIG", "THIN", "NOLIQ"])
        XCTAssertEqual(pools.map(\.alarmCapable), [true, false, false])
        XCTAssertEqual(pools[1].liquidityUsd, 2500.5)
        XCTAssertNil(pools[2].liquidityUsd)
    }

    func testSearchShowsTokenAddressAndRanksAddressMatchesFirst() throws {
        // Zwei «PEPE»: der liquidere ist ein anderer Token; dazu ein Pool mit dem gesuchten Token nur als Gegenwährung
        let body = """
        {"pairs":[
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp1","baseToken":{"symbol":"PEPE","address":"0x1111111111111111111111111111111111112222"},"quoteToken":{"symbol":"WETH"},"priceUsd":"0.1","liquidity":{"usd":900000}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp2","baseToken":{"symbol":"WETH","address":"0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"},"quoteToken":{"symbol":"PEPE","address":"0x6982508145454Ce325dDbE47a25d4ec3d2311933"},"priceUsd":"2500","liquidity":{"usd":500000}},
            {"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xp3","baseToken":{"symbol":"PEPE","address":"0x6982508145454Ce325dDbE47a25d4ec3d2311933"},"quoteToken":{"symbol":"WETH"},"priceUsd":"0.00001","liquidity":{"usd":40000}},
            {"chainId":"solana","dexId":"raydium","pairAddress":"p4","baseToken":{"symbol":"NOADDR"},"quoteToken":{"symbol":"SOL"},"priceUsd":"1"}
        ]}
        """
        let byLiquidity = try DexScreener.parseSearch(body)
        XCTAssertEqual(byLiquidity.map(\.pairAddress), ["0xp1", "0xp2", "0xp3", "p4"])
        XCTAssertEqual(byLiquidity[0].shortBaseAddress, "0x1111…2222")
        XCTAssertEqual(byLiquidity[2].shortBaseAddress, "0x6982…1933")
        XCTAssertEqual(byLiquidity[3].shortBaseAddress, "")
        // Suche per Vertragsadresse (andere Schreibweise): Pools mit dem Token als Basis zuerst, Rest nach Liquidität
        let byAddress = try DexScreener.parseSearch(body, query: " 0x6982508145454ce325ddbe47a25d4ec3d2311933 ")
        XCTAssertEqual(byAddress.map(\.pairAddress), ["0xp3", "0xp1", "0xp2", "p4"])
        // Kurze Adressen bleiben ganz
        XCTAssertEqual(DexScreener.shortAddress(" So11111 "), "So11111")
        XCTAssertEqual(DexScreener.shortAddress("123456789012"), "123456789012")
        XCTAssertEqual(DexScreener.shortAddress("1234567890123"), "123456…0123")
    }
}

// ---- aus DexPairIdParityTests

/// DexScreener-Kennung «chain/adresse» (`Parity/dex_pair_id.json`): ungültige Kennungen gehen nie
/// unkodiert in den Pfad — Einzelkurs «nicht gefunden» ohne Anfrage, Suchtreffer fallen weg, Sicherung lehnt ab.
/// Gegenstück: `DexPairIdParityTest.kt`.
final class DexPairIdParityTests: XCTestCase {
    private typealias S = TestSupport
    private let market = DexScreener()

    private func cases() throws -> [[String: Any]] {
        S.list(try S.fixture("dex_pair_id")["cases"])
    }

    func testKennungWieGemeinsameFaelle() throws {
        for c in try cases() {
            let id = try XCTUnwrap(c["pairId"] as? String)
            XCTAssertEqual(DexScreener.isSafePairId(id), try XCTUnwrap(c["valid"] as? Bool), id)
        }
    }

    func testEinzelkursNurMitGueltigerKennung() throws {
        let base = try XCTUnwrap(try S.fixture("dex_pair_id")["tickerBase"] as? String)
        for c in try cases() {
            let id = try XCTUnwrap(c["pairId"] as? String)
            let info = PairQuery("X", "USD (ethereum)", id, .none)
            if try XCTUnwrap(c["valid"] as? Bool) {
                XCTAssertNil(market.tickerQueryError(call: 0, info: info), id)
                XCTAssertEqual(market.tickerAddress(call: 0, info: info), base + id, id)
            } else {
                let error = market.tickerQueryError(call: 0, info: info) as? JSONError
                XCTAssertEqual(error?.message, DexScreener.poolNotFound, id)
            }
        }
        let missing = market.tickerQueryError(call: 0, info: PairQuery("X", "USD (ethereum)", nil, .none)) as? JSONError
        XCTAssertEqual(missing?.message, DexScreener.poolNotFound)
    }

    /// SEC3-R1: die Sammelabfrage nimmt dieselben Kennungen wie der Einzelkurs.
    func testSammelabfrageNurMitGueltigerKennung() throws {
        for c in try cases() {
            let id = try XCTUnwrap(c["pairId"] as? String)
            if id != id.trimmingCharacters(in: .whitespacesAndNewlines) { continue } // Rand-Leerzeichen werden gekürzt
            let groups = DexScreener.bulkGroups([id])
            XCTAssertEqual(!groups.isEmpty, try XCTUnwrap(c["valid"] as? Bool), id)
            for g in groups { XCTAssertTrue(DexScreener.bulkURL(g).hasSuffix("/" + id), id) }
        }
    }

    func testSuchtrefferMitUngueltigerKennungFallenWeg() throws {
        let search = try XCTUnwrap(try S.fixture("dex_pair_id")["search"] as? [String: Any])
        let pools = try DexScreener.parseSearch(try XCTUnwrap(search["body"] as? String))
        XCTAssertEqual(pools.map(\.pairId), search["expectedPairIds"] as? [String])
    }

    func testSicherungUebernimmtNurGueltigeKennung() throws {
        for c in try cases() {
            let id = try XCTUnwrap(c["pairId"] as? String)
            let kept = BackupRestoreLimits.acceptsPair(marketKey: DexScreener.key, marketName: "DexScreener",
                                                       base: "X", quote: "USD (ethereum)", pairId: id)
            XCTAssertEqual(kept, try XCTUnwrap(c["backupKept"] as? Bool), id)
        }
        XCTAssertFalse(BackupRestoreLimits.acceptsPair(marketKey: DexScreener.key, marketName: "DexScreener",
                                                       base: "X", quote: "USD (ethereum)", pairId: nil))
    }
}

// ---- aus DexScreenerBulkTests

/// Sammelabfrage von DexScreener: Pools derselben Chain in einer Anfrage (`/latest/dex/pairs/{chain}/{a,b,…}`),
/// höchstens 30 Adressen je Anfrage. Gleiche Fälle in `DexScreenerBulkTest.kt`.
final class DexScreenerBulkTests: XCTestCase {
    private let market = DexScreener()

    func testGroupsByChainSortedWithoutDuplicates() {
        let groups = DexScreener.bulkGroups([
            "solana/8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj",
            "ethereum/0xB",
            "ethereum/0xA",
            " ethereum/0xA ",
            // ohne Chain oder Adresse, mit Zeichen, die kodiert werden müssten: Einzelabfrage
            "noslash", "ethereum/", "/0xC", "ethereum/0x D", "ethereum/0xE,0xF", "ethereum/0x1/2",
        ])
        XCTAssertEqual(groups.map { $0.chain }, ["ethereum", "solana"])
        XCTAssertEqual(groups.map { $0.addresses }, [["0xA", "0xB"], ["8sLbNZoA1cfnvMJLPfp98ZLAnFSYCFApfJKMbiXNLwxj"]])
    }

    func testAtMostThirtyAddressesPerRequest() {
        let ids = (1...31).map { String(format: "base/0x%02d", $0) }
        let groups = DexScreener.bulkGroups(ids)
        XCTAssertEqual(groups.map { $0.addresses.count }, [30, 1])
        XCTAssertEqual(groups.first?.addresses.count, DexScreener.bulkMaxPools)
        XCTAssertEqual(groups.last?.addresses, ["0x31"])
    }

    func testRequestAddressesFollowTheGroups() {
        let ids = ["ethereum/0xB", "ethereum/0xA", "bsc/0xC"]
        XCTAssertEqual(market.bulkTickersNumOfRequests, 1)
        XCTAssertEqual(market.bulkTickersRequestCount(pairIds: ids), 2)
        XCTAssertEqual(market.bulkTickersURL(call: 0, pairIds: ids), "https://api.dexscreener.com/latest/dex/pairs/bsc/0xC")
        XCTAssertEqual(market.bulkTickersURL(call: 1, pairIds: ids),
                       "https://api.dexscreener.com/latest/dex/pairs/ethereum/0xA,0xB")
        XCTAssertNil(market.bulkTickersURL(call: 2, pairIds: ids))
        // Keine ungefilterte Liste; ein fehlender Pool geht in die Einzelabfrage
        XCTAssertNil(market.bulkTickersURL(call: 0))
        XCTAssertFalse(market.bulkTickersComplete)
        XCTAssertEqual(market.bulkTickersRequestCount(pairIds: ["invalid"]), 0)
    }
}

// ---- aus DexTokenAddressTests

/// Token-Adresse der DEX-Pools fürs Paar-Blatt (`DexTokenAddress`). Wie `DexTokenAddressTest.kt`.
final class DexTokenAddressTests: XCTestCase {

    private let evm = "0x6982508145454Ce325dDbE47a25d4ec3d2311933"

    func testCleanedKeepsRealAddressesAndDropsJunk() {
        XCTAssertEqual(DexTokenAddress.cleaned("  \(evm) "), evm)
        XCTAssertEqual(DexTokenAddress.cleaned("So11111111111111111111111111111111111111112"),
                       "So11111111111111111111111111111111111111112")
        XCTAssertNil(DexTokenAddress.cleaned(nil))
        XCTAssertNil(DexTokenAddress.cleaned("   "))
        XCTAssertNil(DexTokenAddress.cleaned("0x12 34"))
        XCTAssertNil(DexTokenAddress.cleaned(String(repeating: "a", count: DexTokenAddress.maxLength + 1)))
        XCTAssertEqual(DexTokenAddress.cleaned(String(repeating: "a", count: DexTokenAddress.maxLength))?.count,
                       DexTokenAddress.maxLength)
    }

    func testShownIsShortened() {
        XCTAssertEqual(DexTokenAddress.shown(evm), "0x6982…1933")
        XCTAssertEqual(DexTokenAddress.shown("short"), "short")
    }

    func testSearchResultCarriesTheAddressUsedForTheSheet() throws {
        let body = """
        {"pairs":[{"chainId":"ethereum","dexId":"uniswap","pairAddress":"0xA43fe16908251ee70EF74718545e4FE6C5cCEc9f",\
        "baseToken":{"address":"\(evm)","name":"Pepe","symbol":"PEPE"},"quoteToken":{"symbol":"WETH"},\
        "priceUsd":"0.00001","liquidity":{"usd":1000000}}]}
        """
        let pools = try DexScreener.parseSearch(body, query: "pepe")
        XCTAssertEqual(pools.count, 1)
        XCTAssertEqual(DexTokenAddress.cleaned(pools.first?.baseAddress), evm)
        // Schlüssel wie die Liquidität: Kennung ohne Gross-/Kleinschreibung
        XCTAssertEqual(DexLiquidity.key(pools.first?.pairId), "ethereum/0xa43fe16908251ee70ef74718545e4fe6c5ccec9f")
    }

    /// VoiceOver: die letzten vier Zeichen der Adresse einzeln (Bezeichnung der Zeile im Aktionsblatt).
    func testSpokenTailSpellsLastFourCharacters() {
        XCTAssertEqual(DexTokenAddressLine.spokenTail("0x6982508145454Ce325dDbE47a25d4ec3d2311933"), "1 9 3 3")
        XCTAssertEqual(DexTokenAddressLine.spokenTail(" abc "), "a b c")
    }
}
