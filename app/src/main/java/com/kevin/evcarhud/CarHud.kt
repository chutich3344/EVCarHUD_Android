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
import android.media.MediaMetadata
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

// Màn hình EVCarHUD trên Android Auto (ô CarPlay/Android Auto của xe): app dẫn đường tự vẽ toàn bộ vùng bản đồ.
// Bố cục: nửa trái bản đồ HERE + tuyến; nửa phải từ trên xuống: ô biển tốc độ / tốc độ / hướng rẽ, HUD AI, nhạc đang phát.
// Bản thử: cài APK ngoài, bật "Nguồn không xác định" trong cài đặt nhà phát triển của Android Auto.

// Dữ liệu luồng HUD trên điện thoại chuyển sang xe (Runner ghi, luồng vẽ trên xe đọc)
object CarLink {
    @Volatile var active = false           // đang có màn hình xe: mới chép ảnh camera
    @Volatile var nav: HereNav? = null
    @Volatile var gps: LocationFallback? = null
    @Volatile var pic: Picture? = null
    @Volatile var picH = 576
    @Volatile var cam: Bitmap? = null
    @Volatile var speed = 0f
    @Volatile var limit = 0
    @Volatile var turn: TurnInfo? = null
    @Volatile var street = ""
    @Volatile var at = 0L                  // lần HUD cập nhật gần nhất (elapsedRealtime)
    private var camAt = 0L

