package com.cryptochecker.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.resources.R

/**
 * «+» in der Kopfzeile (Portfolio, Seite eines Coins): gefüllter Kreis in der Themenfarbe wie «+» der Merkliste,
 * 34 dp sichtbar, Tippfläche mindestens 48 dp. Steht oben statt schwebend unten, damit er nie Beträge verdeckt.
 * Wie iOS `HeaderAddButton`.
 */
@Composable
fun HeaderAddButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Druck-Rückmeldung nur auf dem sichtbaren Kreis, nicht auf der grösseren Tippfläche
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .wrapContentSize()
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .indication(interaction, LocalIndication.current),
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = label, tint = AppColors.onAccent, modifier = Modifier.size(20.dp))
    }
}
