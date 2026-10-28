package com.kevin.evcarhud

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Picture
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kevin.evcarhud.ui.theme.EVCarHUDTheme
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

private const val TAG = "ModelInfo"
private const val DW = 1024             // độ rộng ảnh hiển thị (≈ độ rộng thật trên màn hình điện thoại)
private const val REFLECT_CONV_MS = 250L  // chế độ phản chiếu: đổi ảnh camera sang RGB 4 lần/giây
private const val RING = 6              // bitmap xoay vòng: 2 (hàng đợi) + 1 (đang vẽ) + 1 (đang hiện) + 1 (đang dựng) + dư 1
private const val GAIN = 0.004f         // tốc độ tự hiệu chỉnh mỗi khung (nhỏ = chậm, mượt)
private const val AUTO_SIGN = 1f        // đổi thành -1f nếu chân trời chạy dạt về một phía
private const val WARMUP = 40           // số lần suy luận đầu không tự chỉnh
private const val AUTO_MIN_SPEED = 5.5f // m/s (~20 km/h): chỉ tự chỉnh khi đủ nhanh (chạy trong phố cũng chỉnh được)
private const val STEP_US = 50_000L     // model được huấn luyện với bước đúng 50 ms (20 Hz)
private const val DIM_STILL_MS = 20_000L  // đứng yên bao lâu thì giảm sáng màn hình
private const val DIM_BRIGHTNESS = 0.08f  // độ sáng khi giảm (0..1)
private const val SPEED_EMA = 0.9f      // làm mượt tốc độ hiển thị
private const val THERMAL_POLL_MS = 2000L
private const val COOLER_DEW_C = 25f       // sò lạnh: pin dưới mức này dễ đọng hơi nước
private const val STATUS_MS = 500L      // chu kỳ cập nhật dòng trạng thái (đỡ recomposition + log)
private const val RESYNC_NS = 250_000_000L  // trễ quá 250 ms thì đồng bộ lại đồng hồ phát, không "tua nhanh"
private const val STALL_MS = 5000L      // camera không ra khung nào quá 5 s -> coi là treo, tự mở lại
private const val YOLO_ROI_W = 0.8f   // YOLO nhìn dải giữa khung (đường phía trước) rộng 80% chiều ngang; 1f = toàn khung
private const val HOOD_DEFAULT = 0.15f  // mặc định che 15% đáy khung (capo khi đặt máy trên taplo); chọn 5 / 10 / 15% trong Cài đặt
private const val STREAM_DEFAULT = "rtsp://192.168.0.1:554/livestream/12"  // 70mai (M300/M310…); chip Novatek: rtsp://192.168.1.254/xxx.mov

// Nguồn camera trực tiếp
enum class CamKind(val label: String) {
    PHONE("Điện thoại"),
    USB("USB (OTG)"),
    WIFI("Hành trình Wi‑Fi")
}

// Khung dùng để HIỂN THỊ: có mặt ở mọi khung giải mã (30 fps nếu video 30 fps)
internal class FrameJob(
    val bmp: Bitmap,
    val ptsUs: Long,
    val fSrc: Float,
    val cx: Float,
    val cy: Float,
    val w: Int,
    val h: Int,
    val prepMs: Long,
    val gpu: Boolean = false   // hình camera đang đi thẳng vào TextureView (GPU), bmp không đổi mỗi khung
)

// Khung dùng để SUY LUẬN: chỉ những khung nằm trên lưới 20 Hz
internal class InferJob(
    val input: ByteArray,
    val dtUs: Long,
    val ptsUs: Long,
    val fSrc: Float,
    val w: Int,
    val h: Int,
    val yolo: YoloInput?
)

// Kết quả suy luận mới nhất; luồng hiển thị dùng lại cho các khung ở giữa
internal class Latest(
    val out: FloatArray,
    val dets: List<Detection>,
    val speedKmh: Float,
    val npuMs: Long,
    val yoloMs: Long,   // -1 nếu lần này không chạy YOLO
    val n: Int
)

class Runner(private val ctx: Context) {
    var status by mutableStateOf("Chọn một video để bắt đầu")
    var frame by mutableStateOf<Bitmap?>(null)
    var running by mutableStateOf(false)
    var hfov by mutableStateOf(63f)       // FOV ngang của camera hành trình (độ)
    private val prefs = ctx.getSharedPreferences("evcarhud", Context.MODE_PRIVATE)
    // Chân trời (tỉ lệ từ trên xuống): nhớ giá trị đã tự hiệu chỉnh lần trước để lần sau khởi động đúng ngay
    var horizon by mutableStateOf(prefs.getFloat("horizon", 0.60f))
    var autoCal by mutableStateOf(prefs.getBoolean("autoCal", true))   // tự hiệu chỉnh chân trời và độ lệch ngang, bật mặc định
    var cxShift by mutableStateOf(0f)     // độ lệch ngang của điểm chính (tỉ lệ chiều rộng)
    var yoloOn by mutableStateOf(true)    // nhận diện loại vật thể bằng YOLO
    private var detector: YoloDetector? = null
    var thermalAuto by mutableStateOf(true)  // tự giảm tải khi máy nóng
    // Có gắn sò lạnh (quạt Peltier) sau lưng máy: nhắc khi pin lạnh dễ đọng sương, hoặc vẫn nóng (sò mất nguồn)
    var cooler by mutableStateOf(prefs.getBoolean("cooler", false))
    // Nhiệt độ pin mục tiêu (°C): bắt đầu giãn nhịp sớm khi pin tiến gần mức này, không chờ Android báo nóng
    var coolTarget by mutableStateOf(prefs.getFloat("coolTarget", 40f).coerceIn(35f, 45f))
    var capFps by mutableStateOf(true)       // phát đúng nhịp thời gian thực của video
    var thermalLine by mutableStateOf("")
    // Dòng hiệu năng trên HUD (luồng nhiệt ghi, luồng vẽ đọc)
    @Volatile private var perfText = ""
    @Volatile private var perfCpu = Float.NaN
    @Volatile var heatLevel = 0           // mức giảm tải nhiệt hiện tại (trang Bản đồ tự về 2D khi >= 2)
    var warnOn by mutableStateOf(true)        // cảnh báo va chạm (tiếng tít + viền đỏ)
    var alertFlash by mutableStateOf(false)   // pha "sáng" của viền đỏ chớp
    var cautionOn by mutableStateOf(false)    // viền vàng "chú ý" (không tiếng)
    // Giới hạn tốc độ (km/h) từ dữ liệu bản đồ, ví dụ VietMap Speed Alert SDK; 0 = chưa có.
    // Cập nhật từ luồng vị trí/SDK, luồng hiển thị chỉ đọc.
    @Volatile var mapSpeedLimit = 0
    var beepVolume by mutableStateOf(100f)    // âm lượng tiếng tít (0-100, tương đối so với âm lượng phương tiện của máy)
    // Phần đáy khung bị capo che (0..0,45): YOLO không nhìn, không cảnh báo, không vẽ đường ở đó
    var hoodCut by mutableStateOf(prefs.getFloat("hoodCut", HOOD_DEFAULT))
    var camKind by mutableStateOf(CamKind.values().getOrNull(prefs.getInt("camKind", 0)) ?: CamKind.PHONE)
    var streamUrl by mutableStateOf(prefs.getString("streamUrl", STREAM_DEFAULT) ?: STREAM_DEFAULT)
    // (không còn dùng: ước tính giới hạn theo loại đường OSM; giới hạn giờ chỉ theo nguồn cảnh báo giao thông đã chọn)
    var limitGuess by mutableStateOf(prefs.getBoolean("limitGuess", true))
    // Chế độ đường: Trong phố / Cao tốc / Mưa (HudRenderer.MODE_*); hiện ở dòng 3 bên trái HUD
    var roadMode by mutableStateOf(prefs.getInt("roadMode", if (prefs.getBoolean("highway", false)) 1 else 0).coerceIn(0, 2))
    // Biển tốc độ nhận diện được (model YOLO riêng) còn hiệu lực trong 10 phút sau khi thấy
    private fun signLimit(): Int =
        if (HudRenderer.signLimit > 0 && SystemClock.elapsedRealtime() - HudRenderer.signAtMs < 1_800_000L) HudRenderer.signLimit else 0
    // Giới hạn tốc độ từ HERE: đang chỉ đường thì lấy theo tuyến đã tải (0 lượt gọi), không thì theo lần tra gần nhất (HereGeo)
    private fun hereLimit(): Int = if (nav.hasSpans) nav.limitKmh() else here.limitKmh()
    // Giới hạn tốc độ: ưu tiên biển camera đọc được (đúng thực tế nhất), không có thì lấy từ HERE
    fun speedLimit(): Int {
        signLimit().takeIf { it > 0 }?.let { return it }
        return hereLimit()
    }
    fun limitSource(): String = when {
        signLimit() > 0 -> "${signLimit()} km/h (biển camera đọc được)"
        hereLimit() > 0 -> "${hereLimit()} km/h (HERE)"
        else -> "HERE chưa có dữ liệu ở đoạn này"
    }

    // Âm báo: 0 = tiếng tít, 1 = giọng đọc tiếng Việt (máy chưa có giọng Việt thì tự dùng tiếng tít)
    var alertSound by mutableStateOf(prefs.getInt("alertSound", 0).coerceIn(0, 1))
    val voice = AlertVoice(ctx)
    private fun useVoice() = alertSound == 1 && voice.ready
    fun testSound() {
        if (alertSound == 1 && voice.ready) { voice.volume = beepVolume / 100f; voice.say("Giữ khoảng cách", urgent = true, minGapMs = 0L) }
        else testBeep()
    }
    // Ảnh quanh mỗi lần cảnh báo (xem lại, đánh dấu đúng / nhầm) + thu ảnh huấn luyện
    val clips = AlertClips(ctx)
    var collectData by mutableStateOf(prefs.getBoolean("collect", false))
    // Chiều cao camera (m) cho đo khoảng cách theo hộp
    var camH by mutableStateOf(prefs.getFloat("camH", 1.22f))
    // Chế độ phản chiếu kính lái: nền đen, lật gương
    var reflect by mutableStateOf(prefs.getBoolean("reflect", false))
    // Hình camera điện thoại đi thẳng vào GPU (TextureView), HUD ghi thành Picture rồi vẽ bằng GPU trên lớp phủ:
    // CPU không phải đổi YUV -> RGB và vẽ HUD lên bitmap mỗi khung nữa (nguồn nóng chính theo nhật ký nhiệt)
    var gpuPreview by mutableStateOf(prefs.getBoolean("gpuPreview", true))
    @Volatile var previewTexture: SurfaceTexture? = null
    // Xe đứng yên quá 20 s: giảm độ sáng màn hình cho mát máy (màn hình là nguồn nóng lớn); xe chạy hoặc có cảnh báo là sáng lại
    var dimStill by mutableStateOf(prefs.getBoolean("dimStill", true))
    var dimNow by mutableStateOf(false)
    var previewing by mutableStateOf(false)
    var frameAspect by mutableStateOf(16f / 9f)
    @Volatile var hudSink: ((Picture?) -> Unit)? = null
    @Volatile private var curSrc: FrameSource? = null
    // TextureView sẵn sàng / bị huỷ: camera đang chạy mà chưa (hoặc không còn) đẩy hình vào đó thì mở lại
    fun onPreviewTexture(st: SurfaceTexture?) {
        previewTexture = st
        val src = curSrc
        if (src is CameraSource && camKind == CamKind.PHONE && running && useCamera &&
            (if (st != null) gpuPreview && !src.previewing else src.previewing)) restartCamera()
        else if (src is UsbCamSource && camKind == CamKind.USB && running && useCamera &&
            (if (st != null) gpuPreview && !src.previewing else src.previewing)) restartCamera()
    }
    fun restartCamera() { if (running && useCamera) { running = false; startCamera() } }
    // Đứng yên lâu: giảm nhịp NPU / YOLO cho mát máy (xe chạy lại là trở về ngay)
    @Volatile private var idle = false

