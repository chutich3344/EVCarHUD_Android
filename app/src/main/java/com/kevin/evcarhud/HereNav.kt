package com.kevin.evcarhud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Chỉ đường bằng HERE (qua máy chủ riêng giữ key, xem HereApi):
//  - Tìm điểm đến: Autosuggest, trả luôn toạ độ (1 lượt gọi cho mỗi lần tìm)
//  - Tính đường: Routing v8, đường đi dạng Flexible Polyline, hướng rẽ (turnByTurnActions),
//    kèm tên đường + giới hạn tốc độ của từng đoạn trên cả tuyến (spans) -> suốt chuyến không cần tra tên đường / tốc độ nữa
//  - Bám tuyến theo GPS điện thoại, tự tìm lại đường khi đi lệch > 45 m quá 5 s (tối đa 30 s một lần)
// Mỗi lần tìm / tính đường là một lượt gọi API; bám tuyến không tốn lượt nào.
class HereNav(private val gps: LocationFallback) {
    class Place(val refId: String, val name: String, val address: String, val distKm: Double)
    class Cue(val text: String, val near: Boolean)
    // Một phương án tuyến để chọn trước khi đi (trang Bản đồ): giây chạy (có tính kẹt xe), giây trễ do kẹt xe
    class RouteOpt(val lat: DoubleArray, val lon: DoubleArray, val totalM: Double, val totalS: Double, val delayS: Double)

    // sign: mã hướng rẽ kiểu GraphHopper (giữ như bản VietMap để dùng lại câu đọc và mã Waze cho TurnGuide)
    private class Step(val sign: Int, val exit: Int, val street: String, val start: Int)
    private class Route(val lat: DoubleArray, val lon: DoubleArray, val cum: DoubleArray, val steps: List<Step>,
                        val totalM: Double, val totalS: Double,
                        val spanAt: IntArray, val spanName: Array<String>, val spanKmh: IntArray)

    @Volatile var vehicle = "car"            // car / scooter (xe máy)
    @Volatile var showMini = true
    @Volatile var active = false
        private set
    @Volatile var destName = ""
        private set
    @Volatile var note = ""
        private set
    @Volatile var calls = 0
        private set
    @Volatile var turn: TurnInfo? = null      // hướng rẽ tiếp theo cho TurnGuide (mã kiểu Waze)
        private set
    @Volatile var remainText = ""
        private set

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var route: Route? = null
    @Volatile private var busy = false
    private var destLat = 0.0
    private var destLon = 0.0
    private var fails = 0
    private var reroutes = 0
    private val coords = ConcurrentHashMap<String, Pair<Double, Double>>()   // toạ độ của kết quả tìm kiếm

    // Trạng thái bám tuyến (chỉ luồng hiển thị dùng)
    private var cur: Route? = null
    private var idx = -1
    private var along = 0.0
    private var snapLat = 0.0
    private var snapLon = 0.0
    private var heading = 0.0               // rad, theo chiều kim đồng hồ từ hướng bắc
    private var nextStart = -1
    private var lastUpd = 0L
    private var lastReq = -1_000_000L
    private var offSince = -1L
    private var arrivedAt = -1L
    private var cueStep = -1
    private var cueStage = 0
    @Volatile private var cue: Cue? = null

    fun consumeCue(): Cue? { val c = cue; cue = null; return c }

