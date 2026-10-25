@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.heightIn
import com.cryptochecker.app.ui.navigation.cappedTabSize
import com.cryptochecker.app.ui.theme.ChipShape
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.AutoGroup
import com.cryptochecker.app.ui.components.AssetKindIcon
import com.cryptochecker.app.domain.watch.QuickView
import com.cryptochecker.app.domain.watch.WatchFilter
import com.cryptochecker.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * Gruppen-Auswahl oben: «Alle · ★ · ⚡ · Token · Gruppe 1 · Gruppe 2 … · +».
 * Tippen filtert, lange drücken öffnet «Gruppe bearbeiten», «+» legt eine an.
 * Reihenfolge: Alle · ★ · Token · TradFi · α · ⚡ · übrige Gruppen · «+». «Alle», «Token», die Gruppe
 * «TradFi» und «Alpha» nur als Symbol (Formen, Raute, Bank, «α»); Token und TradFi nur zusammen.
 * «Alpha» ([WatchFilter.ALPHA]) nur mit Alpha- und anderen Paaren ([WatchFilter.showsAlpha]).
 * Nur was es gibt: ★ (Favoriten) erst mit mindestens einem Favoriten, ⚡ (Paare, bei denen gerade
 * etwas passiert; [QuickView.ACTIVITY], ohne Zahl) nur mit solchen Paaren, «Token» (alles ausser
 * «TradFi», [WatchFilter.TOKENS]) nur mit TradFi- und anderen Paaren ([WatchFilter.showsTokens]), «+» erst mit einer
 * Gruppe (die erste legt man über das Aktionsblatt eines Paars an). Gibt es nichts davon, bleibt
 * die Zeile leer — ein einzelnes «Alle» wäre nur ein weiterer Knopf ([showsGroupChips]).
 * Ist eine vorübergehende Ansicht ([quickView]) an, ist keine Gruppe markiert (ausser ⚡ selbst).
 * Der gewählte Symbol-Chip zeigt neben dem Symbol seinen Namen («Alle», «Favoriten» …); tippt man einen Symbol-Chip
 * ab (⚡), zeigt er kurz den Namen darunter ([ChipHintPopup]) — immer nur einer, ein weiterer Tipp ersetzt ihn.
 * Geht die Zeile über den Rand hinaus, blendet sie am Ende weich aus ([fadeEnd]) — Hinweis, dass sie weiter scrollt.
 */