    // Hiệu chỉnh chiều cao camera bằng khoảng cách thật tới xe phía trước (đang đứng sau xe đó)
    fun calibrate(realM: Float): String {
        val g = HudRenderer.calibGround
        if (g.isNaN() || g <= 0f) return "Chưa thấy xe phía trước để đo. Dừng sau một xe rồi thử lại."
        val nh = (camH * realM / g).coerceIn(0.6f, 2.2f)
        camH = nh
        HudRenderer.camH = nh
        savePrefs()
        return "Đã chỉnh: app đo %.1f m, thật %.1f m → chiều cao camera %.2f m".format(g, realM, nh)
    }

    // Nhật ký cảnh báo (CSV), bật mặc định
    var logOn by mutableStateOf(prefs.getBoolean("alertLog", true))
    private val alertLog = AlertLog(ctx)
    val thermalLog = ThermalLog(ctx)
    fun savePrefs() {
        prefs.edit().putFloat("hoodCut", hoodCut).putInt("camKind", camKind.ordinal).putString("streamUrl", streamUrl)
            .putBoolean("alertLog", logOn).putBoolean("limitGuess", limitGuess)
            .putInt("roadMode", roadMode).putBoolean("autoCal", autoCal).putFloat("horizon", horizon)
            .putInt("alertSound", alertSound).putBoolean("collect", collectData)
            .putFloat("camH", camH).putBoolean("reflect", reflect)
            .putInt("navVehicle", navVehicle).putBoolean("miniMap", miniMap)
            .putFloat("coolTarget", coolTarget).putBoolean("gpuPreview", gpuPreview).putBoolean("cooler", cooler)
            .putBoolean("dimStill", dimStill).apply()
        nav.vehicle = if (navVehicle == 1) "scooter" else "car"
        nav.showMini = miniMap
        HudRenderer.camH = camH
        HudRenderer.reflect = reflect
    }
    fun logInfo(): String = "Nhật ký: ${alertLog.count} lần trong phiên này, lưu ở ${alertLog.dir.absolutePath}"

    private var clockMin = -1L
    private var clockStr = ""
    val gps = LocationFallback(ctx)           // dự phòng: tốc độ + tên đường theo GPS điện thoại
    val weather = WeatherInfo(gps)            // thời tiết hiện tại (Open-Meteo) cho góc trái HUD
    val here = HereGeo(gps)
    // Chỉ đường HERE (tìm điểm đến, mũi tên rẽ, bản đồ nhỏ); 0 = ô tô, 1 = xe máy
    val nav = HereNav(gps).apply { diskDir = File(ctx.cacheDir, "tiles") }
    var navVehicle by mutableStateOf(prefs.getInt("navVehicle", 0).coerceIn(0, 1))
    var miniMap by mutableStateOf(prefs.getBoolean("miniMap", true))

