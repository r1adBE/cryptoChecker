// Auf-/zuklappbarer Abschnittskopf im Markt-Tab (Titel, zugeklappt mit Zusammenfassung).
// Eigene Datei, weil der Kopf über mehreren Karten steht und mit der einzelnen Zeile nichts teilt
// (wie `MarketSectionHeader.swift`).
package com.cryptochecker.app.ui.features.info

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.components.sectionTitleMarker
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title

/**
 * Überschrift eines zuklappbaren Abschnitts im Markt-Tab («Einordnung», «Daten»): links der
 * Titel, zugeklappt rechts eine kurze Zusammenfassung ([summary], z. B. «Gier 72 · Neutral»),
 * dahinter ein Pfeil. Tippen klappt auf und zu ([onToggle]).
 *
 * Screenreader: eine Überschrift mit Zustand auf-/zugeklappt und der Aktion «… einblenden»
 * bzw. «… ausblenden»; zugeklappt wird die Zusammenfassung mitgelesen. Wie
 * `MarketSectionHeader` (iOS).
 */
@Composable
internal fun MarketSectionHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateText = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    val actionLabel = stringResource(
        if (expanded) R.string.market_section_collapse else R.string.market_section_expand,
        title
    )
    val reduceMotion = rememberReduceMotion()
    // Pfeil nach unten (zu) bzw. oben (offen); dreht so lange wie das Auf-/Zuklappen.
    // Rechts-nach-links ist der Pfeil gespiegelt (zeigt nach links): Drehung umkehren.
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val rotation by animateFloatAsState(
        targetValue = (if (expanded) -90f else 90f) * turn,
        animationSpec = tween(if (reduceMotion) 0 else MarketReveal.SWAP_MILLIS),
        label = "section_chevron"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                stateDescription = stateText
            }
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = title,
            style = SectionTitle.style,
            color = SectionTitle.color,
            maxLines = 1,
            modifier = Modifier.sectionTitleMarker(),
        )
        Text(
            // Offen: keine Zusammenfassung (die Zeilen stehen darunter)
            text = if (expanded) "" else summary,
            style = MaterialTheme.typography.label.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.md)
        )
        // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie ExpandToggleButton)
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = Spacing.xs)
                .size(20.dp)
                .rotate(rotation)
        )
    }
}
