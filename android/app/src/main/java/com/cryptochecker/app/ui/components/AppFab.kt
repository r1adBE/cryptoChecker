package com.cryptochecker.app.ui.components

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.theme.AppColors

/**
 * Der eine «+»-Knopf unten rechts (Portfolio, Coin-Seite, Alarme): immer gefüllt in der
 * Akzentfarbe mit [AppColors.onAccent], 56 dp, Ecken 16 dp (`shapes.medium`). Ohne feste Farben
 * nähme der FAB `primaryContainer` — hell ein blasses Pfirsich, dunkel (dort = `primary`) kräftig
 * —, also dieselbe Aktion in zwei Farben. Wie iOS `PortfolioAddButton`.
 */
@Composable
fun AppFab(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = AppColors.onAccent,
        modifier = modifier,
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = contentDescription)
    }
}
