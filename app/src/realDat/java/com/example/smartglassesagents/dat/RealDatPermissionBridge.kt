package com.example.smartglassesagents.dat

import androidx.activity.ComponentActivity
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

class RealDatPermissionBridge(activity: ComponentActivity) : DatPermissionBridge {
    private var pendingResult: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        pendingResult?.invoke(permissionStatus == PermissionStatus.Granted)
        pendingResult = null
    }

    override fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
        pendingResult = onResult
        launcher.launch(Permission.CAMERA)
    }
}

