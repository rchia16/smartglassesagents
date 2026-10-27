package com.alibaba.mnnllm.android.llm

/** Minimal JNI-compatible wrapper around the MNN Chat on-device runtime. */
class LlmSession(
    private val configPath: String,
    private val mergedConfigJson: String,
    private val runtimeConfigJson: String
) : AutoCloseable {
    private var nativePtr: Long = 0

    fun load() {
        check(nativePtr == 0L) { "MNN session is already loaded." }
        nativePtr = initNative(
            configPath,
            null,
            mergedConfigJson,
            runtimeConfigJson
        )
        check(nativePtr != 0L) { "MNN failed to load the model." }
    }

    fun generate(prompt: String): String {
        check(nativePtr != 0L) { "MNN session is not loaded." }
        val output = StringBuilder()
        val metrics = submitNative(
            nativePtr,
            prompt,
            false,
            GenerateProgressListener { chunk ->
                if (chunk != null) output.append(chunk)
                false
            }
        )
        metrics["error"]?.let { throw IllegalStateException(it.toString()) }
        return output.toString().trim()
    }

    override fun close() {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0
        }
    }

    private external fun initNative(
        configPath: String,
        history: List<String>?,
        mergedConfigStr: String,
        configJsonStr: String
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    private external fun releaseNative(instanceId: Long)

    companion object {
        init {
            System.loadLibrary("MNN")
            System.loadLibrary("mnnllmapp")
        }
    }
}
