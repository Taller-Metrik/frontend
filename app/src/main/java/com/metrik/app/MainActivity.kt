package com.metrik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.metrik.app.ui.navigation.MetrikNavHost
import com.metrik.app.ui.theme.MetrikTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MetrikApp).container
        setContent {
            MetrikTheme {
                MetrikNavHost(container)
            }
        }
    }
}
