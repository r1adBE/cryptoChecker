package com.cryptochecker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R

/**
 * Kerzen | Linie als zwei Symbole in einer Pille: je Hälfte 48 × 48 dp (Trefferfläche), gewählte Hälfte im
 * Sekundär-Container; Screenreader: Optionsfeld «Kerzen»/«Linie» mit Auswahl-Zustand. Einziger
 * Schalter dafür — Chart im Aktionsblatt und Wertverlauf im Portfolio.
 */
@Composable
internal fun ChartTypeSwitch(line: Boolean, onLineChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .selectableGroup()
            .clip(CircleShape)
            .border(1.dp, outline, CircleShape)
    ) {
        ChartTypeSwitchSegment(
            icon = R.drawable.ic_chart_candles,
            label = stringResource(R.string.widget_chart_candles),
            selected = !line,
            onClick = { if (line) onLineChange(false) },
        )
        Box(Modifier.width(1.dp).height(48.dp).background(outline))
        ChartTypeSwitchSegment(
            icon = R.drawable.ic_chart_line,
            label = stringResource(R.string.widget_chart_line),
            selected = line,
            onClick = { if (!line) onLineChange(true) },
        )
    }
}

@Composable
private fun ChartTypeSwitchSegment(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}
