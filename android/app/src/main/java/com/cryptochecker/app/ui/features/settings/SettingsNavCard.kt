package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Column
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.AppCard
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Karte mit Navigationszeilen ohne Überschrift (wie «Wie aktuell?» auf der Seite «Alarme»); ausser der
 * ersten ([first]) mit 12 dp Abstand wie zwischen den Karten der übrigen Seiten.
 */
@Composable
internal fun NavCard(first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier = Modifier.padding(top = if (first) 0.dp else 12.dp), padding = CardPadding.None) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }
}
