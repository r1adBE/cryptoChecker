package com.cryptochecker.app.ui.features.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.SettingsSearch
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/*
 * Suche in den Einstellungen. Die Hauptseite zeigt oben ein Suchfeld; der Index
 * umfasst ihre Zeilen und die Punkte der Unterseiten (Titel, Hinweise als Synonyme). Ein Treffer
 * öffnet die Unterseite und hebt den Punkt kurz hervor (scrollt dorthin); Zeilen der Hauptseite
 * werden dort hervorgehoben. Abgleich in `SettingsSearch` (rein, getestet).
 */

/** Wohin ein Treffer führt; [anchor] ist der Punkt, der kurz hervorgehoben wird. */
sealed interface SettingsSearchTarget {
    val anchor: String?

    /** Zeile der Hauptseite (Sprache, «App zurücksetzen»). */
    data class Main(override val anchor: String) : SettingsSearchTarget
    data class Page(val page: SettingsPage, override val anchor: String? = null) : SettingsSearchTarget
    data class MarketAlerts(override val anchor: String? = null) : SettingsSearchTarget
    data class Speech(override val anchor: String? = null) : SettingsSearchTarget
}

/** Punkt, der auf der gerade gezeigten Seite hervorgehoben werden soll (oder keiner). */
val LocalSettingsHighlight = staticCompositionLocalOf<String?> { null }

/** Gibt [anchor] an die Punkte der Seite [content] weiter. */
@Composable
fun ProvideSettingsHighlight(anchor: String?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsHighlight provides anchor, content = content)
}

/** Wartezeit, bis die Seite eingeblendet ist (Überblendung der Navigation), ms. */
private const val HIGHLIGHT_DELAY_MILLIS = 250L
private const val HIGHLIGHT_IN_MILLIS = 200
private const val HIGHLIGHT_HOLD_MILLIS = 1_200L
private const val HIGHLIGHT_OUT_MILLIS = 700

/**
 * Ein Punkt einer Einstellungsseite, den die Suche ansteuern kann: Ist er das Ziel
 * ([LocalSettingsHighlight]), scrollt die Seite zu ihm und hinterlegt ihn kurz in der
 * Akzentfarbe. Ohne Bewegung (Animationen aus) erscheint die Hinterlegung ohne Überblendung.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsAnchor(id: String, content: @Composable () -> Unit) {
    val active = LocalSettingsHighlight.current == id
    val requester = remember { BringIntoViewRequester() }
    val alpha = remember { Animatable(0f) }
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(active) {
        if (!active) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        delay(HIGHLIGHT_DELAY_MILLIS)
        requester.bringIntoView()
        if (reduceMotion) {
            alpha.snapTo(1f)
            delay(HIGHLIGHT_HOLD_MILLIS + HIGHLIGHT_OUT_MILLIS)
            alpha.snapTo(0f)
        } else {
            alpha.animateTo(1f, tween(HIGHLIGHT_IN_MILLIS))
            delay(HIGHLIGHT_HOLD_MILLIS)
            alpha.animateTo(0f, tween(HIGHLIGHT_OUT_MILLIS))
        }
    }
    val tint = AppColors.brand
    // Column, nicht Box: ein Anker kann mehrere Zeilen umfassen (z. B. «Alarm-Signal» mit dem
    // Weg in die Systemeinstellungen) — in einer Box lägen sie übereinander
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = HIGHLIGHT_ALPHA * alpha.value))
    ) {
        content()
    }
}

/** Stärke der Hinterlegung (Akzentfarbe), dezent auf hellen und dunklen Karten. */
private const val HIGHLIGHT_ALPHA = 0.16f

/**
 * Suchfeld oben auf der Hauptseite; darunter (bei Eingabe) [SettingsSearchResults] statt der Gruppen.
 */
@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    )
}

/** Treffer als Zeilen: Titel, darunter grau die Seite bzw. Gruppe; leer ein Hinweis. */
@Composable
internal fun SettingsSearchResults(
    items: List<SettingsSearchItem>,
    query: String,
    onOpen: (SettingsSearchTarget) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val index = remember(items, locale) { SettingsSearch.index(items.map { it.entry }, locale) }
    val byId = remember(items) { items.associateBy { it.entry.id } }
    val hits = remember(index, query) { SettingsSearch.search(index, query) }
    if (hits.isEmpty()) {
        Text(
            text = stringResource(R.string.watchlist_search_empty, query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.neutral,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        return
    }
    Column {
        hits.forEach { entry ->
            val target = byId[entry.id]?.target ?: return@forEach
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen(target) }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                    if (entry.path.isNotEmpty() && entry.path != entry.title) {
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.neutral,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = AppColors.neutral,
                    modifier = Modifier.padding(start = Spacing.xs)
                )
            }
        }
    }
}
