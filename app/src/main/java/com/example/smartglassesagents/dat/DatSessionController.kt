package com.example.smartglassesagents.dat

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

interface DatSessionController {
    val state: StateFlow<DatDeviceState>

    fun startMonitoring()
    fun startRegistration()
    fun unregister()
    fun discoverMockDevice()
    fun requestCameraPermission()
    fun startSession()
    fun stopSession()
    fun captureFrame(): Bitmap?
}
