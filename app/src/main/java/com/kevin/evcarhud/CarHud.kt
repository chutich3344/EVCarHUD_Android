package com.kevin.evcarhud

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

// Màn hình EVCarHUD trên Android Auto (ô Android Auto của xe): app dẫn đường tự vẽ toàn bộ vùng bản đồ.
// Bố cục: mặc định bản đồ HERE toàn màn (dẫn đường kiểu Waze, tự zoom theo tốc độ). Nút ⚡ / 🔍 / ADAS trên thanh của
// Android Auto mở bảng bên phải (chia đôi có hiệu ứng trượt): trạm sạc -> chọn tuyến -> ĐI, hoặc cột ADAS
// (hướng rẽ + biển, khoảng cách xe trước, camera chỉ có hộp YOLO + đường comma.ai + làn AR).
// Không hiện số tốc độ trên xe (đồng hồ xe đã có), biển giới hạn nháy khi chạy quá.

// Dữ liệu luồng HUD trên điện thoại chuyển sang xe (Runner ghi, luồng vẽ trên xe đọc)
object CarLink {
    @Volatile var active = false           // đang có màn hình xe
        set(v) { field = v; HudRenderer.wantScene = v && adas }
    @Volatile var adas = false             // cột ADAS đang mở: mới chép ảnh camera + lớp cảnh
        set(v) { field = v; HudRenderer.wantScene = v && active; if (!v) { cam = null; scene = null } }
    @Volatile var nav: HereNav? = null
    @Volatile var gps: LocationFallback? = null
    @Volatile var scene: Picture? = null   // lớp cảnh của khung (đường đi, làn AR, hộp YOLO), toạ độ khung rộng 1024
    @Volatile var picH = 576
    @Volatile var cam: Bitmap? = null
    @Volatile var hood = 0.1f
    @Volatile var speed = 0f
    @Volatile var limit = 0
    @Volatile var turn: TurnInfo? = null
    @Volatile var street = ""
    @Volatile var leadM = Float.NaN        // xe phía trước (m), NaN = không có
    @Volatile var leadWhat = ""
    @Volatile var leadGap = 0f             // khoảng cách tối thiểu (cao tốc), 0 = không áp dụng
    @Volatile var leadLevel = 0
    @Volatile var at = 0L                  // lần HUD cập nhật gần nhất (elapsedRealtime)
    private var camAt = 0L

    // Gọi từ luồng hiển thị của Runner sau khi ghi xong Picture của khung
    @Suppress("UNUSED_PARAMETER")
    fun feed(p: Picture, bmp: Bitmap, spd: Float, lim: Int, t: TurnInfo?, st: String, hd: Float) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        picH = bmp.height; speed = spd; limit = lim; turn = t; street = st; hood = hd; at = now
        leadM = HudRenderer.leadM; leadWhat = HudRenderer.leadWhat; leadGap = HudRenderer.leadGap; leadLevel = HudRenderer.leadLevel
        if (!adas) return
        scene = HudRenderer.lastScene
        // Ảnh camera 4 lần/giây, thu nhỏ (bitmap gốc nằm trong vòng đệm, bị ghi đè ở khung sau)
        if (now - camAt >= 250L && bmp.width > 0) {
            camAt = now
            try { cam = Bitmap.createScaledBitmap(bmp, 512, max(1, 512 * bmp.height / bmp.width), true) } catch (_: Throwable) {}
        }
    }
}

// Đọc bài đang phát của app khác (YouTube Music, Spotify...): cần quyền "Truy cập thông báo"
class MediaWatch : NotificationListenerService()

object MediaNow {
    private var ctl: MediaController? = null
    private var ctlAt = 0L

    fun granted(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    // Phiên đang phát (ưu tiên phiên PLAYING), tra lại mỗi giây
    fun current(ctx: Context): MediaController? {
        val now = SystemClock.elapsedRealtime()
        if (now - ctlAt < 1000L) return ctl
        ctlAt = now
        ctl = try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            val list = m.getActiveSessions(ComponentName(ctx, MediaWatch::class.java))
            list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        } catch (_: Throwable) { null }
        return ctl
    }

    fun playing(ctx: Context) = current(ctx)?.playbackState?.state == PlaybackState.STATE_PLAYING
    fun toggle(ctx: Context) { val c = current(ctx) ?: return; if (playing(ctx)) c.transportControls.pause() else c.transportControls.play() }
    fun prev(ctx: Context) { current(ctx)?.transportControls?.skipToPrevious() }
    fun next(ctx: Context) { current(ctx)?.transportControls?.skipToNext() }
}

// Giữ camera + GPS chạy khi màn hình điện thoại tắt (lên xe không cần mở app trên điện thoại)
class HudFgService : Service() {
    companion object {
        // Chỉ gọi lại khi app đang hiện trên màn hình điện thoại: gọi từ nền thì Android hạ loại dịch vụ, mất quyền camera
        fun startIfVisible(ctx: Context) {
            val i = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(i)
            if (i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) start(ctx)
        }
        fun start(ctx: Context) { try { ContextCompat.startForegroundService(ctx, Intent(ctx, HudFgService::class.java)) } catch (_: Throwable) {} }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, HudFgService::class.java)) } catch (_: Throwable) {} }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("car", "EVCarHUD trên xe", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "car").setSmallIcon(R.drawable.car_play)
            .setContentTitle("EVCarHUD đang chạy camera").setOngoing(true).build()
        val type = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try { startForeground(7, n, type) } catch (_: Throwable) {
            try { startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) } catch (_: Throwable) { stopSelf() }
        }
        return START_NOT_STICKY
    }
}

class HudCarService : CarAppService() {
    // Bản thử cài ngoài: nhận mọi host (Android Auto). Phát hành chính thức thì dùng danh sách host của Google
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = HudSession()
}

class HudSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = HudScreen(carContext)
}

class HudScreen(ctx: CarContext) : Screen(ctx) {
    private val surf = HudSurface(ctx)
    private var startedCam = false

    // Mở app trên xe: tự bật camera + AI (không cần mở trên điện thoại); rút kết nối xe thì tắt cho đỡ nóng máy
    private fun autoStart() {
        val app = carContext.applicationContext
        val r = RunnerHolder.get(app)
        if (!r.running) HudFgService.start(app)   // camera đang chạy từ điện thoại thì dịch vụ đã có sẵn
        r.gps.start()
        if (!r.running && ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            r.startCamera(); startedCam = true
        }
    }
    private fun autoStop() {
        val app = carContext.applicationContext
        if (startedCam) { RunnerHolder.get(app).stop(); startedCam = false }
        if (!RunnerHolder.get(app).running) HudFgService.stop(app)
    }

