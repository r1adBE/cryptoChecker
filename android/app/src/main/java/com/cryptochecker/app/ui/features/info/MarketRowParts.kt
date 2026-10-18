// Teile einer Zeile im Markt-Tab: Zeile mit Wert, Laden bzw. Fehler, Herkunft und Alter der Daten.
// Eigene Datei, weil `MarketRow` sonst zu lang würde.
package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.ui.components.ChangePill
import com.cryptochecker.app.ui.components.ChangePillSize
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.ui.theme.title
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Nebenzeile links und Wert rechts unter dem Titel; wird bei einem Wechsel der Art überblendet. */
@Composable
internal fun MarketRowLine(
    kind: Int,
    secondary: String,
    secondaryMaxLines: Int,
    value: String?,
    change: Double?,
    valueColor: Color?,
    failure: String,
    onRetry: () -> Unit,
    spoken: String?,
    meta: StampText?,
) {
    val valueStyle = MaterialTheme.typography.title.tabularNumbers()
    val loadingText = stringResource(R.string.loading_hint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(top = Spacing.xs)
            .then(
                when {
                    kind == ROW_LOADING -> Modifier.clearAndSetSemantics { contentDescription = loadingText }
                    kind == ROW_SHOWN && spoken != null -> Modifier.clearAndSetSemantics { contentDescription = spoken }
                    else -> Modifier
                }
            )
    ) {
        Column(modifier = Modifier.weight(1f)) {
            when (kind) {
                ROW_LOADING -> SkeletonPulse {
                    SkeletonLine(MaterialTheme.typography.label, Modifier.width(120.dp))
                }
                // Neutral wie [UnavailableRow]: eine ausgefallene Quelle ist kein Eingabefehler
                ROW_FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    UnavailableIcon()
                    Text(
                        failure,
                        style = MaterialTheme.typography.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.sm)
                    )
                }
                else -> if (meta == null) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = secondaryMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    StampedSecondary(secondary, meta, secondaryMaxLines)
                }
            }
        }
        when (kind) {
            ROW_LOADING -> SkeletonPulse(modifier = Modifier.padding(start = Spacing.md)) {
                SkeletonLine(valueStyle, Modifier.width(56.dp))
            }
            ROW_FAILED -> TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            else -> {
                if (value != null && valueColor != null) {
                    // Farbige Schrift in der Bedeutungsfarbe, ohne Pille; die Zahl bleibt (Farbe trägt nie allein)
                    Text(
                        value,
                        style = valueStyle,
                        fontWeight = FontWeight.SemiBold,
                        color = valueColor,
                        maxLines = 1,
                        modifier = Modifier.padding(start = Spacing.md)
                    )
                } else if (value != null) {
                    Text(
                        value,
                        style = valueStyle,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Spacing.md).widthIn(max = 200.dp)
                    )
                }
                if (change != null) {
                    ChangePill(
                        change = change,
                        size = ChangePillSize.MEDIUM,
                        modifier = Modifier.padding(start = Spacing.sm)
                    )
                }
            }
        }
    }
}

/** Herkunft und Alter einer Zeile: sichtbarer Text, gesprochene Form, veraltet? */
private class StampText(val provider: String?, val age: String, val stale: Boolean, val spoken: String)

/**
 * Anbieter und Alter zu [stamp] — das Alter läuft mit (alle 30 s neu gerechnet):
 * «gerade eben», «vor 3 Min.», «heute 02:00», sonst das Datum.
 */
@Composable
internal fun rememberStampText(stamp: DataStamp): StampText {
    var now by remember(stamp.savedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stamp.savedAt) {
        while (true) {
            delay(AGE_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    val age = dataAgeText(stamp.savedAt, now)
    val stale = DataFreshness.isStale(stamp.savedAt, now, stamp.ttlMillis)
    val staleWord = stringResource(R.string.widget_outdated)
    val spoken = listOfNotNull(stamp.provider, age, if (stale) staleWord else null).joinToString(", ")
    return StampText(stamp.provider, age, stale, spoken)
}

/** Alter eines Werts als kurzer Text (siehe [DataFreshness.age]). */
@Composable
internal fun dataAgeText(savedAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    return when (val age = DataFreshness.age(savedAt, now, zone)) {
        DataFreshness.Age.JustNow -> stringResource(R.string.time_just_now)
        is DataFreshness.Age.Minutes -> pluralStringResource(R.plurals.market_age_minutes, age.minutes, age.minutes)
        is DataFreshness.Age.Today -> stringResource(R.string.market_age_today, PriceFormat.shortTime(age.at))
        is DataFreshness.Age.Date -> {
            val format = remember { LocaleNumbers.dates(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
            Instant.ofEpochMilli(age.at).atZone(zone).toLocalDate().format(format)
        }
    }
}

/**
 * Nebenzeile mit Herkunft: «[secondary] · Anbieter · Alter»; das Alter in Warnfarbe, wenn
 * veraltet (Screenreader: «veraltet»). Bis zu zwei Zeilen, damit bei grosser Schrift nichts
 * abgeschnitten wird.
 */
@Composable
private fun StampedSecondary(secondary: String, meta: StampText, maxLines: Int) {
    val warning = AppColors.warningText
    val text = buildAnnotatedString {
        val head = listOfNotNull(secondary.takeIf { it.isNotBlank() }, meta.provider).joinToString(SEPARATOR)
        append(head)
        if (head.isNotEmpty()) append(SEPARATOR)
        if (meta.stale) withStyle(SpanStyle(color = warning)) { append(meta.age) } else append(meta.age)
    }
    val staleWord = stringResource(R.string.widget_outdated)
    Text(
        text,
        style = MaterialTheme.typography.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxOf(maxLines, 2),
        overflow = TextOverflow.Ellipsis,
        modifier = if (meta.stale) Modifier.semantics { contentDescription = "${text.text}, $staleWord" } else Modifier,
    )
}

private const val SEPARATOR = " · "

private const val AGE_TICK_MILLIS = 30_000L
