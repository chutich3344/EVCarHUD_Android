package com.kevin.evcarhud

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
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val YTAG = "YoloInfo"

// Tọa độ hộp tính theo pixel của ảnh nguồn (độ phân giải gốc của video).
// id: mã theo dõi (DetTracker gán, -1 = chưa theo dõi); vx, vy: vận tốc tâm hộp (pixel ảnh nguồn / giây).
// cls: mã COCO (0 người, 1 xe đạp, 2 ô tô, 3 xe máy, 5 xe buýt, 7 xe tải, 9 đèn, 11 STOP);
// biển giới hạn tốc độ của model riêng: 100 + tốc độ (ví dụ 150 = biển 50 km/h).
class Detection(
    val cls: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val id: Int = -1, val vx: Float = 0f, val vy: Float = 0f
)

const val SIGN_BASE = 100
fun isSpeedSign(c: Int) = c > SIGN_BASE && c <= SIGN_BASE + 130

// ox, oy: gốc của vùng quan tâm (ROI) trên ảnh nguồn, cộng lại khi đổi hộp về toạ độ ảnh gốc
class Letterbox(val scale: Float, val padX: Float, val padY: Float, val ox: Float = 0f, val oy: Float = 0f)

class YoloInput(val buf: FloatBuffer, val lb: Letterbox)

fun clsName(c: Int) = when (c) {
    0 -> "Người"
    1 -> "Xe đạp"
    2 -> "Ô tô"
    3 -> "Xe máy"
    5 -> "Xe buýt"
    7 -> "Xe tải"
    9 -> "Đèn GT"
    11 -> "Biển STOP"
    else -> if (isSpeedSign(c)) "Biển ${c - SIGN_BASE}" else "?"
}

// YOLOv8 / YOLO11 xuất ONNX (đầu ra [1, 4+nc, N], không NMS). Chạy trên QNN (NPU), lỗi thì rơi về CPU.
// Model mặc định: COCO. Model riêng cho đường Việt Nam: thay assets/yolo_det.onnx và thêm assets/yolo_labels.txt
// (mỗi dòng một tên lớp theo đúng thứ tự lúc huấn luyện). Tên được đổi về mã của app bằng labelCode().
class YoloDetector(private val context: Context) {
    companion object {
        const val MODEL = "yolo_det.onnx"
        const val CTX = "yolo_qnn_ctx.onnx"
        // COCO: 0 người, 1 xe đạp, 2 ô tô, 3 xe máy, 5 xe buýt, 7 xe tải, 9 đèn giao thông, 11 biển STOP
        val CLASSES = intArrayOf(0, 1, 2, 3, 5, 7, 9, 11)
        const val CONF = 0.25f
        const val STOP_CONF = 0.55f    // biển STOP hay bị nhận nhầm (biển đỏ tròn, đèn hậu): ngưỡng cao hơn
        const val LIGHT_CONF = 0.35f
        const val IOU = 0.50f
        const val MAX_CAND = 300
        const val MAX_DET = 40
        const val LABELS = "yolo_labels.txt"
        const val SIGN_CONF = 0.45f

        // Tên lớp (tiếng Anh hoặc không dấu) -> mã của app; -1 = bỏ qua lớp đó
        fun labelCode(name: String): Int {
            val n = name.trim().lowercase().replace(' ', '_').replace('-', '_')
            Regex("(speed|limit|toc_do|bien)_?(limit_)?(\\d{2,3})").find(n)?.let {
                return SIGN_BASE + it.groupValues[3].toInt()
            }
            return when (n) {
                "person", "pedestrian", "nguoi", "nguoi_di_bo" -> 0
                "bicycle", "bike", "xe_dap" -> 1
                "car", "oto", "o_to", "van", "taxi" -> 2
                "motorbike", "motorcycle", "motor", "xe_may", "scooter" -> 3
                "bus", "xe_buyt", "coach" -> 5
                "truck", "xe_tai", "pickup", "container", "tricycle", "xe_ba_gac" -> 7
                "traffic_light", "den_giao_thong" -> 9
                "stop", "stop_sign" -> 11
                else -> -1
            }
        }
    }

