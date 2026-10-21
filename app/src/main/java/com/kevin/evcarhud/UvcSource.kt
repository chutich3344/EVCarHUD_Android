package com.kevin.evcarhud

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val UTAG = "UvcSource"

// Webcam / camera USB chuẩn UVC cắm qua OTG, đọc thẳng bằng libuvc (thư viện UVCAndroid trên Maven Central),
// dùng cho máy KHÔNG đưa camera USB vào Camera2 (ColorOS...). Lần đầu cắm, Android hỏi quyền dùng thiết bị USB.
// Ưu tiên MJPEG 1280x720 (nhẹ băng thông USB 2.0, đủ cho model); khung trả về dạng NV21 rồi tách thành Y/U/V.
// Camera ngoài không xoay theo màn hình: gắn sao cho hình thẳng. Góc nhìn (FOV) lấy theo thanh trượt trong Cài đặt.
class UvcSource(
    private val context: Context,
    private val alive: () -> Boolean
) : FrameSource {
    override var frameUs = 33_333L
        private set

    private val lock = Object()
    private var back = YuvFrame()
    private var ready = false
    private var closed = false
    @Volatile private var error: Throwable? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var t0 = -1L
    private var lastNs = -1L

    @Volatile private var cbCount = 0      // số lần thư viện gọi onFrame
    @Volatile private var dropCount = 0    // khung bỏ vì kích thước không khớp
    @Volatile private var stage = "chưa mở"
    fun info() = "webcam $stage ${width}x$height, nhận $cbCount khung, bỏ $dropCount"

    var width = 0
        private set
    var height = 0
        private set

    // Webcam: interface lớp Video (14), hoặc thiết bị gộp (239) có interface Video
    private fun findCamera(): UsbDevice? {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return um.deviceList.values.firstOrNull { d ->
            d.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
        }
    }

    // Máy đang thấy gì qua cổng USB và Camera2 (để biết vì sao không nhận webcam)
    private fun usbReport(): String {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devs = um.deviceList.values
        val usb = if (devs.isEmpty()) "USB: không có thiết bị nào (cổng đang không ở chế độ OTG/host?)"
        else "USB: " + devs.joinToString("; ") { d ->
            val cls = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }.distinct().joinToString(",")
            String.format("%04x:%04x lớp %d [%s]", d.vendorId, d.productId, d.deviceClass, cls)
        }
        val cams = try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cm.cameraIdList.joinToString(",") { id ->
                when (cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "$id=ngoài"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "$id=trước"
                    else -> "$id=sau"
                }
            }
        } catch (e: Throwable) { "?" }
        return "$usb | Camera2: $cams"
    }

    override fun open() {
        // Cắm qua hub / cổng chia, webcam có thể hiện ra chậm vài giây: chờ tối đa 4 s
        var found = findCamera()
        val until = SystemClock.elapsedRealtime() + 4000L
        while (found == null && alive() && SystemClock.elapsedRealtime() < until) {
            Thread.sleep(250)
            found = findCamera()
        }
        val dev = found ?: run {
            val rep = usbReport()
            Log.w(UTAG, "không thấy webcam: $rep")
            throw IllegalStateException("Không thấy camera USB. $rep")
        }
        val t = HandlerThread("uvc-src").also { it.start() }
        thread = t
        val hd = Handler(t.looper)
        handler = hd
        val opened = CountDownLatch(1)
        hd.post {
            val m = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
                override fun onAttach(device: UsbDevice) {}
                override fun onDetach(device: UsbDevice) {
                    if (device.deviceName == dev.deviceName) fail(IllegalStateException("Camera USB đã bị rút ra"))
                }
                override fun onDeviceOpen(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
                    stage = "đang mở"
                    try {
                        startCamera(ctrlBlock)
                    } catch (e: Throwable) {
                        fail(e)
                    }
                    opened.countDown()
                }
                override fun onDeviceClose(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
                    if (!closed) fail(IllegalStateException("Camera USB đã đóng"))
                }
                override fun onCancel(device: UsbDevice) {
                    fail(IllegalStateException("Chưa cho phép app dùng camera USB"))
                    opened.countDown()
                }
            }, hd)
            monitor = m
            m.register()
            stage = "chờ quyền USB"
            m.requestPermission(dev)   // đã có quyền thì mở ngay, chưa có thì Android hiện hộp thoại hỏi
        }
        // Chờ người dùng bấm Cho phép (tối đa 60 s)
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        while (!opened.await(200, TimeUnit.MILLISECONDS)) {
            if (!alive() || SystemClock.elapsedRealtime() > deadline) {
                throw IllegalStateException("Chưa mở được camera USB (chưa cấp quyền?)")
            }
        }
        error?.let { throw it }
    }

    // Chạy trên luồng uvc-src (UVCCamera cần luồng có Looper)
    private fun startCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        val cam = UVCCamera(null)
        val r = cam.open(ctrlBlock)
        if (r != 0) { cam.destroy(); throw IllegalStateException("Không mở được camera USB (mã $r)") }
        camera = cam
        val sizes = cam.supportedSizeList
        // MJPEG 16:9 rộng tới 1280; không có thì MJPEG lớn nhất tới 1280; cuối cùng lấy kích thước thư viện chọn sẵn
        val mjpeg = sizes.filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG && it.width <= 1280 }
        val pick = mjpeg.filter { abs(it.width * 9 - it.height * 16) <= 16 }.maxByOrNull { it.width * it.height }
            ?: mjpeg.maxByOrNull { it.width * it.height }
        if (pick != null) {
            val fps = when {
                pick.fpsList?.contains(30) == true -> 30
                pick.fpsList?.contains(20) == true -> 20
                else -> pick.fps
            }
            try { cam.setPreviewSize(pick.width, pick.height, UVCCamera.UVC_VS_FRAME_MJPEG, fps) }
            catch (e: Throwable) { Log.w(UTAG, "không đặt được ${pick.width}x${pick.height}@$fps, dùng mặc định", e) }
        }
        val s = cam.previewSize
        width = s.width
        height = s.height
        Log.d(UTAG, "camera USB ${width}x$height type=${s.type} fps=${s.fps}")
        cam.setFrameCallback(IFrameCallback { buf -> onFrame(buf) }, UVCCamera.PIXEL_FORMAT_NV21)
        cam.startPreview()
        stage = "đã chạy"
    }

    // NV21: Y (w*h) rồi VU xen kẽ (w*h/2). Tách ra U, V riêng (uvPix = 1) như các nguồn khác
    private fun onFrame(buf: ByteBuffer) {
        val w = width
        val h = height
        cbCount++
        if (w <= 0 || h <= 0) { dropCount++; return }
        val ySize = w * h
        val cw = w / 2
        val ch = h / 2
        if (buf.remaining() < ySize + cw * ch * 2) {
            if (dropCount++ % 30 == 0) Log.w(UTAG, "khung ${buf.remaining()} byte, cần ${ySize + cw * ch * 2}")
            return
        }
        val now = System.nanoTime()
        if (t0 < 0) t0 = now
        if (lastNs > 0) frameUs = (frameUs * 7 + (now - lastNs) / 1000) / 8
        lastNs = now
        synchronized(lock) {
            if (closed) return
            val f = back
            if (f.yArr.size != ySize) {
                f.yArr = ByteArray(ySize); f.uArr = ByteArray(cw * ch); f.vArr = ByteArray(cw * ch)
            }
            val p = buf.position()
            buf.get(f.yArr, 0, ySize)
            val n = cw * ch
            val u = f.uArr
            val v = f.vArr
            var i = p + ySize
            for (k in 0 until n) {
                v[k] = buf.get(i)
                u[k] = buf.get(i + 1)
                i += 2
            }
            buf.position(p)
            f.w = w; f.h = h; f.yRow = w; f.uvRow = cw; f.uvPix = 1
            f.ptsUs = (now - t0) / 1000
            ready = true
            lock.notifyAll()
        }
    }

    private fun fail(e: Throwable) {
        Log.w(UTAG, "camera USB lỗi", e)
        synchronized(lock) { if (error == null) error = e; lock.notifyAll() }
    }

    override fun nextFrame(minPtsUs: Long, dst: YuvFrame): Boolean {
        return synchronized(lock) {
            while (!ready) {
                error?.let { throw it }
                if (closed || !alive()) return false
                lock.wait(100)
            }
            ready = false
            // Hoán đổi back <-> dst (không sao chép)
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
        val hd = handler
        val done = CountDownLatch(1)
        val job = Runnable {
            try { camera?.stopPreview() } catch (_: Throwable) {}
            try { camera?.destroy() } catch (_: Throwable) {}
            camera = null
            try { monitor?.unregister() } catch (_: Throwable) {}
            try { monitor?.destroy() } catch (_: Throwable) {}
            monitor = null
            done.countDown()
        }
        if (hd != null && hd.post(job)) done.await(3, TimeUnit.SECONDS) else job.run()
        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null; handler = null
    }
}

