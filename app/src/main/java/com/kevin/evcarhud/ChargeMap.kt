package com.kevin.evcarhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

// Trạm sạc VinFast (assets/tram_sac.json, 6.000 trạm công cộng, chỉ để thử; bản thương mại cần nguồn có giấy phép)
object Chargers {
    class St(val lat: Double, val lon: Double, val name: String, val maxKw: Int, val ports: String, val hours: String)

    @Volatile private var all: List<St>? = null

    fun load(ctx: Context): List<St> {
        all?.let { return it }
        val list = try {
            val a = JSONArray(ctx.assets.open("tram_sac.json").bufferedReader().use { it.readText() })
            List(a.length()) { i ->
                val r = a.getJSONArray(i)
                St(r.getDouble(0), r.getDouble(1), r.getString(2), r.getInt(3), r.getString(4), r.getString(5))
            }
        } catch (_: Throwable) { emptyList() }
        all = list
        return list
    }

    fun distKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val x = (bLon - aLon) * cos(Math.toRadians((aLat + bLat) / 2))
        return hypot(x, bLat - aLat) * 111.32
    }
}

// Vẽ trang Bản đồ bằng Canvas Android: ô bản đồ HERE (Web Mercator), tuyến, trạm sạc, vị trí xe.
// z là mức phóng to (số thực); ô tải ở mức nguyên floor(z), phóng thêm phần lẻ. Bản đồ quay quanh (ox, oy) theo rotDeg.
private class MapDraw(private val nav: HereNav, private val k: Float) {
    class Hit(val x: Float, val y: Float, val st: Chargers.St)

