package com.cryptochecker.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Text-Slot für AlertDialogs mit mehr als einem Satz: scrollt senkrecht, damit
 * bei grosser Schrift oder kleinem Bildschirm nichts abgeschnitten wird und die
 * Schaltflächen erreichbar bleiben. Kurze einzeilige Texte brauchen ihn nicht.
 * Der Quelltext-Wächter `DialogScrollGuardTest` prüft die Verwendung.
 */
@Composable
fun DialogText(
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
