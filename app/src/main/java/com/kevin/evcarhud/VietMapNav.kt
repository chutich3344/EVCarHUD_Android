package com.kevin.evcarhud

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Chỉ đường bằng VietMap Maps API (cùng API key với tên đường, nhập trong Cài đặt):
//  - Tìm điểm đến: Autocomplete v4 (dự phòng v3) -> ref_id -> Place v4 (dự phòng v3) -> lat/lng
//  - Tính đường: Route v4 (dự phòng v3), đường đi dạng polyline 5, danh sách hướng rẽ (sign theo kiểu GraphHopper)
//  - Bám tuyến theo GPS điện thoại, tự tìm lại đường khi đi lệch > 45 m quá 5 s (tối đa 30 s một lần)
// Mỗi lần tìm / tính đường là một lượt gọi API (hạn 1500 lượt/ngày mỗi loại); bám tuyến không tốn lượt nào.
class VietMapNav(private val gps: LocationFallback) {
    class Place(val refId: String, val name: String, val address: String, val distKm: Double)
    class Cue(val text: String, val near: Boolean)

    private class Step(val sign: Int, val exit: Int, val street: String, val start: Int)

    // Tên đường sau khi rẽ: street_name, trống thì lấy phần sau "vào" / "onto" trong câu hướng dẫn
    private fun streetOf(name: String, text: String): String {
        if (name.isNotBlank()) return name
        val m = Regex("(?:vào|onto|lên|sang)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text.trim()) ?: return ""
        return m.groupValues[1].trim().trimEnd('.')
    }
    private class Route(val lat: DoubleArray, val lon: DoubleArray, val cum: DoubleArray, val steps: List<Step>,
                        val totalM: Double, val totalS: Double)

    @Volatile var apiKey = ""
    @Volatile var vehicle = "car"            // car / motorcycle
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
        val key = apiKey.trim()
        if (key.isEmpty()) { note = "Chưa có API key VietMap"; return emptyList() }
        if (text.isBlank()) return emptyList()
        val focus = gps.lastLatLon()?.let { String.format(Locale.US, "&focus=%.6f,%.6f", it.first, it.second) } ?: ""
        var err = ""
        for (v in VERSIONS) {
            try {
                val (code, body) = get("$BASE/autocomplete/$v?apikey=${enc(key)}&text=${enc(text.trim())}$focus")
                if (code != 200) { err = httpNote(code); continue }
                val arr = JSONArray(body.trim())
                val out = ArrayList<Place>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ref = o.optString("ref_id", "")
                    if (ref.isEmpty()) continue
                    val name = o.optString("name", "").ifBlank { o.optString("display", "") }
                    out.add(Place(ref, name, o.optString("address", ""), o.optDouble("distance", Double.NaN)))
                }
                note = if (out.isEmpty()) "Không tìm thấy \"$text\"" else ""
                return out
            } catch (e: Throwable) {
                err = "lỗi ${e.javaClass.simpleName}"
                Log.w(TAG, "autocomplete $v", e)
            }
        }
        note = "Tìm điểm đến: $err"
        return emptyList()
    }

    // Toạ độ của một kết quả tìm kiếm (gọi ngoài luồng UI)
    fun place(refId: String): Pair<Double, Double>? {
        val key = apiKey.trim()
        if (key.isEmpty()) return null
        var err = ""
        for (v in VERSIONS) {
            try {
                val (code, body) = get("$BASE/place/$v?apikey=${enc(key)}&refid=${enc(refId)}")
                if (code != 200) { err = httpNote(code); continue }
                val o = JSONObject(body.trim())
                val lat = o.optDouble("lat", Double.NaN)
                val lng = o.optDouble("lng", Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) return lat to lng
                err = "không có toạ độ"
            } catch (e: Throwable) {
                err = "lỗi ${e.javaClass.simpleName}"
                Log.w(TAG, "place $v", e)
            }
        }
        note = "Lấy toạ độ: $err"
        return null
    }

    // ---------- Bắt đầu / dừng ----------
    fun start(lat: Double, lon: Double, name: String) {
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
        val key = apiKey.trim()
        val veh = vehicle
        exec.execute {
            try { fetchRoute(key, p.first, p.second, veh) } finally { busy = false }
        }
    }

    private fun fetchRoute(key: String, lat: Double, lon: Double, veh: String) {
        if (key.isEmpty()) { note = "Chưa có API key VietMap"; fails++; return }
        var err = ""
        for (v in VERSIONS) {
            try {
                val extra = if (v == "v3") "&api-version=1.1" else ""
                val u = String.format(Locale.US, "%s/route/%s?apikey=%s&point=%.6f,%.6f&point=%.6f,%.6f&vehicle=%s&points_encoded=true%s",
                    BASE, v, enc(key), lat, lon, destLat, destLon, veh, extra)
                val (code, body) = get(u)
                if (code != 200) { err = httpNote(code); continue }
                val o = JSONObject(body.trim())
                val path = o.optJSONArray("paths")?.optJSONObject(0)
                if (path == null) { err = "VietMap: ${o.optString("code", "không có đường")}"; continue }
                val r = parseRoute(path)
                if (r == null) { err = "đường đi trống"; continue }
                if (!active) return
                route = r
                fails = 0
                note = "Tới $destName: ${fmtKm(r.totalM)}, ${(r.totalS / 60).roundToInt()} phút"
                return
            } catch (e: Throwable) {
                err = "lỗi ${e.javaClass.simpleName}"
                Log.w(TAG, "route $v", e)
            }
        }
        fails++
        note = "Tính đường: $err"
    }

    private fun parseRoute(p: JSONObject): Route? {
        val lat: DoubleArray
        val lon: DoubleArray
        val pts = p.opt("points")
        when (pts) {
            is String -> { val d = decode(pts); lat = d.first; lon = d.second }
            is JSONObject, is JSONArray -> {
                val arr: JSONArray = (if (pts is JSONObject) pts.optJSONArray("coordinates") else pts as JSONArray) ?: return null
                lat = DoubleArray(arr.length()); lon = DoubleArray(arr.length())
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONArray(i) ?: return null
                    val x = a.optDouble(0); val y = a.optDouble(1)
                    // Việt Nam: vĩ độ 8..24, kinh độ 102..110 -> tự nhận thứ tự [lon,lat] hay [lat,lon]
                    if (x > 90.0) { lon[i] = x; lat[i] = y } else { lat[i] = x; lon[i] = y }
                }
            }
            else -> return null
        }
        val n = lat.size
        if (n < 2) return null
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + meters(lat[i - 1], lon[i - 1], lat[i], lon[i])
        val steps = ArrayList<Step>()
        p.optJSONArray("instructions")?.let { ins ->
            for (i in 0 until ins.length()) {
                val s = ins.optJSONObject(i) ?: continue
                val start = s.optJSONArray("interval")?.optInt(0, 0) ?: 0
                steps.add(Step(s.optInt("sign", 0), s.optInt("exit_number", 0),
                    streetOf(s.optString("street_name", "").trim(), s.optString("text", "")), start.coerceIn(0, n - 1)))
            }
        }
        val total = p.optDouble("distance", cum[n - 1]).let { if (it.isNaN() || it <= 0.0) cum[n - 1] else it }
        val timeS = p.optDouble("time", 0.0) / 1000.0
        return Route(lat, lon, cum, steps, cum[n - 1], if (timeS > 0) timeS * cum[n - 1] / total else cum[n - 1] / 8.0)
    }

    // ---------- Bám tuyến (luồng hiển thị, mỗi khung) ----------
    fun update(speedKmh: Float) {
        if (!active) { turn = null; return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpd < UPDATE_MS) return
        lastUpd = now
        if (arrivedAt > 0 && now - arrivedAt > ARRIVE_HOLD_MS) { stop(); note = "Đã đến $destName"; return }
        val p = gps.lastLatLon()
        val r = route
        if (r == null) {
            if (p == null) note = "Chờ tín hiệu GPS để tính đường…"
            else if (fails >= MAX_FAILS) { active = false; turn = null }
            else if (!busy && now - lastReq > RETRY_MS) requestRoute(now, p)
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

    // ---------- Bản đồ nhỏ (dưới đồng hồ tốc độ), hướng tuyến luôn quay lên ----------
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD90F131A.toInt(); style = Paint.Style.FILL }
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3340.toInt(); style = Paint.Style.STROKE }
    private val pastP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF39FF9E.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val turnP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); style = Paint.Style.FILL }
    private val egoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDDE4E8.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val clip = Path()

    fun drawMini(canvas: Canvas, k: Float, cx: Float, cy: Float, rad: Float, speedKmh: Float) {
        if (!active || !showMini) return
        val r = cur ?: return
        if (idx < 0) return
        canvas.drawCircle(cx, cy, rad, bgP)
        val rangeM = when { speedKmh > 60f -> 600.0; speedKmh > 30f -> 350.0; else -> 220.0 }
        val s = rad / rangeM
        val egoY = cy + rad * 0.4f
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
        clip.reset(); clip.addCircle(cx, cy, rad - 2f * k, Path.Direction.CW)
        canvas.clipPath(clip)
        val n = r.lat.size
        // Đoạn đã đi (mờ)
        path.reset()
        var i0 = idx
        while (i0 > 0 && along - r.cum[i0] < rangeM * 1.5) i0--
        path.moveTo(sx(i0), sy(i0))
        for (i in i0 + 1..idx) path.lineTo(sx(i), sy(i))
        path.lineTo(cx, egoY)
        pastP.strokeWidth = 6f * k
        canvas.drawPath(path, pastP)
        // Đoạn sắp đi
        path.reset()
        path.moveTo(cx, egoY)
        var i = idx + 1
        while (i < n && r.cum[i] - along < rangeM * 2.5) { path.lineTo(sx(i), sy(i)); i++ }
        if (i < n) path.lineTo(sx(i), sy(i))
        routeP.strokeWidth = 8f * k
        canvas.drawPath(path, routeP)
        // Chỗ rẽ kế tiếp
        if (nextStart in 0 until n) canvas.drawCircle(sx(nextStart), sy(nextStart), 7f * k, turnP)
        // Xe mình
        path.reset()
        path.moveTo(cx, egoY - 14f * k)
        path.lineTo(cx + 10f * k, egoY + 10f * k)
        path.lineTo(cx, egoY + 5f * k)
        path.lineTo(cx - 10f * k, egoY + 10f * k)
        path.close()
        canvas.drawPath(path, egoP)
        canvas.restore()
        ringP.strokeWidth = 3f * k
        canvas.drawCircle(cx, cy, rad, ringP)
        if (remainText.isNotEmpty()) {
            textP.textSize = 22f * k
            canvas.drawText(remainText, cx, cy + rad + 30f * k, textP)
        }
    }

    // ---------- Tiện ích ----------
    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
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
        401, 403 -> "API key sai hoặc chưa mở API này (HTTP $code)"
        429 -> "hết lượt gọi trong ngày (HTTP 429)"
        else -> "VietMap lỗi HTTP $code"
    }

    private fun meters(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * 111_320.0
        val dx = (o2 - o1) * 111_320.0 * cos(Math.toRadians(a1))
        return sqrt(dx * dx + dy * dy)
    }

    // Google polyline, độ chính xác 5 chữ số
    private fun decode(s: String): Pair<DoubleArray, DoubleArray> {
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        var i = 0
        var lat = 0
        var lng = 0
        while (i < s.length) {
            for (part in 0..1) {
                var shift = 0
                var res = 0
                var b: Int
                do {
                    if (i >= s.length) return la.toDoubleArray() to lo.toDoubleArray()
                    b = s[i++].code - 63
                    res = res or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val d = if ((res and 1) != 0) (res shr 1).inv() else (res shr 1)
                if (part == 0) lat += d else lng += d
            }
            la.add(lat / 1e5)
            lo.add(lng / 1e5)
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

    // Mã hướng rẽ VietMap/GraphHopper -> mã Waze mà TurnGuide đang vẽ
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
        private const val TAG = "VietMapNav"
        private const val BASE = "https://maps.vietmap.vn/api"
        private val VERSIONS = listOf("v4", "v3")
        const val UPDATE_MS = 300L
        const val OFF_M = 45.0
        const val OFF_MS = 5000L
        const val REROUTE_GAP_MS = 30_000L
        const val MAX_REROUTES = 60
        const val RETRY_MS = 15_000L
        const val MAX_FAILS = 3
        const val ARRIVE_M = 25.0
        const val ARRIVE_HOLD_MS = 15_000L
    }
}
