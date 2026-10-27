package com.cryptochecker.app.domain.alarm

/**
 * Werte eines Paars; null-Felder = diesmal keine Daten. [fundingPercent] pro 8 Stunden
 * ([DerivativesAlarm.fundingPer8h]). [referenceSource] = die Werte stammen nicht
 * von der beobachteten Börse, sondern als Richtwert von dieser Quelle (z. B. «Binance Futures»).
 */
data class DerivativesValues(
    val fundingPercent: Double?,
    val oiUnits: Double?,
    val fetchedAt: Long,
    val referenceSource: String? = null,
)
