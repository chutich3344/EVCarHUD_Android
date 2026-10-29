package com.kevin.evcarhud

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val MODEL_NAME = "big_driving_supercombo.onnx"
private const val CTX_NAME = "model_qnn_ctx.onnx"

// Nhịp xung NPU (Hexagon): "balanced" chạy xung thấp hơn, mát hơn; đủ cho 10 lần/giây. Runner đặt theo "Tiết kiệm nhiệt"
@Volatile var htpMode = "sustained_high_performance"

// comma.ai bản nhẹ: driving_supercombo.onnx của openpilot (~61MB, MIT) thay bản lớn 756MB. Cùng đầu vào, đầu ra
// 2576 số xếp khác thứ tự -> chép về đúng chỗ của bản lớn để Hud.kt / Runner đọc như cũ. Runner đặt theo cài đặt.
@Volatile var smallComma = true
private const val SMALL_MODEL = "driving_supercombo.onnx"
private const val SMALL_CTX = "small_qnn_ctx.onnx"
private const val SMALL_OUT = 2576
// (vị trí trong bản nhẹ, vị trí trong bản lớn, độ dài): làn, xác suất làn, mép đường, meta, desire, pose,
// wide_from_device, road_transform, quỹ đạo, xe dẫn đầu, xác suất xe dẫn đầu, desire_state
private val SMALL_MAP = intArrayOf(
    117, 0, 528, 645, 528, 8, 653, 536, 264, 0, 800, 55, 55, 855, 32, 87, 887, 12,
    99, 899, 6, 105, 905, 12, 1576, 917, 990, 917, 1907, 144, 1061, 2051, 3, 2566, 2054, 8
)

private val STATE_MAP = listOf(
    "state_img_q" to "next_state_img_q",
    "state_desire_q" to "next_state_desire_q",
    "state_feat_q" to "next_state_feat_q"
)

object Preprocess {
    const val W = 512
    const val H = 256
    const val CW = W / 2
    const val CH = H / 2
    const val PLANE = CW * CH
    const val STREAM = 6 * PLANE
    const val INPUT_SIZE = 2 * STREAM   // UINT8 [2, 6, 128, 256]
}

// Warp trực tiếp trên mặt phẳng Y/U/V gốc vào tensor 6 kênh của model (không qua RGB).
// Mỗi điểm ra lấy trung bình 4 mẫu (siêu lấy mẫu 2x2) để giảm răng cưa khi thu nhỏ.
class YuvWarper {
    private val xa = IntArray(Preprocess.W)
    private val xb = IntArray(Preprocess.W)
    private val ya = IntArray(Preprocess.H)
    private val yb = IntArray(Preprocess.H)
    private val cxa = IntArray(Preprocess.CW)
    private val cxb = IntArray(Preprocess.CW)
    private val cya = IntArray(Preprocess.CH)
    private val cyb = IntArray(Preprocess.CH)

    private fun cl(v: Int, hi: Int) = if (v < 0) 0 else if (v > hi) hi else v

    // (cx, cy): điểm chính trên ảnh nguồn (pixel gốc); flModel/cyModel: tiêu cự và điểm chính của model
    fun warp(f: YuvFrame, fSrc: Float, cx: Float, cy: Float, flModel: Float, cyModel: Float, out: ByteArray, base: Int) {
        val s = fSrc / flModel
        val q = 0.25f * s
        val w = f.w
        val h = f.h
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val W = Preprocess.W
        val H = Preprocess.H
        val CW = Preprocess.CW
        val CH = Preprocess.CH
        val PL = Preprocess.PLANE

        for (xo in 0 until W) {
            val xs = (xo + 0.5f - 256f) * s + cx
            xa[xo] = cl((xs - q).toInt(), w - 1)
            xb[xo] = cl((xs + q).toInt(), w - 1)
        }
        for (yo in 0 until H) {
            val ys = (yo + 0.5f - cyModel) * s + cy
            ya[yo] = cl((ys - q).toInt(), h - 1) * f.yRow
            yb[yo] = cl((ys + q).toInt(), h - 1) * f.yRow
        }
        for (c in 0 until CW) {
            val xc = ((2 * c + 1f - 256f) * s + cx) * 0.5f
            cxa[c] = cl((xc - q).toInt(), cw - 1) * f.uvPix
            cxb[c] = cl((xc + q).toInt(), cw - 1) * f.uvPix
        }
        for (r in 0 until CH) {
            val yc = ((2 * r + 1f - cyModel) * s + cy) * 0.5f
            cya[r] = cl((yc - q).toInt(), ch - 1) * f.uvRow
            cyb[r] = cl((yc + q).toInt(), ch - 1) * f.uvRow
        }

        // Luma: 4 mặt phẳng con theo thứ tự (dy + 2*dx)
        val yA = f.yArr
        for (yo in 0 until H) {
            val r = yo shr 1
            val dy = yo and 1
            val r0 = ya[yo]
            val r1 = yb[yo]
            for (xo in 0 until W) {
                val c = xo shr 1
                val dx = xo and 1
                val a = xa[xo]
                val b = xb[xo]
                val sum = (yA[r0 + a].toInt() and 0xFF) + (yA[r0 + b].toInt() and 0xFF) +
                        (yA[r1 + a].toInt() and 0xFF) + (yA[r1 + b].toInt() and 0xFF)
                out[base + (dy + 2 * dx) * PL + r * CW + c] = ((sum + 2) shr 2).toByte()
            }
        }

        // Chroma U, V
        val uA = f.uArr
        val vA = f.vArr
        for (r in 0 until CH) {
            val r0 = cya[r]
            val r1 = cyb[r]
            for (c in 0 until CW) {
                val a = cxa[c]
                val b = cxb[c]
                val i = r * CW + c
                val u = (uA[r0 + a].toInt() and 0xFF) + (uA[r0 + b].toInt() and 0xFF) +
                        (uA[r1 + a].toInt() and 0xFF) + (uA[r1 + b].toInt() and 0xFF)
                val v = (vA[r0 + a].toInt() and 0xFF) + (vA[r0 + b].toInt() and 0xFF) +
                        (vA[r1 + a].toInt() and 0xFF) + (vA[r1 + b].toInt() and 0xFF)
                out[base + 4 * PL + i] = ((u + 2) shr 2).toByte()
                out[base + 5 * PL + i] = ((v + 2) shr 2).toByte()
            }
        }
    }
}

