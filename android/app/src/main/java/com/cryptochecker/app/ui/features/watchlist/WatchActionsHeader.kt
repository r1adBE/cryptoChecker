// Kopf des Aktionsblatts: Vorlese-Text des Kurses und der Hinweis bei deaktivierten Paaren.
// Eigene Datei wie `WatchActionsHeader.swift`; der Kopf selbst (Paar, Kurs, Zeile «↗ +41.71% · 24h ·
// Aktualisiert HH:MM») steht noch im Blatt `WatchActionsSheet`.
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.util.A11ySpeech
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.PriceFormat

/**
 * Deaktiviertes Paar im Aktionsblatt: ruhiger Hinweis unter dem Kurs («Deaktiviert · Keine Abfragen,
 * keine Meldungen», Stand des letzten Kurses) und gleich daneben «Aktivieren» — auch bei
 * zugeklapptem «Mehr» sofort erreichbar.
 */
@Composable
internal fun DisabledNotice(updatedAt: Long, onEnable: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_pause_circle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.watch_disabled) + " · " + stringResource(R.string.watch_disable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (updatedAt > 0) {
                Text(
                    text = stringResource(R.string.watchlist_updated, PriceFormat.time(updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onEnable) { Text(stringResource(R.string.watch_enable)) }
    }
}

/**
 * Kurs im Kopf des Blatts für den Screenreader: «Kurs 61’234.00 US-Dollar» und, wenn veraltet
 * ([A11ySpeech.freshness]), «Kurs veraltet, zuletzt aktualisiert vor 45 Minuten». Deaktiviert,
 * nicht erreichbar und nicht mehr gehandelt stehen schon als Text darunter.
 */
@Composable
internal fun sheetPriceSpoken(watch: WatchEntity, priceText: String, off: Boolean, now: Long, outdatedAfter: Long): String {
    val context = LocalContext.current
    val notTraded = isNotTraded(watch.lastError)
    val freshness = A11ySpeech.freshness(
        disabled = off,
        notTraded = notTraded,
        failed = watch.lastError != null && !notTraded,
        lastUpdate = watch.lastUpdate,
        now = now,
        outdatedAfter = outdatedAfter,
    )
    val outdated = freshness == A11ySpeech.Freshness.OUTDATED || freshness == A11ySpeech.Freshness.UNREACHABLE_OUTDATED
    val stale = if (outdated) {
        A11yText.freshness(context, A11ySpeech.Freshness.OUTDATED, watch.lastUpdate, now, watch.marketName)
    } else {
        null
    }
    val price = stringResource(R.string.a11y_price, A11ySpeech.spokenPrice(priceText, watch.quoteAsset, A11yText.locale(context)))
    return A11ySpeech.join(listOf(price, stale))
}
