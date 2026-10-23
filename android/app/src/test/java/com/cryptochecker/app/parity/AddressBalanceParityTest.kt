package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressObservation
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.app.domain.portfolio.Keccak256
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioPeriodChange
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/** Gemeinsame Testfälle `address_balance.json` (iOS: `AddressBalanceTests.swift`). */
class AddressBalanceParityTest {

    private val data = ParityJson.load("address_balance.json")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun chain(value: Any?): AddressChain? = AddressChain.fromName(value as String?)

    @Test
    fun keccak() {
        data["keccak256"].list().map { it.obj() }.forEach { c ->
            assertEquals("keccak: $c", c["hex"], Keccak256.hex((c["text"] as String).toByteArray()))
        }
        // Permutation gegen SHA3-256 (Python hashlib), auch an Blockgrenzen (136 Byte)
        data["sha3_256"].list().map { it.obj() }.forEach { c ->
            val text = (c["text"] as String).repeat(c["repeat"].num()!!.toInt())
            assertEquals("sha3: ${c["repeat"]}", c["hex"], hex(Keccak256.sha3(text.toByteArray())))
        }
    }

    @Test
    fun eip55() {
        data["eip55"].list().forEach { a ->
            val address = a as String
            assertEquals(address, AddressValidation.eip55(address.substring(2)))
        }
    }

    @Test
    fun validation() {
        data["validation"].list().map { it.obj() }.forEach { c ->
            val check = AddressValidation.check(c["input"] as String?, chain(c["expected"]))
            assertEquals("problem: $c", c["problem"], check.problem?.name)
            assertEquals("chain: $c", c["chain"], check.chain?.name)
            assertEquals("kind: $c", c["kind"], check.kind?.name)
            assertEquals("normalized: $c", c["normalized"], check.normalized)
        }
    }

    @Test
    fun btc() {
        val btc = data["btc"].obj()
        assertEquals(btc["hosts"], AddressBalance.BTC_HOSTS)
        val url = btc["url"].obj()
        assertEquals(url["url"], AddressBalance.btcUrl(url["host"] as String, url["address"] as String))
        btc["cases"].list().map { it.obj() }.forEach { c ->
            val body = c["body"] as String
            val address = c["address"] as String
            // Plausibilität je Netz (Litecoin: 84 Mio.)
            val net = (c["chain"] as String?)?.let { AddressChain.valueOf(it) } ?: AddressChain.BTC
            if (c["error"] == true) {
                try {
                    AddressBalance.parseBtc(body, address, net)
                    fail("Fehler erwartet: ${c["name"]}")
                } catch (e: Exception) {
                    // erwartet
                }
            } else {
                val stats = AddressBalance.parseBtc(body, address, net)
                assertEquals("confirmed: ${c["name"]}", c["confirmedSats"].num()!!.toLong(), stats.confirmedSats)
                assertEquals("pending: ${c["name"]}", c["pendingSats"].num()!!.toLong(), stats.pendingSats)
                assertEquals("balance: ${c["name"]}", c["balance"], AddressBalance.btcText(stats))
            }
        }
    }

    /** Zählende Adressen ohne je geladenes Guthaben (Portfolio-Alarme prüfen dann nicht). */
    @Test
    fun pendingCount() {
        val p = data["pendingCount"].obj()
        val list = p["addresses"].list().map { it.obj() }.map {
            WatchedAddress(
                chain = AddressChain.valueOf(it["chain"] as String), address = it["address"] as String,
                lastBalance = it["lastBalance"] as String?, countInPortfolio = it["countInPortfolio"] == true,
            )
        }
        val now = p["now"].num()!!.toLong()
        assertEquals(p["expected"].num()!!.toInt(), AddressBalance.pendingCount(list, now))
        assertEquals(0, AddressBalance.pendingCount(emptyList(), now))
    }

