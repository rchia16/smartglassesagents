package com.example.smartglassesagents.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smartglassesagents.capture.toJpegBase64
import com.example.smartglassesagents.dat.DatDeviceState
import com.example.smartglassesagents.dat.MockDatSessionController
import com.example.smartglassesagents.experiment.CaptureSource
import com.example.smartglassesagents.experiment.ExperimentStatus
import com.example.smartglassesagents.experiment.HostConfig
import com.example.smartglassesagents.experiment.TaskType
import com.example.smartglassesagents.network.AgentResult
import com.example.smartglassesagents.network.AnalyzeImageRequest
import com.example.smartglassesagents.network.AnalyzeImageResponse
import com.example.smartglassesagents.network.Gb10ApiClient
import com.example.smartglassesagents.speech.AudioRouteController
import com.example.smartglassesagents.speech.AudioRouteState
import com.example.smartglassesagents.speech.SpeechController
import com.example.smartglassesagents.ui.theme.SmartGlassesAgentsTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

@Composable
fun ExperimentApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sessionId = remember { UUID.randomUUID().toString() }
    val speechController = remember { SpeechController(context) }
    val audioRouteController = remember { AudioRouteController(context) }
    val audioRouteState by audioRouteController.state.collectAsState()
    val datController = remember { MockDatSessionController() }
    val datState by datController.state.collectAsState()
    // GB10: 192.168.0.243, ARIA: 192.168.0.194, mock: http://10.0.2.2:8765
    var hostUrl by remember { mutableStateOf("http://192.168.0.243:8765") }
    var pairingToken by remember { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf(TaskType.BoardText) }
    var prompt by remember { mutableStateOf(TaskType.BoardText.defaultPrompt) }
    var voiceTranscript by remember { mutableStateOf("") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var captureSource by remember { mutableStateOf(CaptureSource.PhoneCamera) }
    var status by remember { mutableStateOf<ExperimentStatus>(ExperimentStatus.Idle) }
    var latestResponse by remember { mutableStateOf<AnalyzeImageResponse?>(null) }
    var speechEnabled by remember { mutableStateOf(true) }
    var preferBluetoothSpeech by remember { mutableStateOf(false) }
    var liveSamplingJob by remember { mutableStateOf<Job?>(null) }
    var liveIntervalSeconds by remember { mutableStateOf("5") }
    var liveMaxDurationSeconds by remember { mutableStateOf("60") }
    var liveSpeakResults by remember { mutableStateOf(false) }
    var liveSampleCount by remember { mutableStateOf(0) }
    var lastSpokenLiveText by remember { mutableStateOf("") }
    val isLiveSampling = liveSamplingJob?.isActive == true

    DisposableEffect(Unit) {
        datController.startMonitoring()
        audioRouteController.refresh()
        audioRouteController.clearPreferredOutput()
        onDispose {
            liveSamplingJob?.cancel()
            speechController.shutdown()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status = ExperimentStatus.Error("Camera capture did not return an image.")
        } else {
            capturedBitmap = bitmap
            captureSource = CaptureSource.PhoneCamera
            latestResponse = null
            status = ExperimentStatus.Ready("Image captured from phone camera fallback.")
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            status = ExperimentStatus.CapturingImage
            cameraLauncher.launch(null)
        } else {
            status = ExperimentStatus.Error("Camera permission is required for phone capture.")
        }
    }

    val voiceCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            status = ExperimentStatus.Ready("Voice query was cancelled.")
            return@rememberLauncherForActivityResult
        }
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        if (transcript.isBlank()) {
            status = ExperimentStatus.Error("No speech transcript was returned.")
        } else {
            voiceTranscript = transcript
            status = ExperimentStatus.Ready("Voice query captured.")
        }
    }

    fun startVoiceCapture() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask a short visual question")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceCaptureLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            status = ExperimentStatus.Error("No speech recognizer is available on this phone.")
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceCapture()
        } else {
            status = ExperimentStatus.Error("Microphone permission is required for voice queries.")
        }
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioRouteController.refresh()
        status = if (granted) {
            ExperimentStatus.Ready("Bluetooth audio permission granted.")
        } else {
            ExperimentStatus.Error("Bluetooth Connect permission is required to inspect glasses audio routes.")
        }
    }

    fun client(): Gb10ApiClient = Gb10ApiClient(
        HostConfig(baseUrl = hostUrl, pairingToken = pairingToken)
    )

    fun speak(text: String) {
        speechController.muted = !speechEnabled
        if (speechEnabled && preferBluetoothSpeech) {
            audioRouteController.preferBluetoothOutput()
        }
        speechController.speak(text)
    }

    suspend fun analyzeBitmap(
        bitmap: Bitmap,
        source: CaptureSource,
        liveSample: Boolean,
        sampleIndex: Int?,
        speakResult: Boolean
    ) {
        val request = AnalyzeImageRequest(
            sessionId = sessionId,
            taskType = selectedTask,
            prompt = prompt,
            voiceTranscript = voiceTranscript,
            imageBase64 = bitmap.toJpegBase64(),
            imageMimeType = "image/jpeg",
            captureSource = source,
            capturedAtMs = System.currentTimeMillis(),
            liveSample = liveSample,
            sampleIndex = sampleIndex
        )
        client().analyzeImage(request)
            .onSuccess { response ->
                latestResponse = response
                status = if (liveSample) {
                    ExperimentStatus.Ready("Live sample ${sampleIndex ?: 0} returned ${response.results.size} result(s).")
                } else {
                    ExperimentStatus.Ready("Received ${response.results.size} agent result(s).")
                }
                if (speakResult) {
                    if (liveSample) {
                        val normalizedSpeech = response.speechText.trim()
                        if (normalizedSpeech.isNotBlank() && normalizedSpeech != lastSpokenLiveText) {
                            lastSpokenLiveText = normalizedSpeech
                            speak(normalizedSpeech)
                        }
                    } else {
                        lastSpokenLiveText = ""
                        speak(response.speechText)
                    }
                }
            }
            .onFailure { error ->
                status = ExperimentStatus.Error(error.message ?: "Image analysis failed.")
            }
    }

    fun currentLiveFrame(): Pair<Bitmap, CaptureSource>? {
        if (datController.state.value.isReadyForCapture) {
            datController.captureFrame()?.let { return it to CaptureSource.Mock }
        }
        val fallback = capturedBitmap ?: return null
        return fallback to captureSource
    }

    fun startLiveSampling() {
        if (liveSamplingJob?.isActive == true) return

        val intervalMs = parseSecondsSetting(liveIntervalSeconds, defaultSeconds = 5, minSeconds = 1, maxSeconds = 60) * 1000L
        val maxDurationMs = parseSecondsSetting(liveMaxDurationSeconds, defaultSeconds = 60, minSeconds = 5, maxSeconds = 3600) * 1000L
        liveSampleCount = 0
        lastSpokenLiveText = ""
        latestResponse = null
        liveSamplingJob = coroutineScope.launch {
            val startedAt = System.currentTimeMillis()
            var sampleIndex = 0
            var endedWithError = false
            status = ExperimentStatus.Ready("Live sampling started.")
            try {
                while (isActive && System.currentTimeMillis() - startedAt < maxDurationMs) {
                    val frame = currentLiveFrame()
                    if (frame == null) {
                        endedWithError = true
                        status = ExperimentStatus.Error("Start a DAT mock session or capture one still image before live sampling.")
                        break
                    }

                    sampleIndex += 1
                    liveSampleCount = sampleIndex
                    capturedBitmap = frame.first
                    captureSource = frame.second
                    status = ExperimentStatus.SendingImage
                    analyzeBitmap(
                        bitmap = frame.first,
                        source = frame.second,
                        liveSample = true,
                        sampleIndex = sampleIndex,
                        speakResult = liveSpeakResults
                    )

                    if (isActive && System.currentTimeMillis() - startedAt < maxDurationMs) {
                        delay(intervalMs)
                    }
                }
            } finally {
                liveSamplingJob = null
                if (!endedWithError) {
                    status = ExperimentStatus.Ready("Live sampling stopped after $sampleIndex sample(s).")
                }
            }
        }
    }

    fun stopLiveSampling() {
        liveSamplingJob?.cancel()
        liveSamplingJob = null
        status = ExperimentStatus.Ready("Live sampling stopped after $liveSampleCount sample(s).")
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(status = status)

            ConnectionPanel(
                hostUrl = hostUrl,
                pairingToken = pairingToken,
                onHostUrlChanged = { hostUrl = it },
                onPairingTokenChanged = { pairingToken = it },
                onCheckHost = {
                    status = ExperimentStatus.CheckingHost
                    coroutineScope.launch {
                        client().checkHealth()
                            .onSuccess { status = ExperimentStatus.Ready("GB10 host is $it.") }
                            .onFailure { status = ExperimentStatus.Error(it.message ?: "Host check failed.") }
                    }
                }
            )

            DatPanel(
                datState = datState,
                onRegister = {
                    datController.startRegistration()
                    status = ExperimentStatus.Ready("DAT mock app registration is ready.")
                },
                onUnregister = {
                    datController.unregister()
                    status = ExperimentStatus.Ready("DAT mock state reset.")
                },
                onDiscoverMock = {
                    datController.discoverMockDevice()
                    status = ExperimentStatus.Ready("Mock Ray-Ban Meta device discovered.")
                },
                onGrantCamera = {
                    datController.requestCameraPermission()
                    status = ExperimentStatus.Ready("DAT camera permission marked granted in mock adapter.")
                },
                onStartSession = {
                    datController.startSession()
                    status = datController.state.value.recentError?.let { ExperimentStatus.Error(it) }
                        ?: ExperimentStatus.Ready("DAT mock session is running.")
                },
                onStopSession = {
                    datController.stopSession()
                    status = ExperimentStatus.Ready("DAT mock session stopped.")
                }
            )

            TaskPanel(
                selectedTask = selectedTask,
                prompt = prompt,
                onTaskSelected = { task ->
                    selectedTask = task
                    prompt = task.defaultPrompt
                },
                onPromptChanged = { prompt = it }
            )

            VoiceQueryPanel(
                transcript = voiceTranscript,
                onTranscriptChanged = { voiceTranscript = it },
                onCaptureVoice = {
                    val permissionState = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    )
                    if (permissionState == PackageManager.PERMISSION_GRANTED) {
                        startVoiceCapture()
                    } else {
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onClear = { voiceTranscript = "" }
            )

            CapturePanel(
                bitmap = capturedBitmap,
                captureSource = captureSource,
                datCaptureEnabled = datState.isReadyForCapture,
                onCaptureDatImage = {
                    val bitmap = datController.captureFrame()
                    if (bitmap == null) {
                        status = datController.state.value.recentError?.let { ExperimentStatus.Error(it) }
                            ?: ExperimentStatus.Error("DAT mock capture failed.")
                    } else {
                        capturedBitmap = bitmap
                        captureSource = CaptureSource.Mock
                        latestResponse = null
                        status = ExperimentStatus.Ready("Image captured from DAT mock session.")
                    }
                },
                onCapturePhoneImage = {
                    val permissionState = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    )
                    if (permissionState == PackageManager.PERMISSION_GRANTED) {
                        status = ExperimentStatus.CapturingImage
                        cameraLauncher.launch(null)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onSendImage = {
                    val bitmap = capturedBitmap
                    if (bitmap == null) {
                        status = ExperimentStatus.Error("Capture an image before sending.")
                        return@CapturePanel
                    }
                    status = ExperimentStatus.SendingImage
                    coroutineScope.launch {
                        analyzeBitmap(
                            bitmap = bitmap,
                            source = captureSource,
                            liveSample = false,
                            sampleIndex = null,
                            speakResult = true
                        )
                    }
                }
            )

            LiveSamplingPanel(
                isLiveSampling = isLiveSampling,
                sampleCount = liveSampleCount,
                intervalSeconds = liveIntervalSeconds,
                maxDurationSeconds = liveMaxDurationSeconds,
                speakResults = liveSpeakResults,
                onIntervalChanged = { liveIntervalSeconds = it },
                onMaxDurationChanged = { liveMaxDurationSeconds = it },
                onSpeakResultsChanged = { liveSpeakResults = it },
                onStart = { startLiveSampling() },
                onStop = { stopLiveSampling() }
            )

            SpeechPanel(
                speechEnabled = speechEnabled,
                preferBluetoothSpeech = preferBluetoothSpeech,
                audioRouteState = audioRouteState,
                latestSpeechText = latestResponse?.speechText.orEmpty(),
                onSpeechEnabledChanged = {
                    speechEnabled = it
                    speechController.muted = !it
                    if (!it) speechController.stop()
                },
                onPreferBluetoothChanged = {
                    preferBluetoothSpeech = it
                    if (!it) audioRouteController.clearPreferredOutput()
                },
                onRefreshRoutes = { audioRouteController.refresh() },
                onPreferBluetoothNow = {
                    audioRouteController.preferBluetoothOutput()
                        .onSuccess { label -> status = ExperimentStatus.Ready("Forced Bluetooth communication route: $label.") }
                        .onFailure { error -> status = ExperimentStatus.Error(error.message ?: "Bluetooth route failed.") }
                },
                onRequestBluetoothPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        audioRouteController.refresh()
                    }
                },
                onStop = { speechController.stop() },
                onReplay = { speak(latestResponse?.speechText.orEmpty()) }
            )

            ResultPanel(response = latestResponse)
        }
    }
}

