package com.jetbrains.kmpapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun App() {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)
    ) {
        Surface(color = Color.Black) {
            SiriOrbScreen()
        }
    }
}