    // ---------- Tìm điểm đến (gọi ngoài luồng UI) ----------
    fun search(text: String): List<Place> {
        if (text.isBlank()) return emptyList()
        // Autosuggest bắt buộc có vị trí tham chiếu: chưa có GPS thì lấy giữa Việt Nam
        val p = gps.anyLatLon() ?: (16.0 to 106.0)
        try {
            val u = String.format(Locale.US, "%s?at=%.6f,%.6f&q=%s&lang=vi-VN&in=countryCode:VNM&limit=8",
                SUGGEST, p.first, p.second, enc(text.trim()))
            val (code, body) = get(u)
            if (code != 200) { note = "Tìm điểm đến: ${httpNote(code)}"; return emptyList() }
            val arr = JSONObject(body).optJSONArray("items") ?: JSONArray()
            val out = ArrayList<Place>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pos = o.optJSONObject("position") ?: continue      // bỏ gợi ý dạng "từ khoá" không có toạ độ
                val lat = pos.optDouble("lat", Double.NaN)
                val lng = pos.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                val id = o.optString("id", "").ifEmpty { "$lat,$lng" }
                coords[id] = lat to lng
                val title = o.optString("title", "")
                val addr = o.optJSONObject("address")?.optString("label", "").orEmpty()
                val dist = o.optDouble("distance", Double.NaN) / 1000.0
                out.add(Place(id, title, if (addr == title) "" else addr, dist))
            }
            note = if (out.isEmpty()) "Không tìm thấy \"$text\"" else ""
            return out
        } catch (e: Throwable) {
            note = "Tìm điểm đến: lỗi ${e.javaClass.simpleName}"
            Log.w(TAG, "autosuggest", e)
            return emptyList()
        }
    }

    // Toạ độ của một kết quả tìm kiếm (Autosuggest đã trả sẵn, không tốn thêm lượt gọi)
    fun place(refId: String): Pair<Double, Double>? = coords[refId].also { if (it == null) note = "Không có toạ độ của điểm này" }

    // ---------- Xem trước 2–3 tuyến (gọi ngoài luồng UI, 1 lượt gọi HERE) ----------
    // Trả các phương án nhanh nhất trước; chọn xong gọi startOpt(i) để đi theo đúng tuyến đó, không gọi lại HERE
    @Volatile private var opts: List<Route> = emptyList()

    fun previewRoutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<RouteOpt> {
        opts = emptyList()
        for (withSpans in listOf(true, false)) {
            try {
                val u = String.format(Locale.US,
                    "%s?transportMode=%s&origin=%.6f,%.6f&destination=%.6f,%.6f&alternatives=2&return=polyline,summary,turnByTurnActions%s&lang=vi-VN",
                    ROUTE, vehicle, fromLat, fromLon, toLat, toLon, if (withSpans) "&spans=names,maxSpeed" else "")
                val (code, body) = get(u)
                if (code == 400 && withSpans) continue
                if (code != 200) { note = "Tính đường: ${httpNote(code)}"; return emptyList() }
                val arr = JSONObject(body).optJSONArray("routes") ?: JSONArray()
                val rs = ArrayList<Route>(); val out = ArrayList<RouteOpt>()
                for (i in 0 until min(arr.length(), 3)) {
                    val secs = arr.optJSONObject(i)?.optJSONArray("sections") ?: continue
                    val r = parseRoute(secs) ?: continue
                    var base = 0.0
                    for (j in 0 until secs.length()) base += secs.optJSONObject(j)?.optJSONObject("summary")?.optDouble("baseDuration", 0.0) ?: 0.0
                    rs.add(r); out.add(RouteOpt(r.lat, r.lon, r.totalM, r.totalS, if (base > 0) max(0.0, r.totalS - base) else 0.0))
                }
                // HERE trả tuyến chính trước; xếp lại theo thời gian để phương án 1 luôn nhanh nhất
                val order = out.indices.sortedBy { out[it].totalS }
                opts = order.map { rs[it] }
                note = if (out.isEmpty()) "Không tìm được đường" else ""
                return order.map { out[it] }
            } catch (e: Throwable) {
                note = "Tính đường: lỗi ${e.javaClass.simpleName}"
                Log.w(TAG, "alternatives", e)
                return emptyList()
            }
        }
        return emptyList()
    }

    // Đi theo phương án i vừa xem trước. Lệch tuyến thì vẫn tự tính lại như thường
    fun startOpt(i: Int, lat: Double, lon: Double, name: String) {
        val r = opts.getOrNull(i)
        start(lat, lon, name)
        if (r != null) {
            route = r
            note = "Tới $name: ${fmtKm(r.totalM)}, ${(r.totalS / 60).roundToInt()} phút"
        }
    }

    // ---------- Bắt đầu / dừng ----------
    fun start(lat: Double, lon: Double, name: String) {
        if (simulating) { simulating = false; gps.clearMock() }
        destLat = lat; destLon = lon; destName = name
        route = null
        fails = 0; reroutes = 0
        lastReq = -1_000_000L
        arrivedAt = -1L
        turn = null
        remainText = ""
        note = "Đang tính đường tới $name…"
        active = true
    }

    fun stop() {
        if (simulating) { simulating = false; gps.clearMock() }
        active = false
        route = null
        turn = null
        remainText = ""
        note = "Đã dừng chỉ đường"
    }

    private fun requestRoute(now: Long, p: Pair<Double, Double>) {
        if (busy) return
        busy = true
        lastReq = now
        val veh = vehicle
        exec.execute {
            try { fetchRoute(p.first, p.second, veh) } finally { busy = false }
        }
    }
    private fun fetchRoute(lat: Double, lon: Double, veh: String) {
        var err = ""
        // Lần đầu xin cả tên đường + giới hạn tốc độ theo đoạn (spans); bị từ chối (HTTP 400) thì xin lại không có spans
        for (withSpans in listOf(true, false)) {
            try {
                val u = String.format(Locale.US,
                    "%s?transportMode=%s&origin=%.6f,%.6f&destination=%.6f,%.6f&return=polyline,summary,turnByTurnActions%s&lang=vi-VN",
                    ROUTE, veh, lat, lon, destLat, destLon, if (withSpans) "&spans=names,maxSpeed" else "")
                val (code, body) = get(u)
                if (code == 400 && withSpans) { err = httpNote(code); continue }
                if (code != 200) { err = httpNote(code); break }
                val o = JSONObject(body)
                val secs = o.optJSONArray("routes")?.optJSONObject(0)?.optJSONArray("sections")
                if (secs == null || secs.length() == 0) {
                    err = o.optJSONArray("notices")?.optJSONObject(0)?.optString("title", "").orEmpty().ifEmpty { "không có đường" }
                    break
                }
                val r = parseRoute(secs)
                if (r == null) { err = "đường đi trống"; break }
                if (!active) return
                route = r
                fails = 0
                note = "Tới $destName: ${fmtKm(r.totalM)}, ${(r.totalS / 60).roundToInt()} phút"
                return
            } catch (e: Throwable) {
                err = "lỗi ${e.javaClass.simpleName}"
                Log.w(TAG, "route", e)
                break
            }
        }
        fails++
        note = "Tính đường: $err"
    }

    // Ghép các section (thường chỉ 1) thành một tuyến; offset của hướng rẽ / spans là chỉ số điểm trong polyline của section
    private fun parseRoute(secs: JSONArray): Route? {
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        val steps = ArrayList<Step>()
        val spAt = ArrayList<Int>(); val spName = ArrayList<String>(); val spKmh = ArrayList<Int>()
        var timeS = 0.0
        var lastName = ""
        var lastKmh = 0
        for (si in 0 until secs.length()) {
            val sec = secs.optJSONObject(si) ?: continue
            val (sLat, sLon) = decodeFlex(sec.optString("polyline", ""))
            if (sLat.isEmpty()) continue
            val base = la.size
            for (i in sLat.indices) { la.add(sLat[i]); lo.add(sLon[i]) }
            timeS += sec.optJSONObject("summary")?.optDouble("duration", 0.0) ?: 0.0
            sec.optJSONArray("turnByTurnActions")?.let { acts ->
                for (i in 0 until acts.length()) {
                    val a = acts.optJSONObject(i) ?: continue
                    val act = a.optString("action", "")
                    var exit = a.optInt("exit", 0)
                    if (act == "roundaboutEnter" && exit == 0) {
                        // số lối ra nằm ở hành động roundaboutExit ngay sau
                        for (j in i + 1 until acts.length()) {
                            val b = acts.optJSONObject(j) ?: continue
                            if (b.optString("action") == "roundaboutExit") { exit = b.optInt("exit", 0); break }
                        }
                    }
                    val sign = signOf(act, a.optString("direction", ""), a.optString("severity", ""))
                    steps.add(Step(sign, exit, roadOf(a.optJSONObject("nextRoad")), base + a.optInt("offset", 0)))
                }
            }
            sec.optJSONArray("spans")?.let { sp ->
                for (i in 0 until sp.length()) {
                    val s = sp.optJSONObject(i) ?: continue
                    // maxSpeed (thay speedLimit đã bị HERE bỏ dần) tính bằng m/s, làm tròn về bội số 5 km/h;
                    // đoạn không có dữ liệu hoặc "unlimited" -> 0
                    val ms = s.optDouble("maxSpeed", s.optDouble("speedLimit", Double.NaN))
                    val kmh = if (ms.isNaN() || ms <= 0.0) 0 else (ms * 3.6 / 5.0).roundToInt() * 5
                    val nm = s.optJSONArray("names")?.let { firstName(it) } ?: ""
                    if (spAt.isNotEmpty() && nm == lastName && kmh == lastKmh) continue
                    spAt.add(base + s.optInt("offset", 0)); spName.add(nm); spKmh.add(kmh)
                    lastName = nm; lastKmh = kmh
                }
            }
        }
        val n = la.size
        if (n < 2) return null
        val lat = la.toDoubleArray(); val lon = lo.toDoubleArray()
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + meters(lat[i - 1], lon[i - 1], lat[i], lon[i])
        for (k in steps.indices) if (steps[k].start >= n) steps[k] = Step(steps[k].sign, steps[k].exit, steps[k].street, n - 1)
        // Ô hướng rẽ + câu đọc phải khớp đường vẽ trên bản đồ: chỉnh độ gắt / phía rẽ theo góc thật của tuyến tại chỗ rẽ
        for (k in steps.indices) {
            val st = steps[k]
            val g = geoSign(lat, lon, cum, st.start, st.sign)
            if (g != st.sign) steps[k] = Step(g, st.exit, st.street, st.start)
        }
        return Route(lat, lon, cum, steps, cum[n - 1], if (timeS > 0) timeS else cum[n - 1] / 8.0,
            spAt.toIntArray(), spName.toTypedArray(), spKmh.toIntArray())
    }

    // ---------- Tên đường + giới hạn tốc độ tại vị trí hiện tại trên tuyến (luồng hiển thị) ----------
    // true khi tuyến đang chạy có dữ liệu spans: MainActivity lấy tên đường / tốc độ ở đây thay vì tra HereGeo
    val hasSpans: Boolean get() = active && (cur?.spanAt?.isNotEmpty() == true)

    private fun spanIdx(): Int {
        val r = cur ?: return -1
        if (idx < 0 || r.spanAt.isEmpty()) return -1
        var k = -1
        for (i in r.spanAt.indices) { if (r.spanAt[i] <= idx) k = i else break }
        return k
    }
    fun limitKmh(): Int { val r = cur ?: return 0; val k = spanIdx(); return if (k < 0) 0 else r.spanKmh[k] }
    fun roadName(): String { val r = cur ?: return ""; val k = spanIdx(); return if (k < 0) "" else r.spanName[k] }

    private fun firstName(arr: JSONArray): String {
        var any = ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val v = o.optString("value", "").trim()
            if (v.isEmpty()) continue
            if (o.optString("language", "").startsWith("vi")) return v
            if (any.isEmpty()) any = v
        }
        return any
    }

    private fun roadOf(road: JSONObject?): String {
        if (road == null) return ""
        road.optJSONArray("name")?.let { firstName(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return road.optJSONArray("number")?.let { firstName(it) } ?: ""
    }

    // Hành động HERE (action + direction + severity) -> mã kiểu GraphHopper
    private fun signOf(action: String, dir: String, sev: String): Int {
        val left = dir == "left"
        val right = dir == "right"
        return when (action) {
            "arrive" -> 4
            "uTurn" -> -98
            "roundaboutEnter" -> 6
            "turn" -> when {
                left || dir == "slightlyLeft" || dir == "sharplyLeft" -> when {
                    dir == "slightlyLeft" || sev == "light" -> -1
                    dir == "sharplyLeft" || sev == "heavy" -> -3
                    else -> -2
                }
                right || dir == "slightlyRight" || dir == "sharplyRight" -> when {
                    dir == "slightlyRight" || sev == "light" -> 1
                    dir == "sharplyRight" || sev == "heavy" -> 3
                    else -> 2
                }
                else -> 0
            }
            "keep", "ramp", "exit", "enterHighway" -> when {
                dir.endsWith("eft") -> -7
                dir.endsWith("ight") -> 7
                else -> 0
            }
            else -> 0   // depart, continue, continueHighway, roundaboutPass, roundaboutExit
        }
    }

    // ---------- Bám tuyến (luồng hiển thị, mỗi khung) ----------
    fun update(speedKmh: Float) {
        if (!active) { turn = null; return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpd < UPDATE_MS) return
        lastUpd = now
        if (arrivedAt > 0 && now - arrivedAt > ARRIVE_HOLD_MS) { stop(); note = "Đã đến $destName"; return }
        if (simulating) simStep(now)
        val p = gps.lastLatLon()
        val r = route
        if (r == null) {
            // Tính đường chỉ cần điểm đi gần đúng: chưa có GPS (trong nhà) thì lấy vị trí theo mạng
            val p0 = p ?: gps.anyLatLon()
            if (p0 == null) note = "Chờ tín hiệu GPS để tính đường…"
            else if (fails >= MAX_FAILS) { active = false; turn = null }
            else if (!busy && now - lastReq > RETRY_MS) requestRoute(now, p0)
            return
        }
        if (p == null) return
        if (r !== cur) { cur = r; idx = -1; cueStep = -1; offSince = -1L }

        // Chiếu vị trí GPS lên tuyến (đoạn gần nhất quanh vị trí cũ)
        val n = r.lat.size
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(p.first))
        val from = if (idx < 0) 0 else max(0, idx - 3)
        val to = if (idx < 0) n - 2 else min(n - 2, idx + 300)
        var bi = from
        var bt = 0.0
        var bd = Double.MAX_VALUE
        for (i in from..to) {
            val ax = (r.lon[i] - p.second) * mLon; val ay = (r.lat[i] - p.first) * mLat
            val bx = (r.lon[i + 1] - p.second) * mLon; val by = (r.lat[i + 1] - p.first) * mLat
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val qx = ax + dx * t; val qy = ay + dy * t
            val d = sqrt(qx * qx + qy * qy)
            if (d < bd) { bd = d; bi = i; bt = t }
        }
        idx = bi
        along = r.cum[bi] + (r.cum[bi + 1] - r.cum[bi]) * bt
        snapLat = r.lat[bi] + (r.lat[bi + 1] - r.lat[bi]) * bt
        snapLon = r.lon[bi] + (r.lon[bi + 1] - r.lon[bi]) * bt
        heading = atan2((r.lon[bi + 1] - r.lon[bi]) * mLon, (r.lat[bi + 1] - r.lat[bi]) * mLat)

        // Lệch tuyến: tìm lại đường
        if (bd > OFF_M && speedKmh > 5f) {
            if (offSince < 0L) offSince = now
            if (now - offSince > OFF_MS && now - lastReq > REROUTE_GAP_MS && reroutes < MAX_REROUTES && !busy) {
                reroutes++
                cue = Cue("Đang tìm lại đường", false)
                note = "Lệch tuyến, đang tìm lại đường…"
                requestRoute(now, p)
            }
        } else offSince = -1L

        val remain = max(0.0, r.totalM - along)
        val remainS = r.totalS * remain / max(1.0, r.totalM)
        remainText = "${fmtKm(remain)} · ${max(1, (remainS / 60).roundToInt())} phút"

        // Đến nơi
        if (remain < ARRIVE_M) {
            turn = TurnInfo(17, 0, 0, destName)
            if (arrivedAt < 0L) { arrivedAt = now; cue = Cue("Đã đến nơi", true) }
            return
        }

        // Hướng rẽ kế tiếp (bỏ qua "đi tiếp" chỉ vì đổi tên đường)
        var next: Step? = null
        var ni = -1
        for ((i, s) in r.steps.withIndex()) {
            if (s.sign == 0 || s.sign == 5 || s.sign == -6) continue
            if (r.cum[s.start] > along + 2.0) { next = s; ni = i; break }
        }
        val dist = if (next == null) remain else r.cum[next.start] - along
        val sign = next?.sign ?: 4
        val exit = next?.exit ?: 0
        val street = next?.street ?: destName
        nextStart = next?.start ?: (n - 1)
        turn = TurnInfo(wazeCode(sign), dist.roundToInt(), exit, street)

        // Câu đọc: một lần khi còn ~300 m (800 m nếu chạy nhanh), một lần ngay trước chỗ rẽ
        val v = speedKmh / 3.6
        val farM = if (speedKmh > 60f) 800.0 else 300.0
        val nearM = max(40.0, v * 6.0)
        if (ni != cueStep) { cueStep = ni; cueStage = 0 }
        if (cueStage < 1 && dist <= farM && dist > nearM + 60.0) {
            cueStage = 1
            cue = Cue(if (sign == 4) "Còn ${sayDist(dist)} đến nơi" else "Còn ${sayDist(dist)}, ${action(sign, exit)}${into(street)}", false)
        }
        if (cueStage < 2 && dist <= nearM && sign != 4) {
            cueStage = 2
            cue = Cue(action(sign, exit).replaceFirstChar { it.uppercase() } + into(street), true)
        }
    }

    // ---------- Ô bản đồ bên trái HUD: dòng trên là quãng đường + thời gian còn lại, dưới là tuyến (hướng tuyến quay lên) ----------
    private val mapBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59002846; style = Paint.Style.FILL }
    private val pastP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x554FC3F7; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val turnP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); style = Paint.Style.FILL }
    private val egoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8F6FF.toInt(); textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val panel = RectF()
    private val mapR = RectF()

    // ---------- Nền bản đồ: ô ảnh HERE Raster Tile v3 kiểu lite.night, nhuộm xanh, quay theo hướng tuyến ----------
    // Mỗi ô 256 px tải một lần rồi giữ trong bộ nhớ (tối đa TILE_MAX ô); mỗi ô tải về tính một lượt gọi API.
    // 4 luồng tải song song (1 luồng làm bản đồ trang 2 tải chậm, phóng to / thu nhỏ phải chờ vài giây)
    private val tileExec = Executors.newFixedThreadPool(4)
    private val tiles = object : LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > TILE_MAX
    }
    private val tileWait = HashSet<Long>()
    private var tileFailAt = 0L
    private var tileFails = 0
    private val trafficAt = HashMap<Long, Long>()   // lúc tải ô giao thông, quá 5 phút thì tải lại
    private val tileP = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // độ sáng -> xanh ngọc: nền tối gần đen-xanh, đường sáng thành xanh nhạt
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.05f, 0.10f, 0.02f, 0f, 0f,
            0.20f, 0.45f, 0.10f, 0f, 8f,
            0.25f, 0.55f, 0.12f, 0f, 22f,
            0f, 0f, 0f, 0.85f, 0f)))
    }
    private val tileDst = RectF()

    private fun tileKey(z: Int, x: Int, y: Int, st: Int) = (st.toLong() shl 58) or (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

    // Trang Bản đồ (toàn màn hình): ô kiểu explore.night hoặc ảnh vệ tinh, không nhuộm. Mỗi ô mới tải = 1 lượt gọi HERE
    fun mapTile(z: Int, x: Int, y: Int, sat: Boolean): Bitmap? = tile(z, x, y, if (sat) 2 else 1)

    // Lớp giao thông (HERE Traffic flow, ảnh trong suốt phủ lên bản đồ). Mỗi ô cũng tính một lượt gọi API
    fun trafficTile(z: Int, x: Int, y: Int): Bitmap? = tile(z, x, y, 3)

    // Chỉ lấy ô đã có trong bộ nhớ, không tải: vẽ tạm ô mức thấp hơn (phóng to) trong lúc ô đúng mức đang tải
    fun cachedMapTile(z: Int, x: Int, y: Int, sat: Boolean): Bitmap? =
        synchronized(tiles) { tiles[tileKey(z, x, y, if (sat) 2 else 1)] }

    // style: 0 = lite.night (bản đồ nhỏ trên HUD), 1 = explore.night, 2 = satellite.day, 3 = giao thông
    // Tiết kiệm lượt gọi HERE:
    // - ô trang Bản đồ / giao thông xin cỡ 512 px (1 lượt = diện tích bằng 4 ô 256 px)
    // - ô bản đồ / vệ tinh lưu xuống bộ nhớ máy (diskDir) 30 ngày: mở lại app, đi lại đường cũ không tốn lượt
    // - ô lỗi không xin lại trong 60 s; HERE từ chối cả lớp (HTTP 4xx) thì ngừng lớp đó 10 phút
    @Volatile var diskDir: File? = null
    @Volatile var tileNote = ""
        private set
    private val failAt = HashMap<Long, Long>()
    private val styleBadAt = LongArray(4) { -1_000_000L }
    private var diskWrites = 0

    private fun tileFile(z: Int, x: Int, y: Int, style: Int): File? =
        diskDir?.let { File(it, "s${style}_${z}_${x}_$y") }

    private fun tile(z: Int, x: Int, y: Int, style: Int = 0): Bitmap? {
        val key = tileKey(z, x, y, style)
        synchronized(tiles) {
            val have = tiles[key]
            val now = SystemClock.elapsedRealtime()
            if (have != null && (style != 3 || now - (trafficAt[key] ?: 0L) < 300_000L)) return have
            if (key in tileWait || tileWait.size >= 24) return have
            if (tileFails >= 3 && now - tileFailAt < 5_000L) return have
            if (now - (failAt[key] ?: -1_000_000L) < 60_000L) return have
            if (now - styleBadAt[style] < 600_000L) return have
            tileWait.add(key)
            tileExec.execute {
                var bmp: Bitmap? = null
                var bad = 0
                val opt = BitmapFactory.Options().apply { if (style == 1 || style == 2) inPreferredConfig = Bitmap.Config.RGB_565 }
                val f = if (style == 3) null else tileFile(z, x, y, style)
                try {
                    // 1) bộ nhớ máy (không tốn lượt gọi)
                    if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_DAYS * 86_400_000L)
                        bmp = BitmapFactory.decodeFile(f.absolutePath, opt)
                    // 2) tải từ HERE
                    if (bmp == null) {
                        val sz = if (style == 0) "" else "&size=512"
                        val url = if (style == 3) String.format(Locale.US, "%s/%d/%d/%d/png?size=512", TRAFFIC_TILE, z, x, y)
                        else {
                            val fmt = when (style) { 1 -> "png8?style=explore.night"; 2 -> "jpeg?style=satellite.day"; else -> "png8?style=lite.night" }
                            String.format(Locale.US, "%s/%d/%d/%d/%s%s", TILE, z, x, y, fmt, sz)
                        }
                        val c = HereApi.open(url)
                        // không gọi disconnect(): giữ kết nối để ô sau dùng lại, khỏi bắt tay TLS mỗi ô
                        c.connectTimeout = 6000
                        c.readTimeout = 8000
                        calls++
                        val code = c.responseCode
                        if (code == 200) {
                            val bytes = c.inputStream.use { it.readBytes() }
                            // ô trang bản đồ không cần độ trong suốt: RGB_565 tốn một nửa bộ nhớ
                            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                            if (bmp != null && f != null) saveTile(f, bytes)
                        } else {
                            Log.w("HereNav", "tile HTTP $code (lớp $style)")
                            c.errorStream?.close()
                            if (code in 400..499 && code != 429) bad = code
                        }
                    }
                } catch (e: Exception) {
                    Log.w("HereNav", "tile lỗi: ${e.message}")
                }
                synchronized(tiles) {
                    tileWait.remove(key)
                    val t = SystemClock.elapsedRealtime()
                    if (bmp != null) {
                        tiles[key] = bmp!!; tileFails = 0
                        if (style == 3) trafficAt[key] = t
                    } else {
                        tileFails++; tileFailAt = t
                        failAt[key] = t
                        if (failAt.size > 500) failAt.clear()
                        if (bad != 0) {
                            styleBadAt[style] = t
                            val nm = when (style) { 1 -> "Bản đồ"; 2 -> "Vệ tinh"; 3 -> "Giao thông"; else -> "Bản đồ nhỏ" }
                            tileNote = "HERE từ chối lớp $nm (HTTP $bad), tạm ngừng tải lớp này 10 phút"
                        }
                    }
                }
            }
            return have
        }
    }

    private fun saveTile(f: File, bytes: ByteArray) {
        try {
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            // khoảng 300 lần ghi thì dọn: giữ tối đa DISK_MAX byte, xoá ô cũ nhất trước
            if (++diskWrites % 300 == 1) {
                val all = f.parentFile?.listFiles()?.sortedByDescending { it.lastModified() } ?: return
                var sum = 0L
                for (x in all) { sum += x.length(); if (sum > DISK_MAX) x.delete() }
            }
        } catch (e: Exception) {
            Log.w("HereNav", "lưu ô: ${e.message}")
        }
    }

    // Vẽ các ô quanh vị trí xe trong hệ toạ độ đã quay (gốc tại xe, trục y hướng nam), s = px màn hình / mét
    private fun drawTiles(canvas: Canvas, s: Double, radiusPx: Float, rangeM: Double) {
        val z = if (rangeM > 600.0) 15 else 16
        val world = 256.0 * (1 shl z)
        val latR = Math.toRadians(snapLat)
        val gx = (snapLon + 180.0) / 360.0 * world
        val gy = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / Math.PI) / 2.0 * world
        val f = s * 156_543.034 * cos(latR) / (1 shl z)       // px màn hình cho mỗi px ô ở mức z
        val span = radiusPx / f
        val tx0 = floor((gx - span) / 256.0).toInt(); val tx1 = floor((gx + span) / 256.0).toInt()
        val ty0 = floor((gy - span) / 256.0).toInt(); val ty1 = floor((gy + span) / 256.0).toInt()
        val n = 1 shl z
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            if (ty < 0 || ty >= n) continue
            val b = tile(z, ((tx % n) + n) % n, ty) ?: continue
            val l = ((tx * 256.0 - gx) * f).toFloat(); val t = ((ty * 256.0 - gy) * f).toFloat()
            val sz = (256.0 * f).toFloat()
            tileDst.set(l, t, l + sz + 0.5f, t + sz + 0.5f)
            canvas.drawBitmap(b, null, tileDst, tileP)
        }
    }

    // Cho trang Bản đồ: tuyến đang đi, vị trí đã khớp vào tuyến, hướng đi (rad, từ bắc theo chiều kim đồng hồ)
    // Tuyến mới nhất (không chờ bám GPS): trang Bản đồ vẽ được cả khi chưa có GPS hoặc camera chưa chạy
    fun routeLat(): DoubleArray? = if (active) route?.lat else null
    fun routeLon(): DoubleArray? = if (active) route?.lon else null
    fun egoOnRoute(): Triple<Double, Double, Double>? = if (active && cur != null && idx >= 0) Triple(snapLat, snapLon, heading) else null

    fun drawPanel(canvas: Canvas, k: Float, x: Float, y: Float, w: Float, h: Float, speedKmh: Float) {
        if (!active || !showMini) return
        val r = cur ?: return
        if (idx < 0) return
        panel.set(x, y, x + w, y + h)
        TurnGuide.glass(canvas, panel, k)
        if (remainText.isNotEmpty()) {
            textP.textSize = 26f * k
            canvas.drawText(if (simulating) "$remainText · thử" else remainText, x + 18f * k, y + 40f * k, textP)
        }
        mapR.set(x + 10f * k, y + 58f * k, x + w - 10f * k, y + h - 10f * k)
        val cx = mapR.centerX()
        val egoY = mapR.top + mapR.height() * 0.82f
        val rangeM = when { speedKmh > 60f -> 900.0; speedKmh > 30f -> 520.0; else -> 330.0 }
        val s = mapR.height() / rangeM
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(snapLat))
        val ch = cos(heading); val sh = sin(heading)
        fun sx(i: Int): Float {
            val e = (r.lon[i] - snapLon) * mLon; val nn = (r.lat[i] - snapLat) * mLat
            return cx + ((e * ch - nn * sh) * s).toFloat()
        }
        fun sy(i: Int): Float {
            val e = (r.lon[i] - snapLon) * mLon; val nn = (r.lat[i] - snapLat) * mLat
            return egoY - ((e * sh + nn * ch) * s).toFloat()
        }
        canvas.save()
        canvas.clipRect(mapR)
        canvas.drawRoundRect(mapR, 12f * k, 12f * k, mapBg)
        canvas.save()
        canvas.translate(cx, egoY)
        canvas.rotate(-Math.toDegrees(heading).toFloat())
        drawTiles(canvas, s.toDouble(), hypot(mapR.width() / 2f, egoY - mapR.top) + 8f * k, rangeM)
        canvas.restore()
        val n = r.lat.size
        // Đoạn đã đi (mờ)
        path.reset()
        var i0 = idx
        while (i0 > 0 && along - r.cum[i0] < rangeM * 0.5) i0--
        path.moveTo(sx(i0), sy(i0))
        for (i in i0 + 1..idx) path.lineTo(sx(i), sy(i))
        path.lineTo(cx, egoY)
        pastP.strokeWidth = 6f * k
        canvas.drawPath(path, pastP)
        // Đoạn sắp đi: quầng sáng (nét rộng trong mờ) + nét chính
        path.reset()
        path.moveTo(cx, egoY)
        var i = idx + 1
        while (i < n && r.cum[i] - along < rangeM * 1.6) { path.lineTo(sx(i), sy(i)); i++ }
        if (i < n) path.lineTo(sx(i), sy(i))
        glowP.strokeWidth = 18f * k
        canvas.drawPath(path, glowP)
        routeP.strokeWidth = 8f * k
        canvas.drawPath(path, routeP)
        // Chỗ rẽ kế tiếp
        if (nextStart in 0 until n) canvas.drawCircle(sx(nextStart), sy(nextStart), 7f * k, turnP)
        // Xe mình
        path.reset()
        path.moveTo(cx, egoY - 18f * k)
        path.lineTo(cx + 13f * k, egoY + 13f * k)
        path.lineTo(cx, egoY + 6f * k)
        path.lineTo(cx - 13f * k, egoY + 13f * k)
        path.close()
        canvas.drawPath(path, egoP)
        canvas.restore()
    }

    // ---------- Chạy thử tuyến: xe ảo chạy dọc tuyến HERE thật từ A tới B, thay GPS điện thoại ----------
    // Tốc độ ảo: giới hạn của đoạn đường (spans), không có thì 40 km/h; chậm còn 25 km/h khi còn < 60 m tới chỗ rẽ.
    @Volatile var simulating = false
        private set
    private var simRoute: Route? = null
    private var simAlong = 0.0
    private var simLast = 0L

    fun simulate(aLat: Double, aLon: Double, bLat: Double, bLon: Double, name: String) {
        start(bLat, bLon, name)
        gps.setMock(aLat, aLon, 0f)      // điểm A làm vị trí xuất phát khi tính đường
        simulating = true
        simRoute = null
        simAlong = 0.0
        simLast = 0L
        note = "Chạy thử: đang tính đường tới $name…"
    }

    private fun simStep(now: Long) {
        val r = route ?: return
        if (r !== simRoute) { simRoute = r; simAlong = 0.0; simLast = now }
        val dt = ((now - simLast) / 1000.0).coerceIn(0.0, 1.0)
        simLast = now
        var kmh = limitKmh().takeIf { it > 0 }?.toDouble() ?: SIM_KMH
        val t = turn
        if (t != null && t.turn != 17 && t.distanceM < 60) kmh = min(kmh, SIM_TURN_KMH)
        simAlong = min(r.totalM, simAlong + kmh / 3.6 * dt)
        val n = r.lat.size
        var i = 1
        while (i < n - 1 && r.cum[i] < simAlong) i++
        val seg = r.cum[i] - r.cum[i - 1]
        val f = if (seg > 0.0) ((simAlong - r.cum[i - 1]) / seg).coerceIn(0.0, 1.0) else 0.0
        val lat = r.lat[i - 1] + (r.lat[i] - r.lat[i - 1]) * f
        val lon = r.lon[i - 1] + (r.lon[i] - r.lon[i - 1]) * f
        gps.setMock(lat, lon, if (simAlong >= r.totalM) 0f else kmh.toFloat())
    }

    // ---------- Tiện ích ----------
    private fun get(url: String): Pair<Int, String> {
        val c = HereApi.open(url)
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            calls++
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpNote(code: Int) = when (code) {
        400 -> "yêu cầu không hợp lệ (HTTP 400)"
        401, 403 -> "máy chủ từ chối (HTTP $code)"
        429 -> "gọi quá nhiều, thử lại sau (HTTP 429)"
        else -> "HERE lỗi HTTP $code"
    }

    private fun meters(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * 111_320.0
        val dx = (o2 - o1) * 111_320.0 * cos(Math.toRadians(a1))
        return sqrt(dx * dx + dy * dy)
    }

    // HERE Flexible Polyline: phiên bản, header (độ chính xác, chiều thứ 3), rồi các số chênh lệch lat, lng (, z)
    private fun decodeFlex(s: String): Pair<DoubleArray, DoubleArray> {
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        var i = 0
        fun uvar(): Long {
            var res = 0L
            var shift = 0
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("polyline cụt")
                val v = FLEX.indexOf(s[i++])
                if (v < 0) throw IllegalArgumentException("ký tự lạ trong polyline")
                res = res or ((v and 0x1F).toLong() shl shift)
                if ((v and 0x20) == 0) return res
                shift += 5
            }
        }
        fun svar(): Long { val u = uvar(); return if ((u and 1L) != 0L) (u shr 1).inv() else (u shr 1) }
        try {
            if (s.isEmpty() || uvar() != 1L) return DoubleArray(0) to DoubleArray(0)
            val hdr = uvar()
            val prec = (hdr and 15L).toInt()
            val third = ((hdr shr 4) and 7L).toInt()
            var f = 1.0
            repeat(prec) { f *= 10.0 }
            var lat = 0L
            var lng = 0L
            while (i < s.length) {
                lat += svar()
                lng += svar()
                if (third != 0) svar()
                la.add(lat / f)
                lo.add(lng / f)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "decodeFlex", e)
        }
        return la.toDoubleArray() to lo.toDoubleArray()
    }


    private fun fmtKm(m: Double): String =
        if (m >= 1000.0) String.format(Locale.US, "%.1f km", m / 1000.0).replace('.', ',') else "${(m / 10).roundToInt() * 10} m"

    private fun sayDist(m: Double): String =
        if (m >= 950.0) String.format(Locale.US, "%.1f ki lô mét", m / 1000.0).replace('.', ',').replace(",0 ", " ")
        else "${max(50, ((m / 50).roundToInt() * 50))} mét"

    private fun into(street: String) = if (street.isBlank()) "" else " vào $street"

    private fun action(sign: Int, exit: Int): String = when (sign) {
        -98, -8, 8 -> "quay đầu"
        -7 -> "đi về bên trái"
        7 -> "đi về bên phải"
        -3 -> "ngoặt trái"
        -2 -> "rẽ trái"
        -1 -> "chếch trái"
        1 -> "chếch phải"
        2 -> "rẽ phải"
        3 -> "ngoặt phải"
        6 -> if (exit > 0) "vào vòng xuyến, ra lối thứ $exit" else "vào vòng xuyến"
        4 -> "đến nơi"
        else -> "đi thẳng"
    }

    // Góc rẽ thật của tuyến: hướng 30 m trước chỗ rẽ so với 40 m sau (dương = rẽ phải). Chỉ chỉnh rẽ / giữ làn;
    // vòng xuyến, quay đầu, đến nơi giữ nguyên theo HERE
    private fun geoSign(lat: DoubleArray, lon: DoubleArray, cum: DoubleArray, at: Int, sign: Int): Int {
        if (sign == 0 || (sign !in -3..3 && sign != 7 && sign != -7)) return sign
        val n = lat.size
        if (at <= 0 || at >= n - 1) return sign
        var a = at
        while (a > 0 && cum[at] - cum[a] < 30.0) a--
        var b = at
        while (b < n - 1 && cum[b] - cum[at] < 40.0) b++
        if (a == at || b == at) return sign
        fun brg(i: Int, j: Int): Double {
            val dx = (lon[j] - lon[i]) * cos(Math.toRadians(lat[i]))
            val dy = lat[j] - lat[i]
            return Math.toDegrees(atan2(dx, dy))
        }
        var d = brg(at, b) - brg(a, at)
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        val ad = abs(d)
        val side = if (d > 0) 1 else -1
        return when {
            ad < 15.0 -> sign                                   // gần như thẳng: giữ theo HERE
            sign == 7 || sign == -7 -> if (ad >= 50.0) side * (if (ad < 120.0) 2 else 3) else side * 7
            else -> side * when { ad < 40.0 -> 1; ad < 120.0 -> 2; else -> 3 }
        }
    }

    // Mã hướng rẽ kiểu GraphHopper -> mã Waze mà TurnGuide đang vẽ
    private fun wazeCode(sign: Int): Int = when (sign) {
        -98, -8, 8 -> 8
        -7 -> 13
        7 -> 14
        -3 -> 6
        -2 -> 2
        -1 -> 4
        1 -> 5
        2 -> 3
        3 -> 7
        4 -> 17
        6 -> 10
        else -> 1
    }

    companion object {
        private const val TAG = "HereNav"
        private const val SUGGEST = "${HereApi.PROXY}/autosuggest.search/v1/autosuggest"
        private const val ROUTE = "${HereApi.PROXY}/router/v8/routes"
        private const val TILE = "${HereApi.PROXY}/maps/v3/base/mc"
        private const val TILE_MAX = 160
        private const val DISK_DAYS = 30L
        private const val DISK_MAX = 200L * 1024 * 1024
        private const val TRAFFIC_TILE = "${HereApi.PROXY}/traffic.maps/v3/flow/mc"
        private const val FLEX = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        const val UPDATE_MS = 300L
        const val OFF_M = 45.0
        const val OFF_MS = 5000L
        const val REROUTE_GAP_MS = 30_000L
        const val MAX_REROUTES = 60
        const val RETRY_MS = 15_000L
        const val MAX_FAILS = 3
        const val ARRIVE_M = 25.0
        const val ARRIVE_HOLD_MS = 15_000L
        const val SIM_KMH = 40.0
        const val SIM_TURN_KMH = 25.0
    }
}
