package com.kevin.evcarhud

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan

private const val CTAG = "CameraSource"
private const val MAX_W = 1280       // độ rộng khung camera tối đa
private const val TARGET_FPS = 20
private const val NIGHT_MIN_FPS = 10     // ban đêm cho phép camera hạ xuống tối đa 10 fps để phơi sáng lâu hơn

// Nguồn khung hình chung: file video (VideoDecoder), camera điện thoại / USB (CameraSource, UvcSource),
// camera hành trình qua Wi‑Fi (NetStreamSource)
interface FrameSource {
    val frameUs: Long
    fun open()
    // Điền [dst] bằng khung kế tiếp; false nếu hết nguồn / bị dừng
    fun nextFrame(minPtsUs: Long, dst: YuvFrame): Boolean
    fun release()
}

// Camera sau qua Camera2 (không cần thư viện ngoài, không phụ thuộc vòng đời Activity).
// Yêu cầu: quyền CAMERA, điện thoại đặt NGANG (landscape). Khung YUV_420_888 gốc, pts tính từ 0.
// Khi suy luận chậm hơn camera, khung cũ bị bỏ, luôn giữ khung mới nhất.
// external = true: camera USB (UVC) cắm qua OTG mà máy đưa vào Camera2 (LENS_FACING_EXTERNAL; chỉ một số máy hỗ trợ).
// Camera ngoài không xoay theo màn hình nên bỏ qua bước kiểm tra hướng.
class CameraSource(
    private val context: Context,
    private val external: Boolean = false,
    private val alive: () -> Boolean
) : FrameSource {
    companion object {
        private fun externalId(cm: CameraManager): String? = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
            }
        } catch (_: Throwable) { null }

        // Máy có đưa camera USB vào Camera2 không (nếu không phải dùng UvcSource)
        fun hasExternal(context: Context): Boolean =
            externalId(context.getSystemService(Context.CAMERA_SERVICE) as CameraManager) != null
    }

    private val lock = Object()
    private var back = YuvFrame()
    private var ready = false
    private var closed = false
    @Volatile private var error: Throwable? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    // Hình xem trực tiếp qua GPU (TextureView): camera đẩy thẳng khung vào texture, CPU không phải đổi YUV -> RGB.
    // Đặt trước open(); null = không dùng (app tự đổi YUV -> RGB bằng CPU như cũ).
    var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    // true khi phiên camera đang đẩy hình vào previewTexture
    @Volatile var previewing = false
        private set

    private var flip = false
    private var fpsRange: Range<Int>? = null
    private var aeRegion: MeteringRectangle? = null   // vùng đo sáng: giữa khung (mặt đường), bỏ trời và capo
    private var fixFocus = false                      // khoá nét ở vô cực thay vì lấy nét liên tục
    private var firstTsNs = -1L
    private var lastTsNs = -1L

    override var frameUs = 33_333L
        private set

    // FOV ngang ước lượng từ thông số camera (độ); 0 nếu không đọc được
    var hfovDeg = 0f
        private set
    var width = 0
        private set
    var height = 0
        private set

    private fun rotationNeeded(sensor: Int): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rot = when (dm.getDisplay(Display.DEFAULT_DISPLAY).rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - rot + 360) % 360
    }

    override fun open() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var id: String? = null
        var ch: CameraCharacteristics? = null
        val want = if (external) CameraCharacteristics.LENS_FACING_EXTERNAL else CameraCharacteristics.LENS_FACING_BACK
        for (i in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(i)
            if (c.get(CameraCharacteristics.LENS_FACING) == want) {
                id = i; ch = c; break
            }
        }
        if (id == null || ch == null) {
            throw IllegalStateException(if (external) "Không thấy camera USB" else "Không tìm thấy camera sau")
        }

        if (!external) {
            // Máy phải đặt ngang: sensor thường xoay 90°, ở chế độ ngang ảnh gốc đã thẳng (hoặc ngược 180°)
            val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            // Màn hình có thể đang xoay sang ngang: chờ tối đa 3 s trước khi báo lỗi
            var need = rotationNeeded(sensor)
            val deadline = SystemClock.elapsedRealtime() + 3000L
            while ((need == 90 || need == 270) && SystemClock.elapsedRealtime() < deadline && alive()) {
                Thread.sleep(100)
                need = rotationNeeded(sensor)
            }
            // App luôn khoá ngang; có máy (ColorOS) vẫn báo màn hình dọc lúc mới mở hoặc khi tắt tự xoay.
            // Không dừng camera nữa: coi như máy đang ngang chiều thường (ảnh không lật)
            if (need == 90 || need == 270) {
                Log.w(CTAG, "màn hình báo dọc (cần xoay $need°), coi như đang ngang")
                need = 0
            }
            flip = need == 180
        }

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Camera không có cấu hình luồng")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // 1280x720 là đủ cho model (đầu vào chỉ vài trăm pixel) và YOLO; nhẹ hơn 1080p ~2,25 lần (sao chép, chuyển màu) -> mát máy hơn
        val size: Size = sizes.filter { it.width <= MAX_W && abs(it.width * 9 - it.height * 16) <= 16 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= MAX_W }.maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: throw IllegalStateException("Không có kích thước YUV phù hợp")
        width = size.width
        height = size.height

        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sw = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        // Camera USB thường báo tiêu cự/cảm biến không đúng: giữ FOV người dùng đặt
        // Model chạy 20 Hz: xin camera 20 fps (nếu có) thay vì 30 fps, bớt 1/3 khung phải xử lý.
        // Ưu tiên dải có mức dưới ~10–15 fps (ví dụ [10,20]): ban ngày vẫn 20 fps, ban đêm máy được phơi sáng lâu hơn
        // nên hình sáng và ít nhiễu hơn (luồng suy luận đã tự hiệu chỉnh theo thời gian thật giữa 2 khung).
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().filter { it.upper == TARGET_FPS }
        fpsRange = ranges.filter { it.lower >= NIGHT_MIN_FPS }.minByOrNull { it.lower } ?: ranges.maxByOrNull { it.lower }

        // Đo sáng theo vùng giữa khung (đường và xe phía trước) thay vì cả khung: bớt bị trời sáng / đèn pha xe ngược chiều
        // kéo tối cả hình. Vùng đối xứng nên đúng cả khi máy gắn ngược chiều.
        val maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val act = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        aeRegion = if (!external && maxAe > 0 && act != null)
            MeteringRectangle((act.width() * 0.2f).toInt(), (act.height() * 0.3f).toInt(),
                (act.width() * 0.6f).toInt(), (act.height() * 0.4f).toInt(), MeteringRectangle.METERING_WEIGHT_MAX)
        else null

        // Lấy nét: mọi thứ cần nhìn đều xa > 3 m, nên khoá nét ở vô cực cho ổn định (ban đêm AF hay "dò" làm mờ hình).
        // Chỉ khoá khi máy có hiệu chỉnh khoảng cách nét; không có thì giữ lấy nét liên tục.
        val calib = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        fixFocus = !external && minFocus > 0f && calib != null &&
                calib != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        if (!external && f != null && sw != null && f > 0f) {
            hfovDeg = Math.toDegrees(2.0 * atan((sw / (2f * f)).toDouble())).toFloat()
        }

        val t = HandlerThread("camera-src").also { it.start() }
        thread = t
        val hd = Handler(t.looper)
        handler = hd

        previewTexture?.let { st ->
            try {
                st.setDefaultBufferSize(size.width, size.height)
                previewSurface = Surface(st)
            } catch (e: Throwable) {
                Log.w(CTAG, "không dùng được texture xem trực tiếp", e)
                previewSurface = null
            }
        }

        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Throwable) { null }
            if (img != null) {
                try { onImage(img) } catch (e: Throwable) { fail(e) } finally { img.close() }
            }
        }, hd)

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    startSession(d, r, previewSurface)
                }
                override fun onDisconnected(d: CameraDevice) {
                    fail(IllegalStateException("Camera bị ngắt kết nối")); d.close()
                }
                override fun onError(d: CameraDevice, e: Int) {
                    fail(IllegalStateException("Lỗi camera ($e)")); d.close()
                }
            }, hd)
        } catch (e: SecurityException) {
            throw IllegalStateException("Chưa cấp quyền camera")
        } catch (e: CameraAccessException) {
            throw IllegalStateException("Không mở được camera: ${e.message}")
        }
        Log.d(CTAG, "camera $id ${size.width}x${size.height} hfov=$hfovDeg flip=$flip fps=$fpsRange fixFocus=$fixFocus ae=$aeRegion")
    }

    @Suppress("DEPRECATION")
    private fun startSession(d: CameraDevice, r: ImageReader, pv: Surface?) {
        try {
            d.createCaptureSession(listOfNotNull(r.surface, pv), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(r.surface)
                            if (pv != null) addTarget(pv)
                            if (fixFocus) {
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)   // 0 = vô cực
                            } else if (!external) {
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            }
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            aeRegion?.let { set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
                            // Tắt chống rung điện tử: nó cắt/dịch khung nên làm sai hình học (khoảng cách, chân trời)
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        }.build()
                        s.setRepeatingRequest(req, null, handler)
                        previewing = pv != null
                    } catch (e: Throwable) { fail(e) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Máy không cho 2 luồng (YUV + texture) cùng lúc: chạy lại chỉ với YUV, hiển thị bằng CPU như cũ
                    if (pv != null) { Log.w(CTAG, "không chạy được 2 luồng, bỏ hình GPU"); startSession(d, r, null) }
                    else fail(IllegalStateException("Không cấu hình được phiên camera"))
                }
            }, handler)
        } catch (e: Throwable) { fail(e) }
    }

    private fun fail(e: Throwable) {
        Log.e(CTAG, "camera lỗi", e)
        synchronized(lock) { error = e; lock.notifyAll() }
    }

    private fun grab(b: ByteBuffer, old: ByteArray): ByteArray {
        val n = b.remaining()
        val arr = if (old.size == n) old else ByteArray(n)
        b.get(arr, 0, n)
        return arr
    }

    // Xoay 180° tại chỗ cho một mặt phẳng (rs: bước hàng, ps: bước điểm ảnh)
    private fun rot180(a: ByteArray, rs: Int, ps: Int, cols: Int, rows: Int) {
        for (y in 0 until rows) {
            val y2 = rows - 1 - y
            for (x in 0 until cols) {
                val x2 = cols - 1 - x
                if (y2 < y || (y2 == y && x2 <= x)) continue
                val i = y * rs + x * ps
                val j = y2 * rs + x2 * ps
                if (i >= a.size || j >= a.size) continue
                val t = a[i]; a[i] = a[j]; a[j] = t
            }
        }
    }

    private fun onImage(img: Image) {
        if (img.format != ImageFormat.YUV_420_888) throw IllegalStateException("Định dạng ảnh camera ${img.format} chưa hỗ trợ")
        val ts = img.timestamp
        synchronized(lock) {
            if (closed) return
            val p = img.planes
            val f = back
            f.w = img.width
            f.h = img.height
            f.yArr = grab(p[0].buffer, f.yArr)
            f.uArr = grab(p[1].buffer, f.uArr)
            f.vArr = grab(p[2].buffer, f.vArr)
            f.yRow = p[0].rowStride
            f.uvRow = p[1].rowStride
            f.uvPix = p[1].pixelStride
            if (flip) {
                val cw = (f.w + 1) / 2
                val ch = (f.h + 1) / 2
                rot180(f.yArr, f.yRow, 1, f.w, f.h)
                rot180(f.uArr, f.uvRow, f.uvPix, cw, ch)
                rot180(f.vArr, f.uvRow, f.uvPix, cw, ch)
            }
            if (firstTsNs < 0) firstTsNs = ts
            f.ptsUs = (ts - firstTsNs) / 1000L
            if (lastTsNs >= 0) {
                val d = (ts - lastTsNs) / 1000L
                if (d in 5_000L..200_000L) frameUs = (frameUs * 9 + d) / 10
            }
            lastTsNs = ts
            ready = true
            lock.notifyAll()
        }
    }

    override fun nextFrame(minPtsUs: Long, dst: YuvFrame): Boolean {
        return synchronized(lock) {
            while (!ready) {
                error?.let { throw it }
                if (closed || !alive()) return false
                lock.wait(100)
            }
            ready = false
            // Hoán đổi nội dung back <-> dst (không sao chép, bộ đệm được tái sử dụng)
            val b = back
            val w = dst.w; dst.w = b.w; b.w = w
            val h = dst.h; dst.h = b.h; b.h = h
            val y = dst.yArr; dst.yArr = b.yArr; b.yArr = y
            val u = dst.uArr; dst.uArr = b.uArr; b.uArr = u
            val v = dst.vArr; dst.vArr = b.vArr; b.vArr = v
            val yr = dst.yRow; dst.yRow = b.yRow; b.yRow = yr
            val ur = dst.uvRow; dst.uvRow = b.uvRow; b.uvRow = ur
            val up = dst.uvPix; dst.uvPix = b.uvPix; b.uvPix = up
            val pt = dst.ptsUs; dst.ptsUs = b.ptsUs; b.ptsUs = pt
            true
        }
    }

    override fun release() {
        synchronized(lock) { closed = true; lock.notifyAll() }
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = null
        previewing = false
        try { thread?.quitSafely() } catch (_: Throwable) {}
        session = null; device = null; reader = null; thread = null; handler = null
    }
}
