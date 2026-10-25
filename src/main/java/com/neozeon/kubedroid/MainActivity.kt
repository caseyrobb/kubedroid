package com.neozeon.kubedroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.neozeon.kubedroid.navigation.KubedroidApp
import com.neozeon.kubedroid.ui.theme.KubedroidTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KubedroidTheme {
                KubedroidApp()
            }
        }
    }
}