    // Bảng chỉ số lớp của model -> mã của app (null = model COCO, dùng CLASSES)
    private var classMap: IntArray? = null

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var inName = "images"

    var inW = 640
        private set
    var inH = 384
        private set
    var onNpu = false
        private set

    private lateinit var bufs: Array<FloatBuffer>
    private var bi = 0
    private lateinit var scratch: FloatArray
    private var xa = IntArray(0)
    private var xb = IntArray(0)
    private var xc = IntArray(0)
    private var ya = IntArray(0)
    private var yb = IntArray(0)
    private var yc = IntArray(0)

    // Chép model từ assets ra bộ nhớ trong. Model trong assets đổi (khác kích thước) thì chép lại và xoá cache NPU cũ,
    // nếu không thay model mới vào assets vẫn chạy model cũ. Đếm byte (~vài chục ms) vì asset nén không đọc được độ dài.
    private fun ensureModelFile(): File {
        val file = File(context.filesDir, MODEL)
        val assetLen = try {
            context.assets.open(MODEL).use { ins ->
                val buf = ByteArray(1 shl 16)
                var t = 0L
                while (true) { val r = ins.read(buf); if (r < 0) break; t += r }
                t
            }
        } catch (_: Throwable) { -1L }
        if (!file.exists() || (assetLen > 0 && file.length() != assetLen)) {
            context.assets.open(MODEL).use { i -> file.outputStream().use { i.copyTo(it) } }
            File(context.filesDir, CTX).delete()
        }
        return file
    }

    // Model riêng có lớp biển giới hạn tốc độ (assets/yolo_labels.txt): YOLO nhìn cả bề ngang khung
    val readsSigns: Boolean get() = classMap?.any { isSpeedSign(it) } == true

    private fun loadLabels() {
        classMap = try {
            context.assets.open(LABELS).bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
                .map { labelCode(it) }.toIntArray()
        } catch (_: Throwable) { null }
        Log.d(YTAG, "labels=${classMap?.joinToString() ?: "COCO"}")
    }

