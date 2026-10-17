// Karte eines Alarms in der Alarm-Liste eines Paars: Bedingung, Schalter, Bearbeiten/Löschen, Hinweis «nicht geprüft».
// Eigene Datei, weil die Karte eine eigenständige Ansicht ist; `AlarmsScreen` regelt Liste, Rückgängig und
// Meldungen.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.local.model.convertCurrency
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.notification.AlarmTexts
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.util.PriceFormat

@Composable
internal fun AlarmCard(
    alarm: AlarmEntity,
    baseAsset: String,
    quoteAsset: String,
    /** Zuletzt nicht geprüft (kein Devisenkurs/keine Kerzen); null = geprüft. */
    unchecked: AlarmCheckStatus.Unchecked? = null,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // Nur bei eingeschalteten Alarmen: ausgeschaltete werden ohnehin nicht geprüft
    val uncheckedText = unchecked?.takeIf { alarm.enabled }?.let {
        stringResource(
            when (it.reason) {
                AlarmCheckStatus.Reason.NO_RATE -> R.string.alarm_unchecked_no_rate
                AlarmCheckStatus.Reason.NO_CANDLES -> R.string.alarm_unchecked_no_candles
            }
        )
    }
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
        (uncheckedText?.let { ". $it" } ?: "")
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
            // «Konnte nicht geprüft werden (kein Devisenkurs)» — bis die nächste Prüfung gelingt
            if (uncheckedText != null) {
                Text(
                    text = uncheckedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp, end = 12.dp).clearAndSetSemantics {}
                )
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
                                    PriceFormat.time(alarm.lastTriggeredAt)
                                )
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).clearAndSetSemantics {}
                )
                // Schalter mit Beschriftung: welcher Alarm geschaltet wird
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = title }
                )
            }
        }
    }
}
