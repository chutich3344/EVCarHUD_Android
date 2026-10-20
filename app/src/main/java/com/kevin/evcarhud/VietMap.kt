package com.kevin.evcarhud

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sqrt

// Tên đường + địa chỉ theo GPS từ VietMap Maps API (Reverse v3), dùng API key của bạn (nhập trong Cài đặt).
// GET https://maps.vietmap.vn/api/reverse/v3?apikey=…&lng=…&lat=…
// Mỗi lần tra tính phí theo gói VietMap, nên chỉ tra khi xe đi được ≥ 150 m (hoặc ≥ 30 m sau 60 s), không tra khi đứng yên.
// VietMap Reverse không trả giới hạn tốc độ; giới hạn vẫn lấy từ Waze / OSM / biển báo nhận diện được.
class VietMapGeo(private val gps: LocationFallback) {
    @Volatile var apiKey = ""
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var road = ""
    @Volatile private var area = ""          // phường / quận
    @Volatile private var busy = false
    @Volatile var note = "chưa có API key"
        private set
    @Volatile var calls = 0
        private set
    private var lat0 = Double.NaN
    private var lon0 = Double.NaN
    private var at = -1_000_000L
    private var gotAt = -1_000_000L

    // Gọi thường xuyên từ luồng hiển thị: trả tên đường gần nhất (rỗng nếu chưa có / quá cũ), tự hẹn lần tra kế tiếp
    fun street(): String {
        val key = apiKey.trim()
        if (key.isEmpty()) { note = "chưa có API key"; return "" }
        val p = gps.lastLatLon()
        if (p != null && !busy) {
            val now = SystemClock.elapsedRealtime()
            val d = if (lat0.isNaN()) Double.MAX_VALUE else meters(lat0, lon0, p.first, p.second)
            if (d >= MOVE_M || (d >= MOVE_SLOW_M && now - at >= SLOW_MS)) {
                busy = true
                at = now
                lat0 = p.first; lon0 = p.second
                exec.execute { fetch(key, p.first, p.second); busy = false }
            }
        }
        return if (SystemClock.elapsedRealtime() - gotAt < STALE_MS) road else ""
    }

    fun areaText(): String = area

    private fun meters(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * 111_320.0
        val dx = (o2 - o1) * 111_320.0 * cos(Math.toRadians(a1))
        return sqrt(dx * dx + dy * dy)
    }

    private fun fetch(key: String, lat: Double, lon: Double) {
        try {
            val u = String.format(Locale.US, "https://maps.vietmap.vn/api/reverse/v3?apikey=%s&lng=%.6f&lat=%.6f",
                URLEncoder.encode(key, "UTF-8"), lon, lat)
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000
                c.readTimeout = 5000
                calls++
                if (c.responseCode != 200) {
                    note = if (c.responseCode == 401 || c.responseCode == 403) "API key sai hoặc hết hạn (HTTP ${c.responseCode})"
                           else "VietMap lỗi HTTP ${c.responseCode}"
                    return
                }
                val body = c.inputStream.bufferedReader().use { it.readText() }.trim()
                val obj: JSONObject? = if (body.startsWith("[")) JSONArray(body).optJSONObject(0) else JSONObject(body)
                if (obj == null) { note = "VietMap: không có kết quả"; return }
                val r = roadName(obj)
                if (r.isNotEmpty()) { road = r; gotAt = SystemClock.elapsedRealtime() }
                area = areaOf(obj)
                note = "VietMap: ${r.ifEmpty { "không rõ tên đường" }}${if (area.isNotEmpty()) ", $area" else ""} · $calls lần tra"
            } finally { c.disconnect() }
        } catch (e: Throwable) {
            note = "VietMap lỗi: ${e.javaClass.simpleName}"
            Log.w("VietMap", "reverse lỗi", e)
        }
    }

    // "display" dạng "12 Nguyễn Huệ, Phường Bến Nghé, Quận 1, Thành phố Hồ Chí Minh":
    // bỏ các phần là phường/quận/tỉnh (boundaries), bỏ tên địa điểm nếu điểm gần nhất là một POI, bỏ số nhà ở đầu.
    private fun roadName(o: JSONObject): String {
        val display = o.optString("display", "").ifBlank { o.optString("address", "") }
        val bounds = HashSet<String>()
        o.optJSONArray("boundaries")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { b ->
                bounds.add(b.optString("full_name", "").trim().lowercase())
                bounds.add(b.optString("name", "").trim().lowercase())
            }
        }
        // VietMap trả mã Plus Code (vd "7P28QQX2+4F Phường An Phú") khi chỗ đó không có tên đường: bỏ mã, bỏ phần còn lại nếu là phường/quận
        var parts = display.split(",").map { it.replace(PLUS_CODE, "").trim() }.filter { it.isNotEmpty() && it.lowercase() !in bounds }
        val isPoi = (o.optJSONArray("categories")?.length() ?: 0) > 0
        if (isPoi && parts.size > 1) parts = parts.drop(1)
        val first = parts.firstOrNull() ?: o.optString("name", "").replace(PLUS_CODE, "").trim()
        val road = first.replace(Regex("^\\d+[\\w/\\-]*\\s+"), "").trim()
        // chỉ còn tên phường / xã / quận (không có đường) -> để trống
        if (road.lowercase() in bounds || ADMIN_PREFIX.containsMatchIn(road)) return ""
        return road
    }

    private fun areaOf(o: JSONObject): String {
        val arr = o.optJSONArray("boundaries") ?: return ""
        // thường theo thứ tự phường, quận, tỉnh: lấy 2 cấp đầu
        val names = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 2)) arr.optJSONObject(i)?.optString("full_name", "")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        return names.joinToString(", ")
    }

    companion object {
        const val MOVE_M = 150.0
        const val MOVE_SLOW_M = 30.0
        const val SLOW_MS = 60_000L
        const val STALE_MS = 180_000L
        private val PLUS_CODE = Regex("[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3}\\s*")
        private val ADMIN_PREFIX = Regex("^(Phường|Xã|Thị trấn|Quận|Huyện|Thị xã|Thành phố|Tỉnh)\\s", RegexOption.IGNORE_CASE)
    }
}