    private fun qnnOptions(writeCache: Boolean, ctxFile: File) = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        addQnn(
            mapOf(
                "backend_path" to "libQnnHtp.so",
                "htp_performance_mode" to "sustained_high_performance",
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
        val t0 = SystemClock.elapsedRealtime()
        loadLabels()
        val mf0 = ensureModelFile()   // trước khi đọc cache: model đổi thì cache đã bị xoá
        val ctxFile = File(context.filesDir, CTX)
        var s: OrtSession? = null
        if (ctxFile.exists()) {
            try {
                s = env.createSession(ctxFile.absolutePath, qnnOptions(false, ctxFile))
                onNpu = true
            } catch (e: Throwable) {
                Log.w(YTAG, "YOLO cache lỗi, biên dịch lại", e)
                ctxFile.delete()
            }
        }
        if (s == null) {
            val mf = mf0
            try {
                s = env.createSession(mf.absolutePath, qnnOptions(true, ctxFile))
                onNpu = true
            } catch (e: Throwable) {
                Log.w(YTAG, "YOLO QNN lỗi, dùng CPU", e)
                s = env.createSession(mf.absolutePath, OrtSession.SessionOptions())
                onNpu = false
            }
        }
        val sess = s ?: throw IllegalStateException("Không tạo được phiên YOLO")
        session = sess
        inName = sess.inputNames.first()
        val shape = (sess.inputInfo[inName]!!.info as TensorInfo).shape
        if (shape.size == 4 && shape[2] > 0 && shape[3] > 0) {
            inH = shape[2].toInt()
            inW = shape[3].toInt()
        }
        val sz = 3 * inH * inW
        bufs = Array(3) { ByteBuffer.allocateDirect(sz * 4).order(ByteOrder.nativeOrder()).asFloatBuffer() }
        scratch = FloatArray(sz)
        xa = IntArray(inW); xb = IntArray(inW); xc = IntArray(inW)
        ya = IntArray(inH); yb = IntArray(inH); yc = IntArray(inH)
        Log.d(YTAG, "YOLO in=${inW}x$inH npu=$onNpuText")
        return SystemClock.elapsedRealtime() - t0
    }

    private val onNpuText get() = if (onNpu) "yes" else "no"

    // Ba bộ đệm xoay vòng (hàng đợi suy luận có thể giữ trước 2-3 khung) để luồng tiền xử lý không ghi đè khi NPU còn đang đọc
    fun nextBuf(): FloatBuffer {
        bi = (bi + 1) % bufs.size
        return bufs[bi]
    }

    private fun cl(v: Int, hi: Int) = if (v < 0) 0 else if (v > hi) hi else v

    // Luồng tiền xử lý: YUV gốc -> RGB float [3, inH, inW] (letterbox, nền xám 114)
    // (rx, ry, rw, rh): vùng quan tâm trên ảnh nguồn (pixel gốc), được letterbox vào đầu vào model
    fun prepare(f: YuvFrame, dst: FloatBuffer, rx: Int, ry: Int, rw: Int, rh: Int): Letterbox {
        val scale = min(inW / rw.toFloat(), inH / rh.toFloat())
        val nw = (rw * scale).roundToInt().coerceIn(1, inW)
        val nh = (rh * scale).roundToInt().coerceIn(1, inH)
        val padX = (inW - nw) / 2
        val padY = (inH - nh) / 2
        val plane = inW * inH
        java.util.Arrays.fill(scratch, 114f / 255f)

        val w = f.w
        val h = f.h
        val q = 0.25f / scale
        for (x in 0 until nw) {
            val sx = (x + 0.5f) / scale + rx
            xa[x] = cl((sx - q).toInt(), w - 1)
            xb[x] = cl((sx + q).toInt(), w - 1)
            xc[x] = (cl(sx.toInt(), w - 1) shr 1) * f.uvPix
        }
        for (y in 0 until nh) {
            val sy = (y + 0.5f) / scale + ry
            ya[y] = cl((sy - q).toInt(), h - 1) * f.yRow
            yb[y] = cl((sy + q).toInt(), h - 1) * f.yRow
            yc[y] = (cl(sy.toInt(), h - 1) shr 1) * f.uvRow
        }

        val yA = f.yArr
        val uA = f.uArr
        val vA = f.vArr
        val inv = 1f / 255f
        for (y in 0 until nh) {
            val r0 = ya[y]
            val r1 = yb[y]
            val rc = yc[y]
            val o = (y + padY) * inW + padX
            for (x in 0 until nw) {
                val ys = (yA[r0 + xa[x]].toInt() and 0xFF) + (yA[r0 + xb[x]].toInt() and 0xFF) +
                        (yA[r1 + xa[x]].toInt() and 0xFF) + (yA[r1 + xb[x]].toInt() and 0xFF)
                val yy = (ys shr 2) - 16
                val ci = rc + xc[x]
                val u = (uA[ci].toInt() and 0xFF) - 128
                val v = (vA[ci].toInt() and 0xFF) - 128
                val c = (if (yy < 0) 0 else yy) * 298 + 128
                var r = (c + 459 * v) shr 8
                var g = (c - 55 * u - 136 * v) shr 8
                var b = (c + 541 * u) shr 8
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                val idx = o + x
                scratch[idx] = r * inv
                scratch[plane + idx] = g * inv
                scratch[2 * plane + idx] = b * inv
            }
        }
        dst.clear()
        dst.put(scratch)
        dst.rewind()
        return Letterbox(scale, padX.toFloat(), padY.toFloat(), rx.toFloat(), ry.toFloat())
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val iy = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val inter = ix * iy
        val u = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (u <= 0f) 0f else inter / u
    }

    // Luồng NPU: chạy model, giải mã đầu ra, NMS theo từng lớp
    fun detect(buf: FloatBuffer, lb: Letterbox, srcW: Int, srcH: Int): List<Detection> {
        val sess = session ?: return emptyList()
        val tin = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, inH.toLong(), inW.toLong()))
        val arr: FloatArray
        val d1: Int
        val d2: Int
        try {
            val res = sess.run(mapOf(inName to tin))
            try {
                val ot = res.get(0) as OnnxTensor
                val sh = (ot.info as TensorInfo).shape
                d1 = sh[1].toInt()
                d2 = sh[2].toInt()
                val fb = ot.floatBuffer
                arr = FloatArray(fb.remaining())
                fb.get(arr)
            } finally {
                res.close()
            }
        } finally {
            tin.close()
        }

