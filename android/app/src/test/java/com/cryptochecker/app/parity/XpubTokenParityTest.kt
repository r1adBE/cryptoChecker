@file:Suppress("UNCHECKED_CAST")

package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressBalance
import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.AddressObservation
import com.cryptochecker.app.domain.portfolio.Bip32
import com.cryptochecker.app.domain.portfolio.Erc20Tokens
import com.cryptochecker.app.domain.portfolio.Keccak256
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.portfolio.XpubCache
import com.cryptochecker.app.domain.portfolio.XpubDeriver
import com.cryptochecker.app.domain.portfolio.XpubScan
import com.cryptochecker.app.domain.portfolio.ripemd160
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/** ERC-20, xpub (BIP32/49/84) und Gap-Suche aus `address_balance.json` (iOS: `XpubTokenTests.swift`). */
class XpubTokenParityTest {

    private val data = ParityJson.load("address_balance.json")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun obs(raw: Any?): List<AddressObservation> =
        raw.list().map { it.obj() }.map { AddressObservation(it["t"].num()!!.toLong(), BigDecimal(it["b"] as String)) }

    private fun obsText(list: List<AddressObservation>) = list.map { it.time to PortfolioDecimal.text(it.balance) }

    private fun tokenObs(raw: Any?): Map<String, List<AddressObservation>> =
        (raw as Map<String, Any?>?)?.mapValues { obs(it.value) } ?: emptyMap()

    @Test
    fun ripemd160() {
        data["ripemd160"].list().map { it.obj() }.forEach { c ->
            val text = (c["text"] as String).repeat(c["repeat"].num()!!.toInt())
            assertEquals("ripemd: ${c["text"]}×${c["repeat"]}", c["hex"], hex(Bip32.ripemd160(text.toByteArray())))
        }
        val h = data["bip32"].obj()["hash160"].obj()
        val keyhash = Bip32.hash160(unhex(h["pubkey"] as String))
        assertEquals(h["keyhash"], hex(keyhash))
        assertEquals(h["scriptHash"], hex(Bip32.hash160(byteArrayOf(0x00, 0x14) + keyhash)))
    }

    @Test
    fun bip32Children() {
        val b = data["bip32"].obj()
        b["children"].list().map { it.obj() }.forEach { c ->
            val parent = Bip32.parse(c["parent"] as String)!!
            val child = Bip32.child(parent, c["index"].num()!!.toLong())!!
            assertEquals("child ${c["index"]}", c["child"], Bip32.serialize(child))
            // Hin und zurück
            assertEquals(c["parent"], Bip32.serialize(parent))
        }
        val hardened = b["hardened"].obj()
        assertNull(Bip32.child(Bip32.parse(hardened["parent"] as String)!!, hardened["index"].num()!!.toLong()))
    }

    @Test
    fun bip32Addresses() {
        data["bip32"].obj()["addresses"].list().map { it.obj() }.forEach { c ->
            val key = Bip32.parse(c["key"] as String)!!
            val chainKey = Bip32.child(key, c["chain"].num()!!.toLong())!!
            val leaf = Bip32.child(chainKey, c["index"].num()!!.toLong())!!
            assertEquals("pubkey ${c["note"]}", c["pubkey"], hex(leaf.publicKey))
            assertEquals("address ${c["note"]}", c["address"], Bip32.address(key.script, leaf.publicKey))
            assertEquals(c["address"], XpubDeriver(c["key"] as String).address(c["chain"].num()!!.toInt(), c["index"].num()!!.toInt()))
            // Abgeleitete Adresse besteht die eigene Prüfung
            assertTrue(com.cryptochecker.app.domain.portfolio.AddressValidation.check(c["address"] as String).isValid)
        }
    }

