@file:Suppress("UNCHECKED_CAST")

package com.cryptochecker.app.parity

import com.cryptochecker.app.util.json.JsonArray
import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressObservation
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.ChainBalance
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.Erc20Tokens
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/** Weitere Netze (EVM, TRON, Solana, LTC, XRP, TON) und «Nur anzeigen» aus `address_balance.json` (iOS: `ChainTests.swift`). */
class ChainParityTest {

    private val data = ParityJson.load("address_balance.json")

    private fun expectError(name: String, block: () -> Unit) {
        try {
            block()
            fail("Fehler erwartet: $name")
        } catch (e: Exception) {
            // erwartet
        }
    }

    @Test
    fun lists() {
        val c = data["chainAssets"].obj()
        val evm = c["evm"].list().map { it.obj() }
        assertEquals(evm.map { it["id"] }, ChainAssets.EVM.map { it.id })
        evm.zip(ChainAssets.EVM).forEach { (e, n) ->
            assertEquals(e["name"], n.name)
            assertEquals(e["native"], n.native)
            assertEquals(e["rpcs"], n.rpcs)
            assertEquals(
                e["tokens"].list().map { it.obj() }.map { listOf(it["symbol"], it["contract"], it["decimals"].num()!!.toInt(), it["coin"]) },
                n.tokens.map { listOf(it.symbol, it.contract, it.decimals, it.coin) },
            )
            // Kein Token heisst wie das Netz-Guthaben (Schlüssel eindeutig)
            assertTrue(n.tokens.none { it.symbol == n.native })
        }
        assertEquals(
            c["tron"].list().map { it.obj() }.map { listOf(it["symbol"], it["contract"], it["decimals"].num()!!.toInt(), it["coin"]) },
            ChainAssets.TRON_TOKENS.map { listOf(it.symbol, it.contract, it.decimals, it.coin) },
        )
        assertEquals(
            c["solana"].list().map { it.obj() }.map { listOf(it["symbol"], it["mint"], it["decimals"].num()!!.toInt(), it["coin"]) },
            ChainAssets.SOLANA_TOKENS.map { listOf(it.symbol, it.mint, it.decimals, it.coin) },
        )
        assertEquals(
            c["ton"].list().map { it.obj() }.map { listOf(it["symbol"], it["master"], it["rawHex"], it["decimals"].num()!!.toInt(), it["coin"]) },
            ChainAssets.TON_JETTONS.map { listOf(it.symbol, it.master, it.rawHex, it.decimals, it.coin) },
        )
        c["resolve"].list().map { it.obj() }.forEach { r ->
            val asset = ChainAssets.resolve(AddressChain.fromName(r["chain"] as String)!!, r["key"] as String)
            if (r["coin"] == null) {
                assertNull("resolve $r", asset)
            } else {
                assertEquals("label $r", r["label"], asset!!.label)
                assertEquals("coin $r", r["coin"], asset.coin)
                assertEquals("native $r", r["native"], asset.native)
                assertEquals("network $r", r["network"], asset.network)
            }
        }
    }

    @Test
    fun tron() {
        val t = data["chainBalance"].obj()["tron"].obj()
        assertEquals(t["hosts"], ChainBalance.TRON_HOSTS)
        val address = t["address"] as String
        assertEquals(t["hex20"], ChainBalance.tronHex20(address))
        assertEquals(t["accountUrl"], ChainBalance.tronAccountUrl("https://api.trongrid.io"))
        assertEquals(t["callUrl"], ChainBalance.tronCallUrl("https://api.trongrid.io"))
        assertEquals(t["accountRequest"], ChainBalance.tronAccountRequest(address))
        assertEquals(t["tokenRequest"], ChainBalance.tronTokenRequest(address, ChainAssets.TRON_TOKENS[0]))
        t["accounts"].list().map { it.obj() }.forEach { c ->
            if (c["error"] == true) expectError(c["body"] as String) { ChainBalance.parseTronAccount(c["body"] as String) }
            else assertEquals(c["sun"], ChainBalance.parseTronAccount(c["body"] as String))
        }
        t["calls"].list().map { it.obj() }.forEach { c ->
            if (c["error"] == true) expectError(c["body"] as String) { ChainBalance.parseTronCall(c["body"] as String) }
            else assertEquals(c["digits"], ChainBalance.parseTronCall(c["body"] as String))
        }
    }

