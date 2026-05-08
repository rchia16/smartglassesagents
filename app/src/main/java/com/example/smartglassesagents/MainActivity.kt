package com.example.smartglassesagents

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.smartglassesagents.dat.createDatPermissionBridge
import com.example.smartglassesagents.ui.ExperimentApp
import com.example.smartglassesagents.ui.theme.SmartGlassesAgentsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val datPermissionBridge = createDatPermissionBridge(this)
        setContent {
            SmartGlassesAgentsTheme {
                ExperimentApp(datPermissionBridge = datPermissionBridge)
            }
        }
    }
}