    /** Hintergrund: die am längsten nicht versuchten Adressen zuerst. */
    @Test
    fun dueOrder() {
        val d = data["dueOrder"].obj()
        val now = d["now"].num()!!.toLong()
        val list = d["addresses"].list().map { it.obj() }.map {
            WatchedAddress(id = it["id"].num()!!.toLong(), chain = AddressChain.BTC, address = "x${it["id"]}", lastAttempt = it["lastAttempt"].num()!!.toLong())
        }
        assertEquals(d["foreground"].list().map { it.num()!!.toLong() }, AddressBalance.dueOrder(list, now, force = false, background = false).map { it.id })
        assertEquals(d["background"].list().map { it.num()!!.toLong() }, AddressBalance.dueOrder(list, now, force = false, background = true).map { it.id })
    }

    @Test
    fun eth() {
        val eth = data["eth"].obj()
        assertEquals(eth["rpcs"], AddressBalance.ETH_RPCS)
        val request = eth["request"].obj()
        assertEquals(request["body"], AddressBalance.ethRequest(request["address"] as String))
        eth["cases"].list().map { it.obj() }.forEach { c ->
            val body = c["body"] as String
            if (c["error"] == true) {
                try {
                    AddressBalance.parseEthWei(body)
                    fail("Fehler erwartet: $body")
                } catch (e: Exception) {
                    // erwartet
                }
            } else {
                val wei = AddressBalance.parseEthWei(body)
                assertEquals("wei: $body", c["wei"], wei)
                assertEquals("balance: $body", c["balance"], AddressBalance.ethText(wei))
            }
        }
        assertEquals(data["privacyHosts"], AddressBalance.PRIVACY_HOSTS)
    }

    @Test
    fun numbers() {
        data["hexToDecimal"].list().map { it.obj() }.forEach { c ->
            assertEquals("hex: $c", c["decimal"], AddressBalance.hexToDecimal(c["hex"] as String))
        }
        data["unitsText"].list().map { it.obj() }.forEach { c ->
            assertEquals("units: $c", c["text"], AddressBalance.unitsText(c["digits"] as String, c["decimals"].num()!!.toInt()))
        }
    }

    @Test
    fun cache() {
        val cache = data["cache"].obj()
        assertEquals(cache["cacheMillis"].num()!!.toLong(), AddressBalance.CACHE_MILLIS)
        assertEquals(cache["minForcedMillis"].num()!!.toLong(), AddressBalance.MIN_FORCED_MILLIS)
        cache["cases"].list().map { it.obj() }.forEach { c ->
            val due = AddressBalance.isDue(c["lastAttempt"].num()!!.toLong(), c["now"].num()!!.toLong(), c["force"] == true)
            assertEquals("due: $c", c["due"], due)
        }
        data["providerName"].list().map { it.obj() }.forEach { c ->
            assertEquals(c["name"], AddressBalance.providerName(c["input"] as String))
        }
    }

    @Test
    fun holdings() {
        val h = data["holdings"].obj()
        val list = h["addresses"].list().map { it.obj() }.map {
            WatchedAddress(chain = chain(it["chain"])!!, address = it["address"] as String, lastBalance = it["lastBalance"] as String?)
        }
        val expected = h["expected"].list().map { it.obj() }.map { (it["coin"] as String) to (it["amount"] as String) }
        assertEquals(expected, AddressBalance.holdings(list))
    }

    @Test
    fun rules() {
        val r = data["rules"].obj()
        assertEquals(r["maxAddresses"].num()!!.toInt(), WatchedAddressRules.MAX_ADDRESSES)
        assertEquals(r["maxLabel"].num()!!.toInt(), WatchedAddressRules.MAX_LABEL)
        assertEquals(r["backupKey"], WatchedAddressRules.BACKUP_KEY)
        data["labels"].list().map { it.obj() }.forEach { c ->
            assertEquals("label: $c", c["label"], WatchedAddressRules.cleanLabel(c["input"] as String?))
        }
        data["shortAddress"].list().map { it.obj() }.forEach { c ->
            assertEquals(c["short"], WatchedAddressRules.shortAddress(c["input"] as String))
        }
    }