    init {
        // Mã cài đặt ngẫu nhiên: máy chủ HERE giới hạn lượt gọi theo mã này (không chứa thông tin cá nhân)
        HereApi.device = prefs.getString("installId", null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("installId", it).apply() }
        nav.vehicle = if (navVehicle == 1) "scooter" else "car"
        nav.showMini = miniMap
        HudRenderer.camH = camH
        HudRenderer.reflect = reflect
        CarLink.nav = nav
        CarLink.gps = gps
        // Kiểu chữ HUD tuỳ chọn: đặt file assets/fonts/hud.ttf (ví dụ Exo 2 ExtraLight) để thay Roboto Thin
        try { HudRenderer.setFont(android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/hud.ttf")) } catch (_: Throwable) {}
        // Chữ đậm cho số khoảng cách ở giữa: assets/fonts/hud_bold.ttf (ví dụ Exo 2 Bold), không có thì Roboto Bold
        try { HudRenderer.setBoldFont(android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/hud_bold.ttf")) } catch (_: Throwable) {}
    }
    // Đồng hồ HH:mm, chỉ định dạng lại khi sang phút mới
    private fun clockNow(): String {
        val now = System.currentTimeMillis()
        val min = now / 60_000L
        if (min != clockMin) {
            clockMin = min
            clockStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
        }
        return clockStr
    }

    fun applyBeepVolume() { beeper?.setVolume(beepVolume.toInt()) }

    fun testBeep() {
        Thread {
            val live = beeper
            val b = live ?: BeepPlayer(beepVolume.toInt())
            b.test()
            if (live == null) { try { Thread.sleep(500) } catch (_: Throwable) {}; b.release() }
        }.start()
    }

    // Chạy lâu (1–3 tiếng): không chạy tối đa liên tục. NPU 15 lần/giây khi chạy thường, 20 khi nhanh / có nguy cơ,
    // 10 khi ấm, 5 khi đứng yên hoặc rất nóng. YOLO tính theo lần/giây (vật cản không cần nhìn 20 lần/giây).
    @Volatile private var yoloHz = 7f
    @Volatile private var level = 0          // mức giảm tải đang áp dụng
    @Volatile private var driveKmh = 0f      // tốc độ dùng chọn nhịp NPU (luồng hiển thị ghi)
    @Volatile private var boostUntil = 0L    // đang có nguy cơ va chạm: giữ đủ 20 lần/giây tới lúc này
    private fun npuRate(): Int {
        if (!thermalAuto) return 20
        if (SystemClock.elapsedRealtime() < boostUntil && level < 4) return 20
        if (idle) return 5
        return when {
            level >= 4 -> 5
            level >= 2 -> 10
            driveKmh >= 60f && level == 0 -> 20
            else -> 15
        }
    }
    @Volatile private var displayEvery = 1
    private var lastStreet = ""             // để xoá giới hạn đọc từ biển khi rẽ sang đường khác
    // Tạm nghỉ làm mát (xem infer): không suy luận, không dựng khung, màn hình hiện thông báo
    var coolPause by mutableStateOf(false)
    var coolNote by mutableStateOf("")
    // Lý do app bị đóng lần trước (Android 11+): phân biệt hệ thống đóng vì nóng / thiếu RAM với lỗi app
    val lastExit: String by lazy {
        try {
            if (android.os.Build.VERSION.SDK_INT < 30) return@lazy ""
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val e = am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() ?: return@lazy ""
            val why = when (e.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "app bị lỗi (crash)"
                android.app.ApplicationExitInfo.REASON_ANR -> "app treo (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "hệ thống thiếu RAM"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "dùng quá nhiều tài nguyên"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED, android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "người dùng tắt"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "app tự thoát"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "hệ thống dừng tiến trình (thường do nóng / tiết kiệm pin)"
                else -> "hệ thống đóng (mã ${e.reason})"
            }
            val at = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.US).format(java.util.Date(e.timestamp))
            "$at: $why" + (e.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        } catch (_: Throwable) { "" }
    }
    private fun pauseC(): Float = maxOf(coolTarget + 5f, 44f).coerceAtMost(47f)   // dựng ảnh hiển thị mỗi N khung camera; 2 khi quá nóng (màn hình ~10 hình/s)
    @Volatile private var latest: Latest? = null
    private val tracker = DetTracker()
    private var beeper: BeepPlayer? = null
    private val thermal by lazy { ThermalMonitor(ctx) }
    private var yoloTried = false
    private var videoUri: Uri? = null
    @Volatile private var useCamera = false   // true = nguồn là camera trực tiếp
    private var engine: DrivingEngine? = null
    @Volatile private var lastOutput: FloatArray? = null
    @Volatile private var lastIdx = 0

    // Chạy video test: dừng camera (nếu đang chạy) rồi phát video; lần chạy cũ tự kết thúc trước khi lần mới dùng NPU
    fun playVideo(uri: Uri) {
        videoUri = uri
        wantCamera = false
        resumeCamAfterPick = false
        running = false
        start()
    }

    // Mở trình chọn video: tạm dừng camera cho máy nhẹ (trình chọn file không bị giật); Huỷ thì mở lại
    @Volatile private var resumeCamAfterPick = false
    fun pauseForPick() {
        resumeCamAfterPick = running && useCamera
        if (resumeCamAfterPick) stop()
    }
    fun cancelPick() {
        if (resumeCamAfterPick) { resumeCamAfterPick = false; startCamera() }
    }

    // Đổi nguồn camera; đang chạy camera thì mở lại bằng nguồn mới
    fun selectCamKind(k: CamKind) {
        if (k == camKind) return
        camKind = k
        savePrefs()
        if (running && useCamera) { running = false; startCamera() }
    }

    // Camera là chế độ mặc định: còn "muốn camera" thì tự mở lại khi lỗi / treo / quay lại từ nền
    @Volatile private var wantCamera = false
    private var retryDelayMs = 1500L
    private val mainH = Handler(Looper.getMainLooper())
    val cameraRequested get() = wantCamera

    fun stop() { wantCamera = false; running = false; if (!CarLink.active) HudFgService.stop(ctx) }

    fun resumeIfNeeded() { if (wantCamera && !running) launch() }

    // Mỗi lần chạy có số thứ tự riêng. Lần chạy cũ (camera) chưa kịp thoát khi lần mới (video) bắt đầu
    // trước đây vẫn thấy running = true nên chạy song song: hai luồng cùng đẩy khung, cùng dùng NPU -> khựng.
    @Volatile private var gen = 0
    private var worker: Thread? = null
    private inner class Run(val id: Int) {
        val alive get() = running && gen == id
        val current get() = gen == id
        fun end() { if (gen == id) running = false }
    }

    // Luồng A: giải mã MỌI khung -> dựng ảnh hiển thị; khung nào trúng lưới 50 ms thì thêm warp YUV + YOLO input
    private fun produce(
        run: Run,
        dec: FrameSource,
        det: YoloDetector?,
        frameQ: ArrayBlockingQueue<FrameJob>,
        inferQ: ArrayBlockingQueue<InferJob>,
        done: AtomicBoolean,
        err: AtomicReference<Throwable?>
    ) {
        try {
            val yuv = YuvFrame()
            val warper = YuvWarper()
            val conv = DisplayConverter()
            var rgb = IntArray(0)
            val ring = arrayOfNulls<Bitmap>(RING)
            var ri = 0
            var k = 0L
            var lastPts = -1L
            var acc = 20              // bộ đếm chia nhịp: mỗi ô lưới 50 ms cộng nhịp NPU, đủ 20 thì chạy
            var lastYolo = -1_000_000L
            var dcnt = 0L
            var lastConv = -1_000_000L
            var lastBmp: Bitmap? = null
            while (run.alive) {
                val t0 = SystemClock.elapsedRealtime()
                if (!dec.nextFrame(Long.MIN_VALUE, yuv)) break
                if (coolPause) continue      // tạm nghỉ làm mát: chỉ lấy khung cho camera khỏi treo, không xử lý

                val w = yuv.w
                val h = yuv.h
                val fSrc = (w / 2f) / tan(Math.toRadians(hfov / 2.0)).toFloat()
                val cx = w * (0.5f + cxShift)
                val cy = horizon * h

                // Lưới suy luận 50 ms cố định theo video (cùng logic cũ); dung sai = nửa chu kỳ khung
                var ij: InferJob? = null
                val tol = dec.frameUs / 2 + 1000
                if (yuv.ptsUs >= k * STEP_US - tol) {
                    k = maxOf(k + 1, (yuv.ptsUs + STEP_US / 2) / STEP_US + 1)
                    acc += npuRate()
                    if (acc >= 20) {
                        acc = minOf(acc - 20, 19)
                        val dtUs = if (lastPts < 0) STEP_US else yuv.ptsUs - lastPts
                        lastPts = yuv.ptsUs
                        val input = ByteArray(Preprocess.INPUT_SIZE)
                        warper.warp(yuv, fSrc, cx, cy, 910f, 47.6f, input, 0)
                        warper.warp(yuv, fSrc, cx, cy, 455f, 151.8f, input, Preprocess.STREAM)

                        val yh = if (idle) minOf(yoloHz, 2f) else yoloHz
                        val nowY = SystemClock.elapsedRealtime()
                        val yi: YoloInput? = if (det != null && yoloOn && yh > 0f && nowY - lastYolo >= (1000f / yh).toLong() - 25L) {
                            lastYolo = nowY
                            val b = det.nextBuf()
                            // ROI: dải đường phía trước, cùng tỉ lệ với đầu vào model, bám MÉP CAPO
                            // (trước đây bám đáy khung nên capo lọt vào YOLO, bị nhận là xe sát đầu -> kêu liên tục)
                            val bottom = (h * (1f - hoodCut.coerceIn(0f, 0.6f))).toInt().coerceIn(h / 4, h)
                            if (det.readsSigns) {
                                // Model riêng có biển tốc độ: nhìn CẢ BỀ NGANG từ mép trên tới capo, vì biển ở Việt Nam
                                // thường nằm sát lề phải (ngoài dải giữa). Nên huấn luyện ở imgsz 960 cho biển xa đủ nét.
                                YoloInput(b, det.prepare(yuv, b, 0, 0, w, bottom))
                            } else {
                                var rw = (w * YOLO_ROI_W).toInt()
                                var rh = rw * det.inH / det.inW
                                if (rh > bottom) { rh = bottom; rw = rh * det.inW / det.inH }
                                val rx = (cx.toInt() - rw / 2).coerceIn(0, w - rw)
                                YoloInput(b, det.prepare(yuv, b, rx, bottom - rh, rw, rh))
                            }
                        } else null
                        ij = InferJob(input, dtUs, yuv.ptsUs, fSrc, w, h, yi)
                    }
                }

                // Quá nóng: chỉ dựng ảnh hiển thị mỗi displayEvery khung (suy luận vẫn đủ nhịp đã chọn)
                dcnt++
                if (displayEvery > 1 && dcnt % displayEvery != 0L) {
                    if (ij != null) {
                        while (run.alive && !inferQ.offer(ij, 100, TimeUnit.MILLISECONDS)) { /* chờ chỗ trống */ }
                    }
                    continue
                }
                // Ảnh hiển thị. Phản chiếu kính lái: HUD vẽ trên nền đen nên không cần đổi YUV -> RGB mỗi khung,
                // chỉ đổi 4 lần/giây cho ảnh xem lại cảnh báo / ảnh huấn luyện (đỡ nhiều CPU và nhiệt)
                val dh = DW * h / w
                if (rgb.size != DW * dh) { rgb = IntArray(DW * dh); ring.fill(null) }
                val bmp = ring[ri] ?: Bitmap.createBitmap(DW, dh, Bitmap.Config.ARGB_8888).also { ring[ri] = it }
                val nowConv = SystemClock.elapsedRealtime()
                // Hình đang qua GPU: bitmap chỉ dùng cho ảnh cảnh báo / huấn luyện nên cũng chỉ đổi 4 lần/giây
                val gpuShow = (dec as? CameraSource)?.previewing == true || (dec as? UsbCamSource)?.previewing == true
                var shownBmp = lastBmp
                if (shownBmp == null || shownBmp.height != dh || !(HudRenderer.reflect || gpuShow) || nowConv - lastConv >= REFLECT_CONV_MS) {
                    conv.convert(yuv, DW, dh, rgb)
                    bmp.setPixels(rgb, 0, DW, 0, 0, DW, dh)
                    lastConv = nowConv
                    shownBmp = bmp
                    lastBmp = bmp
                    ri = (ri + 1) % RING   // chỉ xoay vòng khi có ảnh mới: khung không đổi màu dùng lại ảnh gần nhất (không bị ô trống)
                }

                val prepMs = SystemClock.elapsedRealtime() - t0
                if (ij != null) {
                    while (run.alive && !inferQ.offer(ij, 100, TimeUnit.MILLISECONDS)) { /* chờ chỗ trống */ }
                }
                val job = FrameJob(shownBmp ?: bmp, yuv.ptsUs, fSrc, cx, cy, w, h, prepMs, gpuShow)
                while (run.alive && !frameQ.offer(job, 100, TimeUnit.MILLISECONDS)) { /* chờ chỗ trống */ }
            }
        } catch (e: Throwable) {
            err.compareAndSet(null, e)
        } finally {
            done.set(true)
        }
    }

    // Luồng B: NPU (supercombo + YOLO) + tự hiệu chỉnh + bộ điều tiết nhiệt. Không đụng tới nhịp hiển thị.
    private fun infer(
        run: Run,
        eng: DrivingEngine,
        det: YoloDetector?,
        q: ArrayBlockingQueue<InferJob>,
        done: AtomicBoolean,
        err: AtomicReference<Throwable?>
    ) {
        try {
            var n = 0
            var dets: List<Detection> = emptyList()
            var speedEma = 0f
            var lastPoll = 0L
            var lastTLog = 0L
            var held = 0      // mức giảm tải đang áp dụng (tăng ngay, hạ từ từ)
            var cpuEma = Float.NaN
            var cpuLv = 0
            var cool72 = -1L
            var hotSaid = -1_000_000L
            var coolerSaid = -1_000_000L
            var calm = 0
            while (run.alive) {
                val job = q.poll(100, TimeUnit.MILLISECONDS)
                // Đọc nhiệt cả khi không có khung (lúc tạm nghỉ làm mát, luồng dựng khung không gửi gì)
                val c = SystemClock.elapsedRealtime()
                if (c - lastPoll >= THERMAL_POLL_MS) {
                    lastPoll = c
                    try {
                        val ti = thermal.read()
                        // Nhiệt CPU (làm mượt): >= 70°C mức 2, >= 78°C mức 3, giữ mức 3 tới khi < 72°C liền 30 s
                        val cpu = thermalLog.cpuC()
                        if (!cpu.isNaN()) cpuEma = if (cpuEma.isNaN()) cpu else cpuEma * 0.7f + cpu * 0.3f
                        cpuLv = when {
                            cpuEma >= 78f -> { cool72 = -1L; 3 }
                            cpuLv == 3 -> if (cpuEma < 72f) {
                                if (cool72 < 0L) cool72 = c
                                if (c - cool72 >= 30_000L) 2 else 3
                            } else { cool72 = -1L; 3 }
                            cpuEma >= 70f -> 2
                            else -> 0
                        }
                        val lv = maxOf(thermal.level(ti), targetLevel(ti.batteryC), if (thermalAuto) cpuLv else 0)
                        if (lv >= held) { held = lv; calm = 0 }
                        else if (++calm >= 3) { held--; calm = 0 }   // 3 lần đọc liền (6 s) thấp hơn mới hạ mức
                        applyThermal(held)
                        heatLevel = held
                        thermalLine = thermalText(ti, held)
                        perfCpu = cpuEma
                        perfText = buildString {
                            append("CPU ").append(if (cpuEma.isNaN()) "--" else cpuEma.roundToInt().toString()).append("°C")
                            append(" · PIN ").append(if (ti.batteryC.isNaN()) "--" else ti.batteryC.roundToInt().toString()).append("°C")
                            append(" · NPU ").append(npuRate()).append("/s")
                            append(" · YOLO ").append(yoloHz.roundToInt()).append("/s")
                        }
                        // Mức 3 trở lên: nhắc người lái một lần mỗi 10 phút
                        if (thermalAuto && held >= 3 && c - hotSaid > 600_000L) {
                            hotSaid = c
                            val t = when {
                                cooler -> "Máy vẫn nóng dù có sò lạnh, kiểm tra sò đã cắm nguồn và áp sát lưng máy chưa"
                                ti.plugged -> "Máy đang nóng, hãy bật điều hoà hướng vào điện thoại và dùng sạc chậm"
                                else -> "Máy đang nóng, hãy bật điều hoà hướng vào điện thoại"
                            }
                            if (alertSound == 1 && voice.ready) voice.say(t, minGapMs = 0L) else beeper?.chime()
                        }
                        // Sò lạnh: pin dưới 25°C là ngang điểm sương không khí nóng ẩm, lưng máy dễ đọng nước (nhắc mỗi 10 phút)
                        if (cooler && !ti.batteryC.isNaN() && c - coolerSaid > 600_000L) {
                            val t = when {
                                ti.batteryC < COOLER_DEW_C -> "Pin dưới ${COOLER_DEW_C.roundToInt()} độ, dễ đọng hơi nước, nên giảm mức sò lạnh"
                                ti.batteryC >= coolTarget - 2f && held < 3 -> "Pin đang ấm lên, sò lạnh có thể chưa chạy"
                                else -> ""
                            }
                            if (t.isNotEmpty()) {
                                coolerSaid = c
                                if (alertSound == 1 && voice.ready) voice.say(t, minGapMs = 0L) else beeper?.chime()
                                status = t
                            }
                        }
                        // Nhật ký nhiệt: mỗi 10 s một dòng CSV (xem Cài đặt › Thông tin › Nhật ký nhiệt)
                        if (c - lastTLog >= 10_000L) {
                            lastTLog = c
                            thermalLog.log(ti, held, yoloHz, npuRate(), displayEvery, latest?.npuMs ?: -1L, coolPause, gps.speedKmh())
                        }
                        // Tạm nghỉ làm mát: Android báo CRITICAL hoặc pin quá ngưỡng -> dừng NPU/YOLO trước khi hệ thống
                        // (ColorOS...) tự đóng app vì nóng. Chạy lại khi pin về gần nhiệt độ mục tiêu.
                        val hot = ti.status >= 4 || (!ti.batteryC.isNaN() && ti.batteryC >= pauseC())
                        val cooled = ti.status <= 2 && (ti.batteryC.isNaN() || ti.batteryC <= coolTarget + 1f)
                        if (thermalAuto && !coolPause && hot) {
                            coolPause = true
                            latest = null
                            dets = emptyList()
                            tracker.reset()
                            coolNote = "Pin %.1f°C, Android báo %s".format(ti.batteryC, ThermalMonitor.statusName(ti.status))
                            Log.w(TAG, "tạm nghỉ làm mát: $coolNote")
                            if (alertSound == 1 && voice.ready) voice.say("Máy quá nóng, tạm dừng cảnh báo để làm mát", urgent = true, minGapMs = 0L)
                            else beeper?.chime()
                        } else if (coolPause && (cooled || !thermalAuto)) {
                            coolPause = false
                            Log.w(TAG, "hết tạm nghỉ làm mát")
                            if (alertSound == 1 && voice.ready) voice.say("Đã chạy lại cảnh báo", minGapMs = 0L)
                        }
                    } catch (_: Throwable) {}
                }
                if (job == null) {
                    if (done.get() && q.isEmpty()) break else continue
                }
                if (coolPause) continue

                val t0 = SystemClock.elapsedRealtime()
                val out = eng.step(job.input)
                val npuMs = SystemClock.elapsedRealtime() - t0

                // YOLO (NPU) theo nhịp yoloHz (luồng A đã chọn khung); các khung giữa dùng lại hộp gần nhất
                var detMs = -1L
                val yi = job.yolo
                if (det != null && yoloOn && yoloHz > 0f) {
                    if (yi != null) {
                        val s0 = SystemClock.elapsedRealtime()
                        try {
                            dets = tracker.update(det.detect(yi.buf, yi.lb, job.w, job.h), job.ptsUs)
                        } catch (t: Throwable) {
                            Log.w(TAG, "YOLO lỗi, tắt", t)
                            yoloOn = false
                            dets = emptyList()
                        }
                        detMs = SystemClock.elapsedRealtime() - s0
                    }
                } else if (dets.isNotEmpty()) {
                    dets = emptyList()
                    tracker.reset()
                }

                // Tốc độ: model tính theo bước 50 ms, hiệu chỉnh theo dt thực giữa 2 khung suy luận
                val vKmh = out[887] * 3.6f * (STEP_US.toFloat() / job.dtUs.coerceAtLeast(1L))
                speedEma = if (n == 0) vKmh else speedEma * SPEED_EMA + vKmh * (1f - SPEED_EMA)

                // Tự hiệu chỉnh từ hướng chuyển động ước lượng (pose tx,ty,tz tại 887..889; yaw rate tại 892)
                if (autoCal && n >= WARMUP) {
                    val tx = out[887]
                    val ty = out[888]
                    val tz = out[889]
                    val yawRate = out[892]
                    if (tx > AUTO_MIN_SPEED && abs(yawRate) < 0.03f) {
                        val dz = (tz / tx).coerceIn(-0.2f, 0.2f)
                        val dy = (ty / tx).coerceIn(-0.2f, 0.2f)
                        horizon = (horizon + GAIN * AUTO_SIGN * job.fSrc * dz / job.h).coerceIn(0.3f, 0.8f)
                        cxShift = (cxShift + GAIN * AUTO_SIGN * job.fSrc * dy / job.w).coerceIn(-0.15f, 0.15f)
                        if (n % 300 == 0) prefs.edit().putFloat("horizon", horizon).apply()   // nhớ cho lần chạy sau
                    }
                }

                lastOutput = out
                lastIdx = n
                latest = Latest(out, dets, speedEma, npuMs, detMs, n)
                n++
            }
        } catch (e: Throwable) {
            err.compareAndSet(null, e)
            run.end()
        }
    }

    // Luồng C (luồng gọi): vẽ HUD lên MỌI khung, giữ nhịp theo pts của video, rồi đẩy ra UI
    private fun render(run: Run, live: Boolean, beeper: BeepPlayer, frameQ: ArrayBlockingQueue<FrameJob>, done: AtomicBoolean) {
        var shown = 0
        var wall0 = 0L
        var pts0 = 0L
        var lastStatus = SystemClock.elapsedRealtime()
        var shownAtStatus = 0
        var lastYoloMs = -1L
        var riskRun = 0
        var alertUntil = 0L
        var cautionUntil = 0L
        var lastFrameAt = SystemClock.elapsedRealtime()
        var lastRn = -1
        var rnAt = 0L
        var stillSince = -1L      // bắt đầu đứng yên (ms)
        var overSince = -1L       // bắt đầu vượt tốc độ (ms)
        var overSaid = -1_000_000L
        idle = false
        dimNow = false
        while (run.alive) {
            val job = frameQ.poll(100, TimeUnit.MILLISECONDS)
            if (job == null) {
                if (done.get() && frameQ.isEmpty()) break
                // Tạm nghỉ làm mát: luồng A cố ý không đẩy khung, không phải camera treo (trước đây bị mở lại camera liên tục)
                if (coolPause) { lastFrameAt = SystemClock.elapsedRealtime(); continue }
                if (live && SystemClock.elapsedRealtime() - lastFrameAt > STALL_MS) {
                    status = "Camera treo, đang mở lại..." + ((curSrc as? UsbCamSource)?.info()?.let { " ($it)" } ?: "")
                    break
                }
                continue
            }
            // Icon CAMERA: chậm (cam) khi khung cách nhau > 150 ms hoặc kết quả nhận diện cũ > 0,5 s; mất (đỏ) khi chưa có / cũ > 2 s
            val frameGap = SystemClock.elapsedRealtime() - lastFrameAt
            lastFrameAt = SystemClock.elapsedRealtime()

            val r = latest
            if (r != null && r.n != lastRn) { lastRn = r.n; rnAt = lastFrameAt }
            val camState = when {
                r == null || lastFrameAt - rnAt > 2000L -> 2
                frameGap > 150L || lastFrameAt - rnAt > 500L -> 1
                else -> 0
            }
            var risk = 0
            // Viền cảnh báo vẽ ngay trong HUD (vùng mặt đường); dùng trạng thái hiện tại, trễ tối đa 1 khung
            val nowPre = SystemClock.elapsedRealtime()
            val frameAlert = when {
                !warnOn -> 0
                nowPre < alertUntil -> 2
                nowPre < cautionUntil -> 1
                else -> 0
            }
            var logSpd = 0f
            var logWarn = 0f
            var limitNow = 0
            var pic: Picture? = null
            if (r != null) {
                val cam = Cam(job.fSrc, job.cx, job.cy, DW / job.w.toFloat())
                // Camera thật: ưu tiên tốc độ GPS
                // Chạy thử tuyến: tốc độ xe ảo (cả khi đang phát video)
                val spd = if (live || gps.mocking) (gps.speedKmh() ?: r.speedKmh) else r.speedKmh
                // Ảnh gốc (chưa vẽ HUD) cho ảnh cảnh báo và ảnh huấn luyện
                clips.feed(job.bmp, nowPre)
                // Thu ảnh huấn luyện tạm ngừng khi máy ấm (mức 2+) cho đỡ ghi bộ nhớ
                if (collectData && level < 2) clips.collect(job.bmp, nowPre, maxOf(spd, r.speedKmh))
                limitNow = speedLimit()
                // Tên đường từ HERE: đang chỉ đường thì lấy theo tuyến (không tốn lượt gọi), không thì tra theo GPS (có giãn cách)
                val street = if (nav.hasSpans) nav.roadName() else here.street(spd)
                // Giới hạn đọc từ biển: giữ tới khi gặp biển mới, hoặc khi rẽ sang đường khác (tên đường đổi)
                if (street.isNotEmpty()) {
                    if (lastStreet.isNotEmpty() && street != lastStreet && HudRenderer.signLimit > 0) HudRenderer.clearSignLimit()
                    lastStreet = street
                }
                nav.update(spd)
                // HUD ghi thành Picture (danh sách lệnh vẽ), lớp phủ phát lại bằng GPU; không vẽ lên bitmap nữa
                val pk = Picture()
                val pc = pk.beginRecording(DW, job.bmp.height)
                risk = HudRenderer.draw(pc, r.out, cam, DW, spd, r.dets, job.ptsUs,
                    limitNow,
                    false,
                    clockNow(), street, weather.text(),
                    if (nav.active) nav.turn else null, job.ptsUs / 1_000_000f,
                    // Cảnh báo va chạm lấy tốc độ lớn hơn giữa GPS và camera: GPS = 0 (đứng yên / không fix tốt)
                    // không được làm tắt cảnh báo khi camera thấy xe đang chạy
                    // Chạy thử tuyến: cảnh báo chỉ theo tốc độ camera (xe ảo 40 km/h không được làm kêu khi máy đang để yên)
                    if (gps.mocking) r.speedKmh else maxOf(spd, r.speedKmh),
                    job.h, hoodCut, roadMode, weather.wet, frameAlert, nav,
                    if (live || gps.mocking) gps.quality() else 2, camState,
                    perfText, perfCpu >= 70f)
                pk.endRecording()
                pic = pk
                CarLink.feed(pk, job.bmp, spd, limitNow, if (nav.active) nav.turn else null, street)
                if (r.yoloMs >= 0) lastYoloMs = r.yoloMs
                logSpd = spd
                logWarn = maxOf(spd, r.speedKmh)
                driveKmh = logWarn
            }
            // Mức 2 (nguy hiểm): liên tục >= 3 khung (~100 ms) mới kích hoạt tiếng tít + viền đỏ, giữ thêm 0,6 s.
            // Mức 1 (chú ý): chỉ viền vàng, không tiếng (giọng đọc thì nhắc nhẹ), giữ thêm 0,3 s cho đỡ nhấp nháy.
            if (risk >= 2) riskRun++ else riskRun = 0
            val nowMs = SystemClock.elapsedRealtime()
            val dangerStart = riskRun == 3 && nowMs >= alertUntil
            val cautionStart = risk == 1 && nowMs >= cautionUntil && nowMs >= alertUntil
            // Ghi nhật ký khi một đợt cảnh báo BẮT ĐẦU (không ghi lặp mỗi khung khi đang kêu)
            if (logOn && warnOn) {
                if (dangerStart) alertLog.log(2, live, job.ptsUs, logSpd, logWarn, hoodCut)
                else if (cautionStart) alertLog.log(1, live, job.ptsUs, logSpd, logWarn, hoodCut)
            }
            // Ảnh để xem lại: mọi lần nguy hiểm + lần chú ý do xe cắt đầu
            if (warnOn && (dangerStart || (cautionStart && HudRenderer.riskKind == "cutin"))) {
                val info = "Mức ${if (dangerStart) 2 else 1} · ${HudRenderer.riskReason} · " +
                    (if (HudRenderer.riskCls < 0) "Xe phía trước" else clsName(HudRenderer.riskCls)) +
                    (if (HudRenderer.riskDist.isNaN()) "" else " · %.0f m".format(HudRenderer.riskDist)) +
                    " · %.0f km/h".format(logWarn)
                // Ảnh cảnh báo cần cả HUD: ghép bitmap + Picture vào một bản sao
                val shot = job.bmp.copy(Bitmap.Config.ARGB_8888, true)
                pic?.let { Canvas(shot).drawPicture(it) }
                clips.trigger(shot, info, nowMs)
                shot.recycle()
            }
            if (riskRun >= 3) alertUntil = nowMs + 600L
            if (risk >= 1) boostUntil = nowMs + 3000L   // có nguy cơ: NPU đủ 20 lần/giây trong 3 s
            if (risk == 1) cautionUntil = nowMs + 300L

            // Giọng đọc: câu theo loại cảnh báo (không đọc tên xe)
            if (warnOn && useVoice()) {
                voice.volume = beepVolume / 100f
                val kind = HudRenderer.riskKind
                if (riskRun >= 3) {
                    val t = when (kind) {
                        "cutin" -> if (HudRenderer.cutInLeft) "Xe cắt đầu bên trái" else "Xe cắt đầu bên phải"
                        "tail", "gap" -> "Quá gần, giảm tốc"
                        else -> "Phanh! Xe phía trước"
                    }
                    voice.say(t, urgent = true, minGapMs = 2500L)
                } else if (cautionStart) {
                    val t = when (kind) {
                        "cutin" -> if (HudRenderer.cutInLeft) "Xe cắt đầu bên trái" else "Xe cắt đầu bên phải"
                        "tail", "gap" -> "Giữ khoảng cách"
                        else -> "Chú ý xe phía trước"
                    }
                    voice.say(t, urgent = false, minGapMs = 6000L)
                }
            }
            // Chỉ đường: đọc hướng rẽ (giọng đọc), hoặc hai tiếng nhẹ ngay trước chỗ rẽ (tiếng tít)
            nav.consumeCue()?.let { c ->
                if (useVoice()) { voice.volume = beepVolume / 100f; voice.say(c.text, urgent = false, minGapMs = 3000L) }
                else if (c.near) beeper.chime()
            }
            // Xe trước đã chạy (đang đứng chờ)
            if (HudRenderer.consumeLeadMoved()) {
                if (useVoice()) voice.say("Xe trước đã chạy", minGapMs = 10_000L) else beeper.chime()
            }
            // Vượt giới hạn tốc độ hơn 5 km/h liên tục 3 s: nhắc một lần, tối đa 30 s một lần
            if (limitNow > 0 && logSpd > limitNow + 5f) {
                if (overSince < 0L) overSince = nowMs
                if (nowMs - overSince > 3000L && nowMs - overSaid > 30_000L) {
                    overSaid = nowMs
                    if (useVoice()) voice.say("Vượt tốc độ, giới hạn $limitNow", minGapMs = 30_000L) else beeper.chime()
                }
            } else overSince = -1L
            // Đứng yên hơn 3 s: giảm tải
            if (logWarn < 2f) { if (stillSince < 0L) stillSince = nowMs; idle = nowMs - stillSince > 3000L }
            else { stillSince = -1L; idle = false }
            val dim = live && stillSince >= 0L && nowMs - stillSince > DIM_STILL_MS && nowMs >= alertUntil && nowMs >= cautionUntil
            if (dimNow != dim) dimNow = dim
            val alert = warnOn && nowMs < alertUntil
            val cautionNow = warnOn && !alert && nowMs < cautionUntil
            if (cautionOn != cautionNow) cautionOn = cautionNow

            if (capFps && !live) {
                val now = System.nanoTime()
                if (shown == 0) { wall0 = now; pts0 = job.ptsUs }
                var due = wall0 + (job.ptsUs - pts0) * 1000L
                if (now - due > RESYNC_NS) { wall0 += now - due; due = now }
                while (true) {
                    val wait = due - System.nanoTime()
                    if (wait <= 0) break
                    LockSupport.parkNanos(wait)
                }
            }

            hudSink?.invoke(pic)
            val asp = job.w / job.h.toFloat()
            if (abs(asp - frameAspect) > 0.01f) frameAspect = asp
            if (job.gpu) {
                if (!previewing) previewing = true
                if (frame != null) frame = null
            } else {
                if (previewing) previewing = false
                frame = job.bmp
            }
            shown++
            val flashOn = alert && ((job.ptsUs / 150_000L) % 2L == 0L)
            if (alertFlash != flashOn) alertFlash = flashOn
            if (alert && !useVoice()) beeper.beep()

            val t = SystemClock.elapsedRealtime()
            if (t - lastStatus >= STATUS_MS) {
                val fps = (shown - shownAtStatus) * 1000f / (t - lastStatus)
                lastStatus = t
                shownAtStatus = shown
                val sb = StringBuilder()
                sb.append("t=").append("%.1f".format(job.ptsUs / 1_000_000.0)).append("s | khung ").append(shown)
                sb.append(" | hiển thị ").append("%.0f".format(fps)).append(" fps")
                if (r != null) {
                    sb.append(" | suy luận #").append(r.n).append(" | NPU ").append(r.npuMs).append(" ms")
                }
                sb.append(" | CPU ").append(job.prepMs).append(" ms")
                if (lastYoloMs >= 0) sb.append(" | YOLO ").append(lastYoloMs).append(" ms")
                sb.append(" | lệch ngang ").append((cxShift * 100).roundToInt()).append("%")
                status = sb.toString()
                Log.d(TAG, status)
            }
        }
    }

    // Chính sách giảm tải theo mức nhiệt. YOLO luôn chạy (chỉ giãn nhịp) vì là nguồn cảnh báo va chạm;
    // model quen với 20 Hz nên chỉ hạ nhịp suy luận ở mức nặng nhất.
    private fun applyThermal(lv: Int) {
        level = lv
        displayEvery = if (thermalAuto && lv >= 3) 2 else 1
        // YOLO không tắt hẳn ở mức nào: nó là nguồn chính của cảnh báo xe cắt đầu / người đi bộ
        yoloHz = if (!thermalAuto) 10f else when (lv) {
            0, 1 -> 7f
            2 -> 4f
            else -> 2.5f
        }
    }

    // Mức giảm tải theo nhiệt độ pin so với mục tiêu (pin nóng chậm hơn chip nên bắt đầu sớm 1,5 °C)
    private fun targetLevel(batteryC: Float): Int {
        if (!thermalAuto || batteryC.isNaN()) return 0
        val t = coolTarget
        return when {
            batteryC >= t + 4f -> 4
            batteryC >= t + 2f -> 3
            batteryC >= t + 0.5f -> 2
            batteryC >= t - 1.5f -> 1
            else -> 0
        }
    }

    private fun thermalText(i: ThermalInfo, lv: Int): String {
        val sb = StringBuilder()
        sb.append("Pin ").append(if (i.batteryC.isNaN()) "--" else "%.1f".format(i.batteryC)).append("°C")
        sb.append(" | Nhiệt ").append(ThermalMonitor.statusName(i.status))
        if (!i.headroom.isNaN()) sb.append(" (headroom ").append("%.2f".format(i.headroom)).append(")")
        if (i.plugged) sb.append(" | đang sạc")
        else if (!i.powerW.isNaN()) sb.append(" | ≈").append("%.1f".format(i.powerW)).append(" W")
        sb.append(" | mức giảm tải ").append(lv)
        sb.append(" | NPU ").append(npuRate()).append(" lần/s | YOLO ").append("%.1f".format(yoloHz)).append(" lần/s")
        if (displayEvery > 1) sb.append(" | màn hình 10 hình/s")
        return sb.toString()
    }

    fun start() { useCamera = false; wantCamera = false; launch() }
    // Giữ dịch vụ nền camera suốt lúc chạy: tắt màn hình / chuyển app khác (Android Auto) camera vẫn chạy
    fun startCamera() { useCamera = true; wantCamera = true; HudFgService.startIfVisible(ctx); if (!running) status = "Đang mở camera..."; launch() }

    private fun openSource(live: Boolean, uri: Uri?, run: Run): FrameSource {
        if (!live) return VideoDecoder(ctx, uri!!)
        return when (camKind) {
            CamKind.PHONE -> CameraSource(ctx) { run.alive }.also { if (gpuPreview) it.previewTexture = previewTexture }
            // Camera USB: đọc thẳng chuẩn UVC (webcam, module camera USB), không được mới thử Camera2 camera ngoài
            CamKind.USB -> UsbCamSource(ctx) { run.alive }.also { if (gpuPreview) it.previewTexture = previewTexture }
            CamKind.WIFI -> { DashcamWifi.ensure(ctx); NetStreamSource(ctx, streamUrl.trim()) { run.alive } }
        }
    }

    private fun launch() {
        val uri = videoUri
        val live = useCamera
        if (!live && uri == null) { status = "Chưa chọn video"; return }
        if (running) return
        running = true
        val run = Run(++gen)
        val prev = worker
        alertFlash = false
        cautionOn = false
        worker = Thread {
            val startedAt = SystemClock.elapsedRealtime()
            var dec: FrameSource? = null
            var producer: Thread? = null
            var inferT: Thread? = null
            var bp: BeepPlayer? = null
            try {
                // Chờ lần chạy trước thả camera / NPU hẳn rồi mới bắt đầu
                if (prev != null && prev.isAlive) {
                    status = "Đang dừng nguồn cũ..."
                    prev.join()
                }
                if (!run.alive) return@Thread
                cxShift = 0f
                level = 0
                yoloHz = 7f
                boostUntil = 0L
                displayEvery = 1
                latest = null
                tracker.reset()
                HudRenderer.resetRisk()
                bp = BeepPlayer(beepVolume.toInt())
                beeper = bp
                ModelFiles.onProgress = { status = it }
                if (engine == null) {
                    status = "Đang nạp model lên NPU (không có cache sẽ mất ~13 phút)..."
                    val e = DrivingEngine(ctx)
                    Log.d(TAG, "engine loaded in ${e.load()} ms")
                    engine = e
                }
                val eng = engine!!
                eng.reset()
                if (!yoloTried) {
                    yoloTried = true
                    try {
                        status = "Đang nạp YOLO (lần đầu trên NPU có thể mất vài phút)..."
                        val y = YoloDetector(ctx)
                        Log.d(TAG, "yolo loaded in ${y.load()} ms, npu=${y.onNpu}")
                        detector = y
                    } catch (e: Throwable) {
                        Log.w(TAG, "YOLO tắt", e)
                        detector = null
                        yoloOn = false
                        status = "YOLO tắt: ${e.message}"
                    }
                }
                val det = detector
                if (!run.alive) return@Thread
                if (live) status = "Đang mở camera ${camKind.label}..."
                val d = openSource(live, uri, run)
                dec = d
                curSrc = d
                d.open()
                if (d is CameraSource && d.hfovDeg > 0f) hfov = d.hfovDeg.coerceIn(20f, 140f)

                val frameQ = ArrayBlockingQueue<FrameJob>(2)
                val inferQ = ArrayBlockingQueue<InferJob>(2)
                val done = AtomicBoolean(false)
                val err = AtomicReference<Throwable?>(null)
                producer = Thread { produce(run, d, det, frameQ, inferQ, done, err) }.also { it.start() }
                inferT = Thread { infer(run, eng, det, inferQ, done, err) }.also { it.start() }
                render(run, live, bp, frameQ, done)
                err.get()?.let { throw it }
                if (run.current) status += " | kết thúc"
            } catch (e: Throwable) {
                Log.e(TAG, "run failed", e)
                if (run.current) status = "Lỗi: ${e.message}"
            } finally {
                run.end()
                try { producer?.join(3000) } catch (_: Throwable) {}
                try { inferT?.join(3000) } catch (_: Throwable) {}
                try { dec?.release() } catch (_: Throwable) {}
                if (curSrc === dec) curSrc = null
                if (run.current) { previewing = false; hudSink?.invoke(null) }
                try { bp?.release() } catch (_: Throwable) {}
                if (beeper === bp) beeper = null
                if (run.current) {
                    alertFlash = false
                    cautionOn = false
                    // Tự mở lại camera (chờ lâu dần nếu lỗi liên tục)
                    if (live && wantCamera) {
                        val ranMs = SystemClock.elapsedRealtime() - startedAt
                        retryDelayMs = if (ranMs > 10_000L) 1500L else (retryDelayMs * 2).coerceAtMost(10_000L)
                        val dl = retryDelayMs
                        status = "$status | mở lại camera sau ${(dl + 999) / 1000} s"
                        mainH.postDelayed({ if (wantCamera && !running) launch() }, dl)
                    }
                }
            }
        }.also { it.start() }
    }

    // Chép nhật ký + ảnh vào Download/EVCarHUD (luồng riêng: ảnh huấn luyện có thể nhiều)
    fun saveAllToDownloads() {
        status = "Đang lưu vào Download/EVCarHUD..."
        android.widget.Toast.makeText(ctx, status, android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val msg = DownloadExport.saveAll(ctx)
            status = msg
            Handler(Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
        }.start()
    }
}

// Tách riêng để chỉ vùng ảnh recompose ở 30 fps, không kéo theo cả cột nút và thanh trượt
@Composable
private fun FrameView(runner: Runner, modifier: Modifier, scale: ContentScale) {
    runner.frame?.let {
        Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = scale)
    }
}

// Hình camera điện thoại qua GPU. Camera2 đẩy hình vào SurfaceTexture theo chiều dọc tự nhiên của máy;
// app khoá ngang nên xoay lại ±90° và bù tỉ lệ (khung View đã cùng tỉ lệ với ảnh camera)
@Composable
private fun CameraTexture(runner: Runner, modifier: Modifier, rotate: Boolean = true) {
    AndroidView(modifier = modifier, factory = { c ->
        TextureView(c).apply {
            fun fix() {
                val vw = width.toFloat()
                val vh = height.toFloat()
                if (vw <= 0f || vh <= 0f) return
                // Webcam USB: thư viện vẽ đúng chiều, khung View đã cùng tỉ lệ ảnh nên không cần biến đổi
                if (!rotate) { setTransform(null); return }
                @Suppress("DEPRECATION")
                // App khoá ngang: máy báo dọc (0) thì coi như ngang chiều thường, giống CameraSource
                val rot = (display?.rotation ?: Surface.ROTATION_90).let { if (it == Surface.ROTATION_0) Surface.ROTATION_90 else it }
                val m = Matrix()
                val cx = vw / 2f
                val cy = vh / 2f
                if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
                    m.setScale(vh / vw, vw / vh, cx, cy)
                    m.postRotate(90f * (rot - 2), cx, cy)
                } else if (rot == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
                setTransform(m)
            }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { fix(); runner.onPreviewTexture(st) }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fix() }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runner.onPreviewTexture(null); return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    })
}

// Lớp phủ HUD: phát lại Picture của khung mới nhất trên canvas phần cứng (GPU)
private class HudOverlayView(c: Context) : View(c) {
    @Volatile private var pic: Picture? = null
    fun show(p: Picture?) { pic = p; postInvalidateOnAnimation() }
    override fun onDraw(canvas: Canvas) {
        val p = pic ?: return
        val s = width / DW.toFloat()
        canvas.save()
        canvas.scale(s, s)
        canvas.drawPicture(p)
        canvas.restore()
    }
}

@Composable
private fun HudOverlay(runner: Runner, modifier: Modifier) {
    val ctx = LocalContext.current
    val v = remember { HudOverlayView(ctx) }
    DisposableEffect(v) {
        runner.hudSink = v::show
        onDispose { if (runner.hudSink != null) runner.hudSink = null }
    }
    AndroidView(factory = { v }, modifier = modifier)
}

// Tên file hiển thị của video đã chọn (để hỏi xác nhận)
private fun displayName(ctx: Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "video"
} catch (_: Throwable) { uri.lastPathSegment ?: "video" }

// ---- Trang cài đặt: nhóm thẻ bo tròn nền tối, mỗi dòng gồm tiêu đề, giá trị bên phải, mô tả xám ----
private val CardBg = Color(0xFF1C1C1E)
private val DescGray = Color(0xFF9A9A9F)
private val LineGray = Color(0xFF2C2C2E)

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = DescGray, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp))
}

// Thẻ nhóm; các dòng bên trong ngăn bằng RowLine()
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
    ) { content() }
}

