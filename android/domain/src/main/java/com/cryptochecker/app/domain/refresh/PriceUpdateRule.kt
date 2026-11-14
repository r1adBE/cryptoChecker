package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.domain.refresh.RefreshEffects.PriceUpdate
import kotlin.math.abs

/**
 * Was mit der Kurs-Mitteilung eines Paars nach einem neuen Kurs geschieht ([RefreshEffects.priceUpdate]):
 *  - [PriceUpdate.CANCEL]: Kurs-Mitteilungen aus (allgemein oder für das Paar) oder kein Kurs.
 *  - [PriceUpdate.SHOW]: ohne Melde-Schwelle, ohne Bezug oder bewegt um mindestens die Schwelle (neuer Bezug).
 *  - [PriceUpdate.RESTORE]: unter der Schwelle, aber «Nicht wegwischbar» ([ongoing]) ist an und die Mitteilung fehlt
 *    ([shown] false: Neustart, App-Update, «Stopp erzwingen», ab Android 14 Wegwischen) — wieder zeigen, ohne Ton und
 *    mit dem alten Bezug, damit «angeheftet» stimmt und die Schwelle weiter vom letzten gemeldeten Kurs zählt.
 *  - [PriceUpdate.NONE]: sonst (bleibt, wie sie ist).
 * Reine Logik (Test `PriceUpdateRuleTest`); iOS hat kein Gegenstück (Mitteilungen überdauern dort den Neustart, und
 * angeheftete gibt es nicht).
 */
object PriceUpdateRule {
    fun decide(
        notificationsOn: Boolean,
        pairOn: Boolean,
        price: Double?,
        reference: Double?,
        threshold: Double,
        ongoing: Boolean,
        shown: Boolean,
    ): PriceUpdate {
        if (!notificationsOn || !pairOn) return PriceUpdate.CANCEL
        if (price == null || price <= 0.0) return PriceUpdate.CANCEL
        if (threshold > 0 && reference != null && reference > 0.0) {
            val change = abs((price - reference) / reference * 100.0)
            if (change < threshold) return if (ongoing && !shown) PriceUpdate.RESTORE else PriceUpdate.NONE
        }
        return PriceUpdate.SHOW
    }
}
