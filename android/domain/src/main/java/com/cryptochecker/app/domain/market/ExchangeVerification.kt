package com.cryptochecker.app.domain.market

import java.time.LocalDate

/**
 * «Börsen-Anbindungen zuletzt gegen echte Antworten geprüft» (Über die App): Tag der letzten Aufzeichnung echter
 * Börsen-Antworten (`android/tools/record_exchange_fixtures.py` → `testdata/exchanges/recorded`), gegen die alle Leser
 * im Test laufen. Nach jeder neuen Aufzeichnung nachführen — `ExchangeVerificationTest` schlägt fehl, solange das Datum
 * vor der neuesten Aufnahme (`recordedAt`, UTC-Datum) liegt. Spiegel: `ExchangeVerification.swift`.
 */
object ExchangeVerification {

    /** UTC-Datum der neuesten aufgezeichneten Antwort. */
    val LAST_VERIFIED: LocalDate = LocalDate.of(2026, 10, 19)
}
