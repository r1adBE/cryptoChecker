package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.AlarmJson
import com.cryptochecker.app.data.local.model.AlarmWithWatch
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.data.portfolio.PortfolioAlarmEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.util.BidiText
import com.cryptochecker.app.util.PriceFormat

// Karten der Alarm-Übersicht («Alle Alarme»): je Paar und «Portfolio» — wie iOS `AlarmsOverviewScreen.groupCard`
// bzw. `portfolioCard`. Eigene Datei, damit `AlarmsOverviewScreen.kt` unter 400 Zeilen bleibt.

/** Logo bzw. Portfolio-Symbol im Kopf einer Karte (iOS 38 pt). */
private val OVERVIEW_BADGE_SIZE = 38.dp

/**
 * Karte eines Paars: Kopf mit Coin-Logo, Paar, Börse, aktuellem Kurs und Pfeil (Tipp öffnet die Alarme des Paars),
 * darunter je Alarm Richtungs-Symbol, Titel, Satz und Schalter (ganze Zeile schaltet). Wie iOS.
 */
@Composable
internal fun AlarmGroupCard(
    group: List<AlarmWithWatch>,
    onOpenWatch: (Long) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val watch = group.first().watch
    AppCard(padding = CardPadding.None) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenWatch(watch.id) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CoinBadge(
                watch.baseAsset,
                size = OVERVIEW_BADGE_SIZE,
                logo = CoinLogos.allowedFor(watch.exchangeKey),
                pair = CoinLogos.pairKey(watch.exchangeKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name),
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    BidiText.isolate(watch.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    BidiText.isolate(watch.exchangeName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Aktueller Kurs: ordnet «Kurs unter 90’000» ein, ohne das Paar zu öffnen (wie iOS)
            Text(
                PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                style = MaterialTheme.typography.titleSmall.amountNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = Spacing.sm)
            )
            // Spiegelt sich in Rechts-nach-links-Sprachen automatisch
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
        group.forEach { item ->
            val alarm = item.alarm
            val title = AlarmTexts.describe(context, alarm) +
                if (alarm.condition.isPriceThreshold && alarm.convertCurrency == null) " ${watch.quoteAsset}" else ""
            val sentence = alarmSentence(
                context = context,
                condition = alarm.condition,
                symbol = watch.baseAsset,
                threshold = alarm.threshold,
                currency = alarm.convertCurrency ?: watch.quoteAsset,
                windowHours = alarm.windowHours,
            )
            // «fällt um ≥ 100 %» (aus einer Sicherung) löst nie aus — kennzeichnen
            val invalidText = if (AlarmJson.exceedsFall(alarm.condition, alarm.threshold)) {
                stringResource(R.string.alarm_threshold_invalid_fix)
            } else null
            // TalkBack: vollständig, inkl. Wiederholung, Ton und Ansage (sichtbare Texte stumm)
            val description = alarmA11yDescription(context, alarm, title, sentence) +
                (invalidText?.let { ". $it" } ?: "")
            // Ungültige Schwelle nicht einschalten (Hinweis steht in der Zeile)
            val canToggle = AlarmJson.toggleAllowed(alarm.condition, alarm.threshold, enable = !alarm.enabled)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Ganze Zeile schaltet, mit Rolle «Schalter» für die Sprachausgabe
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = description }
                    .toggleable(
                        value = alarm.enabled,
                        enabled = canToggle,
                        role = Role.Switch,
                        onValueChange = { onToggle(alarm.id, it) }
                    )
                    .padding(horizontal = 16.dp, vertical = Spacing.sm)
            ) {
                // Richtungs-Symbol vor dem Titel (wie iOS): Themenfarbe, wenn scharf
                Icon(
                    painterResource(AlarmSymbols.icon(alarm.condition)),
                    contentDescription = null,
                    tint = if (alarm.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = Spacing.sm).size(18.dp)
                )
                Column(modifier = Modifier.weight(1f).clearAndSetSemantics {}) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Der Alarm als Satz, wie in der Alarmliste des Paars
                    Text(
                        sentence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (invalidText != null) {
                        Text(
                            invalidText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Switch(checked = alarm.enabled, onCheckedChange = null, enabled = canToggle)
            }
        }
        Box(modifier = Modifier.padding(bottom = Spacing.xs))
    }
}

/** Karte «Portfolio» der Alarm-Übersicht: je Alarm Satz und Schalter (ganze Zeile), Löschen über das Symbol. */
@Composable
internal fun PortfolioAlarmsCard(
    alarms: List<PortfolioAlarmEntity>,
    pausedHint: String?,
    sentence: (PortfolioAlarmEntity) -> String,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (PortfolioAlarmEntity) -> Unit,
) {
    AppCard(padding = CardPadding.None) {
        // Kopf wie iOS: Portfolio-Symbol im getönten Kreis, daneben der Titel
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_portfolio),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(OVERVIEW_BADGE_SIZE)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape)
                    .padding(9.dp)
            )
            Text(
                stringResource(R.string.portfolio_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
        }
        // Warum die Alarme gerade nicht prüfen (kein aktueller Kurs, Adressen nicht geladen) — sonst schwiegen sie still
        if (pausedHint != null) {
            Text(
                pausedHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        alarms.forEach { alarm ->
            // «fällt um ≥ 100 %» löst nie aus — kennzeichnen, nicht einschalten (ausschalten geht)
            val invalid = PortfolioAlarmLogic.exceedsFall(alarm.kind, alarm.threshold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .toggleable(
                            value = alarm.enabled, enabled = !invalid || alarm.enabled, role = Role.Switch,
                            onValueChange = { onToggle(alarm.id, it) },
                        )
                        .padding(start = 16.dp, top = Spacing.sm, bottom = Spacing.sm, end = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            sentence(alarm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            // Kein Editor für Portfolio-Alarme – löschen und neu anlegen
                            if (invalid) stringResource(R.string.portfolio_alarm_threshold_invalid_recreate)
                            else stringResource(if (alarm.repeating) R.string.alarm_repeating else R.string.alarm_once),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = alarm.enabled, onCheckedChange = null, enabled = !invalid || alarm.enabled)
                }
                IconButton(onClick = { onDelete(alarm) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Box(modifier = Modifier.padding(bottom = Spacing.xs))
    }
}
