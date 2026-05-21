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
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private var terminalStartupError: String? = null

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
        terminalStartupError = null
        val current = _state.value
        val device = activeDevice

        when {
            current.registrationStatus != DatRegistrationStatus.Registered -> {
                setError("Register the app with Meta Wearables DAT before starting a session.")
            }
            device == null -> {
                setError("Pair and connect Ray-Ban Meta glasses before starting a DAT session.")
            }
            activeDeviceRequiresUpdate() -> {
                setError("Ray-Ban Meta glasses need a firmware update before DAT camera can start.")
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
        terminalStartupError = null
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

    private fun stopSessionForRestart() {
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

        runCatching { stream?.stop() }
        runCatching { coreSession?.stop() }

        stream = null
        coreSession = null
        latestFrame = null
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
            stopSessionForRestart()
            terminalStartupError = null

            _state.update {
                it.copy(
                    sessionStatus = DatSessionStatus.Paused,
                    recentError = "Creating DAT device session.",
                    streamError = null,
                )
            }

            val sessionResult = Wearables.createSession(deviceSelector)
            val session = sessionResult.getOrNull()
            if (session == null) {
                setError("DAT session creation failed: ${sessionResult.failureDescription()}.")
                return@launch
            }

            coreSession = session

            val startupFailure = CompletableDeferred<StartupFailure>()

            sessionErrorJob?.cancel()
            sessionErrorJob = coroutineScope.launch {
                session.errors.collect { error ->
                    if (error == DeviceSessionError.SESSION_ENDED_BY_DEVICE && terminalStartupError != null) {
                        return@collect
                    }

                    val failure = error.toStartupFailure()
                    if (failure.isTerminal) {
                        terminalStartupError = failure.message
                    }
                    if (!startupFailure.isCompleted) {
                        startupFailure.complete(failure)
                    }
                    setError(failure.message)
                }
            }

            deviceSessionJob?.cancel()
            deviceSessionJob = coroutineScope.launch {
                session.state.collect { deviceState ->
                    _state.update { it.copy(deviceSessionState = deviceState.name) }
                }
            }

            _state.update {
                it.copy(
                    sessionStatus = DatSessionStatus.Paused,
                    recentError = "Starting DAT device session.",
                )
            }

            try {
                session.start()
            } catch (error: Throwable) {
                setError("DAT device session start failed: ${error.message ?: error::class.java.simpleName}.")
                cleanupFailedStart(session)
                return@launch
            }

            val startedState = withTimeoutOrNull(15_000) {
                while (true) {
                    if (startupFailure.isCompleted) return@withTimeoutOrNull false

                    when (session.state.value) {
                        DeviceSessionState.STARTED,
                        DeviceSessionState.PAUSED -> return@withTimeoutOrNull true

                        DeviceSessionState.STOPPED -> return@withTimeoutOrNull false
                        else -> delay(100)
                    }
                }
            }
            if (startedState != true) {
                val startupError = if (startupFailure.isCompleted) {
                    startupFailure.await().message
                } else {
                    null
                }
                setError(startupError ?: "DAT device session did not reach STARTED after start.")
                cleanupFailedStart(session)
                return@launch
            }

            _state.update {
                it.copy(
                    sessionStatus = DatSessionStatus.Paused,
                    recentError = "Adding DAT camera stream.",
                )
            }

            val streamResult = session.addStream(
                StreamConfiguration(videoQuality = VideoQuality.LOW, 15),
            )

            val createdStream = streamResult.getOrNull()
            if (createdStream == null) {
                setError("DAT stream creation failed: ${streamResult.failureDescription()}.")
                cleanupFailedStart(session)
                return@launch
            }
            stream = createdStream

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

            _state.update {
                it.copy(
                    sessionStatus = DatSessionStatus.Paused,
                    recentError = "Starting DAT camera stream.",
                )
            }

            val streamStartResult = createdStream.start()
            if (streamStartResult.getOrNull() == null) {
                setError("DAT stream start failed: ${streamStartResult.failureDescription()}.")
                stream = null
                cleanupFailedStart(session)
                return@launch
            }
        }
    }

    private fun cleanupFailedStart(session: DeviceSession) {
        streamErrorJob?.cancel()
        streamErrorJob = null
        stateJob?.cancel()
        stateJob = null
        videoJob?.cancel()
        videoJob = null
        deviceSessionJob?.cancel()
        deviceSessionJob = null
        sessionErrorJob?.cancel()
        sessionErrorJob = null
        runCatching { stream?.stop() }
        runCatching { session.stop() }
        stream = null
        coreSession = null
        latestFrame = null
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

    private fun DeviceSessionError.toStartupFailure(): StartupFailure =
        StartupFailure(
            message = when (this) {
                DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
                    "DAT cannot start because the app on the glasses must be updated. Update the glasses and Meta AI app, then reconnect."

                DeviceSessionError.SESSION_ENDED_BY_DEVICE ->
                    "DAT session ended by the glasses before camera streaming could start."

                DeviceSessionError.NO_ELIGIBLE_DEVICE ->
                    "DAT cannot start because no eligible glasses are available."

                DeviceSessionError.DEVICE_DISCONNECTED ->
                    "DAT cannot start because the glasses disconnected."

                DeviceSessionError.DEVICE_POWERED_OFF ->
                    "DAT cannot start because the glasses are powered off."

                else -> "DAT session error: ${descriptionOrType()}"
            },
            isTerminal = this != DeviceSessionError.SESSION_ENDED_BY_DEVICE,
        )

    private fun Any?.descriptionOrType(): String =
        when (this) {
            null -> "Unknown"
            else -> runCatching {
                val description = this::class.java.methods
                    .firstOrNull { it.name == "getDescription" && it.parameterCount == 0 }
                    ?.invoke(this) as? String

                description?.ifBlank { null }
                    ?: "${this::class.java.name}: $this"
            }.getOrDefault("${this::class.java.name}: $this")
        }

    private fun Any?.failureDescription(): String {
        if (this == null) return "Result object was null"

        return runCatching {
            val failure = this::class.java.methods
                .firstOrNull { it.name == "failureOrNull" && it.parameterCount == 0 }
                ?.invoke(this)

            when (failure) {
                null -> "No failure object. result=${this::class.java.name}: $this"
                else -> failure.descriptionOrType()
            }
        }.getOrElse { reflectionError ->
            "Could not inspect failure. result=${this::class.java.name}: $this; inspectionError=${reflectionError.message}"
        }
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

    private fun activeDeviceRequiresUpdate(): Boolean {
        val device = activeDevice ?: return false
        val metadata = Wearables.devicesMetadata[device]?.value
        return metadata?.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED
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

    private data class StartupFailure(
        val message: String,
        val isTerminal: Boolean,
    )
}
