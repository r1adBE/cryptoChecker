package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioRepository.AddWatchedResult
import com.cryptochecker.app.domain.portfolio.AddressValidation
import com.cryptochecker.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Meldung zu «Adresse hinzufügen» (Audit A-01, früher Quelltext-Wächter): nicht geschrieben heisst nie «ungültig» — die
 * Prüfung lief davor grün —, sondern «gesperrt» bzw. «nicht gespeichert». Wie iOS `AddressSaveMessageTests`.
 */
class AddressSaveMessageTest {

    @Test
    fun notWrittenIsNeverInvalid() {
        assertEquals(R.string.portfolio_write_failed, addWatchedMessage(null, locked = false))
        assertEquals(R.string.portfolio_storage_locked, addWatchedMessage(null, locked = true))
    }

    @Test
    fun everyOtherResultNamesItsReason() {
        assertNull(addWatchedMessage(AddWatchedResult.Added(7), locked = false))
        assertEquals(R.string.address_checksum, addWatchedMessage(AddWatchedResult.Invalid(AddressValidation.Problem.BAD_CHECKSUM), locked = false))
        assertEquals(R.string.address_duplicate, addWatchedMessage(AddWatchedResult.Duplicate, locked = false))
        assertEquals(R.string.address_limit, addWatchedMessage(AddWatchedResult.LimitReached, locked = true))
    }
}