    init {
        autoStart()
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(surf)
        // bấm ĐI trên bảng chọn tuyến (luồng vẽ): đổi nút trên thanh (✕ hủy dẫn đường)
        CarUi.onChange = { ctx.mainExecutor.execute { invalidate() } }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { surf.start() }
            override fun onResume(owner: LifecycleOwner) { invalidate() }   // quay về từ màn tìm: đổi nút Hủy
            override fun onStop(owner: LifecycleOwner) { surf.stop() }
            override fun onDestroy(owner: LifecycleOwner) { CarUi.onChange = null; CarUi.close(); surf.quit(); autoStop() }
        })
    }

    override fun onGetTemplate(): Template {
        val ctx = carContext
        val nav = CarLink.nav
        // Chạm lên bảng tự vẽ cần Car API 5 (SurfaceCallback.onClick); xe cũ hơn thì dùng danh sách của Android Auto
        val tap = ctx.carAppApiLevel >= 5
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_search)).setOnClickListener { screenManager.push(SearchScreen(ctx)) }.build())
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_bolt))
                .setOnClickListener { if (tap) CarUi.toggle(CarUi.CHG) else screenManager.push(ChargerScreen(ctx)) }.build())
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_adas)).setOnClickListener { CarUi.toggle(CarUi.ADAS) }.build())
            .apply {
                if (nav?.active == true) addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_close))
                    .setOnClickListener { nav.stop(); invalidate() }.build())
                else addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_traffic))
                    .setOnClickListener { MapCtl.traffic = !MapCtl.traffic; CarToast.show(ctx, if (MapCtl.traffic) "Bật lớp giao thông" else "Tắt lớp giao thông") }.build())
            }
            .build()
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_plus)).setOnClickListener { MapCtl.zoomBy(1) }.build())
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_minus)).setOnClickListener { MapCtl.zoomBy(-1) }.build())
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_locate)).setOnClickListener { MapCtl.recenter() }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).setMapActionStrip(mapStrip).build()
    }
}

private fun carIcon(ctx: CarContext, res: Int) = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

private object CarToast {
    fun show(ctx: CarContext, t: String) { try { androidx.car.app.CarToast.makeText(ctx, t, androidx.car.app.CarToast.LENGTH_SHORT).show() } catch (_: Throwable) {} }
}

private val carExec: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

private fun kmText(km: Double) = if (km < 1.0) "${(km * 1000).roundToInt()} m" else String.format(Locale.US, "%.1f km", km)

// Trạng thái bản đồ trên xe: zoom, kéo, xoay theo hướng xe, lớp giao thông, các phương án tuyến đang xem trước
object MapCtl {
    @Volatile var zoom = 16                 // mức ô khi kéo bản đồ (bắc lên trên)
    @Volatile var zf = 1.0                  // khi bám xe: hệ số zoom tay (+ / −) nhân với zoom tự động theo tốc độ
    @Volatile var follow = true             // bám theo xe, xoay theo hướng đi
    @Volatile var cx = Double.NaN           // tâm khi đang kéo (toạ độ ô ở zoom hiện tại)
    @Volatile var cy = Double.NaN
    @Volatile var traffic = false
    @Volatile var preview: List<HereNav.RouteOpt> = emptyList()
    @Volatile var sel = 0
    fun zoomBy(d: Int) {
        if (follow && preview.isEmpty()) { zf = (zf * if (d > 0) 2.0 else 0.5).coerceIn(0.25, 4.0); return }
        val nz = (zoom + d).coerceIn(10, 18)
        if (!cx.isNaN()) { val f = Math.pow(2.0, (nz - zoom).toDouble()); cx *= f; cy *= f }
        zoom = nz
    }
    fun recenter() { follow = true; zf = 1.0; cx = Double.NaN; cy = Double.NaN }
}

// Bảng bên phải trên màn xe. Nút ⚡ / ADAS trên thanh bật tắt; chọn trạm / tìm điểm đến -> xem tuyến -> ĐI
object CarUi {
    const val NONE = 0
    const val CHG = 1
    const val ROUTE = 2
    const val ADAS = 3
    @Volatile var mode = NONE
        set(v) { field = v; CarLink.adas = v == ADAS }
    @Volatile var destLat = 0.0
    @Volatile var destLon = 0.0
    @Volatile var destName = ""
    @Volatile var opts: List<HereNav.RouteOpt>? = null   // null = đang tính tuyến
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var req = 0

    fun toggle(m: Int) { if (mode == m) close() else { MapCtl.preview = emptyList(); mode = m } }
    fun close() { MapCtl.preview = emptyList(); mode = NONE }

    // Xem 2-3 tuyến HERE tới điểm đã chọn (1 lượt gọi): bản đồ vẽ các tuyến nhìn từ trên, bảng liệt kê để chọn
    fun routeTo(lat: Double, lon: Double, name: String) {
        destLat = lat; destLon = lon; destName = name
        MapCtl.sel = 0; MapCtl.preview = emptyList(); opts = null; mode = ROUTE
        val id = req + 1; req = id
        val nav = CarLink.nav; val from = CarLink.gps?.anyLatLon()
        if (nav == null || from == null) { opts = emptyList(); return }
        carExec.execute {
            val r = nav.previewRoutes(from.first, from.second, lat, lon)
            if (id == req && mode == ROUTE) { opts = r; MapCtl.preview = r }
        }
    }

    fun go(i: Int) {
        val o = opts ?: return
        if (i !in o.indices) return
        MapCtl.sel = i
        CarLink.nav?.startOpt(i, destLat, destLon, destName)
        MapCtl.preview = emptyList(); MapCtl.recenter(); mode = NONE
        onChange?.invoke()
    }
}

// Tìm điểm đến: gõ (khi xe dừng) hoặc nói; mỗi lần tìm là 1 lượt HERE Autosuggest
class SearchScreen(ctx: CarContext) : Screen(ctx) {
    private var items: List<HereNav.Place> = emptyList()
    private var loading = false
    private var lastQ = ""
    private val main = Handler(android.os.Looper.getMainLooper())
    private val debounce = Runnable { run(lastQ) }

    private fun run(q: String) {
        val nav = CarLink.nav ?: return
        if (q.isBlank()) return
        loading = true; invalidate()
        carExec.execute {
            val r = nav.search(q)
            main.post { if (q == lastQ) { items = r; loading = false; invalidate() } }
        }
    }