    // Gọi từ luồng hiển thị của Runner sau khi ghi xong Picture của khung
    fun feed(p: Picture, bmp: Bitmap, spd: Float, lim: Int, t: TurnInfo?, st: String) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        pic = p; picH = bmp.height; speed = spd; limit = lim; turn = t; street = st; at = now
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
        surf.onMediaTap = { invalidate() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { surf.start() }
            override fun onResume(owner: LifecycleOwner) { invalidate() }   // quay về từ màn tìm / chọn tuyến: đổi nút Hủy
            override fun onStop(owner: LifecycleOwner) { surf.stop() }
            override fun onDestroy(owner: LifecycleOwner) { surf.quit(); autoStop() }
        })
    }

    override fun onGetTemplate(): Template {
        val ctx = carContext
        val nav = CarLink.nav
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_search)).setOnClickListener { screenManager.push(SearchScreen(ctx)) }.build())
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_bolt)).setOnClickListener { screenManager.push(ChargerScreen(ctx)) }.build())
            .addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_traffic))
                .setOnClickListener { MapCtl.traffic = !MapCtl.traffic; CarToast.show(ctx, if (MapCtl.traffic) "Bật lớp giao thông" else "Tắt lớp giao thông") }.build())
            .apply {
                if (nav?.active == true) addAction(Action.Builder().setIcon(carIcon(ctx, R.drawable.car_close))
                    .setOnClickListener { nav.stop(); invalidate() }.build())
                else addAction(Action.Builder().setIcon(carIcon(ctx, if (MediaNow.playing(ctx)) R.drawable.car_pause else R.drawable.car_play))
                    .setOnClickListener { MediaNow.toggle(ctx); invalidate() }.build())
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
    @Volatile var zoom = 16
    @Volatile var follow = true             // bám theo xe, xoay theo hướng đi
    @Volatile var cx = Double.NaN           // tâm khi đang kéo (toạ độ ô ở zoom hiện tại)
    @Volatile var cy = Double.NaN
    @Volatile var traffic = false
    @Volatile var preview: List<HereNav.RouteOpt> = emptyList()
    @Volatile var sel = 0
    fun zoomBy(d: Int) {
        val nz = (zoom + d).coerceIn(10, 18)
        if (!cx.isNaN()) { val f = Math.pow(2.0, (nz - zoom).toDouble()); cx *= f; cy *= f }
        zoom = nz
    }
    fun recenter() { follow = true; cx = Double.NaN; cy = Double.NaN }
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
                screenManager.push(RouteScreen(carContext, c.first, c.second, p.name.ifBlank { p.address }))
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

// Vẽ lên Surface của xe ~10 khung/giây trên luồng riêng
class HudSurface(private val ctx: CarContext) : SurfaceCallback {
    private val th = HandlerThread("car-hud").apply { start() }
    private val hd = Handler(th.looper)
    private var surface: Surface? = null
    private var sw = 0
    private var sh = 0
    private val area = Rect()
    private var running = false
    var onMediaTap: (() -> Unit)? = null
    // vùng nút nhạc trên màn hình xe (để bấm trực tiếp)
    private val btnPrev = RectF(); private val btnPlay = RectF(); private val btnNext = RectF()

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            render()
            hd.postDelayed(this, 100L)
        }
    }

    fun start() { CarLink.active = true; hd.post { if (!running) { running = true; hd.post(loop) } } }
    fun stop() { CarLink.active = false; hd.post { running = false; hd.removeCallbacks(loop) } }
    fun quit() { stop(); th.quitSafely() }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val s = surfaceContainer.surface
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        hd.post { surface = s; sw = w; sh = h; if (area.isEmpty) area.set(0, 0, w, h) }
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
    override fun onClick(x: Float, y: Float) {
        hd.post {
            when {
                btnPrev.contains(x, y) -> MediaNow.prev(ctx)
                btnPlay.contains(x, y) -> { MediaNow.toggle(ctx); onMediaTap?.let { f -> ctx.mainExecutor.execute { f() } } }
                btnNext.contains(x, y) -> MediaNow.next(ctx)
            }
        }
    }

    // ---- bút vẽ ----
    private val bg = 0xFF0B0E12.toInt()
    private val cardP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161B22.toInt() }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A323D.toInt(); strokeWidth = 2f }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EDF2.toInt() }
    private val mutP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B96A3.toInt() }
    private val boldP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val signW = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val signR = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt(); style = Paint.Style.STROKE }
    private val signT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val routeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3AD6C4.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val egoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BFF.toInt() }
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553D8BFF }
    private val accP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D8BFF.toInt() }
    private val btnP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF232A33.toInt() }
    private val bmpP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val tmp = RectF()

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
        val g = 10f * k
        val mid = a.left + a.width() / 2f
        drawMap(c, RectF(a.left, a.top, mid - g / 2f, a.bottom), k)
        val rx = mid + g / 2f
        val infoH = a.height() * 0.2f
        val musicH = a.height() * 0.25f
        drawInfo(c, RectF(rx, a.top + g, a.right - g, a.top + infoH), k)
        drawHud(c, RectF(rx, a.top + infoH + g, a.right - g, a.bottom - musicH - g), k)
        drawMusic(c, RectF(rx, a.bottom - musicH, a.right - g, a.bottom - g), k)
    }

    // ---- bản đồ HERE (ô explore.night): xoay theo hướng xe, zoom, kéo, giao thông, trạm sạc, tuyến / phương án tuyến ----
    private var lastCx = Double.NaN; private var lastCy = Double.NaN; private var lastTs = 300f
    private var headDeg = 0f
    private val altP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B5D8A.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val chgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FD18B.toInt() }
    private val chgRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F141A.toInt(); style = Paint.Style.STROKE }
    private val destP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14B8A6.toInt() }

    private fun drawMap(c: Canvas, r: RectF, k: Float) {
        c.save()
        c.clipRect(r)
        c.drawColor(0xFF0F141A.toInt())
        val nav = CarLink.nav
        val ego = nav?.egoOnRoute()
        val ll = if (ego != null) Pair(ego.first, ego.second) else CarLink.gps?.anyLatLon()
        if (nav == null || ll == null) {
            mutP.textSize = 22f * k
            c.drawText("Đang chờ vị trí GPS…", r.left + 20f * k, r.centerY(), mutP)
            c.restore(); return
        }
        val pv = MapCtl.preview
        // xem trước tuyến: tự chọn mức zoom vừa khung, hướng bắc lên trên
        var z = MapCtl.zoom
        val ts = 300f * k
        if (pv.isNotEmpty()) {
            var mnLa = 90.0; var mxLa = -90.0; var mnLo = 180.0; var mxLo = -180.0
            for (o in pv) for (i in o.lat.indices) { mnLa = min(mnLa, o.lat[i]); mxLa = max(mxLa, o.lat[i]); mnLo = min(mnLo, o.lon[i]); mxLo = max(mxLo, o.lon[i]) }
            val z0 = 16
            val n0 = (1 shl z0).toDouble()
            val spanX = (mxLo - mnLo) / 360.0 * n0 * ts
            val spanY = (ty(mnLa, n0) - ty(mxLa, n0)) * ts
            var zz = z0
            var f = max(spanX / (r.width() * 0.85), spanY / (r.height() * 0.85))
            while (f > 1.0 && zz > 6) { zz--; f /= 2.0 }
            z = zz
        }
        val n = (1 shl z).toDouble()
        val follow = MapCtl.follow && pv.isEmpty()
        // tâm bản đồ (toạ độ ô)
        val ex = tx(ll.second, n); val ey = ty(ll.first, n)
        val x0: Double; val y0: Double
        if (pv.isNotEmpty()) {
            var a = 0.0; var b = 0.0; var m = 0
            for (i in pv[0].lat.indices) { a += tx(pv[0].lon[i], n); b += ty(pv[0].lat[i], n); m++ }
            x0 = a / max(1, m); y0 = b / max(1, m)
        } else if (!follow && !MapCtl.cx.isNaN()) { x0 = MapCtl.cx; y0 = MapCtl.cy } else { x0 = ex; y0 = ey }
        lastCx = x0; lastCy = y0; lastTs = ts
        // hướng: theo tuyến khi đang dẫn đường, không thì theo GPS; kéo bản đồ / xem tuyến thì bắc lên trên
        if (follow) {
            val h = ego?.third?.let { Math.toDegrees(it).toFloat() } ?: CarLink.gps?.bearingDeg()
            if (h != null) { var d = h - headDeg; while (d > 180f) d -= 360f; while (d < -180f) d += 360f; headDeg += d * 0.3f }
        }
        val rot = if (follow) -headDeg else 0f
        val cx = r.centerX(); val cy = if (follow) r.top + r.height() * 0.68f else r.centerY()
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
        // phương án tuyến (xem trước) hoặc tuyến đang đi
        if (pv.isNotEmpty()) {
            altP.strokeWidth = 7f * k; routeP.strokeWidth = 8f * k
            for ((i, o) in pv.withIndex()) if (i != MapCtl.sel) line(o.lat, o.lon, altP)
            pv.getOrNull(MapCtl.sel)?.let { o ->
                line(o.lat, o.lon, routeP)
                px(o.lat.last(), o.lon.last(), pt); c.drawCircle(pt[0], pt[1], 11f * k, destP)
            }
        } else {
            val la = nav.routeLat(); val lo = nav.routeLon()
            if (la != null && lo != null && la.size > 1) {
                routeP.strokeWidth = 8f * k
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
        // ô chuyến đi: điểm đến + còn lại (Hủy dẫn đường ở nút ✕ trên thanh của Android Auto)
        if (nav.active && pv.isEmpty()) {
            val l1 = clip(nav.destName.ifBlank { "Điểm đến" }, r.width() - 48f * k, textP.apply { textSize = 18f * k })
            val l2 = nav.remainText.ifBlank { "Đang tính đường…" }
            mutP.textSize = 16f * k
            val w = max(textP.measureText(l1), mutP.measureText(l2)) + 28f * k
            tmp.set(r.left + 12f * k, r.bottom - 74f * k, r.left + 12f * k + w, r.bottom - 12f * k)
            c.drawRoundRect(tmp, 12f * k, 12f * k, cardP)
            c.drawText(l1, tmp.left + 14f * k, tmp.top + 26f * k, textP)
            c.drawText(l2, tmp.left + 14f * k, tmp.top + 50f * k, mutP)
        }
        if (MapCtl.traffic) { mutP.textSize = 13f * k; c.drawText("Giao thông HERE", r.left + 12f * k, r.top + 22f * k, mutP) }
        // ghi công dữ liệu bản đồ (giấy phép ODbL)
        mutP.textSize = 11f * k; c.drawText(OsmTiles.ATTRIB, r.right - mutP.measureText(OsmTiles.ATTRIB) - 8f * k, r.bottom - 6f * k, mutP)
        c.restore()
    }
    private fun tx(lon: Double, n: Double) = (lon + 180.0) / 360.0 * n
    private fun ty(lat: Double, n: Double): Double { val p = Math.toRadians(lat); return (1.0 - ln(tan(p) + 1.0 / cos(p)) / PI) / 2.0 * n }

    private fun fresh() = SystemClock.elapsedRealtime() - CarLink.at < 2000L

    private fun arrow(code: Int) = when (code) {
        1 -> "↑"; 2, 6 -> "←"; 3, 7 -> "→"; 4, 13, 15 -> "↖"; 5, 14, 16 -> "↗"; 8, 20 -> "↶"; 10, 11, 12, 19 -> "⟳"; 17 -> "⚑"; else -> "↑"
    }
    private fun dist(m: Int) = if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000f) else "$m m"

    // ---- ô trên: biển tốc độ, tốc độ, hướng rẽ ----
    private fun drawInfo(c: Canvas, r: RectF, k: Float) {
        c.drawRoundRect(r, 14f * k, 14f * k, cardP)
        val spd = if (fresh()) CarLink.speed else (CarLink.gps?.speedKmh() ?: 0f)
        val lim = if (fresh()) CarLink.limit else 0
        var x = r.left + 16f * k
        val cy = r.centerY()
        val rad = min(r.height() * 0.36f, 34f * k)
        if (lim > 0) {
            c.drawCircle(x + rad, cy, rad, signW)
            signR.strokeWidth = rad * 0.2f
            c.drawCircle(x + rad, cy, rad * 0.9f, signR)
            signT.textSize = rad * 0.85f
            c.drawText(lim.toString(), x + rad, cy + rad * 0.3f, signT)
            x += rad * 2f + 14f * k
        }
        val over = lim > 0 && spd > lim + 3f
        boldP.textSize = 44f * k
        boldP.color = if (over && (SystemClock.elapsedRealtime() / 300L) % 2L == 0L) 0xFFFF4D4D.toInt() else 0xFFFFFFFF.toInt()
        val st = if (spd > 1f) spd.roundToInt().toString() else "--"
        c.drawText(st, x, cy + 10f * k, boldP)
        mutP.textSize = 14f * k
        c.drawText("km/h", x, cy + 30f * k, mutP)
        x += max(boldP.measureText(st), mutP.measureText("km/h")) + 22f * k
        c.drawLine(x, r.top + 14f * k, x, r.bottom - 14f * k, lineP)
        x += 18f * k
        val t = CarLink.turn
        val w = r.right - x - 12f * k
        if (t != null && t.turn > 0 && fresh()) {
            boldP.color = 0xFFFFFFFF.toInt()
            boldP.textSize = 46f * k
            c.drawText(arrow(t.turn), x, cy + 16f * k, boldP)
            val tx = x + 54f * k
            boldP.textSize = 30f * k
            c.drawText(dist(t.distanceM), tx, cy, boldP)
            mutP.textSize = 16f * k
            c.drawText(clip(t.nextStreet.ifBlank { TurnGuide.name(t.turn) }, r.right - tx - 12f * k), tx, cy + 24f * k, mutP)
        } else {
            textP.textSize = 20f * k
            c.drawText(clip(CarLink.street.ifBlank { "EVCarHUD" }, w), x, cy + 8f * k, textP)
        }
    }

    private fun clip(s: String, w: Float, p: Paint = mutP): String {
        if (w <= 0f) return ""
        val n = p.breakText(s, true, w, null)
        return if (n < s.length) s.substring(0, max(0, n - 1)) + "…" else s
    }

    // ---- HUD AI: ảnh camera + lớp HUD của khung mới nhất ----
    private fun drawHud(c: Canvas, r: RectF, k: Float) {
        c.save()
        tmp.set(r)
        path.reset(); path.addRoundRect(tmp, 14f * k, 14f * k, Path.Direction.CW)
        c.clipPath(path)
        c.drawColor(0xFF000000.toInt())
        val pic = CarLink.pic
        if (pic == null || !fresh()) {
            mutP.textSize = 18f * k
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
        val pw = 1024f; val ph = CarLink.picH.toFloat()
        val sc = max(r.width() / pw, r.height() / ph)
        c.translate(r.centerX() - pw * sc / 2f, r.centerY() - ph * sc / 2f)
        c.scale(sc, sc)
        CarLink.cam?.let { b -> tmp.set(0f, 0f, pw, ph); c.drawBitmap(b, null, tmp, bmpP) }
        c.drawPicture(pic)
        c.restore()
    }

    // ---- nhạc đang phát (YouTube Music, Spotify, ...) ----
    private fun drawMusic(c: Canvas, r: RectF, k: Float) {
        c.drawRoundRect(r, 14f * k, 14f * k, cardP)
        btnPrev.setEmpty(); btnPlay.setEmpty(); btnNext.setEmpty()
        if (!MediaNow.granted(ctx)) {
            textP.textSize = 17f * k
            c.drawText("Nhạc: cho phép trên điện thoại", r.left + 16f * k, r.centerY() - 4f * k, textP)
            mutP.textSize = 14f * k
            c.drawText(clip("EVCarHUD › Cài đặt › Nhạc trên Android Auto", r.width() - 32f * k), r.left + 16f * k, r.centerY() + 20f * k, mutP)
            return
        }
        val m = MediaNow.current(ctx)
        val md = m?.metadata
        val pad = 12f * k
        val art = r.height() - pad * 2f
        tmp.set(r.left + pad, r.top + pad, r.left + pad + art, r.bottom - pad)
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) c.drawBitmap(bmp, null, tmp, bmpP) else c.drawRoundRect(tmp, 8f * k, 8f * k, btnP)
        // nút: ⏮ ⏯ ⏭ bên phải
        val br = min(22f * k, r.height() * 0.22f)
        val by = r.centerY()
        val bx3 = r.right - pad - br; val bx2 = bx3 - br * 2.4f; val bx1 = bx2 - br * 2.4f
        btnPrev.set(bx1 - br, by - br, bx1 + br, by + br)
        btnPlay.set(bx2 - br, by - br, bx2 + br, by + br)
        btnNext.set(bx3 - br, by - br, bx3 + br, by + br)
        for (b in listOf(btnPrev, btnPlay, btnNext)) c.drawCircle(b.centerX(), b.centerY(), br, btnP)
        boldP.color = 0xFFFFFFFF.toInt(); boldP.textSize = br * 0.9f
        val playing = m?.playbackState?.state == PlaybackState.STATE_PLAYING
        fun ctr(t: String, b: RectF) { c.drawText(t, b.centerX() - boldP.measureText(t) / 2f, b.centerY() + br * 0.32f, boldP) }
        ctr("⏮", btnPrev); ctr(if (playing) "⏸" else "▶", btnPlay); ctr("⏭", btnNext)
        // tên bài, nghệ sĩ, thanh thời gian
        val tx = tmp.right + 12f * k
        val tw = btnPrev.left - 10f * k - tx
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Chưa phát nhạc"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        textP.textSize = 18f * k
        c.drawText(clip(title, tw, textP), tx, r.centerY() - 10f * k, textP)
        mutP.textSize = 14f * k
        c.drawText(clip(artist, tw), tx, r.centerY() + 12f * k, mutP)
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val ps = m?.playbackState
        if (dur > 0L && ps != null && tw > 0f) {
            var pos = ps.position
            if (playing) pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
            val f = (pos.toFloat() / dur).coerceIn(0f, 1f)
            val yb = r.centerY() + 26f * k
            tmp.set(tx, yb, tx + tw, yb + 3f * k); c.drawRect(tmp, btnP)
            tmp.set(tx, yb, tx + tw * f, yb + 3f * k); c.drawRect(tmp, accP)
        }
    }
}
