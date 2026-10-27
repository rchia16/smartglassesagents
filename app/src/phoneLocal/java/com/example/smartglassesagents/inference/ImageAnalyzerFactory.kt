package com.example.smartglassesagents.inference

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import com.alibaba.mnnllm.android.llm.LlmSession
import com.example.smartglassesagents.experiment.HostConfig
import com.example.smartglassesagents.network.AgentResult
import com.example.smartglassesagents.network.AnalyzeImageRequest
import com.example.smartglassesagents.network.AnalyzeImageResponse
import com.example.smartglassesagents.network.ModelProfile
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val ASSET_PACK_NAME = "fastvlm_model"
private const val MODEL_DIRECTORY = "FastVLM-1.5B-Stage3-MNN"
private const val AGENT_PROFILE = "phone_fastvlm_1_5b"
private const val MODEL_ID = "FastVLM-1.5B-Stage3-MNN"
private const val PROMPT_VERSION = "phone_fastvlm_v1"

@Suppress("UNUSED_PARAMETER")
fun createImageAnalyzer(context: Context, hostConfig: HostConfig): ImageAnalyzer =
    PhoneFastVlmAnalyzer(context)

private class PhoneFastVlmAnalyzer(
    private val context: Context,
    private val assetPacks: AssetPackManager = AssetPackManagerFactory.getInstance(context)
) : ImageAnalyzer {
    private val inferenceLock = Mutex()
    private val sessionGuard = Any()
    private val confirmationDialogShown = AtomicBoolean(false)
    @Volatile
    private var downloadStatus = "downloading"
    @Volatile
    private var closeRequested = false
    private var session: LlmSession? = null
    private var loadedConfigPath: String? = null
    private val assetPackListener = AssetPackStateUpdateListener { state ->
        if (state.name() != ASSET_PACK_NAME) return@AssetPackStateUpdateListener
        downloadStatus = when (state.status()) {
            AssetPackStatus.PENDING -> "pending"
            AssetPackStatus.DOWNLOADING -> {
                val total = state.totalBytesToDownload().coerceAtLeast(1L)
                val percent = 100L * state.bytesDownloaded() / total
                "downloading $percent%"
            }
            AssetPackStatus.TRANSFERRING -> "installing"
            AssetPackStatus.COMPLETED -> "ready"
            AssetPackStatus.WAITING_FOR_WIFI -> "waiting for Wi-Fi or mobile-data approval"
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> "waiting for download approval"
            AssetPackStatus.FAILED -> "download failed (${state.errorCode()})"
            AssetPackStatus.CANCELED -> "download canceled"
            AssetPackStatus.NOT_INSTALLED -> "not installed"
            else -> "unavailable"
        }
        if (
            state.status() == AssetPackStatus.WAITING_FOR_WIFI ||
            state.status() == AssetPackStatus.REQUIRES_USER_CONFIRMATION
        ) {
            showDownloadConfirmation()
        }
    }

    init {
        assetPacks.registerListener(assetPackListener)
    }

    override val destinationLabel: String = "this phone"

    override suspend fun checkAvailability(): Result<String> = runCatching {
        val modelDirectory = resolveModelDirectory() ?: awaitModelDelivery()
        validateModelDirectory(modelDirectory)
        "FastVLM is ready on this phone"
    }

    override suspend fun getModels(): Result<List<ModelProfile>> = runCatching {
        val ready = resolveModelDirectory()?.let { directory ->
            runCatching { validateModelDirectory(directory) }.isSuccess
        } == true
        listOf(
            ModelProfile(
                agentProfile = AGENT_PROFILE,
                modelId = MODEL_ID,
                runtime = "local_runtime",
                status = if (ready) "ready" else downloadStatus,
                purpose = "On-device image analysis; no GB10 connection required."
            )
        )
    }

    override suspend fun analyzeImage(
        request: AnalyzeImageRequest,
        bitmap: Bitmap
    ): Result<AnalyzeImageResponse> = withContext(Dispatchers.IO) {
        runCatching {
            check(!closeRequested) { "FastVLM analyzer is closed." }
            check(inferenceLock.tryLock()) {
                "FastVLM is already analyzing another image."
            }
            try {
                synchronized(sessionGuard) {
                    val modelDirectory = resolveModelDirectory()
                        ?: throw IllegalStateException(
                            "FastVLM model delivery is still in progress. Try again when the model is ready."
                        )
                    validateModelDirectory(modelDirectory)
                    val imageFile = writeInferenceImage(request, bitmap)
                    val startedAt = System.nanoTime()
                    try {
                        val answer = requireSession(modelDirectory).generate(
                            buildPrompt(request, imageFile)
                        ).ifBlank { throw IllegalStateException("FastVLM returned an empty response.") }
                        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000L
                        val runId = UUID.randomUUID().toString()
                        val response = AnalyzeImageResponse(
                            runId = runId,
                            taskType = request.taskType,
                            selectedSpeechAgent = AGENT_PROFILE,
                            results = listOf(
                                AgentResult(
                                    agentProfile = AGENT_PROFILE,
                                    modelId = MODEL_ID,
                                    runtime = "local_runtime",
                                    promptVersion = PROMPT_VERSION,
                                    answer = answer,
                                    observations = emptyList(),
                                    locations = emptyList(),
                                    confidence = 0.0,
                                    uncertainties = listOf("The on-device model did not provide a calibrated confidence score."),
                                    latencyMs = latencyMs
                                )
                            ),
                            speechText = answer.toSpeechText()
                        )
                        appendRunMetadata(response, request)
                        response
                    } finally {
                        imageFile.delete()
                    }
                }
            } finally {
                if (closeRequested) releaseSession()
                inferenceLock.unlock()
            }
        }
    }

    override fun close() {
        assetPacks.unregisterListener(assetPackListener)
        closeRequested = true
        if (!inferenceLock.tryLock()) return
        try {
            releaseSession()
        } finally {
            inferenceLock.unlock()
        }
    }

    private fun releaseSession() {
        synchronized(sessionGuard) {
            session?.close()
            session = null
            loadedConfigPath = null
        }
    }

    private fun resolveModelDirectory(): File? {
        val assetsPath = assetPacks.getPackLocation(ASSET_PACK_NAME)?.assetsPath() ?: return null
        return File(assetsPath, MODEL_DIRECTORY)
    }

    private suspend fun awaitModelDelivery(): File {
        val completion = CompletableDeferred<Unit>()
        val completionListener = AssetPackStateUpdateListener { state ->
            if (state.name() != ASSET_PACK_NAME) return@AssetPackStateUpdateListener
            when (state.status()) {
                AssetPackStatus.COMPLETED -> completion.complete(Unit)
                AssetPackStatus.FAILED -> completion.completeExceptionally(
                    IllegalStateException("FastVLM model delivery failed (${state.errorCode()}).")
                )
                AssetPackStatus.CANCELED -> completion.completeExceptionally(
                    IllegalStateException("FastVLM model delivery was canceled.")
                )
            }
        }

        assetPacks.registerListener(completionListener)
        try {
            resolveModelDirectory()?.let { return it }
            assetPacks.fetch(listOf(ASSET_PACK_NAME))
                .addOnFailureListener { completion.completeExceptionally(it) }
                .addOnCanceledListener {
                    completion.completeExceptionally(
                        IllegalStateException("FastVLM model delivery was canceled.")
                    )
                }
            completion.await()
            return resolveModelDirectory()
                ?: error("FastVLM model delivery completed, but the model files are unavailable.")
        } finally {
            assetPacks.unregisterListener(completionListener)
        }
    }

    @Suppress("DEPRECATION")
    private fun showDownloadConfirmation() {
        val activity = context as? Activity ?: return
        if (!confirmationDialogShown.compareAndSet(false, true)) return
        activity.runOnUiThread {
            assetPacks.showConfirmationDialog(activity)
                .addOnCompleteListener { confirmationDialogShown.set(false) }
        }
    }

    private fun requireSession(modelDirectory: File): LlmSession {
        val configFile = File(modelDirectory, "config.json")
        val configPath = configFile.absolutePath
        session?.takeIf { loadedConfigPath == configPath }?.let { return it }
        session?.close()

        val mergedConfig = JSONObject(configFile.readText())
            .put("max_new_tokens", 256)
            .put(
                "system_prompt",
                "Answer visual questions concisely. Never identify people. For faces, report only count, approximate location, and non-identifying visible details. State uncertainty when details are unclear."
            )
        val mmapDirectory = File(context.cacheDir, "mnn_mmap").apply { mkdirs() }
        val runtimeConfig = JSONObject()
            .put("is_r1", false)
            .put("keep_history", true)
            .put("mmap_dir", mmapDirectory.absolutePath)

        return LlmSession(
            configPath = configPath,
            mergedConfigJson = mergedConfig.toString(),
            runtimeConfigJson = runtimeConfig.toString()
        ).also {
            it.load()
            session = it
            loadedConfigPath = configPath
        }
    }

    private fun writeInferenceImage(request: AnalyzeImageRequest, bitmap: Bitmap): File {
        val directory = File(context.cacheDir, "inference_images").apply { mkdirs() }
        return File(directory, "${request.sessionId}-${request.capturedAtMs}.jpg").also { file ->
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "Could not encode the captured image."
                }
            }
        }
    }

    private fun buildPrompt(request: AnalyzeImageRequest, imageFile: File): String = buildString {
        append("<img>")
        append(imageFile.absolutePath)
        append("</img>\n")
        append(request.prompt.trim())
        if (request.voiceTranscript.isNotBlank()) {
            append("\nUser's spoken question: ")
            append(request.voiceTranscript.trim())
        }
        append("\nGive a short direct answer. Mention uncertainty when fine details are not readable.")
    }

    private fun appendRunMetadata(response: AnalyzeImageResponse, request: AnalyzeImageRequest) {
        val result = response.results.single()
        val record = JSONObject()
            .put("run_id", response.runId)
            .put("session_id", request.sessionId)
            .put("task_type", request.taskType.wireName)
            .put("agent_profile", result.agentProfile)
            .put("model_id", result.modelId)
            .put("runtime", result.runtime)
            .put("prompt_version", result.promptVersion)
            .put("latency_ms", result.latencyMs)
            .put("capture_source", request.captureSource.wireName)
            .put("captured_at_ms", request.capturedAtMs)
            .put("live_sample", request.liveSample)
        File(context.filesDir, "phone_local_experiment_runs.jsonl")
            .appendText(record.toString() + "\n")
    }
}

private fun validateModelDirectory(directory: File) {
    val required = listOf(
        "config.json",
        "llm.mnn",
        "llm.mnn.json",
        "llm.mnn.weight",
        "llm_config.json",
        "tokenizer.txt",
        "visual.mnn",
        "visual.mnn.weight"
    )
    val missing = required.filterNot { File(directory, it).isFile }
    check(missing.isEmpty()) {
        "FastVLM asset pack is incomplete. Missing: ${missing.joinToString()}."
    }
}

internal fun String.toSpeechText(maxLength: Int = 240): String {
    val plain = replace(Regex("[`*_#>|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (plain.length <= maxLength) return plain
    val shortened = plain.take(maxLength + 1).substringBeforeLast(' ').trimEnd(',', ';', ':')
    return shortened.trimEnd('.', '!', '?') + "."
}
