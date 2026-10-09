@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.components.LocalCoinNameSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.components.coinName
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.LocaleNumbers

/** Mitte: Paar (mit Häkchen des Erst-Moments und ⚡), Börse und Zeit bzw. Warnung, Fehler, Notiz. */
@Composable
internal fun RowInfo(
    watch: WatchEntity,
    alarmCount: Int,
    now: Long,
    stale: Boolean,
    status: WatchRowStatus,
    accent: Color,
    check: Animatable<Float, AnimationVector1D>,
    hasActivity: Boolean,
    onActivityClick: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = watch.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { }
            )
            // Erst-Moment: kurzes Häkchen in der Akzentfarbe (das Banner sagt es dem Screenreader)
            if (check.value > 0f) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp)
                        .graphicsLayer {
                            alpha = check.value
                            val scale = 0.7f + 0.3f * check.value
                            scaleX = scale
                            scaleY = scale
                        }
                        .clearAndSetSemantics { }
                )
            }
            // ⚡ Ungewöhnliche Aktivität — Tipp öffnet «Warum bewegt sich das?»
            if (hasActivity) {
                ActivityBolt(onClick = onActivityClick, modifier = Modifier.padding(start = 2.dp))
            }
        }
        // «Namen anzeigen»: Name unter dem Paar («Bitcoin», «NVIDIA»), ohne bekannten Namen «–»;
        // im Zeilensatz enthalten. Gleiche Höhe wie die %-Pille rechts (bodySmall = labelMedium
        // 16 sp + 2 × 2 dp), so stehen Paar/Kurs, Name/Pille und Börse/≈ Umrechnung je auf einer Linie.
        val namesOn = LocalCoinNameSource.current != null
        if (namesOn) {
            Text(
                text = coinName(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name) ?: "–",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp).clearAndSetSemantics { }
            )
        }
        // Im Zeilensatz enthalten, hier für den Screenreader ausgeblendet
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Mit Namen: gleicher Abstand wie die ≈ Umrechnung rechts
            modifier = Modifier.padding(top = if (namesOn) 2.dp else 0.dp).clearAndSetSemantics { }
        ) {
            // Warnung (nicht erreichbar / veraltet) ersetzt die normale Zeitzeile
            val warning = status.warning
            if (warning != null) {
                Icon(
                    painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 4.dp).size(13.dp)
                )
            }
            Text(
                text = when {
                    warning != null -> warning
                    watch.lastUpdate > 0 -> "${BidiText.isolate(watch.marketName)} · ${ago(watch.lastUpdate, now)}"
                    else -> watch.marketName
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    warning != null -> MaterialTheme.colorScheme.error
                    stale && watch.lastUpdate > 0 -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            // Kleine Zeichen: Meldung an, Alarme scharf
            if (watch.notificationEnabled) {
                Icon(
                    painterResource(R.drawable.ic_notifications),
                    contentDescription = stringResource(R.string.watchlist_notification),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.xs).size(13.dp)
                )
            }
            if (alarmCount > 0) {
                Icon(
                    painterResource(R.drawable.ic_alarm_overview),
                    contentDescription = pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount),
                    tint = accent,
                    modifier = Modifier.padding(start = Spacing.xs).size(13.dp)
                )
                Text(
                    text = LocaleNumbers.integer(alarmCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
        watch.lastError?.takeIf { status.showErrorLine }?.let { error ->
            Text(
                text = friendlyError(error),
                style = MaterialTheme.typography.bodySmall,
                color = if (isNotTraded(error)) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
        // Eigene Notiz (#233), dezent unter dem Paar
        watch.note?.let { note ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics { }
            ) {
                Icon(
                    painterResource(R.drawable.ic_note),
                    contentDescription = stringResource(R.string.note_title),
                    tint = accent.copy(alpha = 0.8f),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/**
 * Zustandstexte einer Zeile, auch für den Screenreader: [warning] ersetzt die Zeitzeile
 * («vor 41 Min · Binance nicht erreichbar» bzw. «Binance · veraltet · vor 4 Min.»), [error] ist
 * der verständliche Fehler, [stale] der Hinweis «veraltet» ohne Warnung.
 */
internal class WatchRowStatus(
    val warning: String?,
    val error: String?,
    val stale: String?,
    /** Technischer Grund nur, wenn er mehr sagt als «nicht erreichbar» (z. B. Paar unbekannt). */
    val showErrorLine: Boolean,
)

@Composable
internal fun watchRowStatus(watch: WatchEntity, now: Long, stale: Boolean, outdatedAfter: Long): WatchRowStatus {
    // Letzter Abruf gescheitert, aber es gibt einen älteren Kurs: «vor 41 Min · Binance nicht erreichbar»
    val failed = watch.lastError != null && !isNotTraded(watch.lastError)
    val unreachableText = if (failed && watch.lastUpdate > 0) {
        stringResource(R.string.watchlist_row_unreachable, ago(watch.lastUpdate, now), watch.marketName)
    } else {
        null
    }
    // Kein Fehler, aber älter als die Grenze ([OutdatedRule], live 2 Min.): «Binance · veraltet · vor 4 Min.»
    val outdatedText = if (!failed && !isNotTraded(watch.lastError) && watch.lastUpdate > 0 &&
        now - watch.lastUpdate > outdatedAfter
    ) {
        "${BidiText.isolate(watch.marketName)} · " +
            stringResource(R.string.watchlist_row_outdated_age, ago(watch.lastUpdate, now))
    } else {
        null
    }
    val warningText = unreachableText ?: outdatedText
    val showErrorLine = watch.lastError != null &&
        (unreachableText == null || !isRetryableMarketError(watch.lastError))
    val errorText = watch.lastError?.takeIf { showErrorLine }?.let { friendlyError(it) }
    val staleText = if (warningText == null && stale && watch.lastUpdate > 0) {
        stringResource(R.string.a11y_stale, ago(watch.lastUpdate, now))
    } else {
        null
    }
    return WatchRowStatus(warningText, errorText, staleText, showErrorLine)
}