    override fun onGetTemplate(): Template {
        val cb = object : androidx.car.app.model.SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                lastQ = searchText; main.removeCallbacks(debounce)
                if (searchText.trim().length >= 3) main.postDelayed(debounce, 700L)
            }
            override fun onSearchSubmitted(searchText: String) { lastQ = searchText; main.removeCallbacks(debounce); run(searchText) }
        }
        val b = androidx.car.app.model.SearchTemplate.Builder(cb)
            .setHeaderAction(Action.BACK).setSearchHint("Tìm điểm đến").setShowKeyboardByDefault(true).setInitialSearchText(lastQ)
        if (loading) return b.setLoading(true).build()
        val list = androidx.car.app.model.ItemList.Builder()
        if (items.isEmpty()) list.setNoItemsMessage(if (lastQ.isBlank()) "Nói hoặc gõ tên điểm đến" else (CarLink.nav?.note?.ifBlank { null } ?: "Không có kết quả"))
        for (p in items.take(6)) {
            val row = androidx.car.app.model.Row.Builder().setTitle(p.name.ifBlank { p.address })
            val sub = listOfNotNull(p.distKm.takeIf { !it.isNaN() }?.let { kmText(it) }, p.address.ifBlank { null }).joinToString(" · ")
            if (sub.isNotEmpty()) row.addText(sub)
            row.setOnClickListener {
                val c = CarLink.nav?.place(p.refId) ?: return@setOnClickListener
                val nm = p.name.ifBlank { p.address }
                // Car API 5+: về bản đồ, chọn tuyến ở bảng bên phải; xe cũ: danh sách tuyến của Android Auto
                if (carContext.carAppApiLevel >= 5) { CarUi.routeTo(c.first, c.second, nm); screenManager.popToRoot() }
                else screenManager.push(RouteScreen(carContext, c.first, c.second, nm))
            }
            list.addItem(row.build())
        }
        return b.setItemList(list.build()).build()
    }
}

// Trạm sạc VinFast gần nhất (dữ liệu trong app, không tốn lượt HERE); chọn trạm thì xem tuyến tới đó
class ChargerScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val here = CarLink.gps?.anyLatLon()
        val list = androidx.car.app.model.ItemList.Builder()
        if (here == null) list.setNoItemsMessage("Đang chờ vị trí GPS…")
        else {
            val near = Chargers.load(carContext).map { it to Chargers.distKm(here.first, here.second, it.lat, it.lon) }
                .sortedBy { it.second }.take(10)
            if (near.isEmpty()) list.setNoItemsMessage("Chưa có dữ liệu trạm sạc")
            for ((st, d) in near) {
                list.addItem(androidx.car.app.model.Row.Builder().setTitle(st.name)
                    .addText("${kmText(d)} · ${st.maxKw} kW" + if (st.ports.isNotBlank()) " · ${st.ports}" else "")
                    .setOnClickListener { screenManager.push(RouteScreen(carContext, st.lat, st.lon, st.name)) }.build())
            }
        }
        return androidx.car.app.model.ListTemplate.Builder().setTitle("Trạm sạc gần nhất").setHeaderAction(Action.BACK)
            .setSingleList(list.build()).build()
    }
}

// Xem 2-3 tuyến HERE tới điểm đã chọn (1 lượt gọi), bản đồ bên trái vẽ các tuyến, bấm "Đi" để dẫn đường
class RouteScreen(ctx: CarContext, private val lat: Double, private val lon: Double, private val name: String) : Screen(ctx) {
    private var opts: List<HereNav.RouteOpt>? = null
    private val main = Handler(android.os.Looper.getMainLooper())

    init {
        MapCtl.sel = 0
        val nav = CarLink.nav; val from = CarLink.gps?.anyLatLon()
        if (nav != null && from != null) carExec.execute {
            val r = nav.previewRoutes(from.first, from.second, lat, lon)
            main.post { opts = r; MapCtl.preview = r; invalidate() }
        } else opts = emptyList()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { MapCtl.preview = emptyList() }
        })
    }

    override fun onGetTemplate(): Template {
        val b = androidx.car.app.navigation.model.RoutePreviewNavigationTemplate.Builder()
            .setTitle(name).setHeaderAction(Action.BACK)
        val o = opts ?: return b.setLoading(true).build()
        val list = androidx.car.app.model.ItemList.Builder()
        if (o.isEmpty()) return androidx.car.app.model.MessageTemplate.Builder(CarLink.nav?.note?.ifBlank { null } ?: "Không tìm được đường")
            .setTitle(name).setHeaderAction(Action.BACK).build()
        for ((i, r) in o.withIndex()) {
            val min = (r.totalS / 60).roundToInt()
            val delay = (r.delayS / 60).roundToInt()
            list.addItem(androidx.car.app.model.Row.Builder().setTitle("$min phút · ${kmText(r.totalM / 1000)}")
                .addText(if (delay >= 2) "Kẹt xe thêm $delay phút" else if (i == 0) "Nhanh nhất" else "Tuyến khác").build())
        }
        list.setSelectedIndex(MapCtl.sel).setOnSelectedListener { MapCtl.sel = it }
        return b.setItemList(list.build())
            .setNavigateAction(Action.Builder().setTitle("Đi").setOnClickListener {
                CarLink.nav?.startOpt(MapCtl.sel, lat, lon, name)
                MapCtl.preview = emptyList(); MapCtl.recenter()
                screenManager.popToRoot()
            }.build())
            .build()
    }
}

// Vẽ lên Surface của xe ~10 khung/giây trên luồng riêng (30 khung/giây khi đang trượt bảng)
class HudSurface(private val ctx: CarContext) : SurfaceCallback {
    private val th = HandlerThread("car-hud").apply { start() }
    private val hd = Handler(th.looper)
    private var surface: Surface? = null
    private val area = Rect()
    private var running = false
    private var split = 0f          // 0 = bản đồ toàn màn, 0.5 = chia đôi
    private var shown = CarUi.NONE  // bảng đang vẽ (giữ khi đang trượt ra)
    private var lastT = 0L
    private var dt = 0.1f
    private var sideDx = 0f         // bảng đang trượt: lệch so với chỗ đứng yên (để chạm đúng chỗ)

