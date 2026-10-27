package com.example.smartglassesagents.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.smartglassesagents.capture.toJpegBase64
import com.example.smartglassesagents.experiment.HostConfig
import com.example.smartglassesagents.network.AnalyzeImageRequest
import com.example.smartglassesagents.network.AnalyzeClipRequest
import com.example.smartglassesagents.network.AnalyzeImageResponse
import com.example.smartglassesagents.network.Gb10ApiClient
import com.example.smartglassesagents.network.ModelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun createImageAnalyzer(context: Context, hostConfig: HostConfig): ImageAnalyzer =
    Gb10ImageAnalyzer(Gb10ApiClient(hostConfig))

private class Gb10ImageAnalyzer(
    private val client: Gb10ApiClient
) : ImageAnalyzer {
    override val destinationLabel: String = "GB10 host"

    override suspend fun checkAvailability(): Result<String> = client.checkHealth()

    override suspend fun getModels(): Result<List<ModelProfile>> = client.getModels()

    override suspend fun analyzeImage(
        request: AnalyzeImageRequest,
        bitmap: Bitmap
    ): Result<AnalyzeImageResponse> =
        client.analyzeImage(request.copy(imageBase64 = bitmap.toJpegBase64()))

    override suspend fun analyzeClip(
        request: AnalyzeClipRequest, file: File
    ): Result<AnalyzeImageResponse> = withContext(Dispatchers.IO) {
        if (file.length() !in 1..8_000_000) {
            Result.failure(IllegalArgumentException("Clip must be under 8 MB."))
        } else {
            client.analyzeClip(request.copy(videoBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
        }
    }
}
