package com.example.smartglassesagents.dat

import android.content.Context
import kotlinx.coroutines.CoroutineScope

interface DatPermissionBridge {
    fun requestCameraPermission(onResult: (granted: Boolean) -> Unit)
}

class NoOpDatPermissionBridge : DatPermissionBridge {
    override fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
        onResult(false)
    }
}

fun createDatSessionController(
    context: Context,
    coroutineScope: CoroutineScope,
    permissionBridge: DatPermissionBridge,
): DatSessionController {
    return runCatching {
        val controllerClass = Class.forName("com.example.smartglassesagents.dat.RealDatSessionController")
        val constructor = controllerClass.getConstructor(
            Context::class.java,
            CoroutineScope::class.java,
            DatPermissionBridge::class.java,
        )
        constructor.newInstance(context.applicationContext, coroutineScope, permissionBridge) as DatSessionController
    }.getOrElse {
        MockDatSessionController()
    }
}

