package com.cryptochecker.app.ui.features.info

import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.MarketReveal
import com.cryptochecker.app.ui.components.SkeletonLine
import com.cryptochecker.app.ui.components.SkeletonPulse
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
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

/** Art einer [MarketRow]: Platzhalter, Inhalt, Fehler — nur ein Wechsel der Art wird überblendet. */
private const val ROW_LOADING = 0
private const val ROW_SHOWN = 1
private const val ROW_FAILED = 2

/**
 * Eine Zeile in «Einordnung» und «Daten» des Markt-Tabs — ohne Karte: links der Titel
 * (Body) und eine Nebenzeile (Label, Nebenfarbe), rechts der Wert (Title, halbfett,
 * gleich breite Ziffern), dahinter optional eine kleine Veränderung ([change], z. B.
 * «▲ +1.20%» in der Kursfarbe). Darüber eine dünne Trennlinie ([divider]), [below] steht
 * in voller Breite direkt unter der Zeile (Skala von Fear & Greed).
 *
 * Tippen klappt [details] darunter auf und zu ([expanded], [onToggle]) — dieselben
 * Inhalte, die früher in der Karte standen. Beim Laden ([loading]) stehen der echte Titel
 * und graue Balken in genau der Höhe von Nebenzeile und Wert (kein Springen); bei
 * [failure] die Meldung und «Erneut». Wie `MarketRow` (iOS).
 *
 * Mit [stamp] endet die Nebenzeile mit Anbieter und Alter der Daten («… · CoinGecko ·
 * vor 3 Min.», siehe [DataFreshness]); ist der Wert älter als drei Gültigkeitsdauern, steht
 * das Alter in Warnfarbe und der Screenreader sagt «veraltet». Die Nebenzeile darf dann
 * zwei Zeilen brauchen (grosse Schrift).
 *
 * Screenreader: die Zeile ist ein Element (Titel, Nebenzeile, Wert, Veränderung, Skala)
 * mit Zustand auf-/zugeklappt; [spoken] ersetzt Titel bis Veränderung durch einen Satz.
 */
@Composable
internal fun MarketRow(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String = "",
    value: String? = null,
    divider: Boolean = true,
    loading: Boolean = false,
    failure: String? = null,
    onRetry: () -> Unit = {},
    change: String? = null,
    changeColor: Color = Color.Unspecified,
    secondaryMaxLines: Int = 1,
    spoken: String? = null,
    stamp: DataStamp? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    details: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val kind = when {
        loading -> ROW_LOADING
        failure != null -> ROW_FAILED
        else -> ROW_SHOWN
    }
    val expandable = onToggle != null && details != null
    val stateText = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    val reduceMotion = rememberReduceMotion()
    Column(modifier = modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expandable) Modifier
                        .clickable(role = Role.Button) { onToggle() }
                        .semantics { stateDescription = stateText }
                    else Modifier
                )
        ) {
            CardSwap(kind, { it }) { shownKind ->
                MarketRowLine(
                    kind = shownKind,
                    title = title,
                    secondary = secondary,
                    secondaryMaxLines = secondaryMaxLines,
                    value = value,
                    change = change,
                    changeColor = changeColor,
                    failure = failure.orEmpty(),
                    onRetry = onRetry,
                    spoken = spoken,
                    stamp = stamp,
                )
            }
            if (below != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = Spacing.md)) { below() }
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
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = Spacing.md),
                    content = details
                )
            }
        }
    }
}

@Composable
private fun MarketRowLine(
    kind: Int,
    title: String,
    secondary: String,
    secondaryMaxLines: Int,
    value: String?,
    change: String?,
    changeColor: Color,
    failure: String,
    onRetry: () -> Unit,
    spoken: String?,
    stamp: DataStamp?,
) {
    val valueStyle = MaterialTheme.typography.title.tabularNumbers()
    val loadingText = stringResource(R.string.loading_hint)
    val meta = stamp?.let { rememberStampText(it) }
    // Gesprochener Satz samt Herkunft und Alter («…, CoinGecko, vor 3 Min., veraltet»)
    val spokenFull = if (spoken != null && meta != null) "$spoken, ${meta.spoken}" else spoken
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Links und rechts wie die Abschnittsüberschrift eingerückt
            .padding(horizontal = 4.dp, vertical = Spacing.md)
            .then(
                when {
                    kind == ROW_LOADING -> Modifier.clearAndSetSemantics { contentDescription = "$title, $loadingText" }
                    kind == ROW_SHOWN && spokenFull != null -> Modifier.clearAndSetSemantics { contentDescription = spokenFull }
                    else -> Modifier
                }
            )
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (kind) {
                ROW_LOADING -> SkeletonPulse {
                    SkeletonLine(MaterialTheme.typography.label, Modifier.width(120.dp))
                }
                ROW_FAILED -> Text(
                    failure,
                    style = MaterialTheme.typography.label,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                if (value != null) {
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
                    Text(
                        change,
                        style = MaterialTheme.typography.label.tabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        color = changeColor,
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
private fun rememberStampText(stamp: DataStamp): StampText {
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
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = Spacing.xs)
                .size(20.dp)
                .rotate(rotation)
        )
    }
}