    @Test
    fun solana() {
        val s = data["chainBalance"].obj()["solana"].obj()
        assertEquals(s["rpcs"], ChainBalance.SOLANA_RPCS)
        assertEquals(s["batch"], ChainBalance.solanaBatch(s["address"] as String))
        s["responses"].list().map { it.obj() }.forEach { c ->
            val body = c["body"] as String
            when {
                c["unsupported"] == true -> try {
                    ChainBalance.parseSolanaBatch(body)
                    fail("kein Stapel erwartet")
                } catch (e: Erc20Tokens.BatchUnsupported) {
                    // erwartet
                }
                c["error"] == true -> expectError(c["name"] as String) { ChainBalance.parseSolanaBatch(body) }
                else -> {
                    val r = ChainBalance.parseSolanaBatch(body)
                    assertEquals(c["lamports"], r.lamports)
                    assertEquals(c["tokens"].obj(), r.tokens)
                    assertEquals(c["unknown"].num()!!.toInt(), r.unknown)
                }
            }
        }
    }

    @Test
    fun ltcXrpTon() {
        val b = data["chainBalance"].obj()
        val ltc = b["ltc"].obj()
        assertEquals(ltc["hosts"], ChainBalance.LTC_HOSTS)
        assertEquals(ltc["url"], ChainBalance.ltcUrl("litecoinspace.org", ltc["address"] as String))
        val xrp = b["xrp"].obj()
        assertEquals(xrp["rpcs"], ChainBalance.XRP_RPCS)
        assertEquals(xrp["request"], ChainBalance.xrpRequest(xrp["address"] as String))
        xrp["cases"].list().map { it.obj() }.forEach { c ->
            if (c["error"] == true) expectError(c["body"] as String) { ChainBalance.parseXrp(c["body"] as String) }
            else assertEquals(c["drops"], ChainBalance.parseXrp(c["body"] as String))
        }
        val ton = b["ton"].obj()
        assertEquals(ton["hosts"], ChainBalance.TON_HOSTS)
        assertEquals(ton["url"], ChainBalance.tonUrl("https://toncenter.com", ton["address"] as String))
        assertEquals(ton["rawUrl"], ChainBalance.tonUrl("https://toncenter.com", "0:" + (ton["rawUrl"] as String).substringAfter("%3A")))
        ton["cases"].list().map { it.obj() }.forEach { c ->
            if (c["error"] == true) expectError(c["body"] as String) { ChainBalance.parseTon(c["body"] as String) }
            else assertEquals(c["nano"], ChainBalance.parseTon(c["body"] as String))
        }
        assertEquals(ton["jettonsUrl"], ChainBalance.tonJettonsUrl("https://toncenter.com", ton["jettonsAddress"] as String))
        assertEquals(ton["jettonLimit"].num()!!.toInt(), ChainBalance.TON_JETTON_LIMIT)
        ton["jettons"].list().map { it.obj() }.forEach { c ->
            if (c["error"] == true) expectError(c["name"] as String) { ChainBalance.parseTonJettons(c["body"] as String) }
            else assertEquals(c["name"] as String, c["tokens"].obj(), ChainBalance.parseTonJettons(c["body"] as String))
        }
        val ada = b["ada"].obj()
        assertEquals(ada["hosts"], ChainBalance.ADA_HOSTS)
        assertEquals(ada["url"], ChainBalance.adaUrl("https://api.koios.rest"))
        assertEquals(ada["request"], ChainBalance.adaRequest(ada["address"] as String))
        ada["cases"].list().map { it.obj() }.forEach { c ->
            if (c["error"] == true) expectError(c["body"] as String) { ChainBalance.parseAda(c["body"] as String, ada["address"] as String) }
            else assertEquals(c["lovelace"], ChainBalance.parseAda(c["body"] as String, ada["address"] as String))
        }
        assertEquals(data["privacyHosts"], AddressBalance.PRIVACY_HOSTS)
        // Die Datenschutzerklärung nennt jeden Host, der eine Adresse sieht
        val policy = java.io.File("docs/privacy/index.html").takeIf { it.exists() } ?: java.io.File("../docs/privacy/index.html")
        if (policy.exists()) {
            val text = policy.readText()
            for (host in AddressBalance.PRIVACY_HOSTS) assertTrue("Datenschutz: $host", text.contains(host))
        }
    }