    private val bg = Paint().apply { color = 0xFF0A1620.toInt() }
    private val tileP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x664FC3F7; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val routeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF4FC3F7.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pinP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
    private val pinRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt() }
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x400A84FF }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val egoRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF0A84FF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelRim = Paint(label).apply { style = Paint.Style.STROKE; color = 0xCC000000.toInt() }
    private val path = Path()
    private val dst = RectF()

    private fun gx(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world
    private fun gy(lat: Double, world: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * world
    }

    // Kết quả: các trạm đã vẽ (toạ độ màn hình) để bấm chọn
    fun draw(c: Canvas, w: Float, h: Float, cLat: Double, cLon: Double, z: Double, rotDeg: Float, ox: Float, oy: Float,
             sat: Boolean, stations: List<Chargers.St>, sel: Chargers.St?, ego: Triple<Double, Double, Double?>?): List<Hit> {
        c.drawRect(0f, 0f, w, h, bg)
        val zt = floor(z).toInt().coerceIn(3, 19)
        val world = 256.0 * (1 shl zt)
        val sc = 2.0.pow(z - zt) * k                 // px màn hình / px thế giới ở mức zt
        val cgx = gx(cLon, world); val cgy = gy(cLat, world)
        val rot = Math.toRadians(-rotDeg.toDouble()); val cr = cos(rot); val sr = sin(rot)
        fun sx(lat: Double, lon: Double): Float { val x = (gx(lon, world) - cgx) * sc; val y = (gy(lat, world) - cgy) * sc; return ox + (x * cr - y * sr).toFloat() }
        fun sy(lat: Double, lon: Double): Float { val x = (gx(lon, world) - cgx) * sc; val y = (gy(lat, world) - cgy) * sc; return oy + (x * sr + y * cr).toFloat() }

        // Ô bản đồ (trong khung đã quay)
        c.save()
        c.translate(ox, oy)
        c.rotate(-rotDeg)
        val reach = hypot(w.toDouble(), h.toDouble()) / sc
        val n = 1 shl zt
        val tx0 = floor((cgx - reach) / 256.0).toInt(); val tx1 = floor((cgx + reach) / 256.0).toInt()
        val ty0 = floor((cgy - reach) / 256.0).toInt(); val ty1 = floor((cgy + reach) / 256.0).toInt()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            if (ty < 0 || ty >= n) continue
            val b = nav.mapTile(zt, ((tx % n) + n) % n, ty, sat) ?: continue
            val l = ((tx * 256.0 - cgx) * sc).toFloat(); val t = ((ty * 256.0 - cgy) * sc).toFloat()
            val sz = (256.0 * sc).toFloat()
            dst.set(l, t, l + sz + 0.5f, t + sz + 0.5f)
            c.drawBitmap(b, null, dst, tileP)
        }
        c.restore()

        // Tuyến đang chỉ đường
        val la = nav.routeLat(); val lo = nav.routeLon()
        if (la != null && lo != null && la.size > 1) {
            path.reset()
            path.moveTo(sx(la[0], lo[0]), sy(la[0], lo[0]))
            for (i in 1 until la.size) path.lineTo(sx(la[i], lo[i]), sy(la[i], lo[i]))
            glowP.strokeWidth = 16f * k; c.drawPath(path, glowP)
            routeP.strokeWidth = 7f * k; c.drawPath(path, routeP)
        }

        // Trạm sạc (luôn đứng thẳng, một màu cho mọi trạm)
        val hits = ArrayList<Hit>()
        label.textSize = 11f * k; labelRim.textSize = 11f * k; labelRim.strokeWidth = 3f * k
        val pad = 30f * k
        for (s in stations) {
            val x = sx(s.lat, s.lon); val y = sy(s.lat, s.lon)
            if (x < -pad || x > w + pad || y < -pad || y > h + pad) continue
            val big = s === sel
            val r = (if (big) 15f else 11f) * k
            if (big) c.drawCircle(x, y, r * 2f, haloP)
            bolt(c, x, y, r)
            if (z >= 13.5 || big) {
                val t = "${s.maxKw} kW"
                c.drawText(t, x, y + r + 13f * k, labelRim); c.drawText(t, x, y + r + 13f * k, label)
            }
            hits.add(Hit(x, y, s))
        }

        // Xe: chấm xanh, có mũi tên khi biết hướng
        if (ego != null) {
            val x = sx(ego.first, ego.second); val y = sy(ego.first, ego.second)
            c.drawCircle(x, y, 26f * k, haloP)
            val hd = ego.third
            if (hd == null) {
                c.drawCircle(x, y, 9f * k, white); egoRim.strokeWidth = 4f * k; c.drawCircle(x, y, 9f * k, egoRim)
            } else {
                c.save(); c.translate(x, y); c.rotate((Math.toDegrees(hd) - rotDeg).toFloat())
                path.reset(); path.moveTo(0f, -17f * k); path.lineTo(13f * k, 13f * k); path.lineTo(0f, 6f * k); path.lineTo(-13f * k, 13f * k); path.close()
                c.drawPath(path, white); egoRim.strokeWidth = 3f * k; c.drawPath(path, egoRim)
                c.restore()
            }
        }
        return hits
    }

    private fun bolt(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r, pinP)
        pinRim.strokeWidth = r * 0.18f; c.drawCircle(x, y, r, pinRim)
        val q = r / 10f
        path.reset()
        path.moveTo(x + 1.5f * q, y - 7f * q); path.lineTo(x - 4f * q, y + q); path.lineTo(x - 0.5f * q, y + q)
        path.lineTo(x - 1.5f * q, y + 7f * q); path.lineTo(x + 4f * q, y - q); path.lineTo(x + 0.5f * q, y - q); path.close()
        c.drawPath(path, white)
    }

    // Đổi dịch chuyển màn hình (px, đã quay) thành toạ độ tâm mới
    fun panTo(cLat: Double, cLon: Double, z: Double, rotDeg: Float, dx: Float, dy: Float): Pair<Double, Double> {
        val zt = floor(z).toInt().coerceIn(3, 19)
        val world = 256.0 * (1 shl zt)
        val sc = 2.0.pow(z - zt) * k
        val rot = Math.toRadians(rotDeg.toDouble())
        val wx = (dx * cos(rot) - dy * sin(rot)) / sc; val wy = (dx * sin(rot) + dy * cos(rot)) / sc
        val gxN = gx(cLon, world) - wx; val gyN = gy(cLat, world) - wy
        val lon = gxN / world * 360.0 - 180.0
        val lat = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * gyN / world))))
        return Pair(lat, lon)
    }
}

private val KW_STEPS = intArrayOf(0, 60, 120, 250)

