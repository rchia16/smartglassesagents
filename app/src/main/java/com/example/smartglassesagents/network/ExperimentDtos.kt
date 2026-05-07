package com.example.smartglassesagents.network

import com.example.smartglassesagents.experiment.CaptureSource
import com.example.smartglassesagents.experiment.TaskType
import org.json.JSONArray
import org.json.JSONObject

data class AnalyzeImageRequest(
    val sessionId: String,
    val taskType: TaskType,
    val prompt: String,
    val voiceTranscript: String,
    val imageBase64: String,
    val imageMimeType: String,
    val captureSource: CaptureSource,
    val capturedAtMs: Long,
    val liveSample: Boolean = false,
    val sampleIndex: Int? = null
) {
    fun toJson(): JSONObject {
        val captureMetadata = JSONObject()
            .put("source", captureSource.wireName)
            .put("captured_at_ms", capturedAtMs)
            .put("mode", if (liveSample) "live_sample" else "single_capture")
        sampleIndex?.let { captureMetadata.put("sample_index", it) }

        val json = JSONObject()
            .put("session_id", sessionId)
            .put("task_type", taskType.wireName)
            .put("prompt", prompt)
            .put("image_base64", imageBase64)
            .put("image_mime_type", imageMimeType)
            .put("capture_metadata", captureMetadata)

        if (voiceTranscript.isNotBlank()) {
            json
                .put("voice_transcript", voiceTranscript.trim())
                .put("audio_metadata", JSONObject()
                    .put("source", "phone_speech_recognizer")
                    .put("raw_audio_stored", false)
                )
        }

        return json
    }
}

data class AnalyzeImageResponse(
    val runId: String,
    val taskType: TaskType,
    val selectedSpeechAgent: String,
    val results: List<AgentResult>,
    val speechText: String
) {
    companion object {
        fun fromJson(json: JSONObject): AnalyzeImageResponse {
            val resultsJson = json.optJSONArray("results") ?: JSONArray()
            val parsedResults = buildList {
                for (index in 0 until resultsJson.length()) {
                    add(AgentResult.fromJson(resultsJson.getJSONObject(index)))
                }
            }

            return AnalyzeImageResponse(
                runId = json.optString("run_id", ""),
                taskType = TaskType.fromWireName(json.optString("task_type")),
                selectedSpeechAgent = json.optString("selected_speech_agent", ""),
                results = parsedResults,
                speechText = json.optString("speech_text", "")
            )
        }
    }
}

data class AgentResult(
    val agentProfile: String,
    val modelId: String,
    val runtime: String,
    val promptVersion: String,
    val answer: String,
    val observations: List<String>,
    val locations: List<ResultLocation>,
    val confidence: Double,
    val uncertainties: List<String>,
    val latencyMs: Long
) {
    companion object {
        fun fromJson(json: JSONObject): AgentResult =
            AgentResult(
                agentProfile = json.optString("agent_profile", ""),
                modelId = json.optString("model_id", ""),
                runtime = json.optString("runtime", ""),
                promptVersion = json.optString("prompt_version", ""),
                answer = json.optString("answer", ""),
                observations = json.optJSONArray("observations").toStringList(),
                locations = json.optJSONArray("locations").toLocations(),
                confidence = json.optDouble("confidence", 0.0),
                uncertainties = json.optJSONArray("uncertainties").toStringList(),
                latencyMs = json.optLong("latency_ms", 0L)
            )
    }
}

data class ResultLocation(
    val label: String,
    val position: String,
    val confidence: Double
) {
    companion object {
        fun fromJson(json: JSONObject): ResultLocation =
            ResultLocation(
                label = json.optString("label", ""),
                position = json.optString("position", ""),
                confidence = json.optDouble("confidence", 0.0)
            )
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(optString(index))
        }
    }
}

private fun JSONArray?.toLocations(): List<ResultLocation> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(ResultLocation.fromJson(getJSONObject(index)))
        }
    }
}
