package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Grösse einer Änderungsangabe: [SMALL] für Listenzeilen (12 sp), [MEDIUM] für Karten,
 * Kopfzeilen und Blätter (14 sp). Wie `ChangePillSize` (iOS). Der Name «Pill» ist geblieben,
 * gezeichnet wird seit dem Verzicht auf Pillen nur noch farbige Schrift ohne Fläche.
 */
enum class ChangePillSize { SMALL, MEDIUM }

/**
 * Bedeutung einer Änderungsangabe und damit ihre Schriftfarbe — einzige Stelle, die das festlegt:
 * [UP]/[DOWN] in der Kursfarbe (Schema, hoher Kontrast, Tausch); [NEUTRAL] grau (0, unbekannt,
 * deaktiviert); [AVERAGE] in der normalen Textfarbe für neutrale Kennzahlen wie den
 * Durchschnitt «Ø +0.27%» — nicht Bernstein, das bleibt «Achtung» vorbehalten. Wie `PillTone` (iOS).
 */
enum class PillTone {
    UP, DOWN, NEUTRAL, AVERAGE;

    companion object {
        /** Prozent-Änderung: Richtung erst ab sichtbarer Änderung (≥ 0.005 %, wie [PriceFormat.changePercent]). */
        fun forPercent(change: Double?): PillTone = when {
            change == null || !change.isFinite() || PriceFormat.changePercent(change) == null -> NEUTRAL
            change > 0 -> UP
            else -> DOWN
        }

        /** Betrag oder Prozent mit eigener Null-Regel ([isZero], z. B. halber Cent im Portfolio). */
        fun forValue(value: Double?, isZero: (Double) -> Boolean): PillTone = when {
            value == null || !value.isFinite() || isZero(value) -> NEUTRAL
            value > 0 -> UP
            else -> DOWN
        }
    }
}

/**
 * Schrift- und Pfeilfarbe zur [tone], direkt auf der Fläche (Karte, Zeile, Blatt) — ohne Pille:
 * Kursfarbe steigend/fallend (≥ 4.5:1 bzw. 7:1 auf allen Flächen, siehe PriceColorSchemeTest),
 * grau für 0/unbekannt/deaktiviert, normale Textfarbe für den Durchschnitt (eine Kennzahl ohne
 * Richtung; grau hiesse «0/deaktiviert», Bernstein hiesse «Achtung»). Wie `PillTone.content` (iOS).
 */
@Composable
@ReadOnlyComposable
fun pillContent(tone: PillTone): Color = when (tone) {
    PillTone.UP -> PriceColors.up
    PillTone.DOWN -> PriceColors.down
    PillTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    PillTone.AVERAGE -> MaterialTheme.colorScheme.onSurface
}

/** Schrift der Änderungsangabe: Betragsschrift mit gleich breiten Ziffern (`tnum`), halbfett. */
@Composable
@ReadOnlyComposable
fun pillTextStyle(size: ChangePillSize): TextStyle = when (size) {
    ChangePillSize.SMALL -> MaterialTheme.typography.labelMedium.amountNumbers()
    ChangePillSize.MEDIUM -> MaterialTheme.typography.titleSmall.amountNumbers()
}

/**
 * Beliebiger Text als farbige Schrift in der Farbe von [tone] (keine Fläche, kein Innenabstand) —
 * für Beträge («+12.40 USDT»), Zähler («↗ 404 steigen») und den Durchschnitt.
 * [arrow] gesetzt (Zeichen ↗/↘, siehe [pillArrow]): eigener Pfeil vor [text], ausserhalb der
 * rollenden Ziffern. [rollingValue] gesetzt: Ziffern rollen bei Änderungen ([RollingNumberText]).
 * [contentDescription] ersetzt den Text für den Screenreader (z. B. «gestiegen um 2.35%»).
 */
@Composable
fun TonePill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    size: ChangePillSize = ChangePillSize.SMALL,
    contentDescription: String? = null,
    rollingValue: Double? = null,
    arrow: String? = null,
) {
    val content = pillContent(tone)
    val style = pillTextStyle(size)
    if (arrow == null) {
        PillLabel(text, rollingValue, style, content, contentDescription, modifier)
        return
    }
    // Pfeil und Zahl als eine Angabe; der Screenreader liest einen Text (ohne Pfeilzeichen)
    val spoken = contentDescription ?: text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clearAndSetSemantics { this.contentDescription = spoken }
    ) {
        Text(
            text = arrow,
            style = style,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(end = 3.dp)
        )
        PillLabel(text, rollingValue, style, content, contentDescription, Modifier)
    }
}

