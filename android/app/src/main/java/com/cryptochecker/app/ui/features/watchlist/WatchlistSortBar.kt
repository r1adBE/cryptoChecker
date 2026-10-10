package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.ColumnSort
import com.cryptochecker.app.domain.watch.SortKey
import com.cryptochecker.app.util.ChangeBasisText

/**
 * Schmale Zeile über den Paaren: «Name ⇅ · Kurs ⇅ · 24h ⇅» wie an der Börse. Erster Tipp: Name
 * A–Z bzw. Kurs und 24h gross zuerst; zweiter Tipp: umgekehrt; dritter: eigene Reihenfolge
 * ([ColumnSort.next]). Die aktive Spalte steht in der Themenfarbe, ihr Pfeil ist hervorgehoben.
 * Die 24h-Spalte heisst wie die Pillen («24h», «heute» …). Wie `WatchlistSortBar` (iOS).
 */
@Composable
internal fun WatchlistSortBar(
    sort: ColumnSort?,
    onTap: (SortKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val changeLabel = ChangeBasisText.shortLabel(LocalChangeView.current.basis)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        SortColumn(stringResource(R.string.watchlist_sort_name), SortKey.NAME, sort, onTap)
        Row(Modifier.weight(1f)) {}
        SortColumn(stringResource(R.string.watchlist_sort_price), SortKey.PRICE, sort, onTap)
        SortColumn(changeLabel, SortKey.CHANGE, sort, onTap)
    }
}

@Composable
private fun SortColumn(label: String, key: SortKey, sort: ColumnSort?, onTap: (SortKey) -> Unit) {
    val active = sort?.key == key
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // Nur die aktive Spalte hat eine Richtung (dann ist sort nicht null)
    val descending = sort?.takeIf { it.key == key }?.descending
    val state = when (descending) {
        null -> stringResource(R.string.watchlist_sort_own)
        true -> stringResource(R.string.watchlist_sort_descending)
        false -> stringResource(R.string.watchlist_sort_ascending)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = { onTap(key) })
            .semantics { stateDescription = state }
            .padding(horizontal = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1
        )
        // ▲ über ▼: der aktive Pfeil deutlich, der andere blass
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 3.dp)) {
            Arrow("▲", descending == false, color)
            Arrow("▼", descending == true, color)
        }
    }
}

@Composable
private fun Arrow(symbol: String, on: Boolean, color: Color) {
    Text(
        text = symbol,
        fontSize = 7.sp,
        lineHeight = 8.sp,
        color = color.copy(alpha = if (on) 1f else 0.35f)
    )
}