    @Test
    fun tokenList() {
        val t = data["tokens"].obj()
        val expected = t["list"].list().map { it.obj() }.map {
            Erc20Tokens.Token(it["symbol"] as String, it["contract"] as String, it["decimals"].num()!!.toInt(), it["coin"] as String)
        }
        assertEquals(expected, Erc20Tokens.LIST)
        // Funktionsauswahl = keccak256("balanceOf(address)")[0..4]
        assertEquals(t["selector"], "0x" + Keccak256.hex((t["signature"] as String).toByteArray()).take(8))
        assertEquals(t["selector"], Erc20Tokens.BALANCE_OF)
        val batch = t["batchRequest"].obj()
        assertEquals(batch["body"], Erc20Tokens.batchRequest(batch["address"] as String))
        val single = t["singleRequest"].obj()
        assertEquals(single["body"], Erc20Tokens.singleRequest(single["address"] as String, Erc20Tokens.bySymbol(single["symbol"] as String)!!))
        t["tradeIds"].obj().forEach { (symbol, id) -> assertEquals(id.num()!!.toLong(), AddressBalance.tokenTradeId(symbol)) }
    }

    @Test
    fun tokenResponses() {
        val t = data["tokens"].obj()
        t["batch"].list().map { it.obj() }.forEach { c ->
            val body = c["body"] as String
            when {
                c["unsupported"] == true -> try {
                    Erc20Tokens.parseBatch(body)
                    fail("kein Stapel erwartet")
                } catch (e: Erc20Tokens.BatchUnsupported) {
                    // erwartet
                }
                c["error"] == true -> try {
                    Erc20Tokens.parseBatch(body)
                    fail("Fehler erwartet: ${c["name"]}")
                } catch (e: Erc20Tokens.BatchUnsupported) {
                    fail("kein Stapel ist hier falsch: ${c["name"]}")
                } catch (e: Exception) {
                    // erwartet
                }
                else -> {
                    val parsed = Erc20Tokens.parseBatch(body)
                    assertEquals(c["wei"], parsed.weiDigits)
                    assertEquals(c["tokens"].obj(), parsed.tokens)
                }
            }
        }
        t["single"].list().map { it.obj() }.forEach { c ->
            val token = Erc20Tokens.bySymbol(c["symbol"] as String)!!
            if (c["error"] == true) {
                try {
                    Erc20Tokens.parseSingle(c["body"] as String, token)
                    fail("Fehler erwartet: ${c["body"]}")
                } catch (e: Exception) {
                    // erwartet
                }
            } else {
                assertEquals(c["text"], Erc20Tokens.parseSingle(c["body"] as String, token))
            }
        }
        data["btcTxCount"].list().map { it.obj() }.forEach { c ->
            val stats = AddressBalance.parseBtc(c["body"] as String, c["address"] as String)
            assertEquals(c["txCount"].num()!!.toInt(), stats.txCount)
            assertEquals(c["totalSats"].num()!!.toLong(), stats.totalSats)
        }
    }

    private fun xpubCache(m: Map<String, Any?>?): XpubCache? = m?.let {
        XpubCache(
            receive = it["receive"].list().map { a -> a as String },
            change = it["change"].list().map { a -> a as String },
            usedReceive = it["usedReceive"].list().map { i -> i.num()!!.toInt() },
            usedChange = it["usedChange"].list().map { i -> i.num()!!.toInt() },
            fullScan = it["fullScan"].num()!!.toLong(),
        )
    }

    private fun watched(m: Map<String, Any?>) = WatchedAddress(
        chain = AddressChain.fromName(m["chain"] as String)!!,
        address = m["address"] as String,
        lastBalance = m["lastBalance"] as String?,
        tokenBalances = (m["tokenBalances"] as Map<String, Any?>?)?.mapValues { it.value as String } ?: emptyMap(),
        tokenObservations = tokenObs(m["tokenObservations"]),
        countTokens = m["countTokens"] as Boolean? ?: true,
        xpub = xpubCache(m["xpub"] as Map<String, Any?>?),
    )

