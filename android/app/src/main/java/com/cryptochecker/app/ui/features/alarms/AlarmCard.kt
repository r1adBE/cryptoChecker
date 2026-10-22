// Karte eines Alarms in der Alarm-Liste eines Paars: Bedingung, Schalter, Bearbeiten/Löschen, Statuszeile
// ([AlarmCardStatus]: Mitteilungen aus, geringe Liquidität, wartet auf Kurs, nicht geprüft).
// Eigene Datei, weil die Karte eine eigenständige Ansicht ist; `AlarmsScreen` regelt Liste, Rückgängig und
// Meldungen.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.AlarmJson
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.domain.alarm.AlarmCardStatus
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.openAppNotificationSettings
import com.cryptochecker.app.util.MomentText

@Composable
internal fun AlarmCard(
    alarm: AlarmEntity,
    baseAsset: String,
    quoteAsset: String,
    /** Zuletzt nicht geprüft (kein Devisenkurs/keine Kerzen); null = geprüft. Liefert den Text zu [AlarmCardStatus.Kind.UNCHECKED]. */
    unchecked: AlarmCheckStatus.Unchecked? = null,
    /** Statuszeile ([AlarmCardStatus]); null = keine. */
    status: AlarmCardStatus.Kind? = null,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // Nur bei eingeschalteten Alarmen (das stellt AlarmCardStatus sicher): ausgeschaltete werden nicht geprüft
    val checkLine: Pair<Int, Int>? = when (status) {
        null -> null
        AlarmCardStatus.Kind.NOTIFICATIONS_OFF -> R.string.alarm_status_notifications_off to R.drawable.ic_notifications_off
        AlarmCardStatus.Kind.LOW_LIQUIDITY -> R.string.alarm_status_low_liquidity to R.drawable.ic_warning
        AlarmCardStatus.Kind.WAITING_FOR_PRICE -> R.string.alarm_status_waiting_price to R.drawable.ic_settings_updates
        AlarmCardStatus.Kind.UNCHECKED -> unchecked?.let {
            when (it.reason) {
                AlarmCheckStatus.Reason.NO_RATE -> R.string.alarm_unchecked_no_rate
                AlarmCheckStatus.Reason.NO_CANDLES -> R.string.alarm_unchecked_no_candles
                // Funding/Open Interest ohne Werte: vorhandener Text «Keine Futures-Daten …»
                AlarmCheckStatus.Reason.NO_DATA -> R.string.futures_no_data
            } to R.drawable.ic_error
        }
    }
    // Seit Build 104: «fällt um ≥ 100 %» (aus Build 102) löst nie aus — vor allen anderen Gründen, auch ausgeschaltet
    val invalidThreshold = AlarmJson.exceedsFall(alarm.condition, alarm.threshold)
    val statusLine: Pair<Int, Int>? =
        if (invalidThreshold) R.string.alarm_threshold_invalid_fix to R.drawable.ic_warning else checkLine
    val statusText = statusLine?.let { stringResource(it.first) }
    val title = AlarmTexts.describe(context, alarm) +
        if (alarm.condition.isPriceThreshold && alarm.convertCurrency == null) " $quoteAsset" else ""
    val sentence = if (baseAsset.isEmpty()) null else alarmSentence(
        context = context,
        condition = alarm.condition,
        symbol = baseAsset,
        threshold = alarm.threshold,
        currency = alarm.convertCurrency ?: quoteAsset,
        windowHours = alarm.windowHours,
    )
    // TalkBack: eine Beschreibung mit allen Angaben (inkl. Ton, Ansage, Wiederholung); die
    // sichtbaren Texte unten bleiben dafür stumm, Menü und Schalter sind eigene Elemente
    val description = alarmA11yDescription(context, alarm, title, sentence) +
        (statusText?.let { ". $it" } ?: "")
    val editLabel = stringResource(R.string.action_edit)

    // Ganze Karte antippen = bearbeiten
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTestTags.ALARM_CARD)
            .clickable(onClickLabel = editLabel, onClick = onEdit)
            .semantics { contentDescription = description }
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp, end = 4.dp)) {
            // Titel volle Breite, Menü nur mit «Löschen» (Bearbeiten = Karte antippen)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {}
                )
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.action_more)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
            // Der Alarm als Satz; «einmalig · zuletzt …» bleibt darunter
            if (sentence != null) {
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp).clearAndSetSemantics {}
                )
            }
            // Statuszeile, bis der Grund wegfällt. Vorgelesen über die Beschreibung der Karte; nur «Mitteilungen
            // aus» ist antippbar (öffnet die Mitteilungs-Einstellungen) und dafür ein eigenes Element.
            if (statusLine != null && statusText != null) {
                val notificationsOff = !invalidThreshold && status == AlarmCardStatus.Kind.NOTIFICATIONS_OFF
                val turnOnLabel = stringResource(R.string.alarms_notifications_turn_on)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 2.dp, end = 12.dp)
                        .then(
                            if (notificationsOff) {
                                // Semantik ausdrücklich (Text + Aktion), die Geste übernimmt clickable
                                Modifier
                                    .clearAndSetSemantics {
                                        contentDescription = statusText
                                        role = Role.Button
                                        onClick(label = turnOnLabel) { openAppNotificationSettings(context); true }
                                    }
                                    .clickable { openAppNotificationSettings(context) }
                            } else {
                                Modifier.clearAndSetSemantics {}
                            }
                        )
                ) {
                    val lineColor = if (invalidThreshold) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(
                        painterResource(statusLine.second),
                        contentDescription = null,
                        tint = lineColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = lineColor,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                if (alarm.repeating) R.string.alarm_repeating
                                else R.string.alarm_once
                            )
                        )
                        if (alarm.lastTriggeredAt > 0) {
                            append(" · ")
                            append(
                                stringResource(
                                    R.string.alarm_last_triggered,
                                    // Heute nur die Uhrzeit, sonst mit Datum («zuletzt 12.10.26, 14:02»)
                                    MomentText.moment(alarm.lastTriggeredAt)
                                )
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {}
                )
                // Schalter mit Beschriftung: welcher Alarm geschaltet wird
                // Seit Build 105 (FUN3-02): «Schwelle ungültig» lässt sich nicht einschalten (erst im Editor anpassen)
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                    enabled = AlarmJson.toggleAllowed(alarm.condition, alarm.threshold, enable = !alarm.enabled),
                    modifier = Modifier.semantics { contentDescription = title }
                )
            }
        }
    }
}
