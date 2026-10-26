@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cryptochecker.app.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing

/** Mindesthöhe einer Zeile der Aufklappliste (zugleich Tippfläche des Sterns). */
private val RowHeight = 48.dp

/** Breite der Aufklappliste auf breiten Bildschirmen; schmale bekommen den verfügbaren Platz. */
private val MaxListWidth = 400.dp

/** Abstand der Liste zum Bildschirmrand auf schmalen Geräten (je Seite 16 dp). */
private val ListScreenMargin = 32.dp

/** Höchstens so viele Zeilen sichtbar, darüber wird gerollt. */
private const val MAX_VISIBLE_ROWS = 10

/**
 * Auswahlfeld mit Aufklappliste.
 *
 * @param searchable true: Man kann ins Feld tippen, die Liste zeigt dann nur passende Einträge
 *   (Treffer am Anfang zuerst) — erspart bei Hunderten von Coins das Rollen. «Fertig» nimmt den
 *   ersten Treffer.
 * @param favorites Einträge mit Stern, stehen vorne.
 * @param onToggleFavorite gesetzt: Stern je Zeile und langes Drücken schalten den Favoriten um.
 * @param emptyText blasser Text im Feld, solange nichts gewählt ist.
 * @param sections Abschnittstitel je Eintrag (gleiche Länge wie [itemList], Build 133: Börsenauswahl Spot · Futures ·
 *   DEX-Pools · Forex); die Liste zeigt dann Titelzeilen, die Suche geht über alle Abschnitte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboBox(
    modifier: Modifier = Modifier,
    selectedIndex: Int = -1,
    itemList: List<String>,
    onValueChange: (Int) -> Unit,
    label: String? = null,
    searchable: Boolean = false,
    favorites: Set<String> = emptySet(),
    onToggleFavorite: ((String) -> Unit)? = null,
    enabled: Boolean = true,
    emptyText: String? = null,
    sections: List<String>? = null,
) {
    val focusManager = LocalFocusManager.current
    var open by remember { mutableStateOf(false) }
    // Suchtext; null = keine Suche, das Feld zeigt die Auswahl
    var typed by remember { mutableStateOf<String?>(null) }

    val selectedText = itemList.getOrNull(selectedIndex).orEmpty()
    // Nichts gewählt: «Bitte auswählen» blass im Feld statt leer
    val showsEmptyText = selectedText.isEmpty() && typed == null && !emptyText.isNullOrEmpty()
    val entries = remember(itemList, typed, favorites, sections) { comboEntries(itemList, typed, favorites, sections) }

    fun close() {
        open = false
        typed = null
    }

    fun choose(index: Int) {
        if (index != selectedIndex) onValueChange(index)
        close()
        focusManager.clearFocus()
    }

    ExposedDropdownMenuBox(
        expanded = open,
        modifier = modifier,
        onExpandedChange = {
            if (enabled) {
                open = !open
                if (!open) typed = null
            }
        },
    ) {
        val anchorType = if (searchable) ExposedDropdownMenuAnchorType.PrimaryEditable
        else ExposedDropdownMenuAnchorType.PrimaryNotEditable
        // Beim Tippen steht die bisherige Auswahl blass als Platzhalter da
        val placeholder: (@Composable () -> Unit)? = if (searchable) {
            { Text(selectedText) }
        } else {
            null
        }
        OutlinedTextField(
            value = typed ?: (if (showsEmptyText) emptyText.orEmpty() else selectedText),
            onValueChange = { input ->
                if (searchable) {
                    typed = input
                    open = true
                }
            },
            modifier = Modifier
                .menuAnchor(anchorType, enabled = enabled)
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    // Beim Antippen leeren, damit man nicht hinter den alten Namen tippt
                    if (searchable) {
                        if (focus.isFocused && typed == null) typed = ""
                        if (!focus.isFocused) typed = null
                    }
                },
            enabled = enabled,
            readOnly = !searchable,
            textStyle = if (showsEmptyText) {
                LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LocalTextStyle.current
            },
            label = { if (!label.isNullOrEmpty()) Text(label) },
            placeholder = placeholder,
            trailingIcon = {
                if (enabled) ExposedDropdownMenuDefaults.TrailingIcon(expanded = open)
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    val first = entries.firstOrNull { !it.header }
                    if (first != null) choose(first.index) else close()
                },
            ),
            singleLine = true,
            maxLines = 1,
        )

        ExposedDropdownMenu(expanded = open, onDismissRequest = { close() }) {
            val listState = rememberLazyListState()
            if (entries.isEmpty()) {
                DropdownMenuItem(text = { Text("—") }, onClick = {}, enabled = false)
            } else {
                ComboList(
                    entries = entries,
                    state = listState,
                    selectedIndex = selectedIndex,
                    favorites = favorites,
                    onChoose = ::choose,
                    onToggleFavorite = onToggleFavorite,
                )
            }

            // Ohne Suche zur aktuellen Auswahl rollen (zwei Zeilen Vorlauf), mit Suche nach oben
            LaunchedEffect(open, typed) {
                if (typed.isNullOrEmpty()) {
                    val at = if (selectedIndex < 0) -1 else entries.indexOfFirst { !it.header && it.index == selectedIndex }
                    if (at > 2) listState.scrollToItem(at - 2)
                } else {
                    listState.scrollToItem(0)
                }
            }
        }
    }
}

/** Die Zeilen der Aufklappliste mit Rollbalken; feste Grösse, sonst scheitert die Messung im Menü. */
@Composable
private fun ComboList(
    entries: List<ComboEntry>,
    state: LazyListState,
    selectedIndex: Int,
    favorites: Set<String>,
    onChoose: (Int) -> Unit,
    onToggleFavorite: ((String) -> Unit)?,
) {
    val rows = entries.size.coerceIn(1, MAX_VISIBLE_ROWS)
    // Feste Masse bleiben nötig (LazyColumn kennt keine Intrinsics), sie folgen aber Bildschirm
    // und Schriftgrösse: bei 200 % wächst die Zeile mit, auf schmalen Geräten passt die Liste.
    // Bewusst die Bildschirmbreite aus der Configuration; ein Umbau auf containerSize wäre eine eigene Änderung
    @SuppressLint("ConfigurationScreenWidthHeight")
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val listWidth = minOf(MaxListWidth, (screenWidth - ListScreenMargin).coerceAtLeast(RowHeight * 3))
    val rowHeight = comboRowHeight()
    LazyColumn(
        state = state,
        modifier = Modifier
            .simpleVerticalScrollbar(state = state)
            .width(listWidth)
            .height(rowHeight * rows),
    ) {
        items(entries) { entry ->
            if (entry.header) {
                ComboHeader(entry.text, rowHeight)
                return@items
            }
            ComboRow(
                entry = entry,
                selected = entry.index == selectedIndex,
                favorite = entry.text in favorites,
                rowHeight = rowHeight,
                onChoose = onChoose,
                onToggleFavorite = onToggleFavorite,
            )
        }
    }
}