private fun turnGlyph(code: Int) = when (code) {
    2, 4, 6, 13 -> "↰"
    3, 5, 7, 14 -> "↱"
    8 -> "↶"
    10 -> "⟳"
    17 -> "⚑"
    else -> "↑"
}

// Trang 2: bản đồ trạm sạc toàn màn hình. Bấm trạm -> Dẫn đường (HERE). Đang chỉ đường thì bản đồ quay theo hướng xe,
// hướng rẽ ở trên trái, quãng đường / thời gian ở dưới. Cảnh báo va chạm vẫn chạy nền (âm báo, giọng đọc) như trang HUD.
@Composable
fun BoxScope.MapPage(runner: Runner, onHud: () -> Unit) {
    val ctx = LocalContext.current
    val nav = runner.nav
    val k = LocalDensity.current.density * 0.75f
    val draw = remember { MapDraw(nav, k) }
    val stations by produceState(initialValue = emptyList<Chargers.St>()) { value = withContext(Dispatchers.IO) { Chargers.load(ctx) } }
    // 10 hình/giây là đủ cho bản đồ, đỡ nóng máy hơn 60 hình/giây
    val tick by produceState(0) { while (true) { delay(100); value++ } }
    var kwIdx by rememberSaveable { mutableStateOf(0) }
    var sat by rememberSaveable { mutableStateOf(false) }
    var tilt by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableStateOf(14.5) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var cLat by rememberSaveable { mutableStateOf(Double.NaN) }
    var cLon by rememberSaveable { mutableStateOf(Double.NaN) }
    var sel by remember { mutableStateOf<Chargers.St?>(null) }
    // vị trí các trạm vừa vẽ (ghi trong lúc vẽ nên không dùng State, tránh vẽ lại vòng lặp)
    val hitsRef = remember { arrayOf<List<MapDraw.Hit>>(emptyList()) }

    tick   // đọc ở đây để trang dựng lại 10 lần/giây: trạng thái chỉ đường nằm ở luồng nền
    val ego = nav.egoOnRoute()
    val navOn = nav.active
    val gpsLl = runner.gps.lastLatLon()
    val egoLl: Triple<Double, Double, Double?>? = ego?.let { Triple(it.first, it.second, it.third) }
        ?: gpsLl?.let { Triple(it.first, it.second, null) }
    if ((follow || cLat.isNaN()) && egoLl != null) { cLat = egoLl.first; cLon = egoLl.second }
    if (cLat.isNaN()) { cLat = 10.7769; cLon = 106.7009 }   // chưa có GPS: trung tâm TP.HCM
    val rotDeg = if (navOn && follow && ego != null) Math.toDegrees(ego.third).toFloat() else 0f
    val minKw = KW_STEPS[kwIdx]
    val shown = remember(stations, minKw) { stations.filter { it.maxKw >= minKw } }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A1620))) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (tilt) { rotationX = 38f; cameraDistance = 12f * density; scaleX = 1.25f; scaleY = 1.25f }
                }
                .pointerInput(navOn) {
                    detectTransformGestures { _, pan, zf, _ ->
                        follow = false
                        if (zf != 1f) zoom = (zoom + ln(zf.toDouble()) / ln(2.0)).coerceIn(5.0, 18.5)
                        val p = draw.panTo(cLat, cLon, zoom, rotDeg, pan.x, pan.y)
                        cLat = p.first; cLon = p.second
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val near = hitsRef[0].minByOrNull { hypot(it.x - o.x, it.y - o.y) }
                        sel = if (near != null && hypot(near.x - o.x, near.y - o.y) < 30f * k) near.st else null
                    }
                }
        ) {
            tick
            val oy = if (navOn && follow) size.height * 0.72f else size.height * 0.5f
            drawIntoCanvas { cv ->
                hitsRef[0] = draw.draw(cv.nativeCanvas, size.width, size.height, cLat, cLon, zoom, rotDeg, size.width / 2f, oy,
                    sat, shown, sel, egoLl)
            }
        }

        if (nav.apiKey.isBlank()) {
            Text("Chưa có key HERE: nhập trong Cài đặt để hiện bản đồ", color = Color(0xFFFFC107), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(10.dp))
        }

        // Lọc công suất (ẩn khi đang dẫn đường)
        if (!navOn) {
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tất cả", "≥60 kW", "≥120 kW", "≥250 kW").forEachIndexed { i, t ->
                    Text(t, color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .background(if (i == kwIdx) Color(0xFF0A84FF) else Color(0xE61C1C1E))
                            .clickable { kwIdx = i; sel = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }

        // Hướng rẽ kế tiếp
        val turn = nav.turn
        if (navOn && turn != null) {
            Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xF2082838)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(turnGlyph(turn.turn), color = Color.White, fontSize = 40.sp, modifier = Modifier.padding(end = 14.dp))
                Column {
                    val d = turn.distanceM
                    Text(if (d >= 1000) String.format(Locale.US, "%.1f km", d / 1000f).replace('.', ',') else "$d m",
                        color = Color.White, fontSize = 28.sp)
                    if (turn.nextStreet.isNotBlank()) Text(turn.nextStreet, color = Color(0xFFD6EFFF), fontSize = 16.sp, maxLines = 1)
                }
            }
        }

        // Cột nút bên phải: lớp bản đồ, 3D, về vị trí xe, phóng to / thu nhỏ
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xF00E202C))) {
                MapBtn(if (sat) "Vệ\ntinh" else "Bản\nđồ", false, 12) { sat = !sat }
                MapBtn("3D", tilt, 16) { tilt = !tilt }
                MapBtn("◎", follow, 22) { follow = true; if (!navOn) zoom = 14.5 }
            }
            Column(Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xF00E202C))) {
                MapBtn("+", false, 24) { zoom = (zoom + 1).coerceAtMost(18.5) }
                MapBtn("−", false, 24) { zoom = (zoom - 1).coerceAtLeast(5.0) }
            }
        }

        // Thẻ trạm đã chọn
        val s = sel
        if (s != null && !navOn) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(12.dp).widthIn(max = 440.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xF51C1C1E)).padding(14.dp)
            ) {
                Text(s.name, color = Color.White, fontSize = 17.sp)
                val dk = egoLl?.let { Chargers.distKm(it.first, it.second, s.lat, s.lon) }
                Text((if (dk != null) String.format(Locale.US, "≈%.1f km · ", dk).replace('.', ',') else "") +
                    "⚡ ${s.maxKw} kW · ${s.ports} · ${s.hours}", color = Color(0xFF8E8E93), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Dẫn đường", color = Color.White, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF0A84FF))
                            .clickable { nav.start(s.lat, s.lon, s.name); follow = true; zoom = 16.0; sel = null }
                            .padding(horizontal = 18.dp, vertical = 10.dp))
                    Text("Đóng", color = Color.White, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF3A3A3C))
                            .clickable { sel = null }.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }

        // Đang dẫn đường: tốc độ (dưới trái) + thanh dưới (còn lại, điểm đến, Thoát)
        if (navOn) {
            val spd = runner.gps.speedKmh()
            Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 84.dp).size(76.dp).clip(CircleShape).background(Color(0xF20C0E10)),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(spd?.let { "${it.toInt()}" } ?: "--", color = Color.White, fontSize = 26.sp)
                    Text("km/h", color = Color(0xFFB0B8C0), fontSize = 11.sp)
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xF20C0E10)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(nav.remainText.ifEmpty { nav.note }, color = Color.White, fontSize = 20.sp)
                    Text("Tới " + nav.destName, color = Color(0xFFB0B8C0), fontSize = 13.sp, maxLines = 1)
                }
                Text("Thoát", color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFFF5252))
                        .clickable { nav.stop() }.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }

        // Về trang HUD (cạnh nút ⚙ do trang chính vẽ)
        Box(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 62.dp).size(44.dp)
                .clip(CircleShape).background(Color(0xCC000000)).clickable { onHud() },
            contentAlignment = Alignment.Center
        ) { Text("HUD", color = Color.White, fontSize = 12.sp) }
    }
}

@Composable
private fun MapBtn(text: String, on: Boolean, fs: Int, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(text, color = if (on) Color(0xFF4FC3F7) else Color.White, fontSize = fs.sp, lineHeight = (fs + 2).sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