    private fun trade(m: Map<String, Any?>) = PortfolioTrade(
        id = 0, coin = m["coin"] as String, type = PortfolioTxType.fromName(m["type"] as String)!!,
        amount = BigDecimal(m["amount"] as String), priceUsdt = (m["price"] as String?)?.let { BigDecimal(it) },
        time = m["time"].num()!!.toLong(),
    )

    @Test
    fun portfolioMerge() {
        val m = data["portfolioMerge"].obj()
        val today = m["todayUtcDay"].num()!!.toLong()
        val addresses = m["addresses"].list().map { it.obj() }.map {
            WatchedAddress(chain = chain(it["chain"])!!, address = it["address"] as String, lastBalance = it["lastBalance"] as String?)
        }
        val expected = m["expectedTrades"].list().map { it.obj() }
        m["cases"].list().map { it.obj() }.forEach { c ->
            val real = c["trades"].list().map { trade(it.obj()) }
            val held = c["heldSince"].num()!!.toLong()
            assertEquals("heldSince: ${c["name"]}", held, AddressBalance.heldSince(real, today))
            val synthetic = AddressBalance.trades(addresses, real, today)
            assertEquals(expected.size, synthetic.size)
            synthetic.zip(expected).forEach { (t, e) ->
                assertEquals(e["id"].num()!!.toLong(), t.id)
                assertEquals(e["coin"], t.coin)
                assertEquals(e["type"], t.type.name)
                assertEquals(e["amount"], PortfolioDecimal.text(t.amount))
                assertNull(t.priceUsdt)
                assertEquals(held, t.time)
                assertTrue(AddressBalance.isAddressTrade(t.id))
            }
            assertEquals(real + synthetic, AddressBalance.withAddresses(real, addresses, today))
        }

        // Gesamtwert mit; Adress-Guthaben als eigener Teil — «Seit Kauf» bleibt aus den eigenen Käufen
        val s = m["summary"].obj()
        val prices = s["prices"].obj().mapValues { it.value.num()!! }
        val summary = PortfolioCalculator.summarize(
            AddressBalance.withAddresses(s["trades"].list().map { trade(it.obj()) }, addresses, today), prices,
        )
        assertEquals(s["totalValue"].num()!!, summary.totalValue, 1e-9)
        val btc = summary.open.first { it.coin == "BTC" }
        assertEquals(s["btcHoldings"], PortfolioDecimal.text(btc.holdingsExact))
        assertEquals(s["btcPricedHoldings"], PortfolioDecimal.text(btc.pricedHoldingsExact))
        assertEquals(s["btcAddressHoldings"], PortfolioDecimal.text(btc.addressHoldingsExact))
        assertEquals(s["costMissing"], summary.costMissing)
        assertEquals(s["missingCostCoins"], summary.missingCostCoins.sorted())
        assertEquals(s["unrealized"].num()!!, summary.unrealized!!, 1e-9)
        assertEquals(s["invested"].num()!!, summary.invested!!, 1e-9)
        assertEquals(s["addressValue"].num()!!, summary.addressValue, 1e-9)

        // Zeitraum: Adress-Einträge zählen nie rückwirkend (ohne beobachtete Reihe nichts)
        val g = m["periodGain"].obj()
        val closes = g["closes"].obj().mapValues { (_, v) -> v.obj().entries.associate { it.key.toLong() to it.value.num()!! } }
        val gain = PortfolioPeriodChange.gain(
            AddressBalance.withAddresses(g["trades"].list().map { trade(it.obj()) }, addresses, today),
            g["current"].obj().mapValues { it.value.num()!! },
            closes,
            g["baseDay"].num()!!.toLong(),
        )
        assertNull(g["amount"])
        assertNull(gain)

        // Ohne Adressen bleibt alles beim Alten
        val n = m["noAddresses"].obj()
        val real = n["trades"].list().map { trade(it.obj()) }
        assertEquals(n["expectedCount"].num()!!.toInt(), AddressBalance.withAddresses(real, emptyList(), today).size)
        assertEquals(real, AddressBalance.withAddresses(real, listOf(addresses.last()), today))
        assertNull(AddressBalance.amountFor("ETH", listOf(addresses.last())))
        assertEquals("0.5", PortfolioDecimal.text(AddressBalance.amountFor("btc", addresses)!!))
    }

