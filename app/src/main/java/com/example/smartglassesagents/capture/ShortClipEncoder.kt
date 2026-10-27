package com.example.smartglassesagents.capture

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.smartglassesagents.experiment.CaptureSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class CapturedClip(
    val file: File,
    val source: CaptureSource,
    val capturedAtMs: Long,
    val durationMs: Long,
    val frameCount: Int
)

object ShortClipEncoder {
    private const val FPS = 10

    suspend fun encode(
        cacheDir: File,
        frames: List<Bitmap>,
        source: CaptureSource,
        capturedAtMs: Long
    ): CapturedClip = withContext(Dispatchers.Default) {
        require(frames.size >= 5) { "The camera did not provide enough video frames." }
        val first = frames.first()
        val scale = minOf(1f, 1280f / first.width, 720f / first.height)
        val width = ((first.width * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val height = ((first.height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val file = File(cacheDir, "short-clip-${UUID.randomUUID()}.mp4")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var eosSeen = false

            fun drain(waitForEos: Boolean) {
                var emptyPolls = 0
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Video format changed twice." }
                            track = muxer!!.addTrack(codec.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val output = codec.getOutputBuffer(index)
                            if (info.size > 0 && output != null) {
                                check(muxerStarted) { "Video output arrived before muxer started." }
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                muxer!!.writeSampleData(track, output, info)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                            codec.releaseOutputBuffer(index, false)
                            emptyPolls = 0
                        }
                        else -> {
                            emptyPolls++
                            if (!waitForEos || eosSeen) return
                            check(emptyPolls < 500) { "Video encoder did not finish." }
                        }
                    }
                }
            }

            frames.forEachIndexed { index, bitmap ->
                currentCoroutineContext().ensureActive()
                var inputIndex = codec.dequeueInputBuffer(10_000)
                var inputPolls = 0
                while (inputIndex < 0) {
                    drain(false)
                    check(++inputPolls < 500) { "Video encoder stopped accepting frames." }
                    inputIndex = codec.dequeueInputBuffer(10_000)
                }
                val resized = if (bitmap.width == width && bitmap.height == height) bitmap
                    else Bitmap.createScaledBitmap(bitmap, width, height, true)
                try {
                    val inputSize = codec.getInputBuffer(inputIndex)?.capacity()
                        ?: error("Video encoder has no input buffer.")
                    val image = codec.getInputImage(inputIndex)
                        ?: error("Video encoder has no YUV input image.")
                    try {
                        writeYuv420(resized, image)
                    } finally {
                        image.close()
                    }
                    codec.queueInputBuffer(inputIndex, 0, inputSize,
                        index * 1_000_000L / FPS, 0)
                } finally {
                    if (resized !== bitmap) resized.recycle()
                }
                drain(false)
            }
            var eosIndex = codec.dequeueInputBuffer(10_000)
            var eosPolls = 0
            while (eosIndex < 0) {
                drain(false)
                check(++eosPolls < 500) { "Video encoder did not accept end of clip." }
                eosIndex = codec.dequeueInputBuffer(10_000)
            }
            codec.queueInputBuffer(eosIndex, 0, 0, frames.size * 1_000_000L / FPS,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(eosSeen && muxerStarted) { "Video encoder did not finish the clip." }
            muxer!!.stop()
            muxerStarted = false
            check(file.length() in 1..8_000_000) { "Video encoding failed or clip exceeds 8 MB." }
            CapturedClip(file, source, capturedAtMs, frames.size * 1_000L / FPS, frames.size)
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun writeYuv420(bitmap: Bitmap, image: Image) {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val planes = image.planes
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val r = pixel shr 16 and 0xff
                val g = pixel shr 8 and 0xff
                val b = pixel and 0xff
                val luminance = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                planes[0].buffer.put(y * planes[0].rowStride + x * planes[0].pixelStride,
                    luminance.coerceIn(0, 255).toByte())
                if (x % 2 == 0 && y % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val chromaIndex = (y / 2) * planes[1].rowStride + (x / 2) * planes[1].pixelStride
                    planes[1].buffer.put(chromaIndex, u.toByte())
                    val vIndex = (y / 2) * planes[2].rowStride + (x / 2) * planes[2].pixelStride
                    planes[2].buffer.put(vIndex, v.toByte())
                }
            }
        }
    }
}
