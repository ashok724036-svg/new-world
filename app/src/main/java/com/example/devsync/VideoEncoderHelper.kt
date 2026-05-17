package com.example.devsync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.os.Build
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

object VideoEncoderHelper {

    /** Encode from in-memory bitmaps (existing API — kept for compatibility) */
    fun encode(frames: List<Bitmap>, fps: Int, outFile: File): Boolean =
        encodeInternal(frames.size, fps, outFile) { idx ->
            frames[idx]
        }

    /** Encode from JPEG files on disk — one bitmap in memory at a time, no OOM */
    fun encodeFromFiles(files: List<File>, fps: Int, outFile: File): Boolean =
        encodeInternal(files.size, fps, outFile) { idx ->
            BitmapFactory.decodeFile(files[idx].absolutePath)
        }

    /**
     * Core encoder — [frameCount] frames, loaded one at a time via [getFrame].
     * Caller must NOT hold extra references; each bitmap is recycled after use.
     */
    private fun encodeInternal(
        frameCount: Int,
        fps: Int,
        outFile: File,
        getFrame: (Int) -> Bitmap?
    ): Boolean {
        if (frameCount == 0) return false

        // Determine dimensions from first frame
        val first = getFrame(0) ?: return false
        val w = first.width.let  { if (it % 2 == 0) it else it - 1 }
        val h = first.height.let { if (it % 2 == 0) it else it - 1 }
        first.recycle()

        val muxer  = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        val codec   = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = codec.createInputSurface()
        codec.start()

        var trackIndex   = -1
        var muxerStarted = false
        val info         = MediaCodec.BufferInfo()
        val usPerFrame   = 1_000_000L / fps

        try {
            for (idx in 0 until frameCount) {
                val bitmap = getFrame(idx) ?: continue

                // Draw onto codec surface
                val canvas = if (Build.VERSION.SDK_INT >= 23)
                    surface.lockHardwareCanvas()
                else
                    surface.lockCanvas(null)

                if (canvas != null) {
                    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
                    canvas.drawBitmap(scaled, 0f, 0f, null)
                    surface.unlockCanvasAndPost(canvas)
                    if (scaled !== bitmap) scaled.recycle()
                }
                bitmap.recycle() // free immediately — crucial for file-based path

                // Drain encoder output
                drain@ while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                && info.size > 0 && muxerStarted) {
                                info.presentationTimeUs = idx * usPerFrame
                                muxer.writeSampleData(trackIndex,
                                    codec.getOutputBuffer(outIdx)!!, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@drain
                        }
                        else -> break@drain
                    }
                }
            }

            // Signal EOS and drain remaining
            codec.signalEndOfInputStream()
            val endPts = frameCount * usPerFrame
            var eos = false
            while (!eos) {
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            && info.size > 0 && muxerStarted) {
                            if (info.presentationTimeUs == 0L) info.presentationTimeUs = endPts
                            muxer.writeSampleData(trackIndex,
                                codec.getOutputBuffer(outIdx)!!, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    }
                    else -> eos = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        return outFile.exists() && outFile.length() > 1024L
    }
}