    @Test
    fun balanceHistory() {
        val h = data["balanceHistory"].obj()
        fun obs(raw: Any?) = raw.list().map { it.list() }.map { AddressObservation(it[0].num()!!.toLong(), BigDecimal(it[1] as String)) }
        var list = emptyList<AddressObservation>()
        h["append"].list().map { it.obj() }.forEachIndexed { i, c ->
            list = AddressBalanceHistory.append(list, c["time"].num()!!.toLong(), BigDecimal(c["balance"] as String))
            val expected = obs(c["expected"])
            assertEquals("append $i size", expected.size, list.size)
            expected.zip(list).forEach { (e, a) ->
                assertEquals("append $i time", e.time, a.time)
                assertEquals("append $i balance", 0, e.balance.compareTo(a.balance))
            }
        }
        val histories = h["histories"].list().map { it.obj() }.map {
            AddressBalanceHistory(chain(it["chain"])!!, it["address"] as String, obs(it["observations"]))
        }
        val trades = AddressBalanceHistory.trades(histories)
        val expected = h["expectedTrades"].list().map { it.obj() }
        assertEquals(expected.size, trades.size)
        expected.zip(trades).forEach { (e, t) ->
            assertEquals(e["coin"], t.coin)
            assertEquals(e["type"], t.type.name)
            assertEquals(e["amount"], PortfolioDecimal.text(t.amount))
            assertEquals(e["time"].num()!!.toLong(), t.time)
            assertNull(t.priceUsdt)
            assertTrue(AddressBalance.isAddressTrade(t.id))
        }
        assertEquals(
            h["latestByCoin"].obj().mapValues { it.value as String },
            AddressBalanceHistory.latestByCoin(histories).mapValues { PortfolioDecimal.text(it.value) },
        )
        val g = h["periodGain"].obj()
        val closes = g["closes"].obj().mapValues { (_, v) -> v.obj().entries.associate { it.key.toLong() to it.value.num()!! } }
        val gain = PortfolioPeriodChange.gain(
            emptyList(), g["current"].obj().mapValues { it.value.num()!! }, closes, g["baseDay"].num()!!.toLong(),
            addresses = histories,
        )!!
        assertEquals(g["amount"].num()!!, gain.amountUsd, 1e-9)
        assertEquals(g["percent"].num()!!, gain.percent!!, 1e-9)
        assertEquals(g["skipped"].list(), gain.skipped)

        // Reihen aus den gespeicherten Adressen (ohne Beobachtung: geladenes Guthaben ab seinem Abruf)
        val of = h["of"].obj()
        val addresses = of["addresses"].list().map { it.obj() }.map {
            WatchedAddress(
                chain = chain(it["chain"])!!, address = it["address"] as String, lastBalance = it["lastBalance"] as String?,
                lastUpdated = it["lastUpdated"].num()!!.toLong(), observations = obs(it["observations"]),
            )
        }
        val built = AddressBalanceHistory.of(addresses)
        val expectedOf = of["expected"].list().map { it.obj() }
        assertEquals(expectedOf.size, built.size)
        expectedOf.zip(built).forEach { (e, a) ->
            assertEquals(e["chain"], a.chain.name)
            assertEquals(obs(e["observations"]).map { it.time to PortfolioDecimal.text(it.balance) }, a.observations.map { it.time to PortfolioDecimal.text(it.balance) })
        }

        // Speichern nach einem Abruf: nur bei geändertem Guthaben
        h["record"].list().map { it.obj() }.forEachIndexed { i, c ->
            val after = AddressBalanceHistory.record(obs(c["before"]), c["time"].num()!!.toLong(), BigDecimal(c["balance"] as String))
            assertEquals("record $i", obs(c["expected"]).map { it.time to PortfolioDecimal.text(it.balance) }, after.map { it.time to PortfolioDecimal.text(it.balance) })
        }

        // Speicherformat
        val j = h["json"].obj()
        val decoded = AddressBalanceHistory.decode(j["text"] as String)
        assertEquals(obs(j["expected"]).map { it.time to PortfolioDecimal.text(it.balance) }, decoded.map { it.time to PortfolioDecimal.text(it.balance) })
        assertEquals(j["encoded"], AddressBalanceHistory.encode(decoded))
        assertNull(AddressBalanceHistory.encode(emptyList()))
        assertEquals(emptyList<AddressObservation>(), AddressBalanceHistory.decode("kaputt"))
        assertEquals(emptyList<AddressObservation>(), AddressBalanceHistory.decode(null))

        // Stufen je Coin und Menge zu einem Zeitpunkt (24-h-Verlauf)
        val steps = AddressBalanceHistory.stepsByCoin(histories)
        assertEquals(
            h["steps"].obj().mapValues { (_, v) -> obs(v).map { it.time to PortfolioDecimal.text(it.balance) } },
            steps.mapValues { (_, v) -> v.map { it.time to PortfolioDecimal.text(it.balance) } },
        )
        h["amountAt"].list().map { it.obj() }.forEach { c ->
            val actual = com.cryptochecker.app.domain.portfolio.PortfolioWidgetSeries.amountAt(
                c["coin"] as String, c["amount"].num()!!, c["time"].num()!!.toLong(), steps,
            )
            assertEquals("amountAt $c", c["expected"].num()!!, actual, 1e-12)
        }
    }