    @Test
    fun tokenHoldings() {
        val h = data["tokenHoldings"].obj()
        val list = h["addresses"].list().map { watched(it.obj()) }
        val expected = h["expected"].list().map { it.list() }.map { it[0] as String to it[1] as String }
        assertEquals(expected, AddressBalance.holdings(list))
        assertEquals("100.5", PortfolioDecimal.text(AddressBalance.amountFor("usdt", list)!!))
        // Adress-Einzahlungen: Token mit eigener negativer Id
        val trades = AddressBalance.trades(list, emptyList(), 20_000)
        assertEquals(expected.map { it.first }, trades.map { it.coin })
        assertEquals(-101L, trades.first { it.coin == "USDT" }.id)
        assertTrue(trades.all { AddressBalance.isAddressTrade(it.id) })
        assertEquals(trades.map { it.id }.distinct().size, trades.size)
    }

    @Test
    fun applyFetched() {
        val f = data["applyFetched"].obj()
        var a = watched(f["start"].obj())
        f["steps"].list().map { it.obj() }.forEachIndexed { i, s ->
            val tokens = (s["tokens"] as Map<String, Any?>?)?.mapValues { it.value as String }
            a = WatchedAddressRules.applyFetched(a, s["time"].num()!!.toLong(), s["balance"] as String, "publicnode.com", tokens)
            assertEquals("tokenBalances $i", s["tokenBalances"].obj(), a.tokenBalances)
            assertEquals("tokenObservations $i", tokenObs(s["tokenObservations"]).mapValues { obsText(it.value) }, a.tokenObservations.mapValues { obsText(it.value) })
            assertEquals("observations $i", obsText(obs(s["observations"])), obsText(a.observations))
            assertEquals(s["time"].num()!!.toLong(), a.lastUpdated)
            // Speicherform (Android-Spalte) hin und zurück
            val decoded = WatchedAddressExtras.decode(WatchedAddressExtras.encode(a), a.chain)
            assertEquals(a.tokenBalances, decoded.tokenBalances)
            assertEquals(a.tokenObservations, decoded.tokenObservations)
            assertEquals(a.observations, decoded.observations)
            if (i == 1) {
                val history = AddressBalanceHistory.trades(AddressBalanceHistory.of(listOf(a)))
                    .filter { it.coin == "USDT" || it.coin == "LINK" }
                val expected = f["history"].list().map { it.obj() }
                assertEquals(expected.size, history.size)
                expected.zip(history).forEach { (e, t) ->
                    assertEquals(e["coin"], t.coin)
                    assertEquals(e["type"], t.type.name)
                    assertEquals(e["amount"], PortfolioDecimal.text(t.amount))
                    assertEquals(e["time"].num()!!.toLong(), t.time)
                }
            }
        }
        // «Tokens mitzählen» aus: Token weg, keine Reihen; wieder an: sofort fällig
        val off = WatchedAddressRules.withCountTokens(a.copy(lastAttempt = 5), false)
        assertEquals(f["countTokensOff"].obj()["tokenBalances"].obj(), off.tokenBalances)
        assertTrue(off.tokenObservations.isEmpty())
        assertTrue(AddressBalanceHistory.of(listOf(off)).all { it.token == null })
        assertTrue(WatchedAddressRules.applyFetched(off, 99, "1", null, mapOf("USDT" to "1")).tokenBalances.isEmpty())
        assertEquals(false, WatchedAddressExtras.decode(WatchedAddressExtras.encode(off), off.chain).countTokens)
        assertEquals(0L, WatchedAddressRules.withCountTokens(off, true).lastAttempt)
        // Ohne Zusatzdaten bleibt die Spalte das alte Array
        val plain = WatchedAddress(chain = AddressChain.BTC, address = "x", observations = listOf(AddressObservation(5, BigDecimal("0.1"))))
        assertEquals(AddressBalanceHistory.encode(plain.observations), WatchedAddressExtras.encode(plain))
        assertEquals(plain.observations, WatchedAddressExtras.decode("[{\"t\":5,\"b\":\"0.1\"}]", AddressChain.BTC).observations)
        assertEquals(WatchedAddressExtras.Decoded(), WatchedAddressExtras.decode("{kaputt", AddressChain.ETH))
    }