@Composable
private fun RowLine() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(LineGray))
}

@Composable
private fun RowTitle(title: String, value: String? = null, valueColor: Color = Color(0xFFD0D0D5)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = valueColor, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Desc(text: String, top: Int = 4) {
    Text(text, color = DescGray, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = top.dp))
}

@Composable
private fun SliderRow(
    title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    minLabel: String, maxLabel: String, desc: String?,
    onChange: (Float) -> Unit, onDone: () -> Unit = {}
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        RowTitle(title, valueText)
        Slider(
            value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF48484A)
            ),
            modifier = Modifier.padding(top = 4.dp)
        )
        Row {
            Text(minLabel, color = DescGray, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(maxLabel, color = DescGray, fontSize = 13.sp)
        }
        if (desc != null) Desc(desc, top = 8)
    }
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp)
            if (desc != null) Desc(desc, top = 2)
        }
        Switch(
            checked = checked, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Color.White, checkedBorderColor = Color.White,
                uncheckedThumbColor = Color(0xFF8E8E93), uncheckedTrackColor = Color(0xFF2C2C2E),
                uncheckedBorderColor = Color(0xFF48484A)
            ),
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

// Dòng bấm được: tiêu đề, giá trị/mô tả, mũi tên ›
@Composable
private fun NavRow(title: String, value: String? = null, desc: String? = null, valueColor: Color = Color(0xFFD0D0D5), onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title, value, valueColor)
            if (desc != null) Desc(desc, top = 2)
        }
        if (onClick != null) Text("›", color = DescGray, fontSize = 26.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

// Nút phân đoạn (chọn một trong vài mục)
@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2C2E)).padding(3.dp)
    ) {
        for ((i, label) in labels.withIndex()) {
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Color.White else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (sel) Color.Black else Color.White, fontSize = 14.sp)
            }
        }
    }
}