/** Text der Angabe — mit [rollingValue] rollende Ziffern, sonst einfacher Text. */
@Composable
private fun PillLabel(
    text: String,
    rollingValue: Double?,
    style: TextStyle,
    color: Color,
    spoken: String?,
    modifier: Modifier,
) {
    if (rollingValue != null) {
        RollingNumberText(
            text = text,
            value = rollingValue,
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = color,
            contentDescription = spoken,
            modifier = modifier
        )
    } else {
        Text(
            text = text,
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (spoken != null) {
                Modifier.clearAndSetSemantics { this.contentDescription = spoken }.then(modifier)
            } else {
                modifier
            }
        )
    }
}

/** Schräger Pfeil einer Prozent-Änderung: «↗» steigend, «↘» fallend, sonst keiner. Wie `PillTone.arrowSymbol` (iOS). */
fun pillArrow(tone: PillTone): String? = when (tone) {
    PillTone.UP -> PriceFormat.ARROW_UP
    PillTone.DOWN -> PriceFormat.ARROW_DOWN
    PillTone.NEUTRAL, PillTone.AVERAGE -> null
}

/**
 * Prozent-Änderung als farbige Schrift ohne Pille, wie in den Widgets: steigend «↗ +2.35%» in
 * der Steigend-Farbe, fallend «↘ −1.20%» (echtes Minus U+2212) in der Fallend-Farbe — Pfeil und
 * Vorzeichen, die Bedeutung hängt so nie allein an der Farbe. Praktisch keine Änderung: grau
 * «0.00%» ohne Pfeil. Ohne Wert: mit [dashWhenMissing] ein graues «—», sonst nichts.
 * Screenreader: «gestiegen um 2.35%» statt «+2.35%» (umgebende Zeilen dürfen ersetzen).
 * [neutral]: grau ohne Kursfarbe (deaktivierte Paare zeigen den letzten Wert nur noch) — der
 * Pfeil bleibt, sonst ginge die Richtung verloren.
 */
@Composable
fun ChangePill(
    change: Double?,
    modifier: Modifier = Modifier,
    dashWhenMissing: Boolean = false,
    neutral: Boolean = false,
    size: ChangePillSize = ChangePillSize.SMALL,
) {
    val value = change?.takeIf { it.isFinite() }
    if (value == null && !dashWhenMissing) return
    val text = when (value) {
        null -> "—"
        else -> PriceFormat.changePercent(value) ?: PriceFormat.zeroPercent()
    }
    val direction = PillTone.forPercent(value)
    val tone = if (neutral) PillTone.NEUTRAL else direction
    val spoken = value?.let { A11yText.change(LocalContext.current, it) }
    TonePill(
        text = text,
        tone = tone,
        modifier = modifier,
        size = size,
        contentDescription = spoken,
        arrow = pillArrow(direction),
    )
}

/**
 * Kurs mit Gegenwährung, überall gleich: die Zahl in [priceStyle] (gleich breite Ziffern setzt
 * der Aufrufer), dahinter die Währung («USDT») klein und grau wie der Börsenname der Merkliste
 * (bodySmall, onSurfaceVariant). Immer «Kurs Währung» von links nach rechts (wie zuvor als ein
 * Text), auch bei Schrift von rechts nach links. Ohne Kurs («—») keine Währung. Wie
 * `PriceWithQuote` (iOS).
 */
@Composable
fun PriceWithQuote(
    price: String,
    quote: String?,
    priceStyle: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = FontWeight.SemiBold,
    priceColor: Color = Color.Unspecified,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier = modifier) {
            Text(
                text = price,
                style = priceStyle,
                fontWeight = fontWeight,
                color = priceColor,
                maxLines = 1,
                modifier = Modifier.alignByBaseline()
            )
            if (quote != null && price != "—") {
                Text(
                    text = quote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline().padding(start = 4.dp)
                )
            }
        }
    }
}

/** Währung am Ende von «Zahl Währung» (z. B. «USDT», «CHF»), auch vor Bidi-Steuerzeichen. */
private val TrailingQuote = Regex("""\s([A-Z][A-Z0-9]{1,9})(\p{Cf}*)$""")

/**
 * Wie [PriceWithQuote], aber als ein Text für «Zahl Währung» ([text], z. B. «1’234.00 USDT»):
 * die Währung am Ende klein und grau wie dort (bodySmall, onSurfaceVariant), die Zahl bleibt
 * im Stil des Texts. Als ein Text bricht der Betrag bei grosser Schrift um, statt
 * die Zeile zu sprengen. Ohne Währung («—», «•••») unverändert. Wie `PriceWithQuote.text` (iOS).
 */
@Composable
@ReadOnlyComposable
fun withSmallQuote(text: String): AnnotatedString {
    val match = TrailingQuote.find(text) ?: return AnnotatedString(text)
    val quote = match.groups[1] ?: return AnnotatedString(text)
    val quoteStyle = SpanStyle(
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return buildAnnotatedString {
        append(text.substring(0, quote.range.first))
        withStyle(quoteStyle) { append(quote.value) }
        append(text.substring(quote.range.last + 1))
    }
}
