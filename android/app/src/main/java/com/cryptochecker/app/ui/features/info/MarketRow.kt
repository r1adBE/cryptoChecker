package com.cryptochecker.app.ui.features.info

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.title

/** Art einer [MarketRow]: Platzhalter, Inhalt, Fehler — nur ein Wechsel der Art wird überblendet. */
internal const val ROW_LOADING = 0

internal const val ROW_SHOWN = 1

internal const val ROW_FAILED = 2

/**
 * Ein Eintrag in «Einordnung» und «Daten» des Markt-Tabs — als eigene Karte wie im Register
 * «Jetzt» (Fläche surfaceContainer, grosse Rundung, 16 dp Innenabstand, 12 dp Abstand zur
 * Karte darüber; die erste Karte eines Registers ([first]) ohne Abstand nach oben).
 * Oben in der Karte der Titel als Abschnittsüberschrift mit Strich in der Themenfarbe, ganz
 * rechts ein Pfeil nach unten (zu) bzw. oben (offen), wenn sich die Karte aufklappen lässt.
 * Darunter links eine Nebenzeile (Label, Nebenfarbe), rechts der Wert (Title, halbfett,
 * gleich breite Ziffern), dahinter optional die Veränderung in Prozent ([change]) als
 * gefüllte [ChangePill] «+1.20%» (mittel). [below] steht in voller Breite direkt darunter (Skala von
 * Fear & Greed).
 *
 * Tippen klappt [details] in derselben Karte auf und zu ([expanded], [onToggle]). Beim Laden
 * ([loading]) stehen der echte Titel und graue Balken in genau der Höhe von Nebenzeile und
 * Wert (kein Springen); bei [failure] die Meldung und «Erneut». Wie `MarketRow` (iOS).
 *
 * Mit [stamp] endet die Nebenzeile mit Anbieter und Alter der Daten («… · CoinGecko ·
 * vor 3 Min.», siehe [DataFreshness]); ist der Wert älter als drei Gültigkeitsdauern, steht
 * das Alter in Warnfarbe und der Screenreader sagt «veraltet». Die Nebenzeile darf dann
 * zwei Zeilen brauchen (grosse Schrift).
 *
 * Screenreader: der Kopf der Karte ist ein Element und eine Überschrift (Titel, Nebenzeile,
 * Wert, Veränderung, Skala) mit Zustand auf-/zugeklappt und der Aktion «… einblenden» bzw.
 * «… ausblenden»; [spoken] ersetzt Titel bis Veränderung durch einen Satz.
 */
@Composable
internal fun MarketRow(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String = "",
    value: String? = null,
    first: Boolean = false,
    loading: Boolean = false,
    failure: String? = null,
    onRetry: () -> Unit = {},
    change: Double? = null,
    secondaryMaxLines: Int = 1,
    spoken: String? = null,
    stamp: DataStamp? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    /** Thema des Info-Blatts: «ⓘ» direkt hinter dem Titel; null = ohne. */
    info: MarketInfoTopic? = null,
    /**
     * Wert als farbige Schrift ohne Fläche in dieser Farbe (z. B. Fear & Greed in der Stufenfarbe
     * der Skala, wie im Puls-Widget); null = Wert in der normalen Textfarbe.
     */
    valueColor: Color? = null,
    below: (@Composable () -> Unit)? = null,
    details: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val kind = when {
        loading -> ROW_LOADING
        failure != null -> ROW_FAILED
        else -> ROW_SHOWN
    }
    val expandable = onToggle != null && details != null
    val reduceMotion = rememberReduceMotion()
    val meta = stamp?.let { rememberStampText(it) }
    // Gesprochener Satz samt Herkunft und Alter («…, CoinGecko, vor 3 Min., veraltet»)
    val spokenFull = if (spoken != null && meta != null) "$spoken, ${meta.spoken}" else spoken
    // Der Satz enthält den Titel schon: dann den sichtbaren Titel nicht zusätzlich vorlesen
    val titleSpoken = !(kind == ROW_SHOWN && spokenFull != null)
    AppCard(modifier = modifier.padding(top = if (first) 0.dp else Spacing.md), padding = CardPadding.None) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .marketRowHead(title, expandable, expanded, onToggle)
                .padding(Spacing.lg)
        ) {
            MarketRowTitle(title, titleSpoken, expandable, expanded, reduceMotion, info)
            CardSwap(kind, { it }) { shownKind ->
                MarketRowLine(
                    kind = shownKind,
                    secondary = secondary,
                    secondaryMaxLines = secondaryMaxLines,
                    value = value,
                    change = change,
                    valueColor = valueColor,
                    failure = failure.orEmpty(),
                    onRetry = onRetry,
                    spoken = spokenFull,
                    meta = meta,
                )
            }
            if (below != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) { below() }
            }
        }
        if (details != null) {
            // Auf-/Zuklappen so lang wie das Überblenden einer Zeile; ohne Animation bei reduzierter Bewegung
            AnimatedVisibility(
                visible = expandable && expanded,
                enter = if (reduceMotion) EnterTransition.None
                else expandVertically(tween(MarketReveal.SWAP_MILLIS)) + fadeIn(tween(MarketReveal.SWAP_MILLIS)),
                exit = if (reduceMotion) ExitTransition.None
                else shrinkVertically(tween(MarketReveal.SWAP_MILLIS)) + fadeOut(tween(MarketReveal.SWAP_MILLIS)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                    content = details
                )
            }
        }
    }
}

