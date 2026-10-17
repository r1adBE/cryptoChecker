@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.sectionTitleMarker
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Gruppe der Hauptseite als Karte wie die Karten im Markt-Tab «Jetzt» (Fläche surfaceContainer,
 * grosse Rundung): oben in der Karte die Überschrift mit Strich in der Themenfarbe (für den
 * Screenreader eine Überschrift), darunter die Zeilen — über die ganze Kartenbreite tippbar,
 * mit eigenem Innenabstand ([SettingsNavRow], [SettingsDangerRow]). Zwischen den Karten 12 dp
 * wie im Markt-Tab, keine Trennlinie; die erste Karte ([first]) sitzt dichter unter dem Suchfeld.
 */
@Composable
internal fun SettingsSection(
    title: String,
    first: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(
        modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = if (first) Spacing.xs else Spacing.md),
        padding = CardPadding.None,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            SettingsCardTitle(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
            content()
        }
    }
}

/** Überschrift in einer Karte der Einstellungen: klein, fett, Strich in der Themenfarbe; Überschrift für den Screenreader. */
@Composable
private fun SettingsCardTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = SectionTitle.style,
        color = SectionTitle.color,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() }
            .sectionTitleMarker()
    )
}

/**
 * Symbol links in einer Zeile der Hauptseite: schlichte Linie, 22 dp, 14 dp Abstand zum Titel.
 * Rein schmückend (der Titel sagt alles), daher ohne Beschreibung für den Screenreader.
 */
@Composable
private fun SettingsLeadingIcon(@DrawableRes icon: Int, tint: Color) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.padding(end = 14.dp).size(22.dp)
    )
}

/**
 * Zeile mit unwiderruflicher Aktion («App zurücksetzen»): rot, Papierkorb rechts.
 * [icon]: Symbol links, ebenfalls rot (null = ohne).
 */
@Composable
internal fun SettingsDangerRow(title: String, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) SettingsLeadingIcon(icon, MaterialTheme.colorScheme.error)
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).padding(end = 16.dp)
        )
        Icon(
            painterResource(R.drawable.ic_delete),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error
        )
    }
}

/**
 * Grauer Wert unter dem Titel einer Zeile: klein (bodySmall), höchstens zwei Zeilen mit «…».
 * Wie der Wert in `SettingsNavRow` (iOS, .footnote).
 */
@Composable
internal fun SettingsValueText(value: String, modifier: Modifier = Modifier) {
    Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * Zeile der Hauptseite: Titel oben, darunter klein und grau der aktuelle Wert, rechts der Pfeil —
 * so bleibt langen Beschriftungen die ganze Breite. Mindestens 56 dp hoch, über die ganze Breite
 * tippbar. Für den Screenreader eine Schaltfläche «Titel, Wert» (z. B. «Theme, Orange, Schaltfläche»).
 *
 * [valueContent] ersetzt den Text-Wert (Farbpunkt, farbige Pfeile) in der zweiten Zeile; dann
 * beschreibt [valueDescription] den Wert.
 *
 * [icon]: Symbol links einfarbig in der Textfarbe (weiss im dunklen, schwarz im hellen Design);
 * null = ohne Symbol.
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    valueDescription: String? = value,
    valueContent: (@Composable () -> Unit)? = null,
    /** Öffnet eine Website (Browser): Pfeil nach oben rechts statt «›», vorgelesen mit «Link». */
    external: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val linkLabel = stringResource(R.string.a11y_external_link)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(
                    title,
                    valueDescription?.takeIf { it.isNotEmpty() },
                    linkLabel.takeIf { external },
                ).joinToString(", ")
            }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) SettingsLeadingIcon(icon, MaterialTheme.colorScheme.onSurface)
        // Titel oben, Wert darunter (klein, grau) — beide nutzen die ganze Breite bis zum Pfeil
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (valueContent != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    valueContent()
                }
            } else if (!value.isNullOrEmpty()) {
                SettingsValueText(value, Modifier.padding(top = 2.dp))
            }
        }
        Icon(
            painterResource(if (external) R.drawable.ic_open_external else R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).then(if (external) Modifier.size(20.dp) else Modifier)
        )
    }
}

/**
 * Zeile der Hauptseite mit Schalter (z. B. «Portfolio» in der Gruppe «Mehr»): gleiche Masse und
 * gleiches Symbol links wie [SettingsNavRow], darunter klein und grau der Hinweis, rechts der Schalter.
 * Die ganze Zeile schaltet (Screenreader: «Titel, Hinweis, Schalter, ein/aus»). Wie `SettingsMainSwitchRow` (iOS).
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (icon != null) SettingsLeadingIcon(icon, MaterialTheme.colorScheme.onSurface)
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                SettingsValueText(subtitle, Modifier.padding(top = 2.dp))
            }
        }
        // Klick läuft über die Zeile (toggleable), sonst würde doppelt geschaltet
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Karte innerhalb einer Unterseite — gleiche Fläche wie `SectionCard`. Mit [title] steht die
 * Überschrift (Strich in der Themenfarbe) oben in der Karte, wie bei [SettingsSection] und den
 * Karten im Markt-Tab «Jetzt».
 */
@Composable
internal fun GroupCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    AppCard(padding = CardPadding.List) {
        if (title != null) SettingsCardTitle(title, Modifier.padding(top = 8.dp, bottom = 4.dp))
        content()
    }
}

@Composable
internal fun Hint(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = Spacing.sm)
    )
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Punkt als Status: «in Ordnung» bzw. Bernstein für «Achtung». */
@Composable
internal fun StatusDot(ok: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (ok) AppColors.positive else AppColors.warning)
    )
}

/** Auswahl aus wenigen Werten als Segmentleiste. */
@Composable
internal fun ChoiceRow(
    label: String,
    options: List<Int>,
    selected: Int,
    optionLabel: @Composable (Int) -> String,
    onSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {}
                ) {
                    Text(optionLabel(option), maxLines = 1)
                }
            }
        }
    }
}