    @Test
    fun backupWithObservations() {
        val b = data["backupObservations"].obj()
        val parsed = WatchedAddressRules.fromJson(JSONArray(b["text"] as String))
        val expected = b["expected"].list().map { it.obj() }
        assertEquals(expected.size, parsed.size)
        expected.zip(parsed).forEach { (e, a) ->
            assertEquals(e["address"], a.address)
            assertEquals(e["addedAt"].num()!!.toLong(), a.addedAt)
            assertEquals(
                e["observations"].list().map { it.list() }.map { it[0].num()!!.toLong() to it[1] as String },
                a.observations.map { it.time to PortfolioDecimal.text(it.balance) },
            )
        }
        // Hin und zurück ohne Verlust; Wiederherstellen übernimmt die Beobachtungen
        assertEquals(parsed, WatchedAddressRules.fromJson(WatchedAddressRules.toJson(parsed)))
        val merged = WatchedAddressRules.merge(emptyList(), parsed)
        assertEquals(parsed[0].observations, merged[0].observations)
        assertEquals(parsed[0].observations, WatchedAddressRules.backupEntries(merged)[0].observations)
    }

    private fun entry(m: Map<String, Any?>) = WatchedAddressBackupEntry(
        chain(m["chain"])!!, m["address"] as String, m["label"] as String?, m["addedAt"].num()?.toLong() ?: 0L,
    )

    @Test
    fun mergeAndBackup() {
        val m = data["merge"].obj()
        val existing = m["existing"].list().map { it.obj() }.map { WatchedAddress(chain = chain(it["chain"])!!, address = it["address"] as String) }
        val incoming = m["incoming"].list().map { entry(it.obj()) }
        val merged = WatchedAddressRules.merge(existing, incoming)
        val expected = m["expected"].list().map { entry(it.obj()) }
        assertEquals(expected, WatchedAddressRules.backupEntries(merged))

        val b = data["backupJson"].obj()
        val parsed = WatchedAddressRules.fromJson(JSONArray(b["text"] as String))
        assertEquals(b["expected"].list().map { entry(it.obj()) }, parsed)
        // Hin und zurück ohne Verlust
        assertEquals(parsed, WatchedAddressRules.fromJson(WatchedAddressRules.toJson(parsed)))
    }