// Chế độ đường: Trong phố / Cao tốc / Mưa
@Composable
private fun RoadModePicker(runner: Runner) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        RowTitle("Chế độ đường")
        Segmented(listOf("Trong phố", "Cao tốc", "Mưa"), runner.roadMode) {
            runner.roadMode = it; runner.savePrefs()
        }
        Desc(
            when (runner.roadMode) {
                HudRenderer.MODE_HIGHWAY ->
                    "Từ 60 km/h giữ khoảng cách tối thiểu: 60 km/h 35 m, đến 80 km/h 55 m, đến 100 km/h 70 m, trên 100 km/h 100 m. " +
                    "Gần hơn: viền vàng; gần hơn một nửa: viền đỏ và tiếng tít. Thời tiết báo mưa thì tự tăng 30%. Dưới 60 km/h báo như trong phố."
                HudRenderer.MODE_RAIN ->
                    "Đường ướt phanh dài hơn nên báo sớm hơn: thời gian tới va chạm 2,5 s (đỏ) / 4,5 s (vàng), giãn cách tối thiểu 1,5 s, " +
                    "từ 60 km/h khoảng cách luật tăng 30% (35→46, 55→72, 70→91, 100→130 m). Kẹt xe bò chậm vẫn không kêu."
                else -> "Báo theo thời gian tới va chạm và bám đuôi; kẹt xe, bò chậm sát nhau thì không kêu."
            },
            top = 6
        )
    }
}

