package com.cryptochecker.app.data

import com.cryptochecker.marketdata.config.ExchangeRetirement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ✕ der Hinweise zu stillgelegten Börsen ([RetiredNoticeStore]): Ankündigung und Hinweis nach dem Ende getrennt
 * gespeichert, über einen Neustart (neuer Store, gleiche Einstellungsdatei) erhalten.
 */
class RetiredNoticeStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun dismissalsAreSeparateAndSurviveARestart() = runBlocking {
        val context = FolderContext(folder.root)
        val store = RetiredNoticeStore(context, Dispatchers.Unconfined)
        assertNull(store.dismissed.value)
        store.load()
        assertEquals(emptySet<String>(), store.dismissed.value)

        store.dismissAdvance(" Bitpanda ")
        assertEquals(setOf("bitpanda#advance"), store.dismissed.value)
        // Ankündigung geschlossen: der Hinweis nach dem Ende erscheint trotzdem
        assertEquals(
            listOf("Bitpanda"),
            ExchangeRetirement.noticeExchanges(listOf("Bitpanda"), store.dismissed.value!!, ExchangeRetirement.BITPANDA_END_MILLIS),
        )
        store.dismiss("Bitpanda")
        store.dismiss(" ")
        assertEquals(setOf("bitpanda#advance", "bitpanda"), store.dismissed.value)

        val restarted = RetiredNoticeStore(context, Dispatchers.Unconfined)
        restarted.load()
        assertEquals(setOf("bitpanda#advance", "bitpanda"), restarted.dismissed.value)
    }
}
