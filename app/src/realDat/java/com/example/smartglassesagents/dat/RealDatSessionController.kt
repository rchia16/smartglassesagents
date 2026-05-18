package com.example.smartglassesagents.dat

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val selectedDeviceFlow = MutableStateFlow<DeviceIdentifier?>(null)
    private val deviceSelector = object : DeviceSelector {
        override fun activeDevice(): DeviceIdentifier? = activeDevice
        override fun activeDeviceFlow(): Flow<DeviceIdentifier?> =
            selectedDeviceFlow.asStateFlow()
    }
    private var monitoringStarted = false
    private var activeDevice: DeviceIdentifier? = null
    private var coreSession: DeviceSession? = null
    private var stream: Stream? = null
    private var latestFrame: Bitmap? = null
    private var registrationJob: Job? = null
    private var deviceJob: Job? = null
    private var deviceSessionJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamErrorJob: Job? = null
    private var stateJob: Job? = null
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
                val selectedDevice = activeDevice?.takeIf { it in devices } ?: devices.firstOrNull()
                activeDevice = selectedDevice
                selectedDeviceFlow.value = selectedDevice
                _state.update {
                    it.copy(
                        devices = devices.map { device -> device.toDatDevice() },
                        deviceCount = devices.size,
                        activeDeviceId = selectedDevice?.toString(),
                        recentError = null,
                    )
                }
            }
        }
    }

    override fun startRegistration() {
        val activity = context as? Activity
        if (activity == null) {
            setError("DAT registration requires an Activity context.")
            return
        }
        runCatching { Wearables.startRegistration(activity) }
            .onFailure { error -> setError("DAT registration failed: ${error.message ?: error::class.java.simpleName}") }
    }

    override fun unregister() {
        stopSession()
        val activity = context as? Activity
        if (activity == null) {
            setError("DAT unregistration requires an Activity context.")
            return
        }
        runCatching { Wearables.startUnregistration(activity) }
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
        streamErrorJob?.cancel()
        streamErrorJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
        deviceSessionJob?.cancel()
        deviceSessionJob = null
        stateJob?.cancel()
        stateJob = null
        videoJob?.cancel()
        videoJob = null
        runCatching { coreSession?.stop() }
        coreSession = null
        stream = null
        latestFrame = null
        _state.update {
            it.copy(
                sessionStatus = DatSessionStatus.Stopped,
                deviceSessionState = "Stopped",
                streamState = "Stopped",
                streamError = null,
                recentError = null,
            )
        }
    }

    override fun captureFrame(): Bitmap? {
        val currentStream = stream
        if (currentStream == null || _state.value.sessionStatus != DatSessionStatus.Running) {
            setError("Start a DAT stream session before capturing a frame.")
            return latestFrame
        }
        if (currentStream.state.value != StreamState.STREAMING) {
            setError("DAT stream is ${currentStream.state.value}; wait until it is streaming before capturing.")
            return latestFrame
        }

        coroutineScope.launch {
            currentStream.capturePhoto()
                .onSuccess { photoData ->
                    latestFrame = photoData.toBitmap()
                }
                .onFailure { error ->
                    val detail = error.message ?: error::class.java.simpleName
                    setError("DAT photo capture failed: $detail. Using latest stream frame if available.")
                }
        }
        return latestFrame
    }

    private fun startStreamSession() {
        coroutineScope.launch {
            val sessionResult = Wearables.createSession(deviceSelector)
            val session = sessionResult.getOrNull()
            if (session == null) {
                setError("DAT session creation failed: ${sessionResult.failureDescription()}.")
                return@launch
            }

            sessionErrorJob?.cancel()
            sessionErrorJob = coroutineScope.launch {
                session.errors.collect { error ->
                    setError("DAT session error: ${error.descriptionOrType()}")
                }
            }

            val streamResult = session.addStream(
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
            )
            val createdStream = streamResult.getOrNull()
            if (createdStream == null) {
                setError("DAT stream creation failed: ${streamResult.failureDescription()}.")
                session.stop()
                return@launch
            }
            coreSession = session
            stream = createdStream
            _state.update { it.copy(sessionStatus = DatSessionStatus.Paused, recentError = "DAT stream is starting.") }

            deviceSessionJob?.cancel()
            deviceSessionJob = coroutineScope.launch {
                session.state.collect { deviceState ->
                    _state.update { it.copy(deviceSessionState = deviceState.name) }
                }
            }
            stateJob?.cancel()
            stateJob = coroutineScope.launch {
                createdStream.state.collect { streamState ->
                    _state.update {
                        when (streamState) {
                            StreamState.STREAMING -> it.copy(
                                sessionStatus = DatSessionStatus.Running,
                                streamState = streamState.name,
                                recentError = null,
                            )
                            StreamState.STOPPED,
                            StreamState.CLOSED -> it.copy(
                                sessionStatus = DatSessionStatus.Stopped,
                                streamState = streamState.name,
                                recentError = null,
                            )
                            else -> it.copy(
                                sessionStatus = DatSessionStatus.Paused,
                                streamState = streamState.name,
                                recentError = "DAT stream is $streamState.",
                            )
                        }
                    }
                }
            }
            streamErrorJob?.cancel()
            streamErrorJob = coroutineScope.launch {
                createdStream.errorStream.collect { error ->
                    _state.update {
                        it.copy(
                            sessionStatus = DatSessionStatus.Error,
                            streamError = error.descriptionOrType(),
                            recentError = "DAT stream error: ${error.descriptionOrType()}",
                        )
                    }
                }
            }
            videoJob?.cancel()
            videoJob = coroutineScope.launch {
                createdStream.videoStream.collect { frame: VideoFrame ->
                    latestFrame = frame.toBitmap()
                }
            }

            val startResult = createdStream.start()
            startResult.onFailure { error ->
                setError("DAT stream start failed: ${error.message ?: error::class.java.simpleName}")
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

    private fun Any?.descriptionOrType(): String =
        when (this) {
            null -> "Unknown"
            else -> runCatching {
                val description = this::class.java.getMethod("getDescription").invoke(this) as? String
                description?.ifBlank { null } ?: this::class.java.simpleName
            }.getOrDefault(this::class.java.simpleName)
        }

    private fun Any?.failureDescription(): String =
        runCatching {
            val failure = this?.javaClass?.getMethod("failureOrNull")?.invoke(this)
            failure.descriptionOrType()
        }.getOrDefault("Unknown")

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
            RegistrationState.REGISTERED -> DatRegistrationStatus.Registered
            RegistrationState.REGISTERING -> DatRegistrationStatus.Registering
            RegistrationState.UNAVAILABLE -> DatRegistrationStatus.Unavailable
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
