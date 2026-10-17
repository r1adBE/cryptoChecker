package com.cryptochecker.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.Spacing

/**
 * Leerer Zustand, überall gleich aufgebaut (wie iOS `EmptyStateView`): Symbol in einem getönten
 * Kreis → Titel → Text → Beispiel → Hauptknopf → [secondary] (Textknöpfe, Hinweise). Der Inhalt
 * scrollt, damit bei grosser Schrift nichts abgeschnitten wird, und steht mittig, solange er passt;
 * höchstens [ReadableMaxWidth] breit.
 *
 * Kreis hell `primaryContainer` (halb), dunkel [AppColors.accentTint] — ein halb durchsichtiger
 * Akzent-Container wirkt auf dem dunklen Grund bräunlich.
 */
@Composable
fun EmptyState(
    @DrawableRes iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    example: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    @DrawableRes actionIconRes: Int? = null,
    secondary: @Composable ColumnScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        // Mittig, solange der Inhalt passt; sonst ab oben scrollen
        val fillHeight = if (constraints.hasBoundedHeight) Modifier.heightIn(min = maxHeight) else Modifier
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .then(fillHeight)
                .padding(Spacing.xxl)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = ReadableMaxWidth)
            ) {
                val circle = if (LocalDarkTheme.current) AppColors.accentTint(0.3f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                Icon(
                    painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(circle, CircleShape)
                        .padding(Spacing.xl)
                        .size(44.dp)
                )
                // titleLarge halbfett ≈ iOS title3 semibold
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.md).semantics { heading() }
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                if (example != null) {
                    Text(
                        example,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction, modifier = Modifier.padding(top = Spacing.lg)) {
                        if (actionIconRes != null) {
                            Icon(painterResource(actionIconRes), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            actionLabel,
                            modifier = if (actionIconRes != null) Modifier.padding(start = Spacing.sm) else Modifier
                        )
                    }
                }
                secondary()
            }
        }
    }
}
