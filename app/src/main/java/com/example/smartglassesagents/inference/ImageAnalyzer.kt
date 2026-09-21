package com.example.smartglassesagents.inference

import android.graphics.Bitmap
import com.example.smartglassesagents.network.AnalyzeImageRequest
import com.example.smartglassesagents.network.AnalyzeImageResponse
import com.example.smartglassesagents.network.ModelProfile

interface ImageAnalyzer : AutoCloseable {
    val destinationLabel: String

    suspend fun checkAvailability(): Result<String>

    suspend fun getModels(): Result<List<ModelProfile>>

    suspend fun analyzeImage(
        request: AnalyzeImageRequest,
        bitmap: Bitmap
    ): Result<AnalyzeImageResponse>

    override fun close() = Unit
}
