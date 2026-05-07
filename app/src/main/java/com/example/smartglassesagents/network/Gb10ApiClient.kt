package com.example.smartglassesagents.network

import com.example.smartglassesagents.experiment.HostConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class Gb10ApiClient(private val hostConfig: HostConfig) {
    suspend fun checkHealth(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = requestJson(path = "/health", method = "GET")
            json.optString("status", "unknown")
        }
    }

    suspend fun analyzeImage(request: AnalyzeImageRequest): Result<AnalyzeImageResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = requestJson(
                    path = "/analyze_image",
                    method = "POST",
                    body = request.toJson()
                )
                AnalyzeImageResponse.fromJson(json)
            }
        }

    private fun requestJson(path: String, method: String, body: JSONObject? = null): JSONObject {
        val normalizedBase = hostConfig.baseUrl.trim().trimEnd('/')
        val connection = (URL("$normalizedBase$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (hostConfig.pairingToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${hostConfig.pairingToken}")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        val responseText = stream.use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                reader.readText()
            }
        }

        if (statusCode !in 200..299) {
            throw IllegalStateException("GB10 request failed with HTTP $statusCode: $responseText")
        }

        return JSONObject(responseText)
    }
}