    private fun scanCache(m: Map<String, Any?>?): XpubCache? = m?.let {
        XpubCache(
            receive = (0 until it["receive"].num()!!.toInt()).map { i -> "r$i" },
            change = (0 until it["change"].num()!!.toInt()).map { i -> "c$i" },
            usedReceive = it["usedReceive"].list().map { i -> i.num()!!.toInt() },
            usedChange = it["usedChange"].list().map { i -> i.num()!!.toInt() },
            fullScan = it["fullScan"].num()!!.toLong(),
            // Optional: zuletzt leere benutzte Adressen
            emptyReceive = it["emptyReceive"]?.list()?.map { i -> i.num()!!.toInt() }.orEmpty(),
            emptyChange = it["emptyChange"]?.list()?.map { i -> i.num()!!.toInt() }.orEmpty(),
        )
    }

    @Test
    fun gapScan() {
        val g = data["gapScan"].obj()
        assertEquals(g["gap"].num()!!.toInt(), XpubScan.GAP)
        assertEquals(g["lookahead"].num()!!.toInt(), XpubScan.LOOKAHEAD)
        assertEquals(g["maxRequests"].num()!!.toInt(), XpubScan.MAX_REQUESTS)
        assertEquals(g["fullScanMillis"].num()!!.toLong(), XpubScan.FULL_SCAN_MILLIS)
        g["cases"].list().map { it.obj() }.forEach { c ->
            val name = c["name"] as String
            val used = c["used"].obj().mapValues { (_, v) -> v.list().map { it.list() }.associate { it[0].num()!!.toInt() to it[1].num()!!.toLong() } }
            val scan = XpubScan(XpubScan.Mode.valueOf(c["mode"] as String), scanCache(c["cache"] as Map<String, Any?>?))
            val e = c["expected"].obj()
            val maxIndex = intArrayOf(-1, -1)
            try {
                while (true) {
                    val t = scan.next() ?: break
                    maxIndex[t.chain] = maxOf(maxIndex[t.chain], t.index)
                    val sats = used[t.chain.toString()]?.get(t.index)
                    scan.report(t, if (sats != null) 1 else 0, sats ?: 0)
                }
                if (e["tooMany"] == true) fail("zu viele erwartet: $name")
            } catch (x: XpubScan.TooMany) {
                assertEquals(name, true, e["tooMany"])
                return@forEach
            }
            assertEquals("requests $name", e["requests"].num()!!.toInt(), scan.requests)
            assertEquals("sats $name", e["totalSats"].num()!!.toLong(), scan.totalSats)
            assertEquals("maxReceive $name", e["maxReceive"].num()!!.toInt(), maxIndex[0])
            assertEquals("maxChange $name", e["maxChange"].num()!!.toInt(), maxIndex[1])
            val cache = scan.cache((0..maxOf(maxIndex[0], 0)).map { "r$it" }, (0..maxOf(maxIndex[1], 0)).map { "c$it" }, 777)
            assertEquals("usedReceive $name", e["usedReceive"].list().map { it.num()!!.toInt() }, cache.usedReceive)
            assertEquals("usedChange $name", e["usedChange"].list().map { it.num()!!.toInt() }, cache.usedChange)
            e["emptyReceive"]?.let { assertEquals("emptyReceive $name", it.list().map { i -> i.num()!!.toInt() }, cache.emptyReceive) }
            e["emptyChange"]?.let { assertEquals("emptyChange $name", it.list().map { i -> i.num()!!.toInt() }, cache.emptyChange) }
            val old = scanCache(c["cache"] as Map<String, Any?>?)
            assertEquals(if (c["mode"] == "FULL") 777L else old!!.fullScan, cache.fullScan)
            assertEquals("complete $name", e["complete"] as Boolean? ?: true, scan.complete)
        }
        // Hintergrund mit Vorausblick; unvollständig und tiefer als zuletzt = nicht übernehmen
        assertEquals(g["backgroundLookahead"].num()!!.toInt(), XpubScan.BACKGROUND_LOOKAHEAD)
        g["keepBackground"].list().map { it.obj() }.forEach { c ->
            assertEquals(
                "keepBackground $c", c["keep"],
                XpubScan.keepBackground(c["complete"] == true, c["sats"].num()!!.toLong(), c["lastBalance"] as String?),
            )
        }
        // F-16: benutzte Indizes optional — vollständige Suche sicher zu gross = schnell
        fun ints(v: Any?) = v?.list()?.map { it.num()!!.toInt() }.orEmpty()
        g["modeFor"].list().map { it.obj() }.forEach { c ->
            val cache = (c["cache"] as Map<String, Any?>?)?.let {
                XpubCache(fullScan = it["fullScan"].num()!!.toLong(), usedReceive = ints(it["usedReceive"]), usedChange = ints(it["usedChange"]))
            }
            assertEquals("modeFor $c", c["mode"], XpubScan.modeFor(cache, c["now"].num()!!.toLong(), c["background"] == true)?.name)
        }
        g["fullEstimate"].list().map { it.obj() }.forEach { c ->
            val cache = XpubCache(usedReceive = ints(c["usedReceive"]), usedChange = ints(c["usedChange"]))
            assertEquals("fullEstimate $c", c["estimate"].num()!!.toInt(), XpubScan.fullEstimate(cache))
        }
        // Zwischenspeicher hin und zurück; unpassende Indizes fallen weg
        val cache = XpubCache(listOf("a", "b"), listOf("c"), listOf(1), listOf(0), 42)
        assertEquals(cache, XpubCache.fromJson(XpubCache.toJson(cache)))
        assertEquals(listOf(1), XpubCache.fromJson(org.json.JSONObject("{\"r\":[\"a\",\"b\"],\"ru\":[1,5,-1,1]}"))!!.usedReceive)
        // Leere benutzte hin und zurück; nur benutzte Indizes bleiben
        val withEmpty = XpubCache(listOf("a", "b"), listOf("c"), listOf(0, 1), listOf(0), 42, emptyReceive = listOf(1), emptyChange = listOf(0))
        assertEquals(withEmpty, XpubCache.fromJson(XpubCache.toJson(withEmpty)))
        assertEquals(listOf(1), XpubCache.fromJson(org.json.JSONObject("{\"r\":[\"a\",\"b\"],\"ru\":[1],\"re\":[0,1,7]}"))!!.emptyReceive)
        // F-16: Rückfall-Merkmal «lf» nur wenn true; nach vollständiger Suche wieder false
        val limited = cache.copy(limitedFallback = true)
        assertEquals(true, XpubCache.toJson(limited).getBoolean("lf"))
        assertEquals(limited, XpubCache.fromJson(XpubCache.toJson(limited)))
        assertTrue(!XpubCache.toJson(cache).has("lf"))
        assertEquals(false, XpubCache.fromJson(org.json.JSONObject("{\"r\":[\"a\"],\"lf\":\"ja\"}"))!!.limitedFallback)
        assertEquals(true, XpubScan(XpubScan.Mode.QUICK, limited).cache(listOf("a"), listOf("c"), 777).limitedFallback)
        assertEquals(false, XpubScan(XpubScan.Mode.FULL, limited).cache(listOf("a"), listOf("c"), 777).limitedFallback)
    }