    private fun tradeOf(m: Map<String, Any?>) = PortfolioTrade(
        id = m["id"].num()!!.toLong(),
        coin = m["coin"] as String,
        type = PortfolioTxType.valueOf(m["type"] as String),
        amount = BigDecimal(m["amount"] as String),
        priceUsdt = (m["price"] as String?)?.let { BigDecimal(it) },
        time = m["time"].num()!!.toLong(),
    )

    private fun watched(m: Map<String, Any?>) = WatchedAddress(
        chain = AddressChain.fromName(m["chain"] as String)!!,
        address = m["address"] as String,
        lastBalance = m["lastBalance"] as String?,
        tokenBalances = (m["tokenBalances"] as Map<String, Any?>?)?.mapValues { it.value as String } ?: emptyMap(),
        countTokens = m["countTokens"] as Boolean? ?: true,
        networks = (m["networks"] as List<Any?>?)?.map { it as String },
        countInPortfolio = m["countInPortfolio"] as Boolean? ?: true,
    )

    @Test
    fun multiHoldings() {
        val h = data["multiHoldings"].obj()
        val list = h["addresses"].list().map { watched(it.obj()) }
        val expected = h["expected"].list().map { it.list() }.map { it[0] as String to it[1] as String }
        assertEquals(expected, AddressBalance.holdings(list))
        val trades = AddressBalance.trades(list, emptyList(), 20_000)
        val ids = h["tradeIds"].obj()
        for ((coin, id) in ids) assertEquals("id $coin", id.num()!!.toLong(), trades.first { it.coin == coin }.id)
        assertEquals(trades.size, trades.map { it.id }.distinct().size)
        // «Nur anzeigen» zählt auch im Verlauf nicht
        val shown = list.last().copy(observations = listOf(AddressObservation(5, BigDecimal.ONE)))
        assertTrue(AddressBalanceHistory.of(listOf(shown)).isEmpty())
        val o = h["overlap"].obj()
        val own = o["ownCoins"].list().map { it as String }.toSet()
        assertEquals(o["expected"], WatchedAddressRules.overlap(list[o["address"].num()!!.toInt()], own))
        // Später aufgetauchte Überschneidung MIT passender Menge aller Coins: einmal automatisch «Nur anzeigen»,
        // «Rückgängig» bleibt entschieden; nur gleicher Coin bzw. nur ein Teil der Coins passt: zählt weiter
        val counting = list[o["address"].num()!!.toInt()].copy(countInPortfolio = true, overlapChecked = false)
        for (c in h["overlapAmounts"].list().map { it.obj() }) {
            val name = c["name"] as String
            val a = list[c["address"].num()!!.toInt()].copy(countInPortfolio = true, overlapChecked = false)
            val trades = c["trades"].list().map { tradeOf(it.obj()) }
            val matched = c["matched"].list()
            assertEquals(name, matched, WatchedAddressRules.overlapMatched(a, trades))
            val due = matched.isNotEmpty()
            // Automatisch nur, wenn ALLE Coins mit Guthaben passen (ETH passt, Token nicht: zählt weiter)
            val auto = c["autoShowOnly"] as Boolean
            assertTrue(name, !auto || due)
            assertEquals(name, auto, WatchedAddressRules.overlapAllMatched(a, trades))
            assertEquals(name, if (auto) listOf(a) else emptyList(), WatchedAddressRules.overlapAutoShowOnly(listOf(a), trades))
        }
        val matching = h["overlapAmounts"].list().first().obj()["trades"].list().map { tradeOf(it.obj()) }
        assertEquals(listOf(counting), WatchedAddressRules.overlapAutoShowOnly(listOf(counting), matching))
        val switched = WatchedAddressRules.withOverlapShowOnly(counting)
        assertFalse(switched.countInPortfolio)
        assertTrue(switched.overlapChecked)
        assertTrue(WatchedAddressRules.overlapAutoShowOnly(listOf(switched), matching).isEmpty())
        val undone = WatchedAddressRules.withCountInPortfolio(switched, true)
        assertTrue(undone.countInPortfolio && undone.overlapChecked)
        assertTrue(WatchedAddressRules.overlapAutoShowOnly(listOf(undone), matching).isEmpty())
        val stored = WatchedAddressExtras.decode(WatchedAddressExtras.encode(undone), undone.chain)
        assertTrue(stored.overlapChecked && stored.countInPortfolio)
        val shownStored = WatchedAddressExtras.decode(WatchedAddressExtras.encode(switched), switched.chain)
        assertTrue(shownStored.overlapChecked && !shownStored.countInPortfolio)
        h["overlapAtAdd"].list().map { it.obj() }.forEach { c ->
            assertEquals(c["ask"], WatchedAddressRules.overlapAtAdd(AddressChain.fromName(c["chain"] as String)!!, c["ownCoins"].list().map { it as String }.toSet()))
        }
        // Reihen je Schlüssel: ETH auf Ethereum und Arbitrum getrennt, gleicher Coin
        val a = list[0].copy(
            observations = listOf(AddressObservation(10, BigDecimal.ONE)),
            tokenObservations = mapOf("ARBITRUM:ETH" to listOf(AddressObservation(10, BigDecimal("0.5"))), "BASE:ETH" to listOf(AddressObservation(10, BigDecimal("9")))),
        )
        val histories = AddressBalanceHistory.of(listOf(a))
        assertEquals(listOf("ETH", "ETH"), histories.map { it.coin })
        assertEquals("1.5", com.cryptochecker.app.domain.portfolio.PortfolioDecimal.text(AddressBalanceHistory.latestByCoin(histories).getValue("ETH")))
    }

