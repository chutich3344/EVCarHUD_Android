package com.kevin.evcarhud

import android.content.ComponentName
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

    init {
        // Xe mở app khi điện thoại chưa mở EVCarHUD: tạo Runner để có GPS + bản đồ
        RunnerHolder.get(ctx.applicationContext).gps.start()
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(surf)
        surf.onMediaTap = { invalidate() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { surf.start() }
            override fun onStop(owner: LifecycleOwner) { surf.stop() }
            override fun onDestroy(owner: LifecycleOwner) { surf.quit() }
        })
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val ctx = carContext
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icon(R.drawable.car_prev)).setOnClickListener { MediaNow.prev(ctx) }.build())
            .addAction(Action.Builder().setIcon(icon(if (MediaNow.playing(ctx)) R.drawable.car_pause else R.drawable.car_play))
                .setOnClickListener { MediaNow.toggle(ctx); invalidate() }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.car_next)).setOnClickListener { MediaNow.next(ctx) }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
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

    // ---- bản đồ HERE (ô explore.night, hướng bắc lên trên) + tuyến + vị trí xe ----
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
        val z = 16
        val n = (1 shl z).toDouble()
        val ts = 300f * k                       // cỡ một ô 512 px trên màn hình xe
        fun tx(lon: Double) = (lon + 180.0) / 360.0 * n
        fun ty(lat: Double): Double { val p = Math.toRadians(lat); return (1.0 - ln(tan(p) + 1.0 / cos(p)) / PI) / 2.0 * n }
        val x0 = tx(ll.second); val y0 = ty(ll.first)
        val cx = r.centerX(); val cy = r.top + r.height() * 0.62f
        val ix0 = floor(x0 - (cx - r.left) / ts).toInt(); val ix1 = floor(x0 + (r.right - cx) / ts).toInt()
        val iy0 = floor(y0 - (cy - r.top) / ts).toInt(); val iy1 = floor(y0 + (r.bottom - cy) / ts).toInt()
        for (ix in ix0..ix1) for (iy in iy0..iy1) {
            val b = nav.mapTile(z, ix, iy, false) ?: continue
            val l = cx + ((ix - x0) * ts).toFloat(); val t = cy + ((iy - y0) * ts).toFloat()
            tmp.set(l, t, l + ts, t + ts)
            c.drawBitmap(b, null, tmp, bmpP)
        }
        // tuyến đang đi
        val la = nav.routeLat(); val lo = nav.routeLon()
        if (la != null && lo != null && la.size > 1) {
            path.reset()
            for (i in la.indices) {
                val px = cx + ((tx(lo[i]) - x0) * ts).toFloat(); val py = cy + ((ty(la[i]) - y0) * ts).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            routeP.strokeWidth = 8f * k
            c.drawPath(path, routeP)
        }
        // xe: mũi tên theo hướng đi
        val head = ego?.third ?: 0.0
        c.drawCircle(cx, cy, 26f * k, haloP)
        c.save()
        c.rotate(Math.toDegrees(head).toFloat(), cx, cy)
        path.reset()
        path.moveTo(cx, cy - 17f * k); path.lineTo(cx + 13f * k, cy + 13f * k); path.lineTo(cx, cy + 5f * k); path.lineTo(cx - 13f * k, cy + 13f * k); path.close()
        c.drawPath(path, egoP)
        c.restore()
        if (nav.active && nav.remainText.isNotEmpty()) {
            textP.textSize = 20f * k
            tmp.set(r.left + 12f * k, r.bottom - 50f * k, r.left + 24f * k + textP.measureText(nav.remainText), r.bottom - 12f * k)
            c.drawRoundRect(tmp, 10f * k, 10f * k, cardP)
            c.drawText(nav.remainText, tmp.left + 6f * k, tmp.bottom - 12f * k, textP)
        }
        c.restore()
    }

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
            c.drawText("Mở EVCarHUD trên điện thoại và chạy camera", r.left + 16f * k, r.centerY(), mutP)
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
