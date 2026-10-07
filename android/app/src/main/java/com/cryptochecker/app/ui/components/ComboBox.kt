@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cryptochecker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/**
 * Auswahlfeld mit Aufklappliste.
 *
 * @param searchable true: Man kann ins Feld tippen; die Liste zeigt dann nur
 *   passende Einträge (Treffer am Anfang zuerst). Spart bei Hunderten von
 *   Coins das Scrollen. Enter wählt den ersten Treffer.
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
) {
    val haptics = LocalHapticFeedback.current
    val favoriteAddLabel = stringResource(com.cryptochecker.app.R.string.favorite_add)
    val favoriteRemoveLabel = stringResource(com.cryptochecker.app.R.string.favorite_remove)
    var expanded by remember { mutableStateOf(false) }
    // Suchtext; null = nicht am Suchen, das Feld zeigt die aktuelle Auswahl.
    var query by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    val selectedText = if (selectedIndex in itemList.indices) itemList[selectedIndex] else ""
    // Noch nichts gewählt: «Bitte auswählen» blass im Feld statt leer.
    val showEmptyText = selectedText.isEmpty() && query == null && !emptyText.isNullOrEmpty()

    // Gefilterte Einträge mit ihrem Index in der vollen Liste.
    // Favoriten stehen jeweils zuerst — ohne Suche und innerhalb der Treffer.
    val visible: List<Pair<Int, String>> = remember(itemList, query, favorites) {
        val q = query?.trim().orEmpty()
        val all = itemList.mapIndexed { i, item -> i to item }
        val matches = if (q.isEmpty()) all else {
            val starts = all.filter { it.second.startsWith(q, ignoreCase = true) }
            val contains = all.filter { !it.second.startsWith(q, ignoreCase = true) && it.second.contains(q, ignoreCase = true) }
            starts + contains
        }
        matches.filter { it.second in favorites } + matches.filterNot { it.second in favorites }
    }

    fun select(index: Int) {
        if (index != selectedIndex) onValueChange(index)
        query = null
        expanded = false
        focusManager.clearFocus()
    }

    // Platzhalter zeigt beim Tippen die bisherige Auswahl blass an.
    val placeholder: (@Composable () -> Unit)? =
        if (searchable) { { Text(selectedText) } } else null

    ExposedDropdownMenuBox(
        expanded = expanded,
        modifier = modifier,
        onExpandedChange = {
            if (!enabled) return@ExposedDropdownMenuBox
            expanded = !expanded
            if (!expanded) query = null
        }) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(
                    if (searchable) ExposedDropdownMenuAnchorType.PrimaryEditable
                    else ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = enabled
                )
                .fillMaxWidth()
                .onFocusChanged { state ->
                    // Beim Antippen leeren, damit man nicht hinter den alten Namen tippt.
                    if (!searchable) return@onFocusChanged
                    if (state.isFocused && query == null) query = ""
                    if (!state.isFocused) query = null
                },
            singleLine = true,
            maxLines = 1,
            readOnly = !searchable,
            enabled = enabled,
            value = query ?: if (showEmptyText) emptyText.orEmpty() else selectedText,
            textStyle = if (showEmptyText)
                LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LocalTextStyle.current,
            onValueChange = { input ->
                if (!searchable) return@OutlinedTextField
                query = input
                expanded = true
            },
            placeholder = placeholder,
            label = { if(!label.isNullOrEmpty()) Text(label) },
            trailingIcon = {
                if (enabled) {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { visible.firstOrNull()?.let { select(it.first) } ?: run { query = null; expanded = false } }
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = null
            }
        ) {

            val listState = rememberLazyListState()
            val lazyHeight = visible.size.coerceIn(1, 10) * 48

            if (visible.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("—") },
                    onClick = {},
                    enabled = false
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .simpleVerticalScrollbar(state = listState)
                        .size(400.dp, lazyHeight.dp) // Required to fix intrinsic issue
                ) {
                    itemsIndexed(visible) { _, (index, itemText) ->
                        val isFavorite = itemText in favorites
                        // Eigene Zeile statt DropdownMenuItem: das kennt kein langes Drücken.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .let {
                                    if (selectedIndex == index)
                                        it.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                    else it
                                }
                                .semantics {
                                    if (onToggleFavorite != null) {
                                        customActions = listOf(
                                            CustomAccessibilityAction(
                                                if (isFavorite) favoriteRemoveLabel else favoriteAddLabel
                                            ) {
                                                onToggleFavorite(itemText); true
                                            }
                                        )
                                    }
                                }
                                .combinedClickable(
                                    onClick = { select(index) },
                                    onLongClick = onToggleFavorite?.let { toggle ->
                                        {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            toggle(itemText)
                                        }
                                    }
                                )
                                .padding(start = 12.dp, end = 4.dp)
                        ) {
                            Text(
                                text = itemText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            // Stern direkt in der Zeile; langes Drücken geht weiterhin.
                            if (onToggleFavorite != null) {
                                IconButton(
                                    onClick = { onToggleFavorite(itemText) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        painterResource(
                                            if (isFavorite) com.cryptochecker.app.R.drawable.ic_star
                                            else com.cryptochecker.app.R.drawable.ic_star_outline
                                        ),
                                        contentDescription = stringResource(
                                            if (isFavorite) com.cryptochecker.app.R.string.favorite_remove
                                            else com.cryptochecker.app.R.string.favorite_add
                                        ),
                                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else if (isFavorite) {
                                Text("★", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // Ohne Suche zur aktuellen Auswahl springen (wie bisher).
            LaunchedEffect(expanded, query) {
                if (query.isNullOrEmpty()) {
                    val pos = visible.indexOfFirst { it.first == selectedIndex }
                    if (pos > 2) listState.scrollToItem(pos - 2)
                } else {
                    listState.scrollToItem(0)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun ComboBoxPreview(sampleCount: Int = 3) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxSize(),
    ) {
        Row {
            (1..sampleCount).forEach {
                ComboBox(
                    modifier = Modifier
//                        .widthIn(20.dp, 200.dp)
                        .weight(1f)
                        ,

                    itemList = listOf("Item1", "Item2"),
                    label = "Test_$it",
                    onValueChange = {}
                )
            }
        }
        Row {
            ComboBox(
                modifier =
                    Modifier
                        .weight(1f)
//                        .fillMaxWidth()
                        .widthIn(max = 100.dp)
                ,
                itemList = listOf("Item1", "Item2"),
                label = "Test",
                onValueChange = {}
            )
        }

        Row {
            (1..sampleCount).forEach {
//                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
//                        .width(50.dp)
//                            .widthIn(10.dp, 200.dp)
//                            .defaultMinSize(minWidth = 1.dp)
                        ,
                        value = "Label $it",
                        singleLine = true,
                        onValueChange = {},
                    )
                }
  //          }
        }

    }
}