        val chanMajor = d1 < d2          // [1, 4+nc, N] hoặc [1, N, 4+nc]
        val nc = if (chanMajor) d1 else d2
        val n = if (chanMajor) d2 else d1
        val sC = if (chanMajor) n else 1
        val sI = if (chanMajor) 1 else nc

        val cands = ArrayList<Detection>()
        val map = classMap
        val nCls = nc - 4
        for (i in 0 until n) {
            var bestC = -1
            var bestS = CONF
            if (map == null) {
                for (cc in CLASSES) {
                    if (4 + cc >= nc) continue
                    val sc = arr[(4 + cc) * sC + i * sI]
                    val th = when (cc) { 11 -> STOP_CONF; 9 -> LIGHT_CONF; else -> CONF }
                    if (sc > th && sc > bestS) { bestS = sc; bestC = cc }
                }
            } else {
                // Model riêng: chỉ số lớp -> mã app theo yolo_labels.txt
                for (k in 0 until minOf(nCls, map.size)) {
                    val code = map[k]
                    if (code < 0) continue
                    val sc = arr[(4 + k) * sC + i * sI]
                    val th = when {
                        code == 11 -> STOP_CONF
                        code == 9 -> LIGHT_CONF
                        isSpeedSign(code) -> SIGN_CONF
                        else -> CONF
                    }
                    if (sc > th && sc > bestS) { bestS = sc; bestC = code }
                }
            }
            if (bestC < 0) continue
            val cx = arr[i * sI]
            val cy = arr[sC + i * sI]
            val bw = arr[2 * sC + i * sI]
            val bh = arr[3 * sC + i * sI]
            val x1 = ((cx - bw / 2f - lb.padX) / lb.scale + lb.ox).coerceIn(0f, srcW.toFloat())
            val y1 = ((cy - bh / 2f - lb.padY) / lb.scale + lb.oy).coerceIn(0f, srcH.toFloat())
            val x2 = ((cx + bw / 2f - lb.padX) / lb.scale + lb.ox).coerceIn(0f, srcW.toFloat())
            val y2 = ((cy + bh / 2f - lb.padY) / lb.scale + lb.oy).coerceIn(0f, srcH.toFloat())
            if (x2 - x1 < 2f || y2 - y1 < 2f) continue
            cands.add(Detection(bestC, bestS, x1, y1, x2, y2))
        }
        cands.sortByDescending { it.score }
        val pool = if (cands.size > MAX_CAND) cands.subList(0, MAX_CAND) else cands

        val kept = ArrayList<Detection>()
        for (d in pool) {
            var ok = true
            for (kd in kept) {
                if (kd.cls == d.cls && iou(kd, d) > IOU) { ok = false; break }
            }
            if (ok) kept.add(d)
            if (kept.size >= MAX_DET) break
        }

        // Bỏ "người" đang ngồi trên xe máy / xe đạp (chân nằm trong khung xe) cho đỡ rối
        val out = ArrayList<Detection>()
        for (d in kept) {
            if (d.cls == 0) {
                val px = (d.x1 + d.x2) / 2f
                val rider = kept.any { m ->
                    (m.cls == 3 || m.cls == 1) &&
                            px >= m.x1 && px <= m.x2 &&
                            d.y2 >= m.y1 && d.y2 <= m.y2 + 0.15f * (m.y2 - m.y1)
                }
                if (rider) continue
            }
            out.add(d)
        }
        return out
    }
}
