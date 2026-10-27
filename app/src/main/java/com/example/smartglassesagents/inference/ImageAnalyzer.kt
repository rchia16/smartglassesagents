package com.example.smartglassesagents.inference

import android.graphics.Bitmap
import com.example.smartglassesagents.network.AnalyzeImageRequest
import com.example.smartglassesagents.network.AnalyzeClipRequest
import com.example.smartglassesagents.network.AnalyzeImageResponse
import com.example.smartglassesagents.network.ModelProfile
import java.io.File

interface ImageAnalyzer : AutoCloseable {
    val destinationLabel: String

    suspend fun checkAvailability(): Result<String>

    suspend fun getModels(): Result<List<ModelProfile>>

    suspend fun analyzeImage(
        request: AnalyzeImageRequest,
        bitmap: Bitmap
    ): Result<AnalyzeImageResponse>

    suspend fun analyzeClip(request: AnalyzeClipRequest, file: File): Result<AnalyzeImageResponse> =
        Result.failure(UnsupportedOperationException("Clip analysis requires the GB10 host build."))

    override fun close() = Unit
}
