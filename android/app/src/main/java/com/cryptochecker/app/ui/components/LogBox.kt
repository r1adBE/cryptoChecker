package com.cryptochecker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Entwickleroption: HTTP-Protokoll als nur lesbares Feld mit Kopfzeile «Logs». */
@Composable
fun LogBox(logText: String) {
    val colors = MaterialTheme.colorScheme
    val boxModifier = Modifier.fillMaxWidth()
    Column {
        Text(
            text = "Logs",
            color = colors.onTertiary,
            style = MaterialTheme.typography.titleMedium,
            modifier = boxModifier.background(colors.tertiary).padding(4.dp),
        )
        // Markier- und kopierbar, aber nicht änderbar
        BasicTextField(
            value = logText,
            onValueChange = {},
            readOnly = true,
            singleLine = false,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onTertiaryContainer),
            modifier = boxModifier.background(colors.tertiaryContainer).padding(4.dp),
        )
    }
}

@Preview
@Composable
private fun LogBoxPreview() {
    LogBox(logText = "GET https://example.org\n200 OK")
}
