package com.example.smartglassesagents.capture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/** Repackage a phone-camera MP4 without its audio track before upload. */
fun stripAudioFromMp4(input: File, cacheDir: File): File {
    val output = File(cacheDir, "silent-clip-${UUID.randomUUID()}.mp4")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var started = false
    try {
        extractor.setDataSource(input.absolutePath)
        val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("Phone camera clip has no video track.")
        val format = extractor.getTrackFormat(videoTrack)
        check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) {
            "Phone camera must record H.264 MP4 video."
        }
        extractor.selectTrack(videoTrack)
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(input.absolutePath)
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            muxer.setOrientationHint(rotation)
        } finally {
            metadata.release()
        }
        val outputTrack = muxer.addTrack(format)
        muxer.start()
        started = true
        val buffer = ByteBuffer.allocate(8_000_000)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            check(size < buffer.capacity()) { "Phone video sample is too large." }
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            buffer.position(0)
            buffer.limit(size)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
        muxer.stop()
        started = false
        check(output.length() in 1..8_000_000) { "Silent clip exceeds 8 MB." }
        return output
    } catch (error: Exception) {
        output.delete()
        throw error
    } finally {
        if (started) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        extractor.release()
    }
}
