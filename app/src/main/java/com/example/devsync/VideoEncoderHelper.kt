package com.example.devsync

import android.graphics.Bitmap
import android.media.*
import android.os.Build
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

object VideoEncoderHelper {

    fun encode(frames: List<Bitmap>, fps: Int, outFile: File): Boolean {
        if (frames.isEmpty()) return false

        // Dimensions must be even for H.264
        val w = frames[0].width.let  { if (it % 2 == 0) it else it - 1 }
        val h = frames[0].height.let { if (it % 2 == 0) it else it - 1 }

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
            frames.forEachIndexed { idx, bitmap ->
                // Draw frame onto codec surface
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

                // Drain any available output from encoder
                drain@ while (true) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                && info.size > 0 && muxerStarted) {
                                info.presentationTimeUs = idx * usPerFrame
                                val buf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                                muxer.writeSampleData(trackIndex, buf, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@drain
                        }
                        else -> break@drain
                    }
                }
            }

            // Signal end of stream
            codec.signalEndOfInputStream()

            // Drain remaining frames until EOS
            var eos = false
            val endPts = frames.size * usPerFrame
            while (!eos) {
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            && info.size > 0 && muxerStarted) {
                            if (info.presentationTimeUs == 0L) info.presentationTimeUs = endPts
                            muxer.writeSampleData(trackIndex, codec.getOutputBuffer(outIdx)!!, info)
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
