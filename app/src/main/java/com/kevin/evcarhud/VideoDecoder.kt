package com.kevin.evcarhud

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer

// Khung YUV420 thô (độ phân giải gốc), bộ đệm được tái sử dụng giữa các khung
class YuvFrame {
    var w = 0
    var h = 0
    var yArr = ByteArray(0)
    var uArr = ByteArray(0)
    var vArr = ByteArray(0)
    var yRow = 0
    var uvRow = 0
    var uvPix = 0
    var ptsUs = 0L
}

// Giải mã tuần tự bằng phần cứng; trả về khung YUV gốc, không đổi sang RGB
class VideoDecoder(private val context: Context, private val uri: Uri) : FrameSource {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var eos = false
    private var lastOutPts = -1L
    private var gotInterval = false

    var durationUs = 0L
        private set

    // Chu kỳ khung ước lượng từ chính video (không cần biết fps trước)
    override var frameUs = 33_333L
        private set

    override fun open() {
        extractor.setDataSource(context, uri, null)
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                extractor.selectTrack(i)
                format = f
                break
            }
        }
        val fmt = format ?: throw IllegalStateException("Không tìm thấy luồng video")
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        fmt.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        c.configure(fmt, null, null, 0)
        c.start()
        codec = c
    }

    private fun trackInterval(pts: Long) {
        if (lastOutPts >= 0) {
            val d = pts - lastOutPts
            if (d in 5_000L..200_000L) {
                frameUs = if (gotInterval) (frameUs * 9 + d) / 10 else d
                gotInterval = true
            }
        }
        lastOutPts = pts
    }

    // Điền [dst] bằng khung đầu tiên có pts >= minPtsUs; trả về false nếu hết video
    override fun nextFrame(minPtsUs: Long, dst: YuvFrame): Boolean {
        val c = codec ?: return false
        while (!eos) {
            if (!inputDone) {
                val ii = c.dequeueInputBuffer(2000)
                if (ii >= 0) {
                    val buf = c.getInputBuffer(ii)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 2000)
            if (oi >= 0) {
                val pts = info.presentationTimeUs
                val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                var ok = false
                if (info.size > 0) {
                    trackInterval(pts)
                    if (pts >= minPtsUs) {
                        val img = c.getOutputImage(oi)
                        if (img != null) {
                            copyPlanes(img, dst)
                            dst.ptsUs = pts
                            img.close()
                            ok = true
                        }
                    }
                }
                c.releaseOutputBuffer(oi, false)
                if (isEos) eos = true
                if (ok) return true
            }
        }
        return false
    }

    private fun grab(b: ByteBuffer, old: ByteArray): ByteArray {
        val n = b.remaining()
        val arr = if (old.size == n) old else ByteArray(n)
        b.get(arr, 0, n)
        return arr
    }

    private fun copyPlanes(img: Image, f: YuvFrame) {
        if (img.format != ImageFormat.YUV_420_888) {
            throw IllegalStateException("Định dạng ảnh ${img.format} chưa hỗ trợ (video HDR 10-bit?)")
        }
        val p = img.planes
        f.w = img.width
        f.h = img.height
        f.yArr = grab(p[0].buffer, f.yArr)
        f.uArr = grab(p[1].buffer, f.uArr)
        f.vArr = grab(p[2].buffer, f.vArr)
        f.yRow = p[0].rowStride
        f.uvRow = p[1].rowStride
        f.uvPix = p[1].pixelStride
    }

    override fun release() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
        codec = null
    }
}

// YUV420 -> ARGB cho phần hiển thị (BT.709, dải giới hạn), lấy mẫu gần nhất, bộ đệm đầu ra dùng lại
class DisplayConverter {
    private var xY = IntArray(0)
    private var xC = IntArray(0)

    fun convert(f: YuvFrame, dw: Int, dh: Int, out: IntArray) {
        if (xY.size != dw) { xY = IntArray(dw); xC = IntArray(dw) }
        for (i in 0 until dw) {
            val sx = (i.toLong() * f.w / dw).toInt()
            xY[i] = sx
            xC[i] = (sx shr 1) * f.uvPix
        }
        val yA = f.yArr
        val uA = f.uArr
        val vA = f.vArr
        val xy = xY
        val xc = xC
        for (j in 0 until dh) {
            val sy = (j.toLong() * f.h / dh).toInt()
            val yBase = sy * f.yRow
            val uvBase = (sy shr 1) * f.uvRow
            val o = j * dw
            for (i in 0 until dw) {
                val y = (yA[yBase + xy[i]].toInt() and 0xFF) - 16
                val uvIdx = uvBase + xc[i]
                val u = (uA[uvIdx].toInt() and 0xFF) - 128
                val v = (vA[uvIdx].toInt() and 0xFF) - 128
                val c = (if (y < 0) 0 else y) * 298 + 128
                var r = (c + 459 * v) shr 8
                var g = (c - 55 * u - 136 * v) shr 8
                var b = (c + 541 * u) shr 8
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[o + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}