@Composable
internal fun GroupChips(
    groups: List<String>,
    selected: String?,
    hasFavorites: Boolean,
    onSelect: (String?) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    hasActivity: Boolean = false,
    quickView: QuickView? = null,
    onToggleActivity: () -> Unit = {},
    /** «Token»-Chip zeigen ([WatchFilter.showsTokens]). */
    hasTokens: Boolean = false,
    /** «Alpha»-Chip zeigen ([WatchFilter.showsAlpha]). */
    hasAlpha: Boolean = false,
) {
    val activitySelected = quickView == QuickView.ACTIVITY
    val groupSelected: (String?) -> Boolean = { it == selected && quickView == null }
    // Namenshinweis unter einem Symbol-Chip: jeder Tipp ein neues Objekt, damit der Zeitgeber
    // auch beim erneuten Tippen auf denselben Chip neu beginnt
    var hint by remember { mutableStateOf<ChipHint?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(CHIP_HINT_MILLIS)
            hint = null
        }
    }
    val showHint: (String) -> Unit = { hint = ChipHint(it) }
    val hintShown: (String) -> Boolean = { hint?.key == it }
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fadeEnd { rowState.canScrollForward }
    ) {
        if (!showsGroupChips(groups, selected, hasFavorites, hasActivity || activitySelected, hasAlpha)) return@LazyRow
        item(key = "all") {
            // Nur das Formen-Symbol (Screenreader: «Alle»)
            GroupChip(
                icon = R.drawable.ic_category,
                description = stringResource(R.string.group_all),
                selected = groupSelected(null),
                onClick = { onSelect(null); showHint("all") },
                showHint = hintShown("all")
            )
        }
        // Nur der Stern (Screenreader: «Favoriten»); auch ohne Favoriten sichtbar, solange die
        // Ansicht gewählt ist (sonst gäbe es keinen Weg zurück)
        if (hasFavorites || WatchFilter.isFavorites(selected)) {
            item(key = "favorites") {
                GroupChip(
                    icon = R.drawable.ic_star,
                    description = stringResource(R.string.group_favorites),
                    selected = groupSelected(WatchFilter.FAVORITES),
                    onClick = { onSelect(WatchFilter.FAVORITES); showHint("favorites") },
                    showHint = hintShown("favorites")
                )
            }
        }
        // Token (Raute) und TradFi (Bank) nur zusammen: beide nur, wenn es TradFi- und andere
        // Paare gibt ([WatchFilter.showsTokens]); TradFi bleibt sichtbar, solange es gewählt ist
        val showTradFi = AutoGroup.TRADFI in groups && (hasTokens || selected == AutoGroup.TRADFI)
        if (hasTokens || WatchFilter.isTokens(selected)) {
            item(key = "tokens") {
                // Keine Gruppe, also nicht bearbeitbar (kein langes Drücken)
                GroupChip(
                    kind = AssetKind.TOKEN,
                    description = stringResource(R.string.group_tokens),
                    selected = groupSelected(WatchFilter.TOKENS),
                    onClick = { onSelect(WatchFilter.TOKENS); showHint("tokens") },
                    showHint = hintShown("tokens")
                )
            }
        }
        if (showTradFi) {
            item(key = "group:${AutoGroup.TRADFI}") {
                GroupChip(
                    kind = AssetKind.TRADFI,
                    description = AutoGroup.TRADFI,
                    selected = groupSelected(AutoGroup.TRADFI),
                    onClick = { onSelect(AutoGroup.TRADFI); showHint("tradfi") },
                    onLongClick = { onEdit(AutoGroup.TRADFI) },
                    showHint = hintShown("tradfi")
                )
            }
        }
        // «α» (Alpha-Tokens der Binance-Alpha-Liste): keine Gruppe, nicht bearbeitbar; bleibt sichtbar,
        // solange gewählt
        if (hasAlpha || WatchFilter.isAlpha(selected)) {
            item(key = "alpha") {
                GroupChip(
                    kind = AssetKind.ALPHA,
                    description = stringResource(R.string.group_alpha),
                    selected = groupSelected(WatchFilter.ALPHA),
                    onClick = { onSelect(WatchFilter.ALPHA); showHint("alpha") },
                    showHint = hintShown("alpha")
                )
            }
        }
        // ⚡ wie in den Zeilen (neutral, gewählt in der Themenfarbe), nur wenn gerade etwas passiert;
        // Screenreader: «Ungewöhnliche Aktivität» (wie die Einstellung und das ⚡ in der Zeile)
        if (hasActivity || activitySelected) {
            item(key = "activity") {
                GroupChip(
                    icon = R.drawable.ic_bolt,
                    description = stringResource(R.string.activity_card_title),
                    selected = activitySelected,
                    onClick = { onToggleActivity(); showHint("activity") },
                    showHint = hintShown("activity")
                )
            }
        }
        // Übrige Gruppen (QTLY, eigene) als Text
        items(groups.filter { it != AutoGroup.TRADFI }, key = { "group:$it" }) { group ->
            GroupChip(
                text = group,
                selected = groupSelected(group),
                onClick = { onSelect(group) },
                onLongClick = { onEdit(group) }
            )
        }
        if (groups.isNotEmpty()) item(key = "add") {
            // Druck-Rückmeldung nur auf dem sichtbaren Feld, nicht auf der grösseren Tippfläche
            val addInteraction = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    // Tippfläche (antippbarer Knoten) 48 × 48 dp, darin mittig das kleine Feld
                    .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                    .clickable(interactionSource = addInteraction, indication = null, onClick = onAdd)
                    .wrapContentSize()
                    .size(32.dp)
                    .clip(ChipShape)
                    // Rand ist die einzige Kontur: outline (≥ 3:1, WCAG 1.4.11) statt outlineVariant (≈ 2:1)
                    .border(1.dp, MaterialTheme.colorScheme.outline, ChipShape)
                    .indication(addInteraction, LocalIndication.current)
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.group_add),
                    // Neutral wie die übrigen Chips
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Gruppen-Chips zeigen? Nur wenn es neben «Alle» etwas zu wählen gibt (oder eine Auswahl aktiv ist). */
internal fun showsGroupChips(
    groups: List<String>,
    selected: String?,
    hasFavorites: Boolean,
    hasActivity: Boolean = false,
    /** «Alpha»-Chip ([WatchFilter.showsAlpha]). */
    hasAlpha: Boolean = false,
): Boolean = groups.isNotEmpty() || hasFavorites || hasActivity || hasAlpha || selected != null

/**
 * Chip im Stil des Material-FilterChips, aber mit langem Drücken
 * (FilterChip kennt das nicht). Alle Chips neutral: Text und Symbol in onSurface, grauer Rand,
 * keine Füllung. Der gewählte bekommt Rand, Text und Symbol in der Themenfarbe (orange) —
 * ebenfalls ohne Füllung; ein gewählter Symbol-Chip zeigt dazu seinen Namen ([description]) als Text.
 * Screenreader: Tab mit Zustand «ausgewählt» (wie iOS `.isSelected`); der Name wird einmal gelesen.
 */
@Composable
private fun GroupChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String? = null,
    /** Nur ein Symbol statt [text] (Alle, ★ Favoriten, ⚡ Aktivität); dann [description] angeben. */
    icon: Int? = null,
    /** Art-Symbol statt [text] (Token, TradFi, Alpha; [AssetKindIcon]); dann [description] angeben. */
    kind: AssetKind? = null,
    onLongClick: (() -> Unit)? = null,
    /** Vorgelesen statt [text] bzw. für das Symbol. */
    description: String? = null,
    /** Nur Symbol-Chips: [description] kurz als Hinweis unter dem Chip (nur ungewählt; gewählt steht er daneben). */
    showHint: Boolean = false,
) {
    val shape = ChipShape
    val longClickLabel = stringResource(R.string.group_edit_title)
    val accent = MaterialTheme.colorScheme.primary
    val chipSelected = selected
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Tippfläche (antippbarer Knoten) mindestens 48 × 48 dp, darin mittig der sichtbare Chip mit
            // mindestens 32 dp Höhe — bei grosser Schrift wächst er mit, statt den Text abzuschneiden
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            // Screenreader: «Gruppe bearbeiten» als benannte Aktion des langen Drückens
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) longClickLabel else null,
            )
            .semantics { this.selected = chipSelected }
            .wrapContentSize()
            .heightIn(min = 32.dp)
            .clip(shape)
            .border(
                if (selected) 1.5.dp else 1.dp,
                // Ungewählt: outline (≥ 3:1 auf surfaceContainer, WCAG 1.4.11) — der Rand ist die einzige Kontur
                if (selected) accent else MaterialTheme.colorScheme.outline,
                shape
            )
            // Druck-Rückmeldung nur auf dem sichtbaren Chip, nicht auf der grösseren Tippfläche
            .indication(interaction, LocalIndication.current)
            .padding(horizontal = if (icon != null || kind != null) 12.dp else 16.dp)
    ) {
        val contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurface
        val a11y = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        // Schrift wächst bis 1,5-fach mit (wie die Tab-Leiste, [cappedTabSize]) — die Chip-Zeile bleibt eine Zeile
        val label = MaterialTheme.typography.labelLarge
        val labelStyle = label.copy(
            fontSize = cappedTabSize(label.fontSize),
            lineHeight = cappedTabSize(label.lineHeight),
        )
        if (icon != null || kind != null) {
            // Gewählt: Symbol und Name («Alle»); der Name steht schon am Symbol für den Screenreader
            val named = selected && description != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp).then(a11y)
                    )
                } else if (kind != null) {
                    AssetKindIcon(kind = kind, tint = contentColor, size = 18.dp, modifier = a11y)
                }
                if (named) {
                    Text(
                        text = description.orEmpty(),
                        style = labelStyle,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.xs).clearAndSetSemantics { }
                    )
                }
            }
            if (description != null) ChipHintPopup(visible = showHint && !named, text = description)
        } else {
            Text(
                text = text.orEmpty(),
                style = labelStyle,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = a11y
            )
        }
    }
}