// Vùng capo che đáy khung: 3 mức 5 / 10 / 15%
private val HOOD_STEPS = floatArrayOf(0.05f, 0.10f, 0.15f)

@Composable
private fun HoodPicker(runner: Runner) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        RowTitle("Che vùng capo")
        val sel = HOOD_STEPS.indices.minByOrNull { abs(HOOD_STEPS[it] - runner.hoodCut) } ?: 2
        Segmented(listOf("5%", "10%", "15%"), sel) { runner.hoodCut = HOOD_STEPS[it]; runner.savePrefs() }
        Desc("Phần đáy khung bị capo/taplo che: không nhận diện, không cảnh báo. Chọn mức cho vạch trắng nằm sát mép capo.", top = 6)
    }
}

// Ô chọn nguồn camera (dạng nút phân đoạn)
@Composable
private fun SourcePicker(runner: Runner) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        RowTitle("Nguồn camera")
        val kinds = CamKind.values()
        Segmented(kinds.map { it.label }, runner.camKind.ordinal) { runner.selectCamKind(kinds[it]) }
        if (runner.camKind == CamKind.WIFI) {
            OutlinedTextField(
                value = runner.streamUrl,
                onValueChange = { runner.streamUrl = it; runner.savePrefs() },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                label = { Text("Địa chỉ luồng", fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            DashcamFinder(runner)
            Desc("rtsp://… hoặc http://… (MJPEG). Bấm \"Tìm cam & dò luồng\": app tự nối Wi‑Fi cam (4G vẫn giữ cho bản đồ) và tự điền địa chỉ.", top = 6)
        }
    }
}

// Nút tự tìm cam: chọn Wi‑Fi cam (mật khẩu mặc định 12345678) -> app tự nối -> dò IP / cổng / đường dẫn hay gặp
@Composable
private fun DashcamFinder(runner: Runner) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("evcarhud", Context.MODE_PRIVATE) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ssids by remember { mutableStateOf(emptyList<String>()) }
    var pass by remember { mutableStateOf(prefs.getString("camPass", DashcamWifi.DEFAULT_PASS) ?: DashcamWifi.DEFAULT_PASS) }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun probe(net: android.net.Network?) {
        val wasRunning = runner.running
        if (wasRunning) runner.stop()   // cam thường chỉ cho 1 máy xem: dừng luồng đang chạy để dò
        busy = true
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                if (wasRunning) delay(1500)
                DashcamProbe.find(ctx, net, { true }) { m -> main.post { status = m } }
            }
            busy = false
            if (url != null) {
                runner.streamUrl = url
                runner.savePrefs()
                status = "Đã tìm thấy luồng: $url"
                runner.startCamera()
            } else {
                status = "Không tìm thấy luồng nào. Cam có thể đã khoá luồng video, hoặc chưa nối đúng Wi‑Fi cam."
                if (wasRunning) runner.startCamera()
            }
        }
    }

    Button(
        onClick = {
            ssids = DashcamWifi.nearby(ctx)
            picking = true
        },
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E), contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    ) { Text(if (busy) "Đang dò…" else "Tìm cam & dò luồng", fontSize = 15.sp) }
    status?.let { Desc(it, top = 6) }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Chọn Wi‑Fi của camera") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it }, singleLine = true,
                        label = { Text("Mật khẩu Wi‑Fi cam") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(Modifier.padding(top = 8.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            "Máy đã nối Wi‑Fi cam rồi, chỉ dò luồng",
                            modifier = Modifier.fillMaxWidth().clickable {
                                picking = false
                                status = "Đang dò luồng…"
                                probe(DashcamWifi.current(ctx))
                            }.padding(vertical = 12.dp)
                        )
                        if (ssids.isEmpty()) Text("Không thấy Wi‑Fi nào. Bật Wi‑Fi trên cam, bật vị trí của máy rồi thử lại.", fontSize = 14.sp)
                        for (s in ssids) {
                            Text(
                                (if (DashcamWifi.looksLikeCam(s)) "📷 " else "") + s,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    picking = false
                                    busy = true
                                    status = "Đang nối $s… (Android sẽ hỏi xác nhận)"
                                    prefs.edit().putString("camSsid", s).putString("camPass", pass).apply()
                                    DashcamWifi.connect(ctx, s, pass) { n ->
                                        main.post {
                                            if (n == null) {
                                                busy = false
                                                status = "Không nối được $s: sai mật khẩu hoặc đã huỷ."
                                            } else {
                                                status = "Đã nối $s, đang dò luồng…"
                                                probe(n)
                                            }
                                        }
                                    }
                                }.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Đóng") } }
        )
    }
}

