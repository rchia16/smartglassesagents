package com.alibaba.mnnllm.android.llm

fun interface GenerateProgressListener {
    fun onProgress(progress: String?): Boolean
}