    private fun target() = if (CarUi.mode != CarUi.NONE) 0.5f else 0f

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            render()
            // nhịp vẽ: đang trượt bảng 30 khung/giây, xe chạy 15, đứng yên 5 (đỡ nóng máy)
            val delay = when {
                abs(split - target()) > 0.001f -> 33L
                speedNow() > 3f || CarLink.adas -> 66L
                else -> 200L
            }
            hd.postDelayed(this, delay)
        }
    }

    fun start() { CarLink.active = true; hd.post { if (!running) { running = true; hd.post(loop) } } }
    fun stop() { CarLink.active = false; hd.post { running = false; hd.removeCallbacks(loop) } }
    fun quit() { stop(); th.quitSafely() }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val s = surfaceContainer.surface
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        hd.post { surface = s; if (area.isEmpty) area.set(0, 0, w, h) }
    }
    override fun onVisibleAreaChanged(visibleArea: Rect) { val r = Rect(visibleArea); hd.post { area.set(r) } }
    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) { hd.post { surface = null } }
    // Kéo bản đồ (nút ✥ của Android Auto) / chụm hai ngón để zoom
    override fun onScroll(distanceX: Float, distanceY: Float) {
        hd.post {
            if (MapCtl.cx.isNaN()) { MapCtl.cx = lastCx; MapCtl.cy = lastCy }
            MapCtl.follow = false
            MapCtl.cx += distanceX / lastTs; MapCtl.cy += distanceY / lastTs
        }
    }
    private var scaleAcc = 1f
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        hd.post {
            scaleAcc *= scaleFactor
            if (scaleAcc > 1.6f) { MapCtl.zoomBy(1); scaleAcc = 1f } else if (scaleAcc < 0.62f) { MapCtl.zoomBy(-1); scaleAcc = 1f }
        }
    }
    // Chạm lên bảng bên phải: chọn trạm, chọn tuyến, ĐI, đóng
    override fun onClick(x: Float, y: Float) {
        hd.post {
            val m = CarUi.mode
            if (m == CarUi.NONE) return@post
            val sx = x - sideDx
            if (closeR.contains(sx, y)) { CarUi.close(); return@post }
            if (m == CarUi.CHG) {
                for (i in rowR.indices) if (rowR[i].contains(sx, y)) { chgRows.getOrNull(i)?.let { CarUi.routeTo(it.lat, it.lon, it.name) }; break }
            } else if (m == CarUi.ROUTE) {
                for (i in goR.indices) if (goR[i].contains(sx, y)) { CarUi.go(i); return@post }
                for (i in rowR.indices) if (rowR[i].contains(sx, y)) { MapCtl.sel = i; break }
            }
        }
    }

    // ---- bút vẽ ----
    private val bg = 0xFF0B0E12.toInt()
    private val bgP = Paint().apply { color = 0xFF0B0E12.toInt() }
    private val cardP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161B22.toInt() }
    private val blackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0000000.toInt() }
    private val selP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C2C3E.toInt() }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EDF2.toInt() }
    private val mutP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B96A3.toInt() }
    private val boldP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val signW = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val signR = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD62020.toInt(); style = Paint.Style.STROKE }
    private val signT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val routeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3AD6C4.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val egoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BFF.toInt() }
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553D8BFF }
    private val goP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
    private val accP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BFF.toInt() }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val tmp = RectF()

    // vùng chạm của bảng (toạ độ khi bảng đứng yên)
    private val closeR = RectF()
    private val rowR = ArrayList<RectF>()
    private val goR = ArrayList<RectF>()
    private var chgRows: List<Chargers.St> = emptyList()
    private var chgAt = 0L

    private fun render() {
        val s = surface ?: return
        if (!s.isValid || area.isEmpty) return
        val c = try { s.lockHardwareCanvas() } catch (_: Throwable) { return }
        try { draw(c) } catch (_: Throwable) {} finally { try { s.unlockCanvasAndPost(c) } catch (_: Throwable) {} }
    }

    private fun draw(c: Canvas) {
        c.drawColor(bg)
        val a = RectF(area)
        val k = a.height() / 560f
        val now = SystemClock.elapsedRealtime()
        dt = if (lastT == 0L) 0.1f else ((now - lastT) / 1000f).coerceIn(0f, 0.5f)
        lastT = now
        // trượt bảng ~0,35 s, nhanh dần rồi chậm dần
        val tg = target()
        val step = dt / 0.35f * 0.5f
        split = if (split < tg) min(tg, split + step) else max(tg, split - step)
        if (CarUi.mode != CarUi.NONE) shown = CarUi.mode
        val e = split / 0.5f
        val f = 0.5f * e * e * (3f - 2f * e)
        val full = a.width() * 0.5f
        val sideW = a.width() * f
        val open = f > 0.01f
        drawMap(c, RectF(a.left, a.top, a.right - sideW, a.bottom), k, open)
        if (sideW > 1f) {
            sideDx = full - sideW
            c.save()
            c.clipRect(a.right - sideW, a.top, a.right, a.bottom)
            c.translate(sideDx, 0f)
            val sr = RectF(a.right - full, a.top, a.right, a.bottom)
            c.drawRect(sr, bgP)
            when (shown) {
                CarUi.CHG -> drawChargers(c, sr, k)
                CarUi.ROUTE -> drawRoutes(c, sr, k)
                CarUi.ADAS -> drawAdas(c, sr, k)
            }
            c.restore()
        } else { shown = CarUi.NONE; closeR.setEmpty(); rowR.clear(); goR.clear() }
    }

    private fun speedNow() = if (fresh()) CarLink.speed else (CarLink.gps?.speedKmh() ?: 0f)

    // ---- bản đồ HERE (ô explore.night) ----
    // Bám xe: hướng đi lên trên, xe ở 80% chiều cao, tự zoom theo tốc độ (dưới 5 km/h thấy ~120 m phía trước,
    // chạy nhanh thấy 150 + 3·km/h m, tối đa 500 m), đổi mượt. Xem tuyến: bắc lên trên, vừa khung mọi tuyến + nhãn phút.
    private var lastCx = Double.NaN; private var lastCy = Double.NaN; private var lastTs = 300f
    private var headDeg = 0f
    private var mpp = Double.NaN     // mét / pixel đang dùng khi bám xe (đã làm mượt)
    private var sLat = Double.NaN
    private var sLon = Double.NaN
    private val altP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B5D8A.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val chgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FD18B.toInt() }
    private val chgRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F141A.toInt(); style = Paint.Style.STROKE }
    private val destP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14B8A6.toInt() }

    private fun drawMap(c: Canvas, r: RectF, k: Float, sideOpen: Boolean) {
        c.save()
        c.clipRect(r)
        c.drawColor(0xFF0F141A.toInt())
        val nav = CarLink.nav
        val ego = nav?.egoOnRoute()
        val raw = if (ego != null) Pair(ego.first, ego.second) else CarLink.gps?.anyLatLon()
        if (nav == null || raw == null) {
            mutP.textSize = 22f * k
            c.drawText("Đang chờ vị trí GPS…", r.left + 20f * k, r.centerY(), mutP)
            c.restore(); return
        }
        // vị trí xe làm mượt giữa các lần GPS / khung AI cập nhật (nhảy xa > 300 m thì đặt lại)
        if (sLat.isNaN() || abs(raw.first - sLat) > 0.003 || abs(raw.second - sLon) > 0.003) { sLat = raw.first; sLon = raw.second }
        else { val f = min(1.0, dt * 5.0); sLat += (raw.first - sLat) * f; sLon += (raw.second - sLon) * f }
        val ll = Pair(sLat, sLon)
        val pv = MapCtl.preview
        val follow = MapCtl.follow && pv.isEmpty()
        val ts0 = 300f * k
        var z = MapCtl.zoom
        var ts = ts0
        if (pv.isNotEmpty()) {
            // xem trước tuyến: mức zoom vừa khung
            var mnLa = 90.0; var mxLa = -90.0; var mnLo = 180.0; var mxLo = -180.0
            for (o in pv) for (i in o.lat.indices) { mnLa = min(mnLa, o.lat[i]); mxLa = max(mxLa, o.lat[i]); mnLo = min(mnLo, o.lon[i]); mxLo = max(mxLo, o.lon[i]) }
            val z0 = 16
            val n0 = (1 shl z0).toDouble()
            val spanX = (mxLo - mnLo) / 360.0 * n0 * ts0
            val spanY = (ty(mnLa, n0) - ty(mxLa, n0)) * ts0
            var zz = z0
            var fz = max(spanX / (r.width() * 0.8), spanY / (r.height() * 0.75))
            while (fz > 1.0 && zz > 6) { zz--; fz /= 2.0 }
            z = zz
        } else if (follow) {
            val spd = speedNow()
            val ahead = if (spd < 5f) 120.0 else min(500.0, 150.0 + spd * 3.0)
            val want = ahead / (r.height() * 0.8) / MapCtl.zf
            mpp = if (mpp.isNaN()) want else mpp + (want - mpp) * min(1.0, dt * 1.5)
            val worldM = 40075016.686 * cos(Math.toRadians(ll.first))
            z = (ln(worldM / (mpp * ts0)) / ln(2.0)).roundToInt().coerceIn(10, 18)
            ts = (worldM / (1 shl z) / mpp).toFloat()
            MapCtl.zoom = z
        }
        val n = (1 shl z).toDouble()
        // tâm bản đồ (toạ độ ô)
        val ex = tx(ll.second, n); val ey = ty(ll.first, n)
        val x0: Double; val y0: Double
        if (pv.isNotEmpty()) {
            var mnX = Double.MAX_VALUE; var mxX = -Double.MAX_VALUE; var mnY = Double.MAX_VALUE; var mxY = -Double.MAX_VALUE
            for (o in pv) for (i in o.lat.indices) {
                val qx = tx(o.lon[i], n); val qy = ty(o.lat[i], n)
                mnX = min(mnX, qx); mxX = max(mxX, qx); mnY = min(mnY, qy); mxY = max(mxY, qy)
            }
            x0 = (mnX + mxX) / 2.0; y0 = (mnY + mxY) / 2.0
        } else if (!follow && !MapCtl.cx.isNaN()) { x0 = MapCtl.cx; y0 = MapCtl.cy } else { x0 = ex; y0 = ey }
        lastCx = x0; lastCy = y0; lastTs = ts
        // hướng: theo tuyến khi đang dẫn đường, không thì theo GPS; kéo bản đồ / xem tuyến thì bắc lên trên
        if (follow) {
            val h = ego?.third?.let { Math.toDegrees(it).toFloat() } ?: CarLink.gps?.bearingDeg()
            if (h != null) { var d = h - headDeg; while (d > 180f) d -= 360f; while (d < -180f) d += 360f; headDeg += d * min(1f, dt * 3f) }
        }
        val rot = if (follow) -headDeg else 0f
        val cx = r.centerX(); val cy = if (follow) r.top + r.height() * 0.8f else r.centerY()
        val rr = Math.hypot(r.width().toDouble(), r.height().toDouble()).toFloat()
        c.save()
        c.rotate(rot, cx, cy)
        val ix0 = floor(x0 - rr / ts).toInt(); val ix1 = floor(x0 + rr / ts).toInt()
        val iy0 = floor(y0 - rr / ts).toInt(); val iy1 = floor(y0 + rr / ts).toInt()
        for (ix in ix0..ix1) for (iy in iy0..iy1) {
            val l = cx + ((ix - x0) * ts).toFloat(); val t = cy + ((iy - y0) * ts).toFloat()
            tmp.set(l, t, l + ts, t + ts)
            nav.mapTile(z, ix, iy, false)?.let { c.drawBitmap(it, null, tmp, bmpP) }
            if (MapCtl.traffic) nav.trafficTile(z, ix, iy)?.let { c.drawBitmap(it, null, tmp, bmpP) }
        }
        c.restore()
        val rad = Math.toRadians(rot.toDouble()); val cr = cos(rad).toFloat(); val sr = sin(rad).toFloat()
        // toạ độ màn hình (đã xoay) của một điểm
        fun px(lat: Double, lon: Double, out: FloatArray) {
            val dx = ((tx(lon, n) - x0) * ts).toFloat(); val dy = ((ty(lat, n) - y0) * ts).toFloat()
            out[0] = cx + dx * cr - dy * sr; out[1] = cy + dx * sr + dy * cr
        }
        val pt = FloatArray(2)
        fun line(la: DoubleArray, lo: DoubleArray, p: Paint) {
            path.reset()
            for (i in la.indices) { px(la[i], lo[i], pt); if (i == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1]) }
            c.drawPath(path, p)
        }
        // trạm sạc trong khung
        if (z >= 12) for (st in Chargers.load(ctx)) {
            px(st.lat, st.lon, pt)
            if (!r.contains(pt[0], pt[1])) continue
            chgRim.strokeWidth = 2f * k
            c.drawCircle(pt[0], pt[1], 8f * k, chgP); c.drawCircle(pt[0], pt[1], 8f * k, chgRim)
        }
        // phương án tuyến (xem trước, kèm nhãn số phút) hoặc tuyến đang đi
        if (pv.isNotEmpty()) {
            altP.strokeWidth = 7f * k; routeP.strokeWidth = 8f * k
            for ((i, o) in pv.withIndex()) if (i != MapCtl.sel) line(o.lat, o.lon, altP)
            pv.getOrNull(MapCtl.sel)?.let { o ->
                line(o.lat, o.lon, routeP)
                px(o.lat.last(), o.lon.last(), pt); c.drawCircle(pt[0], pt[1], 11f * k, destP)
            }
            for ((i, o) in pv.withIndex()) {
                if (o.lat.isEmpty()) continue
                val j = ((o.lat.size - 1) * (0.4f + 0.15f * i)).toInt().coerceIn(0, o.lat.size - 1)
                px(o.lat[j], o.lon[j], pt)
                bubble(c, pt[0], pt[1], "${max(1, (o.totalS / 60).roundToInt())} phút", i == MapCtl.sel, k)
            }
        } else {
            val la = nav.routeLat(); val lo = nav.routeLon()
            if (la != null && lo != null && la.size > 1) {
                routeP.strokeWidth = 9f * k
                line(la, lo, routeP)
                px(la.last(), lo.last(), pt); c.drawCircle(pt[0], pt[1], 11f * k, destP)
            }
        }
        // xe
        px(ll.first, ll.second, pt)
        val head = if (follow) 0f else (ego?.third?.let { Math.toDegrees(it).toFloat() } ?: CarLink.gps?.bearingDeg() ?: 0f)
        c.drawCircle(pt[0], pt[1], 26f * k, haloP)
        c.save()
        c.rotate(head, pt[0], pt[1])
        val ax = pt[0]; val ay = pt[1]
        path.reset()
        path.moveTo(ax, ay - 17f * k); path.lineTo(ax + 13f * k, ay + 13f * k); path.lineTo(ax, ay + 5f * k); path.lineTo(ax - 13f * k, ay + 13f * k); path.close()
        c.drawPath(path, egoP)
        c.restore()

        // ---- thẻ trên bản đồ ----
        val g = 10f * k
        val navOn = nav.active && pv.isEmpty()
        var etaBottom = false
        if (navOn) {
            // ADAS mở: hướng rẽ đã ở cột phải -> thẻ giờ đến lên góc trái trên
            if (!sideOpen) turnCard(c, r.left + g, r.top + g, min(300f * k, r.width() - 84f * k), k)
            etaCard(c, r.left + g, if (sideOpen) r.top + g else -1f, r.bottom - g, k)
            etaBottom = !sideOpen
        }
        // biển giới hạn (ADAS mở thì biển ở cột phải)
        if (!sideOpen && pv.isEmpty() && fresh() && CarLink.limit > 0) {
            val rs = 34f * k
            val by = if (etaBottom) r.bottom - 84f * k - rs else r.bottom - g - rs
            limitSign(c, r.left + g + rs, by, rs, CarLink.limit, speedNow() > CarLink.limit + 3f)
        }
        // tên đường đang đi: viên giữa đáy
        val stName = CarLink.street.takeIf { fresh() }.orEmpty()
        if (stName.isNotEmpty() && pv.isEmpty()) {
            textP.textSize = 17f * k
            val left = if (etaBottom) r.left + 230f * k else r.left + g
            val maxW = r.right - left - 70f * k
            if (maxW > 60f * k) {
                val t = clip(stName, maxW - 32f * k, textP)
                val w = textP.measureText(t) + 32f * k
                val mx = min(max(r.centerX(), left + w / 2f), r.right - 70f * k - w / 2f)
                tmp.set(mx - w / 2f, r.bottom - g - 36f * k, mx + w / 2f, r.bottom - g)
                c.drawRoundRect(tmp, 18f * k, 18f * k, blackP)
                c.drawText(t, tmp.left + 16f * k, tmp.bottom - 12f * k, textP)
            }
        }
        if (MapCtl.traffic && !navOn) { mutP.textSize = 13f * k; c.drawText("Giao thông HERE", r.left + 12f * k, r.top + 22f * k, mutP) }
        // ghi công dữ liệu bản đồ (giấy phép ODbL)
        mutP.textSize = 11f * k; c.drawText(OsmTiles.ATTRIB, r.right - mutP.measureText(OsmTiles.ATTRIB) - 8f * k, r.bottom - 6f * k, mutP)
        c.restore()
    }
    private fun tx(lon: Double, n: Double) = (lon + 180.0) / 360.0 * n
    private fun ty(lat: Double, n: Double): Double { val p = Math.toRadians(lat); return (1.0 - ln(tan(p) + 1.0 / cos(p)) / PI) / 2.0 * n }

    // nhãn số phút trên tuyến (kiểu Apple Maps)
    private fun bubble(c: Canvas, x: Float, y: Float, t: String, sel: Boolean, k: Float) {
        boldP.textSize = 16f * k; boldP.color = 0xFFFFFFFF.toInt()
        val w = boldP.measureText(t) + 20f * k
        tmp.set(x - w / 2f, y - 34f * k, x + w / 2f, y - 8f * k)
        c.drawRoundRect(tmp, 8f * k, 8f * k, if (sel) accP else cardP)
        c.drawText(t, tmp.left + 10f * k, tmp.bottom - 8f * k, boldP)
    }

    // thẻ đen hướng rẽ: mũi tên, khoảng cách, đường sắp vào
    private fun turnCard(c: Canvas, x: Float, y: Float, w: Float, k: Float) {
        val t = CarLink.turn?.takeIf { fresh() && it.turn > 0 }
        tmp.set(x, y, x + w, y + 96f * k)
        c.drawRoundRect(tmp, 14f * k, 14f * k, blackP)
        if (t == null) {
            textP.textSize = 18f * k
            c.drawText(clip(CarLink.nav?.note?.ifBlank { null } ?: "Đang tính đường…", w - 32f * k, textP), x + 16f * k, y + 54f * k, textP)
            return
        }
        boldP.color = 0xFFFFFFFF.toInt(); boldP.textSize = 50f * k
        c.drawText(arrow(t.turn), x + 16f * k, y + 64f * k, boldP)
        val tx = x + 82f * k
        boldP.textSize = 32f * k
        c.drawText(dist(t.distanceM), tx, y + 44f * k, boldP)
        textP.textSize = 17f * k
        c.drawText(clip(t.nextStreet.ifBlank { TurnGuide.name(t.turn) }, x + w - tx - 12f * k, textP), tx, y + 74f * k, textP)
    }

    // thẻ đen giờ đến / phút / km. top < 0: đặt theo đáy
    private fun etaCard(c: Canvas, x: Float, top: Float, bottom: Float, k: Float) {
        val nav = CarLink.nav ?: return
        val sec = nav.remainSec; val m = nav.remainM
        val arr = if (sec >= 0) String.format(Locale.US, "%tH:%<tM", System.currentTimeMillis() + (sec * 1000).toLong()) else "--:--"
        val mins = if (sec >= 0) max(1, (sec / 60).roundToInt()).toString() else "--"
        val km = if (m >= 0) (if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)) else "--"
        val cols = listOf(arr to "đến nơi", mins to "phút", km to "")
        boldP.textSize = 22f * k; boldP.color = 0xFFFFFFFF.toInt()
        mutP.textSize = 13f * k
        val cw = cols.map { max(boldP.measureText(it.first), mutP.measureText(it.second)) + 18f * k }
        val w = cw.sum() + 12f * k
        val h = 58f * k
        val t = if (top >= 0f) top else bottom - h
        tmp.set(x, t, x + w, t + h)
        c.drawRoundRect(tmp, 14f * k, 14f * k, blackP)
        var cx = x + 6f * k
        for ((i, col) in cols.withIndex()) {
            val mid = cx + cw[i] / 2f
            c.drawText(col.first, mid - boldP.measureText(col.first) / 2f, t + 28f * k, boldP)
            c.drawText(col.second, mid - mutP.measureText(col.second) / 2f, t + 48f * k, mutP)
            cx += cw[i]
        }
    }

    // biển giới hạn tốc độ (viền đỏ); chạy quá thì nháy
    private fun limitSign(c: Canvas, x: Float, y: Float, rs: Float, lim: Int, over: Boolean) {
        val blink = over && (SystemClock.elapsedRealtime() / 300L) % 2L == 0L
        signW.color = if (blink) 0xFFFF4D4D.toInt() else 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, rs, signW)
        signR.strokeWidth = rs * 0.22f
        c.drawCircle(x, y, rs * 0.89f, signR)
        signT.textSize = rs * (if (lim >= 100) 0.72f else 0.85f)
        c.drawText(lim.toString(), x, y + rs * 0.3f, signT)
    }

    private fun fresh() = SystemClock.elapsedRealtime() - CarLink.at < 2000L

    private fun arrow(code: Int) = when (code) {
        1 -> "↑"; 2, 6 -> "←"; 3, 7 -> "→"; 4, 13, 15 -> "↖"; 5, 14, 16 -> "↗"; 8, 20 -> "↶"; 10, 11, 12, 19 -> "⟳"; 17 -> "⚑"; else -> "↑"
    }
    private fun dist(m: Int) = if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000f) else "$m m"

    private fun clip(s: String, w: Float, p: Paint = mutP): String {
        if (w <= 0f) return ""
        val n = p.breakText(s, true, w, null)
        return if (n < s.length) s.substring(0, max(0, n - 1)) + "…" else s
    }

    // tiêu đề bảng + nút ✕
    private fun header(c: Canvas, r: RectF, title: String, k: Float): Float {
        val g = 10f * k
        closeR.set(r.right - g - 44f * k, r.top + g, r.right - g, r.top + g + 44f * k)
        c.drawRoundRect(closeR, 22f * k, 22f * k, cardP)
        boldP.color = 0xFFFFFFFF.toInt(); boldP.textSize = 20f * k
        c.drawText("✕", closeR.centerX() - boldP.measureText("✕") / 2f, closeR.centerY() + 7f * k, boldP)
        boldP.textSize = 21f * k
        c.drawText(clip(title, closeR.left - r.left - 2 * g, boldP), r.left + g + 4f * k, r.top + g + 30f * k, boldP)
        rowR.clear(); goR.clear()
        return r.top + g + 54f * k
    }

    // ---- bảng trạm sạc VinFast gần nhất (dữ liệu trong app, không tốn lượt HERE) ----
    private fun drawChargers(c: Canvas, r: RectF, k: Float) {
        var y = header(c, r, "Trạm sạc gần nhất", k)
        val here = CarLink.gps?.anyLatLon()
        val now = SystemClock.elapsedRealtime()
        if (here != null && (chgRows.isEmpty() || now - chgAt > 15_000L)) {
            chgAt = now
            chgRows = Chargers.load(ctx).map { it to Chargers.distKm(here.first, here.second, it.lat, it.lon) }
                .sortedBy { it.second }.take(10).map { it.first }
        }
        if (here == null || chgRows.isEmpty()) {
            mutP.textSize = 17f * k
            c.drawText(if (here == null) "Đang chờ vị trí GPS…" else "Chưa có dữ liệu trạm sạc", r.left + 16f * k, y + 30f * k, mutP)
            return
        }
        val g = 10f * k
        val rh = 64f * k
        for (st in chgRows) {
            if (y + rh > r.bottom - g) break
            val rr = RectF(r.left + g, y, r.right - g, y + rh - 6f * k)
            c.drawRoundRect(rr, 12f * k, 12f * k, cardP)
            rowR.add(rr)
            boldP.textSize = 18f * k; boldP.color = 0xFF4FD18B.toInt()
            val kw = "${st.maxKw} kW"
            c.drawText(kw, rr.right - 12f * k - boldP.measureText(kw), rr.top + 36f * k, boldP)
            boldP.color = 0xFFFFFFFF.toInt()
            val tw = rr.width() - boldP.measureText(kw) - 40f * k
            textP.textSize = 18f * k
            c.drawText(clip(st.name, tw, textP), rr.left + 12f * k, rr.top + 26f * k, textP)
            mutP.textSize = 14f * k
            val d = Chargers.distKm(here.first, here.second, st.lat, st.lon)
            c.drawText(clip(kmText(d) + if (st.ports.isNotBlank()) " · ${st.ports}" else "", tw), rr.left + 12f * k, rr.top + 48f * k, mutP)
            y += rh
        }
    }

    // ---- bảng chọn tuyến: chạm dòng = xem trên bản đồ, nút ĐI xanh = dẫn đường ----
    private fun drawRoutes(c: Canvas, r: RectF, k: Float) {
        var y = header(c, r, CarUi.destName.ifBlank { "Chọn lộ trình" }, k)
        val o = CarUi.opts
        mutP.textSize = 17f * k
        if (o == null) { c.drawText("Đang tính tuyến…", r.left + 16f * k, y + 30f * k, mutP); return }
        if (o.isEmpty()) { c.drawText(clip(CarLink.nav?.note?.ifBlank { null } ?: "Không tìm được đường", r.width() - 32f * k), r.left + 16f * k, y + 30f * k, mutP); return }
        val g = 10f * k
        val rh = 74f * k
        for ((i, op) in o.withIndex()) {
            if (y + rh > r.bottom - g) break
            val rr = RectF(r.left + g, y, r.right - g, y + rh - 8f * k)
            val sel = i == MapCtl.sel
            c.drawRoundRect(rr, 12f * k, 12f * k, if (sel) selP else cardP)
            if (sel) { tmp.set(rr.left, rr.top + 10f * k, rr.left + 5f * k, rr.bottom - 10f * k); c.drawRoundRect(tmp, 3f * k, 3f * k, accP) }
            rowR.add(rr)
            val gb = RectF(rr.right - 12f * k - 72f * k, rr.centerY() - 22f * k, rr.right - 12f * k, rr.centerY() + 22f * k)
            c.drawRoundRect(gb, 22f * k, 22f * k, goP)
            goR.add(gb)
            boldP.color = 0xFF04210F.toInt(); boldP.textSize = 19f * k
            c.drawText("ĐI", gb.centerX() - boldP.measureText("ĐI") / 2f, gb.centerY() + 7f * k, boldP)
            boldP.color = 0xFFFFFFFF.toInt(); boldP.textSize = 22f * k
            val mins = "${max(1, (op.totalS / 60).roundToInt())} phút"
            c.drawText(mins, rr.left + 16f * k, rr.top + 30f * k, boldP)
            mutP.textSize = 16f * k
            c.drawText(kmText(op.totalM / 1000), rr.left + 26f * k + boldP.measureText(mins), rr.top + 30f * k, mutP)
            val delay = (op.delayS / 60).roundToInt()
            mutP.textSize = 14f * k
            c.drawText(if (delay >= 2) "Kẹt xe thêm $delay phút" else if (i == 0) "Nhanh nhất" else "Tuyến khác", rr.left + 16f * k, rr.top + 54f * k, mutP)
            y += rh
        }
    }

    // ---- cột ADAS: ô ngắn hướng rẽ + biển, dải khoảng cách xe trước, camera (chỉ YOLO + comma.ai + làn AR) ----
    private fun drawAdas(c: Canvas, r: RectF, k: Float) {
        closeR.setEmpty(); rowR.clear(); goR.clear()
        val g = 10f * k
        // ô trên
        val top = RectF(r.left + g, r.top + g, r.right - g, r.top + g + 92f * k)
        c.drawRoundRect(top, 14f * k, 14f * k, cardP)
        val lim = if (fresh()) CarLink.limit else 0
        val rs = 34f * k
        var right = top.right - 12f * k
        if (lim > 0) { limitSign(c, right - rs, top.centerY(), rs, lim, speedNow() > lim + 3f); right -= rs * 2f + 14f * k }
        val t = CarLink.turn?.takeIf { fresh() && it.turn > 0 }
        val x = top.left + 16f * k
        if (t != null) {
            boldP.color = 0xFFFFFFFF.toInt(); boldP.textSize = 44f * k
            c.drawText(arrow(t.turn), x, top.centerY() + 16f * k, boldP)
            val tx = x + 60f * k
            boldP.textSize = 28f * k
            c.drawText(dist(t.distanceM), tx, top.centerY() - 2f * k, boldP)
            mutP.textSize = 16f * k
            c.drawText(clip(t.nextStreet.ifBlank { TurnGuide.name(t.turn) }, right - tx - 8f * k), tx, top.centerY() + 24f * k, mutP)
        } else {
            textP.textSize = 19f * k
            c.drawText(clip(CarLink.street.takeIf { fresh() }.orEmpty().ifBlank { "EVCarHUD" }, right - x - 8f * k, textP), x, top.centerY() + 7f * k, textP)
        }
        // dải xe phía trước: trắng / vàng / đỏ theo mức cảnh báo; cao tốc thì kèm khoảng cách tối thiểu
        val lead = RectF(r.left + g, top.bottom + 8f * k, r.right - g, top.bottom + 8f * k + 46f * k)
        c.drawRoundRect(lead, 14f * k, 14f * k, cardP)
        val lm = if (fresh()) CarLink.leadM else Float.NaN
        mutP.textSize = 13f * k
        c.drawText(clip((CarLink.leadWhat.ifBlank { "XE PHÍA TRƯỚC" }), lead.width() * 0.45f), lead.left + 16f * k, lead.centerY() + 5f * k, mutP)
        boldP.textSize = 28f * k
        boldP.color = when { lm.isNaN() -> 0xFF8B96A3.toInt(); CarLink.leadLevel >= 2 -> 0xFFFF5252.toInt(); CarLink.leadLevel == 1 -> 0xFFFFC107.toInt(); else -> 0xFFFFFFFF.toInt() }
        val num = if (lm.isNaN()) "--" else "${lm.roundToInt()} m"
        val gap = if (fresh() && CarLink.leadGap > 0f) "tối thiểu ${CarLink.leadGap.roundToInt()} m" else ""
        val gw = if (gap.isNotEmpty()) mutP.measureText(gap) + 14f * k else 0f
        c.drawText(num, lead.right - 16f * k - gw - boldP.measureText(num), lead.centerY() + 10f * k, boldP)
        if (gap.isNotEmpty()) c.drawText(gap, lead.right - 16f * k - mutP.measureText(gap), lead.centerY() + 5f * k, mutP)
        boldP.color = 0xFFFFFFFF.toInt()
        // camera
        drawCam(c, RectF(r.left + g, lead.bottom + 8f * k, r.right - g, r.bottom - g), k)
    }

    private fun drawCam(c: Canvas, r: RectF, k: Float) {
        if (r.height() < 20f * k) return
        c.save()
        path.reset(); path.addRoundRect(r, 14f * k, 14f * k, Path.Direction.CW)
        c.clipPath(path)
        c.drawColor(0xFF000000.toInt())
        val cam = CarLink.cam
        if (cam == null || !fresh()) {
            mutP.textSize = 17f * k
            // Chưa có hình: ghi rõ lý do (chưa cấp quyền, camera chưa chạy, đang tải model, lỗi...)
            val app = ctx.applicationContext
            val rn = RunnerHolder.get(app)
            val msg = when {
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
                    "Mở EVCarHUD trên điện thoại để cấp quyền camera"
                !rn.running -> "Camera chưa chạy: mở EVCarHUD trên điện thoại"
                else -> rn.status.ifBlank { "Đang mở camera…" }
            }
            c.drawText(clip(msg, r.width() - 32f * k), r.left + 16f * k, r.centerY(), mutP)
            c.restore(); return
        }
        // khung 1024 x picH; bỏ phần capo, phủ kín ô (cắt hai bên nếu cần)
        val pw = 1024f; val ph = CarLink.picH.toFloat()
        val vis = (ph * (1f - CarLink.hood.coerceIn(0f, 0.6f)) + 12f).coerceAtMost(ph)
        val sc = max(r.width() / pw, r.height() / vis)
        c.translate(r.centerX() - pw * sc / 2f, r.centerY() - vis * sc / 2f)
        c.scale(sc, sc)
        tmp.set(0f, 0f, pw, ph)
        c.drawBitmap(cam, null, tmp, bmpP)
        CarLink.scene?.let { c.drawPicture(it) }
        c.restore()
    }
}
