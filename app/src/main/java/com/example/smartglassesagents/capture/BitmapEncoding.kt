package com.example.smartglassesagents.capture

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

fun Bitmap.toJpegBase64(quality: Int = 85): String {
    val output = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}
