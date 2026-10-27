package com.example.smartglassesagents.dat

import android.graphics.Bitmap
import com.example.smartglassesagents.capture.CapturedClip
import java.io.File
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
    suspend fun captureFrame(): Bitmap?
    suspend fun captureClip(cacheDir: File): CapturedClip?
}
