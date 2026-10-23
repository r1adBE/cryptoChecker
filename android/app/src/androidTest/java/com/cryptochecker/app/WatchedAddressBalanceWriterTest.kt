package com.cryptochecker.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.data.portfolio.WatchedAddressBalanceWriter
import com.cryptochecker.app.data.portfolio.WatchedAddressDao
import com.cryptochecker.app.data.portfolio.WatchedAddressEntity
import com.cryptochecker.app.data.portfolio.WatchedAddressSource
import com.cryptochecker.app.data.portfolio.toDomain
import com.cryptochecker.app.domain.portfolio.AddressChain
import com.cryptochecker.app.domain.portfolio.ChainAssets
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressExtras
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Speichern eines Adress-Abrufs gegen die echte Room-Datenbank ([WatchedAddressBalanceWriter]): Hat der Nutzer während
 * des Abrufs etwas dazugenommen (Netz, «Tokens mitzählen»), wird verworfen und die Adresse bleibt fällig — auch wenn
 * der Abzug noch nie versucht war. Unverändert oder weniger gewählt: gespeichert.
 */
@RunWith(AndroidJUnit4::class)
class WatchedAddressBalanceWriterTest {

    private lateinit var db: PortfolioDatabase
    private lateinit var dao: WatchedAddressDao
    private val old = 1_000_000L
    private val time = 2_000_000L

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PortfolioDatabase::class.java).allowMainThreadQueries().build()
        dao = db.getWatchedAddressDao()
    }

    @After
    fun close() = db.close()

    private suspend fun insert(a: WatchedAddress): Long = dao.insert(WatchedAddressEntity.from(a))

    private suspend fun read(id: Long): WatchedAddress = dao.getById(id)!!.toDomain()!!

    /** Nutzer-Änderung wie das Repository (changeWatched). */
    private suspend fun change(id: Long, transform: (WatchedAddress) -> WatchedAddress) {
        val updated = transform(read(id))
        dao.setExtras(id, WatchedAddressExtras.encode(updated), updated.lastAttempt)
    }

    private fun fetched(tokens: Map<String, String>? = null) = WatchedAddressSource.Fetched("1.5", "publicnode.com", tokens)

    private fun update(snapshot: WatchedAddress, fetched: WatchedAddressSource.Fetched?, error: String? = null) =
        WatchedAddressSource.Update(snapshot.id, time, fetched, error, snapshot.countTokens, snapshot.networks)

    private fun evm(lastAttempt: Long, networks: List<String>?, countTokens: Boolean = true) = WatchedAddress(
        chain = AddressChain.ETH, address = "0x0000000000000000000000000000000000000001",
        lastAttempt = lastAttempt, networks = networks, countTokens = countTokens,
    )

    @Test
    fun unchangedIsSaved() = runBlocking {
        val id = insert(evm(old, listOf("BSC")))
        val snapshot = read(id)
        WatchedAddressBalanceWriter.save(db, dao, update(snapshot, fetched(mapOf(ChainAssets.key("BSC", "BNB") to "2"))))
        val saved = read(id)
        assertEquals("1.5", saved.lastBalance)
        assertEquals(time, saved.lastAttempt)
        assertEquals("2", saved.tokenBalances[ChainAssets.key("BSC", "BNB")])
    }

    /** Netz A angetippt (Abzug mit A, lastAttempt 0), während des Abrufs Netz B: verworfen, bleibt fällig. */
    @Test
    fun networkAddedDuringFetchIsDiscardedAndStaysDue() = runBlocking {
        val id = insert(evm(old, listOf("BSC")))
        change(id) { WatchedAddressRules.withNetworks(it, listOf("BSC", "POLYGON")) }
        val snapshot = read(id)
        assertEquals(0L, snapshot.lastAttempt)
        change(id) { WatchedAddressRules.withNetworks(it, listOf("BSC", "POLYGON", "ARBITRUM")) }
        WatchedAddressBalanceWriter.save(db, dao, update(snapshot, fetched()))
        val after = read(id)
        assertEquals(0L, after.lastAttempt)
        assertNull(after.lastBalance)
        assertEquals(listOf("BSC", "POLYGON", "ARBITRUM"), after.networks)
        // Die wartende Runde liest frisch und speichert
        WatchedAddressBalanceWriter.save(db, dao, update(after, fetched(mapOf(ChainAssets.key("ARBITRUM", "ETH") to "0.1"))))
        val saved = read(id)
        assertEquals(time, saved.lastAttempt)
        assertEquals("0.1", saved.tokenBalances[ChainAssets.key("ARBITRUM", "ETH")])
    }

    /** Neue Adresse (nie versucht), «Tokens mitzählen» während der ersten Runde: Erfolg und Fehler verworfen. */
    @Test
    fun tokensOnDuringFirstFetchIsDiscarded() = runBlocking {
        val id = insert(evm(0, null, countTokens = false))
        val snapshot = read(id)
        change(id) { WatchedAddressRules.withCountTokens(it, true) }
        WatchedAddressBalanceWriter.save(db, dao, update(snapshot, fetched()))
        WatchedAddressBalanceWriter.save(db, dao, update(snapshot, null, "timeout"))
        val after = read(id)
        assertEquals(0L, after.lastAttempt)
        assertNull(after.lastBalance)
        assertNull(after.lastError)
        assertTrue(after.countTokens)
    }

    /** Netz abgewählt während des Abrufs: gespeichert, das abgewählte Netz zählt nicht mehr. */
    @Test
    fun networkRemovedDuringFetchIsSavedFiltered() = runBlocking {
        val id = insert(evm(old, listOf("BSC", "POLYGON")))
        val snapshot = read(id)
        change(id) { WatchedAddressRules.withNetworks(it, listOf("BSC")) }
        val tokens = mapOf(ChainAssets.key("BSC", "BNB") to "2", ChainAssets.key("POLYGON", "POL") to "3")
        WatchedAddressBalanceWriter.save(db, dao, update(snapshot, fetched(tokens)))
        val saved = read(id)
        assertEquals(time, saved.lastAttempt)
        assertEquals(setOf(ChainAssets.key("BSC", "BNB")), saved.tokenBalances.keys)
    }
}