@Composable
private fun Header(status: ExperimentStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Smart Glasses Agent Experiment",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = status.messageText(),
            style = MaterialTheme.typography.bodyMedium,
            color = when (status) {
                is ExperimentStatus.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun LiveSamplingPanel(
    isLiveSampling: Boolean,
    sampleCount: Int,
    intervalSeconds: String,
    maxDurationSeconds: String,
    speakResults: Boolean,
    onIntervalChanged: (String) -> Unit,
    onMaxDurationChanged: (String) -> Unit,
    onSpeakResultsChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Section(title = "Sampled live mode") {
        Text(
            text = "Samples DAT mock frames when a mock session is running; otherwise resends the latest captured still. Only one request is in flight at a time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (isLiveSampling) "Running, $sampleCount sample(s) sent." else "Stopped, $sampleCount sample(s) sent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = intervalSeconds,
                onValueChange = { onIntervalChanged(it.filter(Char::isDigit).take(2)) },
                label = { Text("Interval sec") },
                singleLine = true,
                enabled = !isLiveSampling,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = maxDurationSeconds,
                onValueChange = { onMaxDurationChanged(it.filter(Char::isDigit).take(4)) },
                label = { Text("Max sec") },
                singleLine = true,
                enabled = !isLiveSampling,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Speak live results", modifier = Modifier.weight(1f))
            Switch(
                checked = speakResults,
                onCheckedChange = onSpeakResultsChanged,
                enabled = !isLiveSampling
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart, enabled = !isLiveSampling) {
                Text("Start live")
            }
            TextButton(onClick = onStop, enabled = isLiveSampling) {
                Text("Stop live")
            }
        }
    }
}

@Composable
private fun VoiceQueryPanel(
    transcript: String,
    onTranscriptChanged: (String) -> Unit,
    onCaptureVoice: () -> Unit,
    onClear: () -> Unit
) {
    Section(title = "Voice query") {
        Text(
            text = "Phone microphone fallback is active. Raw audio is not stored; only the transcript is sent to the GB10 host.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = transcript,
            onValueChange = onTranscriptChanged,
            label = { Text("Transcript") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCaptureVoice) {
                Text("Push to talk")
            }
            TextButton(onClick = onClear, enabled = transcript.isNotBlank()) {
                Text("Clear")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DatPanel(
    datState: DatDeviceState,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onDiscoverMock: () -> Unit,
    onGrantCamera: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit
) {
    Section(title = "Ray-Ban Meta DAT") {
        Text(
            text = "Adapter: ${datState.adapterName}. Real SDK wiring is gated on GitHub Packages credentials and a Meta Wearables application ID.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = "Registration: ${datState.registrationStatus.label}")
        Text(text = "Camera permission: ${datState.cameraPermissionStatus.label}")
        Text(text = "Session: ${datState.sessionStatus.label}")
        Text(text = "Active device: ${datState.activeDevice?.name ?: "None"}")
        datState.recentError?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRegister) {
                Text("Register")
            }
            TextButton(onClick = onDiscoverMock) {
                Text("Mock device")
            }
            TextButton(onClick = onGrantCamera) {
                Text("Grant camera")
            }
            TextButton(onClick = onStartSession) {
                Text("Start session")
            }
            TextButton(onClick = onStopSession) {
                Text("Stop")
            }
            TextButton(onClick = onUnregister) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    hostUrl: String,
    pairingToken: String,
    onHostUrlChanged: (String) -> Unit,
    onPairingTokenChanged: (String) -> Unit,
    onCheckHost: () -> Unit
) {
    Section(title = "GB10 host") {
        OutlinedTextField(
            value = hostUrl,
            onValueChange = onHostUrlChanged,
            label = { Text("Host URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Use http://10.0.2.2:8765 only in the Android emulator. A physical phone needs the desktop/GB10 Wi-Fi IP, for example http://192.168.0.194:8765 on the current network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = pairingToken,
            onValueChange = onPairingTokenChanged,
            label = { Text("Pairing token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onCheckHost) {
            Text("Check host")
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskPanel(
    selectedTask: TaskType,
    prompt: String,
    onTaskSelected: (TaskType) -> Unit,
    onPromptChanged: (String) -> Unit
) {
    Section(title = "Task") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskType.entries.forEach { task ->
                FilterChip(
                    selected = selectedTask == task,
                    onClick = { onTaskSelected(task) },
                    label = { Text(task.label) }
                )
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChanged,
            label = { Text("Prompt") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CapturePanel(
    bitmap: Bitmap?,
    captureSource: CaptureSource,
    datCaptureEnabled: Boolean,
    onCaptureDatImage: () -> Unit,
    onCapturePhoneImage: () -> Unit,
    onSendImage: () -> Unit
) {
    Section(title = "Capture") {
        Text(
            text = "Use the DAT mock frame to exercise the glasses capture path now, or the phone camera fallback for real images.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Capture source: ${captureSource.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (bitmap == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(
                    onClick = onCaptureDatImage,
                    enabled = datCaptureEnabled,
                    label = { Text("Capture DAT mock") }
                )
                AssistChip(
                    onClick = onCapturePhoneImage,
                    label = { Text("Capture phone") }
                )
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCaptureDatImage, enabled = datCaptureEnabled) {
                    Text("DAT mock")
                }
                Button(onClick = onCapturePhoneImage) {
                    Text("Phone")
                }
                Button(onClick = onSendImage) {
                    Text("Send to GB10")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SpeechPanel(
    speechEnabled: Boolean,
    preferBluetoothSpeech: Boolean,
    audioRouteState: AudioRouteState,
    latestSpeechText: String,
    onSpeechEnabledChanged: (Boolean) -> Unit,
    onPreferBluetoothChanged: (Boolean) -> Unit,
    onRefreshRoutes: () -> Unit,
    onPreferBluetoothNow: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onStop: () -> Unit,
    onReplay: () -> Unit
) {
    Section(title = "Speech readout") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (speechEnabled) "Speech enabled" else "Speech muted",
                modifier = Modifier.weight(1f)
            )
            Switch(checked = speechEnabled, onCheckedChange = onSpeechEnabledChanged)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onStop) {
                Text("Stop")
            }
            TextButton(onClick = onReplay, enabled = latestSpeechText.isNotBlank() && speechEnabled) {
                Text("Replay")
            }
        }
        Text(
            text = "Bluetooth outputs: ${
                audioRouteState.bluetoothOutputs.joinToString { it.label }.ifBlank { "none detected" }
            }",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Preferred route: ${audioRouteState.preferredOutputName ?: "system default"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        audioRouteState.activeCommunicationDeviceName?.let { route ->
            Text(
                text = "Active communication route: $route",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        audioRouteState.recentError?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Force Bluetooth call route",
                modifier = Modifier.weight(1f)
            )
            Switch(checked = preferBluetoothSpeech, onCheckedChange = onPreferBluetoothChanged)
        }
        Text(
            text = "Leave this off for clearer media-quality TTS. Use it only if Android will not send audio to the glasses through the normal media route.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefreshRoutes) {
                Text("Refresh routes")
            }
            TextButton(onClick = onPreferBluetoothNow, enabled = audioRouteState.bluetoothOutputs.isNotEmpty()) {
                Text("Force route")
            }
            if (audioRouteState.bluetoothPermissionRequired && !audioRouteState.bluetoothPermissionGranted) {
                TextButton(onClick = onRequestBluetoothPermission) {
                    Text("Allow Bluetooth")
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(response: AnalyzeImageResponse?) {
    Section(title = "Results") {
        if (response == null) {
            Text(
                text = "No result yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Section
        }

        Text(
            text = "Run ${response.runId}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = response.speechText, style = MaterialTheme.typography.bodyLarge)
        response.results.forEach { result ->
            AgentResultView(result = result)
        }
    }
}

@Composable
private fun AgentResultView(result: AgentResult) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = result.agentProfile,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = result.answer)
            Text(
                text = "${result.modelId} / ${result.runtime} / ${result.latencyMs} ms",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (result.observations.isNotEmpty()) {
                Text(text = "Observations: ${result.observations.joinToString("; ")}")
            }
            if (result.locations.isNotEmpty()) {
                Text(
                    text = "Locations: " + result.locations.joinToString("; ") {
                        "${it.label} ${it.position} (${String.format("%.2f", it.confidence)})"
                    }
                )
            }
            if (result.uncertainties.isNotEmpty()) {
                Text(
                    text = "Uncertainty: ${result.uncertainties.joinToString("; ")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 0.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        content()
        Spacer(modifier = Modifier.height(4.dp))
    }
}

private fun ExperimentStatus.messageText(): String =
    when (this) {
        ExperimentStatus.Idle -> "Connect to the GB10 host, capture a still image, then send it for local analysis."
        ExperimentStatus.CheckingHost -> "Checking GB10 host..."
        ExperimentStatus.CapturingImage -> "Opening camera..."
        ExperimentStatus.SendingImage -> "Sending image to GB10..."
        is ExperimentStatus.Ready -> message
        is ExperimentStatus.Error -> message
    }

private fun parseSecondsSetting(
    value: String,
    defaultSeconds: Int,
    minSeconds: Int,
    maxSeconds: Int
): Int =
    value.toIntOrNull()
        ?.coerceIn(minSeconds, maxSeconds)
        ?: defaultSeconds

@Preview(showBackground = true)
@Composable
private fun ExperimentAppPreview() {
    SmartGlassesAgentsTheme {
        ExperimentApp()
    }
}