/** Titelzeile eines Abschnitts: nicht wählbar, für Bedienungshilfen eine Überschrift. */
@Composable
private fun ComboHeader(title: String, rowHeight: Dp) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 12.dp)
            .semantics { heading() },
    )
}

/** Zeilenhöhe: eine Textzeile bodyLarge plus Innenabstand, nie unter 48 dp (wächst mit der Schrift). */
@Composable
private fun comboRowHeight(): Dp {
    val lineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    return maxOf(RowHeight, lineHeight + 16.dp)
}

/**
 * Eine Zeile: eigene Zeile statt DropdownMenuItem, weil dieses kein langes Drücken kennt. Der
 * Favorit lässt sich per Stern, langem Drücken oder Bedienungshilfe umschalten.
 */
@Composable
private fun ComboRow(
    entry: ComboEntry,
    selected: Boolean,
    favorite: Boolean,
    rowHeight: Dp,
    onChoose: (Int) -> Unit,
    onToggleFavorite: ((String) -> Unit)?,
) {
    val haptics = rememberHaptics()
    val toggleLabel = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add)

    var rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = rowHeight)
    if (selected) rowModifier = rowModifier.background(AppColors.accentTint(0.3f))
    rowModifier = rowModifier
        .semantics {
            if (onToggleFavorite != null) {
                customActions = listOf(
                    CustomAccessibilityAction(toggleLabel) {
                        onToggleFavorite(entry.text)
                        true
                    },
                )
            }
        }
        .combinedClickable(
            onClick = { onChoose(entry.index) },
            onLongClick = onToggleFavorite?.let { toggle ->
                {
                    haptics.play(HapticEvent.LONG_PRESS)
                    toggle(entry.text)
                }
            },
        )
        .padding(start = 12.dp, end = 4.dp)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyLarge,
            // Eine Zeile mit «…»: die Listenhöhe rechnet mit genau einer Textzeile je Eintrag
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            // Stern direkt in der Zeile; langes Drücken bleibt
            onToggleFavorite != null -> IconButton(
                onClick = { onToggleFavorite(entry.text) },
                // Tippfläche so hoch wie die Zeile, das Symbol bleibt 20 dp
                modifier = Modifier.size(RowHeight),
            ) {
                Icon(
                    painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline),
                    contentDescription = toggleLabel,
                    // Leerer Stern auf der getönten gewählten Zeile in onSurfaceVariant: outline hielt dort < 3:1
                    tint = when {
                        favorite -> MaterialTheme.colorScheme.primary
                        selected -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            // Symbol statt «★»: TalkBack las das Zeichen als «schwarzer Stern»
            favorite -> Icon(
                painterResource(R.drawable.ic_star),
                contentDescription = stringResource(R.string.a11y_favorite),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = Spacing.sm).size(16.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ComboBoxPreview() {
    Column(Modifier.padding(8.dp)) {
        ComboBox(itemList = listOf("BTC", "ETH", "SOL"), selectedIndex = 1, label = "Coin", onValueChange = {})
        ComboBox(
            modifier = Modifier.padding(top = 8.dp),
            itemList = listOf("USDT", "USDC"),
            label = "Quote",
            emptyText = "—",
            favorites = setOf("USDC"),
            onToggleFavorite = {},
            onValueChange = {},
        )
    }
}