    @Test
    fun networkRules() {
        val r = data["networkRules"].obj()
        val f = r["firstScan"].obj()
        val fresh = WatchedAddress(chain = AddressChain.ETH, address = f["address"] as String)
        val after = WatchedAddressRules.applyFetched(
            fresh, 100, "1", "publicnode.com", (f["tokens"] as Map<String, Any?>).mapValues { it.value as String },
            networks = f["networks"].list().map { it as String },
        )
        assertEquals(f["expectedNetworks"], after.networks)
        // Erste Wahl: stumme Netze bleiben gewählt, im Hintergrund keine Wahl
        r["discovered"].list().map { it.obj() }.forEach { c ->
            assertEquals(
                "discovered $c", c["expected"],
                WatchedAddressRules.discoveredNetworks(c["found"].list().map { it as String }, c["unanswered"].list().map { it as String }, c["background"] == true),
            )
        }
        assertEquals(f["expectedBalances"].obj(), after.tokenBalances)
        // Netze und «Nur anzeigen» überleben Speicherform und Sicherung
        val shown = WatchedAddressRules.withCountInPortfolio(after, false)
        val decoded = WatchedAddressExtras.decode(WatchedAddressExtras.encode(shown), AddressChain.ETH)
        assertEquals(after.networks, decoded.networks)
        assertFalse(decoded.countInPortfolio)
        val entries = WatchedAddressRules.backupEntries(listOf(shown))
        val back = WatchedAddressRules.fromJson(WatchedAddressRules.toJson(entries))
        assertEquals(entries, back)
        val merged = WatchedAddressRules.merge(emptyList(), back).single()
        assertEquals(after.networks, merged.networks)
        assertFalse(merged.countInPortfolio)

        val base = watched(data["multiHoldings"].obj()["addresses"].list()[0].obj()).copy(lastAttempt = 5)
        val d = r["deselect"].obj()
        assertEquals(d["before"], base.networks)
        val off = WatchedAddressRules.withNetworks(base, d["after"].list().map { it as String })
        assertEquals(d["after"], off.networks)
        assertEquals(d["keptKeys"].list().toSet(), off.tokenBalances.keys)
        assertEquals(5L, off.lastAttempt)
        val s = r["select"].obj()
        val one = base.copy(networks = s["before"].list().map { it as String })
        val on = WatchedAddressRules.withNetworks(one, s["after"].list().map { it as String })
        assertEquals(0L, on.lastAttempt)
        // «Tokens mitzählen» aus: Netz-Guthaben bleiben, Token weg
        val noTokens = WatchedAddressRules.withCountTokens(base, false)
        assertEquals(setOf("ARBITRUM:ETH", "BSC:BNB"), noTokens.tokenBalances.keys)
        // Ältere Sicherung ohne neue Felder
        val legacy = WatchedAddressRules.fromJson(JsonArray("[{\"chain\":\"SOL\",\"address\":\"${data["chainBalance"].obj()["solana"].obj()["address"]}\"}]"))
        assertEquals(listOf(WatchedAddressBackupEntry(AddressChain.SOL, legacy[0].address)), legacy)
    }

