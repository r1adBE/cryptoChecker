package com.cryptochecker.app.parity

import com.cryptochecker.app.domain.portfolio.AddressAlarmState
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.ChainBalance
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressBackupEntry
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `testdata/parity/address_balance.json`, Gegenstück `Tests/AddressAlarmStateTests.swift`: nie geladene
 * Adressen sperren die Portfolio-Alarme nur noch 24 Stunden ([AddressAlarmState]), nicht bestätigte Token-Guthaben
 * ([WatchedAddress.staleTokens]) aus einer vollen TON-Jetton-Seite ([ChainBalance.parseTonJettonPage]).
 */
class AddressAlarmStateParityTest {

    private val data = ParityJson.load("address_balance.json")

    @Suppress("UNCHECKED_CAST")
    private fun watched(o: Map<String, Any?>) = WatchedAddress(
        chain = AddressChain.valueOf(o["chain"] as String),
        address = o["address"] as String,
        label = o["label"] as String?,
        lastBalance = o["lastBalance"] as String?,
        lastError = o["lastError"] as String?,
        addedAt = o["addedAt"].num()?.toLong() ?: 0L,
        countInPortfolio = o["countInPortfolio"] as Boolean? ?: true,
        tokenBalances = (o["tokenBalances"] as Map<String, Any?>?)?.mapValues { it.value as String }.orEmpty(),
        staleTokens = (o["staleTokens"] as List<*>?)?.map { it as String }.orEmpty(),
        pendingSince = o["pendingSince"].num()?.toLong() ?: 0L,
    )

    @Test
    fun alarmState() {
        val block = data["addressAlarmState"].obj()
        val state = AddressAlarmState.of(block["addresses"].list().map { watched(it.obj()) }, block["now"].num()!!.toLong())
        val e = block["expected"].obj()
        assertEquals(e["pending"].num()!!.toInt(), state.pending)
        assertEquals(e["failing"], state.failing)
        assertEquals(e["staleTokenAddresses"], state.staleTokenAddresses)
        assertEquals(e["staleTokenAmounts"], state.staleTokenAmounts.mapValues { PortfolioDecimal.text(it.value) })
        val prices = block["prices"].obj().mapValues { it.value.num()!! }
        assertEquals(e["staleTokenValue"].num()!!, state.staleTokenValue(prices), 1e-9)
        assertEquals(0.0, AddressAlarmState().staleTokenValue(prices), 0.0)
    }

    /**
     * Eingespielt (Sicherung) mit altem `addedAt`: [WatchedAddress.pendingSince] = Zeitpunkt des Einspielens, damit die
     * Adresse wieder 24 h zum Laden hat; gerätelokal — nicht in der Sicherung, aber in der Android-Spalte («ps»).
     */
    @Test
    fun pendingSinceLocal() {
        val now = 1_700_000_000_000L
        val entry = WatchedAddressBackupEntry(AddressChain.BTC, "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", addedAt = 0)
        val restored = WatchedAddressRules.merge(emptyList(), listOf(entry), now).single()
        assertEquals(now, restored.pendingSince)
        assertEquals(0L, restored.addedAt)
        assertEquals(listOf(entry), WatchedAddressRules.backupEntries(listOf(restored)))
        assertFalse(WatchedAddressRules.toJson(WatchedAddressRules.backupEntries(listOf(restored))).toString().contains("pendingSince"))
        // Erster Fehler kurz nach dem Einspielen: lädt noch, nach über 24 h fehlgeschlagen
        val failed = restored.copy(lastError = "timeout")
        assertTrue(AddressAlarmState.isPending(failed, now + 3_600_000L))
        assertFalse(AddressAlarmState.isPending(failed, now + AddressAlarmState.PENDING_MILLIS))
        assertFalse(AddressAlarmState.isPending(failed.copy(pendingSince = 0), now))
        // Speicherform hin und zurück; ohne Zusatzdaten (keine Beobachtungen: null)
        assertEquals(now, WatchedAddressExtras.decode(WatchedAddressExtras.encode(restored), AddressChain.BTC).pendingSince)
        assertNull(WatchedAddressExtras.encode(restored.copy(pendingSince = 0)))
        assertEquals(0L, WatchedAddressExtras.decode("""{"o":[],"ps":-5}""", AddressChain.BTC).pendingSince)
    }

    @Test
    fun keptTokens() {
        val block = data["keptTokens"].obj()
        var a = watched(block["start"].obj())
        block["steps"].list().map { it.obj() }.forEachIndexed { i, s ->
            @Suppress("UNCHECKED_CAST")
            val tokens = (s["tokens"] as Map<String, Any?>?)?.mapValues { it.value as String }
            val kept = (s["kept"] as List<*>?)?.map { it as String }
            a = WatchedAddressRules.applyFetched(a, s["time"].num()!!.toLong(), s["balance"] as String, null, tokens, keptTokens = kept)
            assertEquals("tokenBalances $i", s["tokenBalances"], a.tokenBalances)
            assertEquals("staleTokens $i", s["staleTokens"], a.staleTokens)
            // Speicherform (Android-Spalte) hin und zurück
            assertEquals("Spalte $i", a.staleTokens, WatchedAddressExtras.decode(WatchedAddressExtras.encode(a), a.chain).staleTokens)
        }
        // «Tokens mitzählen» aus: nicht bestätigte Jettons fallen mit ihrem Guthaben weg
        val stale = watched(block["start"].obj()).copy(staleTokens = listOf("USDT"))
        assertTrue(WatchedAddressRules.withCountTokens(stale, false).staleTokens.isEmpty())
    }

    @Test
    fun tonJettonPageKept() {
        val ton = data["chainBalance"].obj()["ton"].obj()
        ton["jettons"].list().map { it.obj() }.filter { it["error"] != true }.forEach { c ->
            val page = ChainBalance.parseTonJettonPage(c["body"] as String)
            assertEquals(c["name"] as String, c["tokens"], page.tokens)
            assertEquals(c["name"] as String, c["kept"], page.kept)
        }
    }
}