class DrivingEngine(private val context: Context) {
    val small = smallComma
    private val modelName = if (small) SMALL_MODEL else MODEL_NAME
    private val ctxName = if (small) SMALL_CTX else CTX_NAME
    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var prev: OrtSession.Result? = null
    private val zeros = mutableListOf<OnnxTensor>()
    private lateinit var desireT: OnnxTensor
    private lateinit var trafficT: OnnxTensor
    private lateinit var actionT: OnnxTensor
    private val inBuf: ByteBuffer = ByteBuffer.allocateDirect(Preprocess.INPUT_SIZE)

    // Có trong assets thì chép ra, không thì tải từ máy chủ (chỉ lần đầu, khi chưa có cache NPU)
    private fun ensureModelFile(): File = ModelFiles.ensure(context, modelName)

    private fun floatTensor(values: FloatArray, shape: LongArray): OnnxTensor {
        val fb = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(values); fb.rewind()
        return OnnxTensor.createTensor(env, fb, shape)
    }

    private fun zeroTensor(name: String): OnnxTensor {
        val t = session!!.inputInfo[name]!!.info as TensorInfo
        val shape = t.shape
        val count = shape.fold(1L) { a, b -> a * b }.toInt()
        return if (t.type == OnnxJavaType.UINT8) {
            OnnxTensor.createTensor(env, ByteBuffer.allocateDirect(count), shape, OnnxJavaType.UINT8)
        } else {
            val fb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            OnnxTensor.createTensor(env, fb, shape)
        }
    }

    private fun options(writeCache: Boolean, ctxFile: File) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        addQnn(
            mapOf(
                "backend_path" to "libQnnHtp.so",
                "htp_performance_mode" to htpMode,
                "enable_htp_fp16_precision" to "1"
            )
        )
        if (writeCache) {
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_file_path", ctxFile.absolutePath)
            addConfigEntry("ep.context_embed_mode", "0")
        }
    }

    fun load(): Long {
        val ctxFile = File(context.filesDir, ctxName)
        val t0 = SystemClock.elapsedRealtime()
        var created = false
        if (ctxFile.exists()) {
            try {
                session = env.createSession(ctxFile.absolutePath, options(false, ctxFile))
                created = true
            } catch (e: Throwable) {
                // Cache hỏng hoặc không tương thích: xóa và biên dịch lại từ model gốc
                Log.w("ModelInfo", "QNN cache lỗi, biên dịch lại", e)
                ctxFile.delete()
            }
        }
        if (!created) {
            session = env.createSession(ensureModelFile().absolutePath, options(true, ctxFile))
        }
        desireT = floatTensor(FloatArray(8), longArrayOf(8))
        // Xe lái bên trái, đi bên phải đường (Việt Nam) = (1, 0)
        trafficT = floatTensor(floatArrayOf(1f, 0f), longArrayOf(1, 2))
        actionT = floatTensor(FloatArray(2), longArrayOf(1, 2))
        return SystemClock.elapsedRealtime() - t0
    }

    fun reset() {
        prev?.close(); prev = null
        zeros.forEach { it.close() }; zeros.clear()
    }

    fun close() { reset(); try { session?.close() } catch (_: Throwable) {}; session = null }

    fun step(yuv: ByteArray): FloatArray {
        val s = session!!
        inBuf.clear()
        inBuf.put(yuv)
        inBuf.rewind()
        val img = OnnxTensor.createTensor(env, inBuf, longArrayOf(2, 6, 128, 256), OnnxJavaType.UINT8)

        val inputs = HashMap<String, OnnxTensor>()
        inputs["new_img"] = img
        inputs["desire"] = desireT
        inputs["traffic_convention"] = trafficT
        inputs["action_t"] = actionT
        val p = prev
        for ((inName, outName) in STATE_MAP) {
            inputs[inName] = if (p != null) {
                p.get(outName).get() as OnnxTensor
            } else {
                zeroTensor(inName).also { zeros.add(it) }
            }
        }

        val res = s.run(inputs)
        img.close()
        prev?.close()
        zeros.forEach { it.close() }; zeros.clear()
        prev = res

        val fb = (res.get("outputs").get() as OnnxTensor).floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        if (arr.size != SMALL_OUT) return arr
        val o = FloatArray(2066)
        var i = 0
        while (i < SMALL_MAP.size) { System.arraycopy(arr, SMALL_MAP[i], o, SMALL_MAP[i + 1], SMALL_MAP[i + 2]); i += 3 }
        return o
    }
}