// Nguồn "Camera USB": đọc thẳng chuẩn UVC trước (cách các app webcam khác dùng, chạy được trên ColorOS);
// chỉ khi không mở được mới thử Camera2 (máy đưa webcam vào Camera2 dạng camera ngoài).
// Trước đây ưu tiên Camera2: trên một số máy camera ngoài mở được nhưng không ra khung -> báo "Camera treo" lặp mãi.
class UsbCamSource(private val context: Context, private val alive: () -> Boolean) : FrameSource {
    private var src: FrameSource? = null
    override val frameUs: Long get() = src?.frameUs ?: 33_333L
    fun info(): String = (src as? UvcSource)?.info() ?: if (src is CameraSource) "camera ngoài qua Camera2" else ""

    override fun open() {
        val uvc = UvcSource(context, alive)
        src = uvc
        try {
            uvc.open()
            return
        } catch (e: Throwable) {
            // Chỉ chuyển sang Camera2 khi danh sách USB không có webcam mà máy lại có camera ngoài;
            // lỗi khác (quyền, mở thất bại) thì báo thẳng để biết nguyên nhân
            val notFound = e.message?.startsWith("Không thấy camera USB") == true
            if (!alive() || !notFound || !CameraSource.hasExternal(context)) throw e
            try { uvc.release() } catch (_: Throwable) {}
            Log.w(UTAG, "không thấy webcam qua USB, thử Camera2 camera ngoài", e)
        }
        val cam = CameraSource(context, external = true, alive = alive)
        src = cam
        cam.open()
    }

    override fun nextFrame(minPtsUs: Long, dst: YuvFrame): Boolean = src?.nextFrame(minPtsUs, dst) ?: false

    override fun release() { try { src?.release() } finally { src = null } }
}
