package com.framestack.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.framestack.android.ui.MainScreen
import com.framestack.android.ui.PlayerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF59E0B),
                    background = Color(0xFF09090B),
                    surface = Color(0xFF111114),
                    onPrimary = Color(0xFF09090B),
                    onBackground = Color(0xFFF4F4F5),
                    onSurface = Color(0xFFF4F4F5)
                )
            ) {
                val vm: PlayerViewModel = viewModel()
                MainScreen(viewModel = vm)
            }
        }
    }
}