    @Test
    fun mergeStopsAtLimit() {
        val existing = (1..WatchedAddressRules.MAX_ADDRESSES - 1).map { WatchedAddress(id = it.toLong(), chain = AddressChain.BTC, address = "x$it") }
        val incoming = listOf(
            WatchedAddressBackupEntry(AddressChain.BTC, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"),
            WatchedAddressBackupEntry(AddressChain.BTC, "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"),
        )
        val merged = WatchedAddressRules.merge(existing, incoming)
        assertEquals(1, merged.size)
        assertTrue(WatchedAddressRules.canAdd(existing))
        assertTrue(!WatchedAddressRules.canAdd(existing + merged))
        assertNull(AddressValidation.check(null).normalized)
    }
    /** Einzahlung/Umbuchung gleich Guthaben oder beobachtetem Zugang (± 1 %) — gleiche Fälle wie iOS. */
    @Test
    fun transferMatchesAddress() {
        fun d(text: String) = PortfolioDecimal.parse(text)!!
        fun tx(id: Long, coin: String, type: PortfolioTxType, amount: String, time: Long, coinFee: String? = null) =
            PortfolioTrade(
                id = id, coin = coin, type = type, amount = d(amount), priceUsdt = null, time = time,
                fee = coinFee?.let { d(it) }, feeUnit = coinFee?.let { com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit.COIN },
            )
        val a = WatchedAddress(
            id = 1, chain = AddressChain.BTC, address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            lastBalance = "0.5", lastUpdated = 2000,
            observations = listOf(AddressObservation(1000, d("0.3")), AddressObservation(2000, d("0.5"))),
        )
        val trades = listOf(
            tx(1, "BTC", PortfolioTxType.DEPOSIT, "0.5", 500),          // = Guthaben
            tx(2, "BTC", PortfolioTxType.DEPOSIT, "0.2015", 400),       // Zunahme 0.2 (+0.75 %)
            tx(3, "BTC", PortfolioTxType.DEPOSIT, "0.21", 300),         // +5 % daneben
            tx(4, "BTC", PortfolioTxType.TRANSFER, "0.52", 600, "0.02"), // erhalten 0.50
            tx(5, "BTC", PortfolioTxType.BUY, "0.5", 700),              // Kauf: keine Umbuchung
            tx(6, "ETH", PortfolioTxType.DEPOSIT, "0.5", 800),          // anderer Coin
            tx(-1, "BTC", PortfolioTxType.DEPOSIT, "0.5", 900),         // Adress-Einzahlung
            tx(8, "btc", PortfolioTxType.DEPOSIT, "0.3", 100),          // erste Beobachtung
        )
        assertEquals(listOf(8L, 2L, 1L, 4L), WatchedAddressRules.transferMatches(a, trades).map { it.id })
        // «Nur anzeigen» ändert die Erkennung nicht (Hinweis bleibt prüfbar)
        assertEquals(4, WatchedAddressRules.transferMatches(a.copy(countInPortfolio = false), trades).size)
        // Ohne geladenes Guthaben: nichts
        assertEquals(emptyList<PortfolioTrade>(), WatchedAddressRules.transferMatches(WatchedAddress(chain = AddressChain.BTC, address = "x"), trades))
        // Coin-Detail: alle zählenden Adressen des Coins, je Transaktion einmal; «Nur anzeigen» warnt nicht
        assertEquals(listOf(8L, 2L, 1L, 4L), WatchedAddressRules.transferMatchesFor("btc", listOf(a, a), trades).map { it.id })
        assertEquals(emptyList<PortfolioTrade>(), WatchedAddressRules.transferMatchesFor("BTC", listOf(a.copy(countInPortfolio = false)), trades))
        assertEquals(emptyList<PortfolioTrade>(), WatchedAddressRules.transferMatchesFor("ETH", listOf(a), trades))
    }
}