// Trang cài đặt: nằm dưới nút ⚙. Máy xoay ngang thì chiếm nửa phải màn hình để vẫn thấy hình (căn vùng capo, chân trời)
@Composable
private fun BoxScope.SettingsPanel(runner: Runner, land: Boolean, onPick: () -> Unit, onCamera: () -> Unit,
                                   onReview: () -> Unit, onCalibrate: () -> Unit, onNav: () -> Unit) {
    val white = Color.White
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .then(if (land) Modifier.widthIn(max = 480.dp).fillMaxHeight() else Modifier.fillMaxSize())
            .background(Color(0xF2000000))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
    ) {
        Text("Cài đặt", color = white, fontSize = 30.sp, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp))

        SectionTitle("Nguồn hình")
        SettingsCard {
            SourcePicker(runner)
            RowLine(); NavRow(if (runner.running) "Dừng" else "Chạy camera",
                desc = if (runner.running) "Đang chạy" else "Mở ${runner.camKind.label}",
                onClick = onCamera)
            RowLine(); NavRow("Chạy video test", desc = "Chọn video, xác nhận rồi chạy", onClick = onPick)
        }

        SectionTitle("Cảnh báo")
        SettingsCard {
            SwitchRow("Cảnh báo va chạm", "Âm báo và viền đỏ khi sắp va chạm; viền vàng khi cần chú ý.", runner.warnOn) { runner.warnOn = it }
            RowLine(); Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                RowTitle("Âm báo")
                Segmented(listOf("Tiếng tít", "Giọng đọc"), runner.alertSound) { runner.alertSound = it; runner.savePrefs() }
                Desc(
                    if (runner.alertSound == 1)
                        "Đọc câu ngắn: \"Phanh! Xe phía trước\", \"Giữ khoảng cách\", \"Xe cắt đầu bên phải\", \"Xe trước đã chạy\", \"Vượt tốc độ\". " +
                        "Trạng thái: ${runner.voice.note}."
                    else "Tít liên tục khi nguy hiểm; hai tiếng nhẹ khi xe trước đã chạy hoặc vượt tốc độ.",
                    top = 6
                )
            }
            RowLine(); SliderRow("Âm lượng cảnh báo", "${runner.beepVolume.roundToInt()}%", runner.beepVolume, 10f..100f,
                "10%", "100%", "Tương đối so với âm lượng phương tiện của máy.",
                onChange = { runner.beepVolume = it }, onDone = { runner.applyBeepVolume(); runner.testSound() })
            RowLine(); NavRow("Thử âm báo", onClick = { runner.testSound() })
            RowLine(); NavRow("Xem lại cảnh báo", desc = "Ảnh 2 s trước và sau mỗi lần cảnh báo; đánh dấu đúng / nhầm để chỉnh ngưỡng.", onClick = onReview)
            RowLine(); RoadModePicker(runner)
            RowLine(); HoodPicker(runner)
        }

        SectionTitle("Bản đồ HERE")
        SettingsCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                RowTitle("Tên đường & giới hạn tốc độ")
                Desc("Tên đường, giới hạn tốc độ và chỉ đường. Chỉ tra khi xe chạy trên 10 km/h và đi thêm ~150 m (400 m trên cao tốc) hoặc vừa rẽ; " +
                    "đang chỉ đường thì dùng dữ liệu cả tuyến, không tra thêm. Biển camera đọc được luôn được ưu tiên. " +
                    "Giới hạn: ${runner.limitSource()}. ${runner.here.note}", top = 6)
            }
            RowLine(); NavRow("Chỉ đường",
                desc = if (runner.nav.active) "Đang dẫn đường tới ${runner.nav.destName}" else "Tìm điểm đến, mũi tên rẽ và bản đồ nhỏ trên HUD",
                onClick = onNav)
            RowLine()
            val ctx = LocalContext.current
            NavRow("Nhạc trên Android Auto", value = if (MediaNow.granted(ctx)) "Đã cho phép" else "Chưa cho phép",
                desc = "Cho EVCarHUD đọc bài đang phát (YouTube Music, Spotify...) để hiện trên màn hình xe. Bấm để mở Truy cập thông báo.",
                onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            RowLine(); SwitchRow("Bản đồ nhỏ", "Tuyến đường dưới đồng hồ tốc độ khi đang chỉ đường, kèm quãng đường và thời gian còn lại.", runner.miniMap) {
                runner.miniMap = it; runner.savePrefs()
            }
        }

        SectionTitle("Camera và hiệu chỉnh")
        SettingsCard {
            SliderRow("FOV ngang camera", "${runner.hfov.roundToInt()}°", runner.hfov, 20f..140f,
                "20°", "140°", "Góc nhìn ngang của ống kính. Camera hành trình thường 120–130°.",
                onChange = { runner.hfov = it })
            RowLine(); SliderRow("Đường chân trời", "${(runner.horizon * 100).roundToInt()}%", runner.horizon, 0.3f..0.8f,
                "30%", "80%", "Tính từ mép trên khung hình. Tự hiệu chỉnh sẽ chỉnh dần khi xe chạy thẳng.",
                onChange = { runner.horizon = it }, onDone = { runner.savePrefs() })
            RowLine(); SwitchRow("Tự hiệu chỉnh", "Bật mặc định. Chỉnh dần chân trời và độ lệch ngang khi xe chạy thẳng trên ~20 km/h, nhớ cho lần sau.", runner.autoCal) { runner.autoCal = it; runner.savePrefs() }
            RowLine(); SwitchRow("Nhận diện vật thể (YOLO)", "Ô tô, xe máy, người, đèn giao thông, biển STOP.", runner.yoloOn) { runner.yoloOn = it }
            RowLine(); SliderRow("Chiều cao camera", "%.2f m".format(runner.camH), runner.camH, 0.6f..2.2f,
                "0,6 m", "2,2 m", "Từ mặt đường tới ống kính. Dùng để đo khoảng cách theo hộp nhận diện.",
                onChange = { runner.camH = it; HudRenderer.camH = it }, onDone = { runner.savePrefs() })
            RowLine(); NavRow("Hiệu chỉnh bằng khoảng cách thật", desc = "Dừng sau một xe, đo (hoặc ước) khoảng cách thật rồi nhập; app tự chỉnh chiều cao camera.", onClick = onCalibrate)
        }

        SectionTitle("Hiển thị")
        SettingsCard {
            SwitchRow("Phản chiếu kính lái", "Nền đen, chỉ chữ số và viền cảnh báo, lật gương để đặt điện thoại dưới kính lái. Màn OLED gần như tắt nên mát máy và đỡ pin.", runner.reflect) {
                runner.reflect = it; runner.savePrefs()
            }
        }

        SectionTitle("Hiệu năng")
        SettingsCard {
            SwitchRow("Tự giảm tải khi nóng", "Giãn nhịp YOLO và NPU theo nhiệt độ máy.", runner.thermalAuto) { runner.thermalAuto = it }
            RowLine(); SwitchRow("Có sò lạnh", "Bật khi gắn sò lạnh (quạt Peltier) sau lưng máy. App nhắc khi pin xuống dưới 25°C (dễ đọng hơi nước) " +
                "và khi pin vẫn ấm lên (sò mất nguồn hoặc không áp sát). Tắt: nhắc bật điều hoà hướng vào máy như cũ.", runner.cooler) {
                runner.cooler = it; runner.savePrefs()
            }
            RowLine(); SwitchRow("Giảm sáng khi đứng yên", "Xe dừng quá 20 giây thì màn hình tối bớt cho mát máy. Xe chạy hoặc có cảnh báo là sáng lại ngay.",
                runner.dimStill) { runner.dimStill = it; runner.savePrefs() }
            RowLine(); SliderRow("Nhiệt độ mục tiêu", "${runner.coolTarget.roundToInt()}°C", runner.coolTarget, 35f..45f,
                "35°C", "45°C",
                "Pin gần tới mức này thì app giãn nhịp YOLO trước, vượt quá thì hạ NPU còn 10 Hz. Đặt thấp thì máy mát hơn nhưng hộp nhận diện cập nhật chậm hơn. " +
                    (if (runner.thermalLine.isNotEmpty()) runner.thermalLine else ""),
                onChange = { runner.coolTarget = it }, onDone = { runner.savePrefs() })
            RowLine(); SwitchRow("Hình camera qua GPU (mát máy)", "Bật mặc định. Camera điện thoại hiện thẳng bằng GPU, CPU không phải đổi màu từng khung nên máy mát hơn nhiều. Tắt nếu hình bị xoay sai hoặc đen.", runner.gpuPreview) {
                runner.gpuPreview = it; runner.savePrefs(); runner.restartCamera()
            }
            RowLine(); SwitchRow("Giữ nhịp video", "Phát video test đúng tốc độ thật.", runner.capFps) { runner.capFps = it }
        }

        SectionTitle("Chẩn đoán")
        SettingsCard {
            SwitchRow("Nhật ký cảnh báo", if (runner.logOn) runner.logInfo() else "Ghi mỗi lần cảnh báo ra file CSV để xem lại.", runner.logOn) {
                runner.logOn = it; runner.savePrefs()
            }
            RowLine(); SwitchRow("Thu ảnh huấn luyện",
                "1 ảnh mỗi 5 s khi xe chạy, để gán nhãn và huấn luyện YOLO cho đường Việt Nam. Lưu ở ${runner.clips.datasetDir.absolutePath}" +
                    (if (runner.clips.datasetCount >= 0) " (${runner.clips.datasetCount} ảnh)" else ""),
                runner.collectData) { runner.collectData = it; runner.savePrefs() }
        }

        SectionTitle("Thông tin")
        SettingsCard {
            NavRow("Trạng thái hoạt động", desc = runner.status)
            if (runner.thermalLine.isNotEmpty()) { RowLine(); NavRow("Nhiệt độ", desc = runner.thermalLine) }
            val shareCtx = LocalContext.current
            RowLine(); NavRow("Nhật ký nhiệt", desc = (runner.thermalLog.file?.let { "${runner.thermalLog.rows} dòng (10 s/dòng): ${it.name}" }
                ?: "Ghi mỗi 10 s khi app chạy.") + " Bấm để gửi file qua Zalo, Gmail, Drive...") {
                if (!runner.thermalLog.share(shareCtx)) runner.status = "Chưa có file nhật ký nhiệt"
            }
            RowLine(); NavRow("Lưu tất cả vào Download", desc = "Chép nhật ký nhiệt, nhật ký cảnh báo, ảnh cảnh báo và ảnh huấn luyện vào Download/EVCarHUD " +
                "(thư mục con thermal, alerts, alerts/clips, dataset). File đã chép rồi thì bỏ qua.") { runner.saveAllToDownloads() }
            if (runner.lastExit.isNotEmpty()) { RowLine(); NavRow("Lần thoát trước", desc = runner.lastExit) }
            RowLine(); NavRow("GPS", desc = runner.gps.statusText())
        }
    }
}

// Xem lại các lần cảnh báo: ảnh có HUD lúc cảnh báo + mô tả, đánh dấu Đúng / Nhầm
@Composable
private fun BoxScope.AlertReviewPanel(runner: Runner, land: Boolean, onClose: () -> Unit) {
    var version by remember { mutableStateOf(0) }
    val events by produceState(initialValue = emptyList<AlertClips.Event>(), version) {
        value = withContext(Dispatchers.IO) { runner.clips.list() }
    }
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .then(if (land) Modifier.widthIn(max = 560.dp).fillMaxHeight() else Modifier.fillMaxSize())
            .background(Color(0xF7000000))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            Text("‹", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onClose() }.padding(end = 12.dp))
            Text("Xem lại cảnh báo", color = Color.White, fontSize = 26.sp)
        }
        Desc(if (events.isEmpty()) "Chưa có lần cảnh báo nào được lưu." else "${events.size} lần gần nhất, mới nhất ở trên. Ảnh gốc nằm trong thư mục alerts/clips.", top = 0)
        LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp)) {
            items(events, key = { it.dir.name }) { e ->
                val thumb by produceState<Bitmap?>(initialValue = null, e.dir.name) {
                    value = withContext(Dispatchers.IO) { runner.clips.thumb(e) }
                }
                var verdict by remember(e.dir.name) { mutableStateOf(e.verdict) }
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(12.dp)) {
                    thumb?.let {
                        Image(it.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillWidth)
                    }
                    Text(e.title, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    Desc(e.info, top = 2)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((label, v) in listOf("Đúng" to "dung", "Nhầm" to "nham")) {
                            val sel = verdict == v
                            Box(
                                Modifier.clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) (if (v == "dung") Color(0xFF00C853) else Color(0xFFFF5252)) else Color(0xFF2C2C2E))
                                    .clickable { verdict = v; runner.clips.mark(e, v == "dung") }
                                    .padding(horizontal = 22.dp, vertical = 9.dp)
                            ) { Text(label, color = Color.White, fontSize = 15.sp) }
                        }
                    }
                }
            }
        }
    }
}

