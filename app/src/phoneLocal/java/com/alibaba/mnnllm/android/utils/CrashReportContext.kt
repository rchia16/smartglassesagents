package com.alibaba.mnnllm.android.utils

/** No-op compatibility hook used by the upstream JNI runtime. */
object CrashReportContext {
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun reportLlmSetConfig(stage: String, configJson: String) = Unit
}
