package com.cryptochecker.app.domain.portfolio

// Texte der Jahresübersicht ([TaxExport.yearSummary]): Platzhalter, Vermerk-Text, Bezeichnungen im Vermögenswert und
// im Orientierungs-Block.

/**
 * Platzhalter für das Datum in einem Vermerk ([TaxSummaryNote.WINDOW_OPEN]): die Texte setzen ihn ein (Android
 * `stringResource(…, TAX_NOTE_DATE)`, iOS `L(…, TaxExport.noteDateToken)`), [noteText] ersetzt ihn durch das Datum.
 */
const val TAX_NOTE_DATE = "{date}"

/** Text des Vermerks [TaxSummaryLine.note] aus [texts] samt Datum ([TaxSummaryLine.noteDate], ISO); null = keiner. */
fun TaxSummaryLine.noteText(texts: TaxSummaryTexts): String? {
    val text = note?.let { texts.notes[it] }?.takeIf { it.isNotEmpty() } ?: return null
    return noteDate?.let { text.replace(TAX_NOTE_DATE, it.toString()) } ?: text
}

/** Texte der Jahresübersicht ([TaxExport.summaryCsv]); [labels] je Posten. */
data class TaxSummaryTexts(
    val title: String,
    val disclaimer: String,
    val item: String,
    val usdt: String,
    /** Spalte der Steuerwährung, z. B. «GBP». */
    val target: String,
    val note: String,
    /** «Orientierung». */
    val orientation: String,
    val labels: Map<TaxLine, String>,
    val below: String,
    val reached: String,
    val unknown: String,
    val incomplete: String,
    val noFx: String,
    /** Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = [orientation]. */
    val orientationHeading: String = "",
    /** Posten im Orientierungs-Block «Orientierung: %s»; leer = «[orientation]: Posten». */
    val orientationItem: String = "",
    /** Vermerk «nicht gerechnet – ESTV-Kursliste <Link>» ([TaxSummaryLine.notCalculated]); leer = keiner. */
    val notCalculated: String = "",
    /** Status «Verlust» ([TaxLimitStatus.LOSS]); leer = [below]. */
    val loss: String = "",
    /** Texte der Zusatz-Vermerke ([TaxSummaryLine.note]); fehlt einer = kein Vermerk. */
    val notes: Map<TaxSummaryNote, String> = emptyMap(),
    /**
     * CH Vermögenswert, Zeile je Coin: Text mit den Platzhaltern [TAX_WEALTH_COIN] und [TAX_WEALTH_AMOUNT] (z. B. «{coin} –
     * Bestand {amount}», [wealthCoinLabel]); leer = «Coin Menge».
     */
    val wealthCoin: String = "",
    /** «nicht gerechnet» bei einem Coin ohne Wert ([TaxWealthRow.missing]). */
    val wealthMissing: String = "",
    /** Grund «kein Kurs» ([TaxWealthRow.noPrice]). */
    val wealthNoPrice: String = "",
    /** Grund «ungeprüft» ([TaxWealthRow.unchecked]). */
    val wealthUnchecked: String = "",
    /** Grund «Adresse nicht beobachtet» ([TaxWealthRow.addressUnobserved]). */
    val wealthAddressUnobserved: String = "",
    /** Zeile «Dollarkurs von USDT fehlt – gerechnet als 1 US-Dollar» ([TaxYearSummary.usdtApproximate]); leer = keine. */
    val usdtAssumed: String = "",
)

/** Platzhalter für Coin und Menge in [TaxSummaryTexts.wealthCoin] (wie [TAX_NOTE_DATE]). */
const val TAX_WEALTH_COIN = "{coin}"
const val TAX_WEALTH_AMOUNT = "{amount}"

/** Bezeichnung der Zeile eines Coins im Vermögenswert; [amount] schon als Text (Dezimalzeichen der Ausgabe). */
fun TaxSummaryTexts.wealthCoinLabel(coin: String, amount: String): String =
    if (wealthCoin.isEmpty()) "$coin $amount"
    else wealthCoin.replace(TAX_WEALTH_COIN, coin).replace(TAX_WEALTH_AMOUNT, amount)

/**
 * Hinweis eines Coins im Vermögenswert: «nicht gerechnet – kein Kurs» usw.; ohne Devisenkurs [TaxSummaryTexts.noFx]; leer =
 * gerechnet.
 */
fun TaxSummaryTexts.wealthCoinNote(r: TaxWealthRow): String {
    val reasons = listOfNotNull(
        wealthNoPrice.takeIf { r.noPrice && it.isNotEmpty() },
        wealthUnchecked.takeIf { r.unchecked && it.isNotEmpty() },
        wealthAddressUnobserved.takeIf { r.addressUnobserved && it.isNotEmpty() },
    )
    val missing = if (r.missing) (listOfNotNull(wealthMissing.takeIf { it.isNotEmpty() }) + reasons).joinToString(" – ") else ""
    return listOf(missing, noFx.takeIf { r.noFx && !r.missing }.orEmpty()).filter { it.isNotEmpty() }.joinToString(", ")
}

/**
 * Posten im Orientierungs-Block: [item] mit «%s» für [label]; ohne [item] «[fallback]: [label]» (leer = nur [label]).
 * Jeder Schluss nach den Regeln einer Vorlage steht so sichtbar als Orientierung da (CSV und Karte).
 */
fun taxOrientationLabel(item: String, fallback: String, label: String): String = when {
    item.contains("%s") -> item.replace("%s", label)
    fallback.isNotEmpty() -> "$fallback: $label"
    else -> label
}
