package com.kevin.evcarhud

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Tên đường + giới hạn tốc độ theo GPS từ HERE Reverse Geocoding (một lượt gọi lấy cả hai).
// GET https://revgeocode.search.hereapi.com/v1/revgeocode?at=lat,lng&types=street&lang=vi-VN&showNavAttributes=speedLimits&apiKey=…
// API key nhập trong Cài đặt (chỉ để test; bản thương mại gọi qua máy chủ riêng giữ key).
// Tiết kiệm lượt gọi:
//  - không gọi khi xe chạy dưới 10 km/h (đèn đỏ, kẹt xe, đỗ xe)
//  - chỉ gọi khi đi thêm >= 150 m (trong phố) / >= 400 m (trên 70 km/h), hoặc hướng xe đổi > 35° sau >= 30 m (vừa rẽ)
//  - cách nhau tối thiểu 8 s
//  - đang chỉ đường thì MainActivity lấy tên đường + tốc độ từ tuyến đã tải (HereNav), không gọi ở đây
class HereGeo(private val gps: LocationFallback) {
    @Volatile var apiKey = ""
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var road = ""
    @Volatile private var limit = 0
    @Volatile private var busy = false
    @Volatile var note = "chưa có API key"
        private set
    @Volatile var calls = 0
        private set
    @Volatile private var navAttrs = true    // tài khoản không hỗ trợ showNavAttributes -> chỉ lấy tên đường
    private var lat0 = Double.NaN
    private var lon0 = Double.NaN
    private var brg0 = Double.NaN            // hướng xe lúc gọi lần trước (độ)
    private var pLat = Double.NaN
    private var pLon = Double.NaN
    private var brg = Double.NaN             // hướng xe hiện tại, tính từ 2 điểm GPS cách nhau >= 15 m
    private var at = -1_000_000L
    private var gotAt = -1_000_000L

    fun limitKmh(): Int = if (SystemClock.elapsedRealtime() - gotAt < STALE_MS) limit else 0

    // Gọi thường xuyên từ luồng hiển thị: trả tên đường gần nhất, tự hẹn lần tra kế tiếp
    fun street(speedKmh: Float): String {
        val key = apiKey.trim()
        if (key.isEmpty()) { note = "chưa có API key"; return "" }
        val p = gps.lastLatLon()
        if (p != null) {
            if (pLat.isNaN()) { pLat = p.first; pLon = p.second }
            else if (meters(pLat, pLon, p.first, p.second) >= 15.0) {
                brg = bearing(pLat, pLon, p.first, p.second)
                pLat = p.first; pLon = p.second
            }
            val now = SystemClock.elapsedRealtime()
            if (!busy && speedKmh >= MIN_KMH && now - at >= MIN_GAP_MS) {
                val d = if (lat0.isNaN()) Double.MAX_VALUE else meters(lat0, lon0, p.first, p.second)
                val far = if (speedKmh > 70f) MOVE_FAST_M else MOVE_M
                val turned = !brg.isNaN() && !brg0.isNaN() && angleDiff(brg, brg0) > TURN_DEG && d >= 30.0
                if (d >= far || turned) {
                    busy = true
                    at = now
                    lat0 = p.first; lon0 = p.second; brg0 = brg
                    exec.execute { try { fetch(key, p.first, p.second) } finally { busy = false } }
                }
            }
        }
        return if (SystemClock.elapsedRealtime() - gotAt < STALE_MS) road else ""
    }

    private fun fetch(key: String, lat: Double, lon: Double) {
        try {
            var r = get(url(key, lat, lon, navAttrs))
            if (navAttrs && r.first == 400) {
                // Gói / phiên bản API không nhận showNavAttributes: bỏ đi, chỉ lấy tên đường
                navAttrs = false
                r = get(url(key, lat, lon, false))
            }
            val (code, body) = r
            if (code != 200) {
                note = when (code) {
                    401, 403 -> "API key sai hoặc chưa mở dịch vụ (HTTP $code)"
                    429 -> "hết lượt gọi (HTTP 429)"
                    else -> "HERE lỗi HTTP $code"
                }
                return
            }
            val item = JSONObject(body).optJSONArray("items")?.optJSONObject(0)
            if (item == null) { note = "HERE: không có đường ở đây"; return }
            val name = item.optJSONObject("address")?.optString("street", "")?.trim().orEmpty()
            var lim = 0
            item.optJSONObject("navigationAttributes")?.optJSONArray("speedLimits")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val v = s.optDouble("maxSpeed", Double.NaN)
                    if (v.isNaN() || v <= 0.0) continue
                    val unit = s.optString("speedUnit", "kph").lowercase()
                    lim = (if (unit.startsWith("mph") || unit.startsWith("miles")) v * 1.609 else v).roundToInt()
                    break
                }
            }
            road = name
            limit = lim
            gotAt = SystemClock.elapsedRealtime()
            note = "HERE: ${name.ifEmpty { "không rõ tên đường" }}" +
                (if (lim > 0) ", giới hạn $lim km/h" else if (navAttrs) ", chưa có giới hạn" else "") + " · $calls lần tra"
        } catch (e: Throwable) {
            note = "HERE lỗi: ${e.javaClass.simpleName}"
            Log.w("HereGeo", "revgeocode lỗi", e)
        }
    }

    private fun url(key: String, lat: Double, lon: Double, nav: Boolean): String =
        String.format(Locale.US, "https://revgeocode.search.hereapi.com/v1/revgeocode?at=%.6f,%.6f&types=street&lang=vi-VN%s&apiKey=%s",
            lat, lon, if (nav) "&showNavAttributes=speedLimits" else "", URLEncoder.encode(key, "UTF-8"))

    private fun get(u: String): Pair<Int, String> {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            calls++
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    private fun meters(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * 111_320.0
        val dx = (o2 - o1) * 111_320.0 * cos(Math.toRadians(a1))
        return sqrt(dx * dx + dy * dy)
    }

    private fun bearing(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = a2 - a1
        val dx = (o2 - o1) * cos(Math.toRadians(a1))
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double { val d = abs(a - b) % 360.0; return if (d > 180.0) 360.0 - d else d }

    companion object {
        const val MIN_KMH = 10f
        const val MOVE_M = 150.0
        const val MOVE_FAST_M = 400.0
        const val TURN_DEG = 35.0
        const val MIN_GAP_MS = 8_000L
        const val STALE_MS = 600_000L
    }
}