/**
 * Kopf einer Karte für den Screenreader: ein Element und eine Überschrift; aufklappbar
 * zusätzlich antippbar mit Zustand («eingeklappt»/«ausgeklappt») und Aktion «… einblenden»
 * bzw. «… ausblenden». «Erneut» bleibt ein eigenes Element (eigener Knopf).
 */
@Composable
private fun Modifier.marketRowHead(
    title: String,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
): Modifier {
    val stateText = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    val actionLabel = stringResource(
        if (expanded) R.string.market_section_collapse else R.string.market_section_expand,
        title
    )
    return if (expandable && onToggle != null) {
        this
            .clickable(onClickLabel = actionLabel, role = Role.Button) { onToggle() }
            .semantics {
                heading()
                stateDescription = stateText
            }
    } else {
        this.semantics(mergeDescendants = true) { heading() }
    }
}

/**
 * Titelzeile in der Karte: Überschrift mit Strich in der Themenfarbe, direkt dahinter optional
 * das Info-Zeichen ([MarketInfoButton]), ganz rechts der Pfeil
 * zum Auf-/Zuklappen (nur wenn aufklappbar). Der Pfeil zeigt nach unten (zu) bzw. oben
 * (offen) und dreht so lange wie das Auf-/Zuklappen — wie bei «Details anzeigen»
 * ([com.cryptochecker.app.ui.components.ExpandToggleButton]); bei reduzierter Bewegung sofort.
 */
@Composable
private fun MarketRowTitle(
    title: String,
    spoken: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    reduceMotion: Boolean,
    info: MarketInfoTopic?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Titel und «ⓘ» direkt dahinter; der Pfeil bleibt ganz rechts
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = SectionTitle.style,
                color = SectionTitle.color,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (spoken) Modifier else Modifier.clearAndSetSemantics { })
                    .sectionTitleMarker()
            )
            // Eigener Knopf (eigenes Element für den Screenreader); sein Tipp klappt nicht auf
            if (info != null) MarketInfoButton(info, Modifier.padding(start = 2.dp))
        }
        if (expandable) {
            ExpandChevron(expanded, reduceMotion, Modifier.padding(start = Spacing.sm))
        }
    }
}

/** Pfeil nach unten (zu) bzw. oben (offen), in der Themenfarbe; dekorativ (Zustand steht am Kopf). */
@Composable
private fun ExpandChevron(expanded: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    // Rechts-nach-links ist der Pfeil gespiegelt (zeigt nach links): Drehung umkehren
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val rotation by animateFloatAsState(
        targetValue = (if (expanded) -90f else 90f) * turn,
        animationSpec = tween(if (reduceMotion) 0 else MarketReveal.SWAP_MILLIS),
        label = "row_chevron"
    )
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(20.dp)
            .rotate(rotation)
    )
}