    /**
     * xpub eines Segwit-Kontos (Ledger Live): dieselben Schlüssel wie die BIP84-zpub bzw. BIP49-ypub der Testvektoren,
     * nur mit xpub-Versionsbytes — als P2WPKH/P2SH-P2WPKH abgeleitet ergeben sie die Vektor-Adressen.
     */
    @Test
    fun xpubWithSegwitScript() {
        val cases = data["bip32"].obj()["addresses"].list().map { it.obj() }
            .filter { (it["key"] as String).startsWith("zpub") || (it["key"] as String).startsWith("ypub") }
        assertTrue(cases.any { (it["key"] as String).startsWith("zpub") } && cases.any { (it["key"] as String).startsWith("ypub") })
        cases.forEach { c ->
            val key = Bip32.parse(c["key"] as String)!!
            val asXpub = Bip32.serialize(Bip32.ExtendedKey(Bip32.XPUB, key.depth, key.parentFingerprint, key.childNumber, key.chainCode, key.publicKey))
            assertTrue(asXpub.startsWith("xpub"))
            val chain = c["chain"].num()!!.toInt()
            val index = c["index"].num()!!.toInt()
            // Ohne Typ: xpub = P2PKH (andere Adresse)
            assertEquals(Bip32.Script.P2PKH, XpubDeriver(asXpub).script)
            assertTrue(XpubDeriver(asXpub).address(chain, index) != c["address"])
            assertEquals("${c["note"]}", c["address"], XpubDeriver(asXpub, key.script).address(chain, index))
            // ypub/zpub: Typ nach Präfix, keine Suche
            assertEquals(listOf(key.script), XpubScan.scripts(key, null))
            assertNull(XpubScan.pickScript(key, listOf(key.script to 3)))
        }
    }