// Chỉ đường HERE: tìm điểm đến, chọn kết quả là bắt đầu dẫn đường; xem trạng thái / dừng
@Composable
private fun BoxScope.NavPanel(runner: Runner, land: Boolean, onClose: () -> Unit) {
    val nav = runner.nav
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HereNav.Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    // Chạy thử tuyến: chọn điểm A (xuất phát) rồi điểm B; xe ảo chạy dọc tuyến HERE thật thay GPS
    var simMode by rememberSaveable { mutableStateOf(false) }
    var simName by rememberSaveable { mutableStateOf("") }
    var simLat by rememberSaveable { mutableStateOf(Double.NaN) }
    var simLon by rememberSaveable { mutableStateOf(Double.NaN) }
    val pickA = simMode && simLat.isNaN()
    val scope = rememberCoroutineScope()
    // trạng thái chỉ đường nằm ở luồng nền: làm mới mỗi giây
    val tick by produceState(0) { while (true) { delay(1000); value++ } }
    fun doSearch() {
        if (busy || query.isBlank()) return
        busy = true; msg = "Đang tìm…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { nav.search(query) }
            results = r
            msg = if (r.isEmpty()) nav.note else ""
            busy = false
        }
    }
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .then(if (land) Modifier.widthIn(max = 520.dp).fillMaxHeight() else Modifier.fillMaxSize())
            .background(Color(0xF7000000))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
            Text("‹", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onClose() }.padding(end = 12.dp))
            Text("Chỉ đường", color = Color.White, fontSize = 26.sp)
        }
        if (tick >= 0 && nav.active) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
                Text((if (nav.simulating) "Chạy thử tới " else "Đang dẫn đường tới ") + nav.destName, color = Color.White, fontSize = 17.sp)
                Desc(listOf(nav.remainText, nav.note).filter { it.isNotBlank() }.joinToString(" · "), top = 4)
                Button(
                    onClick = { nav.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text("Dừng chỉ đường") }
            }
        } else if (nav.note.isNotBlank() && results.isEmpty() && msg.isEmpty()) {
            Desc(nav.note, top = 6)
        }
        Column(Modifier.padding(top = 12.dp)) {
            Segmented(listOf("Ô tô", "Xe máy"), runner.navVehicle) { runner.navVehicle = it; runner.savePrefs() }
        }
        Column(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(CardBg)) {
            SwitchRow("Chạy thử tuyến", "Chọn điểm A rồi điểm B: xe ảo chạy dọc tuyến HERE thật (khoảng 40 km/h, theo biển tốc độ nếu có), HUD hiện hướng rẽ, tên đường, giới hạn tốc độ như đang chạy thật.", simMode) {
                simMode = it; results = emptyList(); msg = ""
                if (!it) { simLat = Double.NaN; simLon = Double.NaN; simName = "" }
            }
            if (simMode && !simLat.isNaN()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 10.dp)) {
                    Text("A: $simName", color = Color(0xFF8FD8FF), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("Đổi", color = Color(0xFF0A84FF), fontSize = 15.sp,
                        modifier = Modifier.clickable { simLat = Double.NaN; simLon = Double.NaN; simName = "" }.padding(8.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                label = { Text(if (pickA) "Điểm xuất phát A" else if (simMode) "Điểm đến B" else "Điểm đến (địa chỉ, tên địa điểm)", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { doSearch() }, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) { Text("Tìm") }
        }
        if (msg.isNotEmpty()) Desc(msg, top = 6)
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(results) { p ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(CardBg)
                        .clickable(enabled = !busy) {
                            busy = true; msg = "Đang lấy vị trí ${p.name}…"
                            scope.launch {
                                val ll = withContext(Dispatchers.IO) { nav.place(p.refId) }
                                busy = false
                                if (ll == null) msg = nav.note
                                else if (pickA) {
                                    simLat = ll.first; simLon = ll.second; simName = p.name
                                    results = emptyList(); query = ""; msg = "Đã chọn A. Giờ tìm điểm đến B."
                                } else if (simMode) {
                                    nav.simulate(simLat, simLon, ll.first, ll.second, p.name)
                                    results = emptyList(); msg = ""
                                    onClose()
                                } else {
                                    nav.start(ll.first, ll.second, p.name)
                                    results = emptyList(); msg = ""
                                    onClose()
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(p.name, color = Color.White, fontSize = 16.sp)
                    val extra = (if (p.distKm.isNaN()) "" else String.format(Locale.US, "%.1f km · ", p.distKm).replace('.', ',')) + p.address
                    if (extra.isNotBlank()) Desc(extra, top = 2)
                }
            }
        }
    }
}

// Giữ Runner sống qua lần xoay màn hình (Activity bị tạo lại, video vẫn đang chạy)
internal object RunnerHolder {
    private var runner: Runner? = null
    fun get(ctx: Context): Runner = runner ?: Runner(ctx).also { runner = it }
}

class MainActivity : ComponentActivity() {
    override fun onDestroy() {
        if (isFinishing) RunnerHolder.get(applicationContext).gps.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        RunnerHolder.get(applicationContext).let { it.resumeIfNeeded(); it.gps.start() }   // quay lại từ nền: mở lại camera nếu đã dừng
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            EVCarHUDTheme {
                val ctx = LocalContext.current.applicationContext
                val runner = remember { RunnerHolder.get(ctx) }
                // Chọn video xong thì hỏi xác nhận: Chạy / Huỷ (Huỷ = quay lại như trước, camera chạy lại nếu đang chạy)
                var pendingVideo by rememberSaveable { mutableStateOf<Uri?>(null) }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) pendingVideo = uri else runner.cancelPick() }
                val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                // Đứng yên lâu: hạ độ sáng màn hình (chỉ trong app, không đổi cài đặt máy)
                val dimScreen = runner.dimStill && runner.dimNow && runner.running
                LaunchedEffect(dimScreen) {
                    val a = window.attributes
                    a.screenBrightness = if (dimScreen) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = a
                }

                // Xoay ngang: ẩn thanh hệ thống (vuốt mép để gọi lại), video chiếm toàn màn hình
                SideEffect {
                    val c = WindowInsetsControllerCompat(window, window.decorView)
                    if (land) {
                        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        c.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        c.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                var showSettings by remember { mutableStateOf(false) }
                var showReview by remember { mutableStateOf(false) }
                var showNav by remember { mutableStateOf(false) }
                // Trang 2: bản đồ trạm sạc toàn màn hình (camera và cảnh báo vẫn chạy nền)
                var showMap by rememberSaveable { mutableStateOf(false) }
                var showCalib by remember { mutableStateOf(false) }
                var calibText by remember { mutableStateOf("") }
                var calibMsg by remember { mutableStateOf<String?>(null) }
                val camPerm = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { ok -> if (ok) runner.startCamera() else runner.status = "Chưa cấp quyền camera" }
                // Mặc định: tự mở camera (khoá xoay ngang). Video chỉ để test trong cài đặt.
                val startPerm = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { res ->
                    if (res[Manifest.permission.CAMERA] == true && !runner.cameraRequested) runner.startCamera()
                    if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) runner.gps.start()
                }
                LaunchedEffect(Unit) {
                    if (!runner.cameraRequested && !runner.running) {
                        this@MainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        val needCam = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                        val needLoc = !runner.gps.hasPermission()
                        if (!needCam) runner.startCamera()
                        if (!needLoc) runner.gps.start()
                        val ask = ArrayList<String>()
                        if (needCam) ask.add(Manifest.permission.CAMERA)
                        if (needLoc) ask.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (ask.isNotEmpty()) startPerm.launch(ask.toTypedArray())
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // Khung đúng tỉ lệ ảnh: hình camera (GPU hoặc bitmap) và lớp HUD chồng khít lên nhau.
                        // Phản chiếu kính lái: lật gương cả khung để chữ đọc đúng chiều trên kính
                        Box(
                            Modifier.aspectRatio(runner.frameAspect)
                                .then(if (runner.reflect) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
                        ) {
                            // Camera điện thoại và webcam USB đều vẽ hình qua GPU; key: đổi nguồn thì tạo lại (cách xoay khác nhau)
                            if (runner.gpuPreview && (runner.camKind == CamKind.PHONE || runner.camKind == CamKind.USB))
                                key(runner.camKind) { CameraTexture(runner, Modifier.fillMaxSize(), rotate = runner.camKind == CamKind.PHONE) }
                            // Đang ở trang Bản đồ: không vẽ HUD bên dưới cho đỡ tốn GPU (TextureView camera giữ nguyên, không phải mở lại camera)
                            if (!showMap) {
                                FrameView(runner, Modifier.fillMaxSize(), ContentScale.FillBounds)
                                HudOverlay(runner, Modifier.fillMaxSize())
                            }
                        }
                        if (runner.frame == null && !runner.previewing) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            ) {
                                Text(runner.status, color = Color.White, fontSize = 16.sp)
                                Text(
                                    "Bấm ⚙ để chọn video hoặc chạy camera",
                                    color = Color(0xAAFFFFFF), fontSize = 14.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }

                    // Tạm nghỉ làm mát: che khung hình, nói rõ đang dừng cảnh báo
                    if (runner.coolPause) {
                        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                Text("Máy quá nóng", color = Color(0xFFFF8A65), fontSize = 26.sp)
                                Text("Tạm dừng nhận diện và cảnh báo để làm mát, tự chạy lại khi pin nguội.",
                                    color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                                Text(runner.coolNote, color = Color(0xAAFFFFFF), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }

                    if (showMap) MapPage(runner) { showMap = false }

                    if (showNav) {
                        NavPanel(runner, land) { showNav = false }
                    } else if (showSettings && showReview) {
                        AlertReviewPanel(runner, land) { showReview = false }
                    } else if (showSettings) {
                        SettingsPanel(
                            runner = runner,
                            land = land,
                            onReview = { showReview = true },
                            onNav = { showNav = true },
                            onCalibrate = { calibText = ""; showCalib = true },
                            onPick = { runner.pauseForPick(); picker.launch(arrayOf("video/*")) },
                            onCamera = {
                                if (runner.running) {
                                    runner.stop()
                                    this@MainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                } else {
                                    // Camera cần máy nằm ngang: tự khoá xoay ngang (theo cảm biến) khi chạy
                                    this@MainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    showSettings = false
                                    // Camera Wi‑Fi không cần quyền CAMERA; điện thoại và USB (Android 9+) thì cần
                                    if (runner.camKind == CamKind.WIFI ||
                                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) runner.startCamera()
                                    else camPerm.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                    }

                    // Nút cài đặt (góc phải trên), vẽ sau trang cài đặt để luôn bấm được nút ✕
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(10.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .clickable {
                                if (showNav) showNav = false else showSettings = !showSettings
                                showReview = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (showSettings || showNav) "✕" else "⚙", color = Color.White, fontSize = 22.sp)
                    }
                    // Nút sang trang Bản đồ trạm sạc
                    if (!showSettings && !showNav && !showMap) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 10.dp, end = 114.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0x66000000))
                                .clickable { showMap = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🗺", color = Color.White, fontSize = 20.sp)
                        }
                    }
                    // Nút chỉ đường nhanh (cạnh nút cài đặt)
                    if (!showSettings && !showNav && !showMap) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 10.dp, end = 62.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (runner.nav.active) Color(0x9900C853) else Color(0x66000000))
                                .clickable { showNav = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("➤", color = Color.White, fontSize = 20.sp)
                        }
                    }

                    if (showCalib) {
                        AlertDialog(
                            onDismissRequest = { showCalib = false },
                            title = { Text("Khoảng cách thật tới xe phía trước") },
                            text = {
                                Column {
                                    Text("Dừng sau một xe, đo từ đầu xe mình tới đuôi xe đó (mét).")
                                    OutlinedTextField(
                                        value = calibText, onValueChange = { calibText = it }, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        label = { Text("Ví dụ 5,5") },
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val v = calibText.replace(',', '.').toFloatOrNull()
                                    showCalib = false
                                    calibMsg = if (v == null || v < 1f || v > 60f) "Nhập số từ 1 đến 60 m." else runner.calibrate(v)
                                }) { Text("Chỉnh") }
                            },
                            dismissButton = { TextButton(onClick = { showCalib = false }) { Text("Huỷ") } }
                        )
                    }
                    calibMsg?.let { m ->
                        AlertDialog(
                            onDismissRequest = { calibMsg = null },
                            text = { Text(m) },
                            confirmButton = { TextButton(onClick = { calibMsg = null }) { Text("OK") } }
                        )
                    }

                    pendingVideo?.let { uri ->
                        AlertDialog(
                            onDismissRequest = { pendingVideo = null; runner.cancelPick() },
                            title = { Text("Chạy video test?") },
                            text = { Text(displayName(ctx, uri)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingVideo = null
                                    showSettings = false
                                    runner.playVideo(uri)
                                }) { Text("Chạy") }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingVideo = null; runner.cancelPick() }) { Text("Huỷ") }
                            }
                        )
                    }
                }
            }
        }
    }
}
