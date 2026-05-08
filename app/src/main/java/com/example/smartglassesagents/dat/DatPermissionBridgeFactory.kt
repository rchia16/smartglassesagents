package com.example.smartglassesagents.dat

import androidx.activity.ComponentActivity

fun createDatPermissionBridge(activity: ComponentActivity): DatPermissionBridge {
    return runCatching {
        val bridgeClass = Class.forName("com.example.smartglassesagents.dat.RealDatPermissionBridge")
        val constructor = bridgeClass.getConstructor(ComponentActivity::class.java)
        constructor.newInstance(activity) as DatPermissionBridge
    }.getOrElse {
        NoOpDatPermissionBridge()
    }
}