    /** Adresstypen der Suche und gemerkter Typ (xpub). */
    @Test
    fun xpubScriptSearch() {
        val xpub = Bip32.parse(data["bip32"].obj()["addresses"].list().map { it.obj() }.first { (it["key"] as String).startsWith("xpub") }["key"] as String)!!
        val all = listOf(Bip32.Script.P2PKH, Bip32.Script.P2SH_P2WPKH, Bip32.Script.P2WPKH)
        assertEquals(all, XpubScan.scripts(xpub, null))
        assertEquals(all, XpubScan.scripts(xpub, XpubCache(listOf("a"), fullScan = 5)))
        // Wie in älteren Versionen: mit benutzter Adresse = als P2PKH gefunden
        assertEquals(listOf(Bip32.Script.P2PKH), XpubScan.scripts(xpub, XpubCache(listOf("a"), usedReceive = listOf(0))))
        assertEquals(listOf(Bip32.Script.P2WPKH), XpubScan.scripts(xpub, XpubCache(script = Bip32.Script.P2WPKH)))
        // Erster Typ mit Verlauf; keiner = null (wieder alle prüfen); nur einer = dieser
        assertEquals(Bip32.Script.P2WPKH, XpubScan.pickScript(xpub, listOf(Bip32.Script.P2PKH to 0, Bip32.Script.P2SH_P2WPKH to 0, Bip32.Script.P2WPKH to 2)))
        assertNull(XpubScan.pickScript(xpub, all.map { it to 0 }))
        assertEquals(Bip32.Script.P2PKH, XpubScan.pickScript(xpub, listOf(Bip32.Script.P2PKH to 0)))
        // Gespeichert als «s»; ohne Typ kein «s»; unbekannt = keiner
        val cache = XpubCache(listOf("a"), listOf("c"), listOf(0), emptyList(), 42, Bip32.Script.P2SH_P2WPKH)
        assertEquals("P2SH_P2WPKH", XpubCache.toJson(cache).getString("s"))
        assertEquals(cache, XpubCache.fromJson(XpubCache.toJson(cache)))
        assertTrue(!XpubCache.toJson(cache.copy(script = null)).has("s"))
        assertNull(XpubCache.fromJson(org.json.JSONObject("{\"r\":[\"a\"],\"s\":\"P2XX\"}"))!!.script)
    }

    @Test
    fun backupWithTokens() {
        val b = data["backupTokens"].obj()
        val parsed = WatchedAddressRules.fromJson(JSONArray(b["text"] as String))
        val expected = b["expected"].list().map { it.obj() }
        assertEquals(expected.size, parsed.size)
        expected.zip(parsed).forEach { (e, a) ->
            assertEquals(e["address"], a.address)
            assertEquals(e["countTokens"], a.countTokens)
            assertEquals(
                e["tokens"].obj().mapValues { (_, v) -> v.list().map { it.list() }.map { p -> p[0].num()!!.toLong() to p[1] as String } },
                a.tokenObservations.mapValues { obsText(it.value) },
            )
        }
        assertEquals(parsed, WatchedAddressRules.fromJson(WatchedAddressRules.toJson(parsed)))
        val merged = WatchedAddressRules.merge(emptyList(), parsed)
        assertEquals(3, merged.size)
        assertEquals(false, merged[0].countTokens)
        assertEquals(parsed[1].tokenObservations, merged[1].tokenObservations)
        assertTrue(merged[2].isXpub)
        assertEquals(parsed, WatchedAddressRules.backupEntries(merged))
    }
}
