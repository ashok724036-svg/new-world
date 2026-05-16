package com.example.devsync

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.*
import android.os.Build
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

object VideoEncoderHelper {

    fun encode(frames: List<Bitmap>, fps: Int, outFile: File): Boolean {
        if (frames.isEmpty()) return false
        val w = frames[0].width
        val h = frames[0].height
        var muxerStarted = false
        var trackIndex = -1
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = codec.createInputSurface()
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val usPerFrame = (1_000_000L / fps)

        try {
            frames.forEachIndexed { index, bitmap ->
                // Draw bitmap onto encoder surface
                val canvas: Canvas? = if (Build.VERSION.SDK_INT >= 23)
                    surface.lockHardwareCanvas() else surface.lockCanvas(null)
                canvas?.let {
                    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
                    it.drawBitmap(scaled, 0f, 0f, null)
                    surface.unlockCanvasAndPost(it)
                    if (scaled != bitmap) scaled.recycle()
                }
                // Drain encoder
                drainEncoder(codec, muxer, bufferInfo, false,
                    index * usPerFrame, { trackIdx -> trackIndex = trackIdx },
                    { muxerStarted = true }, trackIndex, muxerStarted)
            }
            // Signal EOS
            codec.signalEndOfInputStream()
            drainEncoder(codec, muxer, bufferInfo, true,
                frames.size * usPerFrame.toLong(),
                { trackIdx -> trackIndex = trackIdx },
                { muxerStarted = true }, trackIndex, muxerStarted)
        } finally {
            codec.stop()
            codec.release()
            surface.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
        return outFile.exists() && outFile.length() > 0
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        eos: Boolean,
        ptsUs: Long,
        onTrackAdded: (Int) -> Unit,
        onMuxerStarted: () -> Unit,
        trackIndex: Int,
        muxerStarted: Boolean
    ) {
        var localTrack = trackIndex
        var localStarted = muxerStarted
        val timeout = if (eos) 5_000L else 0L
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeout)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    localTrack = muxer.addTrack(codec.outputFormat)
                    onTrackAdded(localTrack)
                    muxer.start()
                    onMuxerStarted()
                    localStarted = true
                }
                idx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0 && localStarted) {
                        val buf: ByteBuffer = codec.getOutputBuffer(idx)!!
                        info.presentationTimeUs = ptsUs
                        muxer.writeSampleData(localTrack, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!eos) return
            }
        }
    }
}
