package com.example.smartglassesagents.dat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MockDatSessionController : DatSessionController {
    private val _state = MutableStateFlow(DatDeviceState())
    override val state: StateFlow<DatDeviceState> = _state

    override fun startMonitoring() {
        _state.update {
            it.copy(
                adapterName = "Mock DAT adapter",
                recentError = null
            )
        }
    }

    override fun startRegistration() {
        _state.update {
            it.copy(
                registrationStatus = DatRegistrationStatus.Registered,
                recentError = null
            )
        }
    }

    override fun unregister() {
        _state.value = DatDeviceState()
    }

    override fun discoverMockDevice() {
        val device = DatDevice(
            id = "mock-rayban-meta-001",
            name = "Mock Ray-Ban Meta",
            kind = DatDeviceKind.MockRayBanMeta
        )
        _state.update {
            it.copy(
                devices = listOf(device),
                activeDeviceId = device.id,
                sessionStatus = DatSessionStatus.Stopped,
                recentError = null
            )
        }
    }

    override fun requestCameraPermission() {
        _state.update {
            it.copy(cameraPermissionStatus = DatPermissionStatus.Granted, recentError = null)
        }
    }

    override fun startSession() {
        val current = _state.value
        when {
            current.registrationStatus != DatRegistrationStatus.Registered -> {
                setError("Register the app with DAT before starting a session.")
            }
            current.activeDevice == null -> {
                setError("Discover or pair a Ray-Ban Meta device before starting a session.")
            }
            current.cameraPermissionStatus != DatPermissionStatus.Granted -> {
                setError("Grant DAT camera permission before starting a session.")
            }
            else -> {
                _state.update { it.copy(sessionStatus = DatSessionStatus.Running, recentError = null) }
            }
        }
    }

    override fun stopSession() {
        _state.update { it.copy(sessionStatus = DatSessionStatus.Stopped, recentError = null) }
    }

    override fun captureFrame(): Bitmap? {
        if (!_state.value.isReadyForCapture) {
            setError("Start a DAT session before capturing a frame.")
            return null
        }
        return createMockFrame()
    }

    private fun setError(message: String) {
        _state.update { it.copy(sessionStatus = DatSessionStatus.Error, recentError = message) }
    }

    private fun createMockFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        canvas.drawColor(Color.rgb(242, 244, 247))

        paint.color = Color.rgb(35, 47, 62)
        paint.textSize = 42f
        paint.isFakeBoldText = true
        canvas.drawText("MOCK RAY-BAN META FRAME", 44f, 78f, paint)

        paint.isFakeBoldText = false
        paint.textSize = 30f
        canvas.drawText("Captured by DAT session mock at $now", 44f, 122f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 255, 255)
        canvas.drawRoundRect(60f, 170f, 900f, 330f, 12f, 12f, paint)
        paint.color = Color.rgb(20, 93, 160)
        paint.textSize = 36f
        paint.isFakeBoldText = true
        canvas.drawText("BOARD: SAFETY CHECKLIST TODAY", 92f, 238f, paint)
        paint.textSize = 30f
        paint.isFakeBoldText = false
        canvas.drawText("Fine-detail OCR target", 92f, 286f, paint)

        paint.color = Color.rgb(181, 136, 99)
        canvas.drawRoundRect(90f, 410f, 870f, 650f, 20f, 20f, paint)
        paint.color = Color.rgb(219, 68, 55)
        canvas.drawCircle(210f, 520f, 42f, paint)
        paint.color = Color.rgb(15, 157, 88)
        canvas.drawRect(430f, 475f, 515f, 560f, paint)
        paint.color = Color.rgb(66, 133, 244)
        canvas.drawRoundRect(675f, 500f, 795f, 575f, 12f, 12f, paint)

        paint.color = Color.rgb(35, 47, 62)
        paint.textSize = 26f
        canvas.drawText("red circle: front left", 140f, 612f, paint)
        canvas.drawText("green square: center", 386f, 612f, paint)
        canvas.drawText("blue item: right", 654f, 612f, paint)

        return bitmap
    }
}
