package com.cryptochecker.app.ui.features.loading

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.cryptochecker.app.ui.theme.CryptoCheckerTheme

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
/*
    var progress by remember { mutableStateOf(0.1f) }
    val animatedProgress = animateFloatAsState(
        targetValue = progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
    ).value
*/
    // Platzhalter-Zeilen statt nur eines Kreisels
    Surface(modifier = modifier.fillMaxSize()) {
        com.cryptochecker.app.ui.components.SkeletonList()
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LoadingScreenPreview() {
    CryptoCheckerTheme {
        LoadingScreen()
    }
}
