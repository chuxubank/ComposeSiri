package com.jetbrains.kmpapp

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun App() {
    var state by remember { mutableStateOf(SiriOrbState.Listening) }
    MaterialTheme(
        colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black)
    ) {
        Surface(color = Color.Black) {
            SiriOrbScreen(
                modifier = Modifier.clickable {
                    state = state.nextState()
                },
                state = state,
            )
        }
    }
}

private fun SiriOrbState.nextState(): SiriOrbState = when (this) {
    SiriOrbState.Listening -> SiriOrbState.Thinking
    SiriOrbState.Thinking -> SiriOrbState.Speaking
    SiriOrbState.Speaking -> SiriOrbState.Idle
    SiriOrbState.Idle -> SiriOrbState.Error
    SiriOrbState.Error -> SiriOrbState.Listening
}