    @Test
    fun wrappedCoinsStayOwnCoinWithBaseFallback() {
        // Gekapselt/gebrückt: eigener Coin (kein stilles Verschmelzen), Basis nur als Ersatzkurs
        assertEquals("WBTC", ChainAssets.resolve(AddressChain.ETH, "WBTC")?.coin)
        assertEquals("BTCB", ChainAssets.resolve(AddressChain.ETH, "BSC:BTCB")?.coin)
        assertEquals("BTC.B", ChainAssets.resolve(AddressChain.ETH, "AVALANCHE:BTC.b")?.coin)
        assertEquals("WETH", ChainAssets.resolve(AddressChain.ETH, "BASE:WETH")?.coin)
        assertEquals("WSOL", ChainAssets.resolve(AddressChain.SOL, "wSOL")?.coin)
        // Gebrückte Stablecoins zählen als Basis-Stablecoin
        assertEquals("USDC", ChainAssets.resolve(AddressChain.ETH, "ARBITRUM:USDC.e")?.coin)
        assertEquals("USDC", ChainAssets.resolve(AddressChain.ETH, "BASE:USDbC")?.coin)
        assertEquals("BTC", ChainAssets.wrappedBase("btc.b"))
        assertNull(ChainAssets.wrappedBase("BTC"))
        assertNull(ChainAssets.wrappedBase("USDC.E"))
        assertEquals(listOf("WBTC", "ETH", "BTC.B", "BTC"), ChainAssets.withBases(listOf("WBTC", "ETH", "BTC.b")))
        // Eigener Kurs (WBTC an einer Börse) gewinnt; ohne eigenen Kurs die Basis, nur mit Kurs der Basis
        assertEquals(
            mapOf("BTC.B" to "BTC"),
            ChainAssets.priceViaBase(listOf("WBTC", "BTC.B", "WAVAX", "ETH"), setOf("WBTC", "BTC", "ETH")),
        )
        assertEquals(
            mapOf("WBTC" to 1.0, "BTC" to 1.0, "BTCB" to 1.0),
            ChainAssets.fillFromBase(listOf("BTCB", "WETH"), mapOf("WBTC" to 1.0, "BTC" to 1.0)),
        )
    }
}
