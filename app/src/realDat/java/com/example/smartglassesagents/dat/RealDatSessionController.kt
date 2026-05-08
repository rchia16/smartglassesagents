package com.example.smartglassesagents.dat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RealDatSessionController(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val permissionBridge: DatPermissionBridge,
) : DatSessionController {
    private val _state = MutableStateFlow(
        DatDeviceState(
            adapterName = "Meta Wearables DAT adapter",
            supportsRealSdk = true,
        )
    )
    override val state: StateFlow<DatDeviceState> = _state

    private val deviceSelector = AutoDeviceSelector()
    private var monitoringStarted = false
    private var activeDevice: DeviceIdentifier? = null
    private var streamSession: StreamSession? = null
    private var latestFrame: Bitmap? = null
    private var registrationJob: Job? = null
    private var deviceJob: Job? = null
    private var activeDeviceJob: Job? = null
    private var videoJob: Job? = null

    override fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true
        runCatching { Wearables.initialize(context) }
            .onFailure { error ->
                setError("DAT initialization failed: ${error.message ?: error::class.java.simpleName}")
                return
            }

        registrationJob = coroutineScope.launch {
            Wearables.registrationState.collect { registrationState ->
                _state.update {
                    it.copy(
                        registrationStatus = registrationState.toDatRegistrationStatus(),
                        recentError = null,
                    )
                }
            }
        }
        deviceJob = coroutineScope.launch {
            Wearables.devices.collect { devices ->
                _state.update {
                    it.copy(
                        devices = devices.map { device -> device.toDatDevice() },
                        recentError = null,
                    )
                }
            }
        }
        activeDeviceJob = coroutineScope.launch {
            deviceSelector.activeDevice(Wearables.devices).collect { device ->
                activeDevice = device
                _state.update { it.copy(activeDeviceId = device?.toString(), recentError = null) }
            }
        }
    }

    override fun startRegistration() {
        runCatching { Wearables.startRegistration(context) }
            .onFailure { error -> setError("DAT registration failed: ${error.message ?: error::class.java.simpleName}") }
    }

    override fun unregister() {
        stopSession()
        runCatching { Wearables.startUnregistration(context) }
            .onFailure { error -> setError("DAT unregistration failed: ${error.message ?: error::class.java.simpleName}") }
    }

    override fun discoverMockDevice() {
        _state.update {
            it.copy(
                sessionStatus = DatSessionStatus.Discovering,
                recentError = "Real DAT discovers paired Meta AI glasses automatically. Pair glasses in the Meta AI app, then wait for this list to update.",
            )
        }
    }

    override fun requestCameraPermission() {
        coroutineScope.launch {
            val existing = checkCameraPermission()
            if (existing == DatPermissionStatus.Granted) {
                _state.update { it.copy(cameraPermissionStatus = DatPermissionStatus.Granted, recentError = null) }
                return@launch
            }
            permissionBridge.requestCameraPermission { granted ->
                _state.update {
                    it.copy(
                        cameraPermissionStatus = if (granted) DatPermissionStatus.Granted else DatPermissionStatus.Denied,
                        recentError = if (granted) null else "DAT camera permission was denied.",
                    )
                }
            }
        }
    }

    override fun startSession() {
        val current = _state.value
        when {
            current.registrationStatus != DatRegistrationStatus.Registered -> {
                setError("Register the app with Meta Wearables DAT before starting a session.")
            }
            activeDevice == null -> {
                setError("Pair and connect Ray-Ban Meta glasses before starting a DAT session.")
            }
            current.cameraPermissionStatus != DatPermissionStatus.Granted -> {
                setError("Grant DAT camera permission before starting a session.")
            }
            else -> startStreamSession()
        }
    }

    override fun stopSession() {
        videoJob?.cancel()
        videoJob = null
        runCatching { streamSession?.close() }
        streamSession = null
        latestFrame = null
        _state.update { it.copy(sessionStatus = DatSessionStatus.Stopped, recentError = null) }
    }

    override fun captureFrame(): Bitmap? {
        val session = streamSession
        if (session == null || _state.value.sessionStatus != DatSessionStatus.Running) {
            setError("Start a DAT stream session before capturing a frame.")
            return latestFrame
        }

        coroutineScope.launch {
            session.capturePhoto()
                .onSuccess { photoData ->
                    latestFrame = photoData.toBitmap()
                }
                .onFailure {
                    setError("DAT photo capture failed.")
                }
        }
        return latestFrame
    }

    private fun startStreamSession() {
        val session = runCatching {
            Wearables.startStreamSession(
                context,
                deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
            )
        }.getOrElse { error ->
            setError("DAT stream session failed: ${error.message ?: error::class.java.simpleName}")
            return
        }

        streamSession = session
        _state.update { it.copy(sessionStatus = DatSessionStatus.Running, recentError = null) }
        videoJob?.cancel()
        videoJob = coroutineScope.launch {
            session.videoStream.collect { frame ->
                latestFrame = frame.toBitmap()
            }
        }
    }

    private suspend fun checkCameraPermission(): DatPermissionStatus {
        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        return when (result.getOrNull()) {
            PermissionStatus.Granted -> DatPermissionStatus.Granted
            PermissionStatus.Denied -> DatPermissionStatus.Denied
            else -> DatPermissionStatus.Unknown
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(sessionStatus = DatSessionStatus.Error, recentError = message) }
    }

    private fun DeviceIdentifier.toDatDevice(): DatDevice {
        val metadata = Wearables.devicesMetadata[this]?.value
        val name = metadata?.name?.ifBlank { toString() } ?: toString()
        val compatibility = when (metadata?.compatibility) {
            DeviceCompatibility.DEVICE_UPDATE_REQUIRED -> DatDeviceCompatibility.UpdateRequired
            null -> DatDeviceCompatibility.Unknown
            else -> DatDeviceCompatibility.Compatible
        }
        return DatDevice(
            id = toString(),
            name = name,
            kind = DatDeviceKind.RayBanMeta,
            compatibility = compatibility,
        )
    }

    private fun RegistrationState.toDatRegistrationStatus(): DatRegistrationStatus =
        when (this) {
            is RegistrationState.Registered -> DatRegistrationStatus.Registered
            is RegistrationState.Registering -> DatRegistrationStatus.Registering
            is RegistrationState.Unavailable -> DatRegistrationStatus.Unavailable
            else -> DatRegistrationStatus.NotRegistered
        }

    private fun VideoFrame.toBitmap(): Bitmap {
        val originalPosition = buffer.position()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        buffer.position(originalPosition)
        val nv21 = convertI420ToNv21(bytes, width, height)
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val jpeg = ByteArrayOutputStream().use { output ->
            image.compressToJpeg(Rect(0, 0, width, height), 70, output)
            output.toByteArray()
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    private fun PhotoData.toBitmap(): Bitmap =
        when (this) {
            is PhotoData.Bitmap -> bitmap
            is PhotoData.HEIC -> {
                val originalPosition = data.position()
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                data.position(originalPosition)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                applyExifTransform(bitmap, bytes)
            }
        }

    private fun applyExifTransform(bitmap: Bitmap, imageBytes: ByteArray): Bitmap {
        val matrix = Matrix()
        val exif = runCatching { ExifInterface(ByteArrayInputStream(imageBytes)) }.getOrNull()
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
        }
        return if (matrix.isIdentity) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    private fun convertI420ToNv21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4
        input.copyInto(output, 0, 0, size)
        for (index in 0 until quarter) {
            output[size + index * 2] = input[size + quarter + index]
            output[size + index * 2 + 1] = input[size + index]
        }
        return output
    }
}
