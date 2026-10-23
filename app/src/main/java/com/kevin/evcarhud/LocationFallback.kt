package com.kevin.evcarhud

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val LTAG = "GpsFallback"

// Dự phòng khi Waze Mod không cấp tốc độ / tên đường: dùng GPS của điện thoại.
// Tốc độ lấy từ GPS (Location.speed), tên đường tra bằng Geocoder (cần mạng, có thể trống).
// Giới hạn tốc độ dự phòng (khi Waze không có): thẻ maxspeed của đoạn đường trên OpenStreetMap;
// đường chưa gắn thẻ thì ước tính theo loại đường và khu vực theo Thông tư 38/2024/TT-BGTVT (ô tô con).
class LocationFallback(ctx: Context) : LocationListener {
    private val context = ctx.applicationContext
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val geo: Geocoder? by lazy {
        try { if (Geocoder.isPresent()) Geocoder(context, Locale("vi", "VN")) else null } catch (_: Throwable) { null }
    }
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile private var loc: Location? = null
    @Volatile private var locAt = 0L
    @Volatile private var street = ""
    @Volatile private var busy = false
    @Volatile private var geoNote = "chưa tra"
    @Volatile private var osmLimit = 0          // km/h từ thẻ maxspeed OSM; 0 = không có
    @Volatile private var guessLimit = 0        // km/h ước tính theo loại đường; 0 = không đoán
    @Volatile private var roadType = ""
    private var geoLoc: Location? = null
    private var geoAt = -100_000L
    private var started = false

    // Vị trí gần đúng (mạng / Wi‑Fi, hoặc vị trí cuối cùng máy còn nhớ): chỉ để đặt tâm bản đồ / thời tiết khi GPS
    // chưa bắt được (trong nhà, vừa mở app). Không dùng cho tốc độ, tên đường hay cảnh báo.
    @Volatile private var approx: Location? = null
    private val netListener = LocationListener { l -> approx = l }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (started || !hasPermission()) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            started = true
            approx = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null } }
                .maxByOrNull { it.elapsedRealtimeNanos }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, netListener, Looper.getMainLooper())
        } catch (e: Throwable) {
            Log.w(LTAG, "không bật được GPS", e)
        }
    }

    fun stop() {
        if (!started) return
        try { lm.removeUpdates(this); lm.removeUpdates(netListener) } catch (_: Throwable) {}
        started = false
    }

    // Vị trí ảo (Chạy thử tuyến trong Chỉ đường): khi đặt, thay vị trí + tốc độ GPS thật cho mọi nơi đọc
    @Volatile private var mockLat = Double.NaN
    @Volatile private var mockLon = Double.NaN
    @Volatile private var mockKmh = 0f
    val mocking: Boolean get() = !mockLat.isNaN()
    fun setMock(lat: Double, lon: Double, kmh: Float) { mockLon = lon; mockKmh = kmh; mockLat = lat }
    fun clearMock() { mockLat = Double.NaN }

    // Chất lượng GPS cho icon trên HUD: 0 = tốt, 1 = yếu (fix cũ > 3 s hoặc sai số > 25 m), 2 = mất (không có fix 10 s)
    fun quality(): Int {
        if (mocking) return 0
        val l = loc ?: return 2
        val age = SystemClock.elapsedRealtime() - locAt
        if (age > 10_000L) return 2
        if (age > 3000L || !l.hasAccuracy() || l.accuracy > 25f) return 1
        return 0
    }

    // Tốc độ GPS (km/h); null nếu chưa có fix hoặc dữ liệu cũ hơn 3 s
    fun speedKmh(): Float? {
        if (mocking) return mockKmh
        val l = loc ?: return null
        if (SystemClock.elapsedRealtime() - locAt > 3000L || !l.hasSpeed()) return null
        return l.speed * 3.6f
    }

    // Tên đường theo GPS; rỗng nếu chưa có / mất fix quá 60 s
    fun street(): String =
        if (loc != null && SystemClock.elapsedRealtime() - locAt < 60_000L) street else ""

    // Giới hạn tốc độ theo bản đồ OSM. guess = true thì dùng cả giá trị ước tính khi đường chưa gắn thẻ.
    // 0 nếu không có hoặc mất GPS quá 60 s.
    fun limitKmh(guess: Boolean): Int {
        if (loc == null || SystemClock.elapsedRealtime() - locAt > 60_000L) return 0
        return if (osmLimit > 0) osmLimit else if (guess) guessLimit else 0
    }

    // Vị trí GPS gần nhất (trong 10 phút): bám tuyến, tên đường, giới hạn tốc độ
    fun lastLatLon(): Pair<Double, Double>? {
        if (mocking) return Pair(mockLat, mockLon)
        val l = loc ?: return null
        if (SystemClock.elapsedRealtime() - locAt > 600_000L) return null
        return Pair(l.latitude, l.longitude)
    }

    // Hướng đi theo GPS (độ, từ bắc theo chiều kim đồng hồ); null khi đứng yên / chưa có
    fun bearingDeg(): Float? {
        val l = loc ?: return null
        if (mocking || !l.hasBearing() || !l.hasSpeed() || l.speed < 1.5f) return null
        return l.bearing
    }

    // Cho bản đồ / thời tiết / gợi ý tìm kiếm: GPS nếu có, không thì vị trí gần đúng theo mạng
    // hoặc vị trí máy nhớ lần cuối (trong 1 ngày)
    fun anyLatLon(): Pair<Double, Double>? {
        lastLatLon()?.let { return it }
        val a = approx ?: return null
        if (SystemClock.elapsedRealtimeNanos() - a.elapsedRealtimeNanos > 86_400_000_000_000L) return null
        return Pair(a.latitude, a.longitude)
    }

    fun limitText(): String = when {
        osmLimit > 0 -> "$osmLimit km/h (OSM, đường $roadType)"
        guessLimit > 0 -> "~$guessLimit km/h (ước tính, đường $roadType)"
        roadType.isNotEmpty() -> "không rõ (đường $roadType)"
        else -> "chưa có"
    }

    fun statusText(): String {
        if (!hasPermission()) return "GPS: chưa cấp quyền vị trí"
        if (loc == null || SystemClock.elapsedRealtime() - locAt > 5000L) return "GPS: đang chờ định vị"
        val sp = speedKmh()?.let { "%.0f km/h".format(it) } ?: "--"
        val acc = loc?.takeIf { it.hasAccuracy() }?.let { " · sai số ~%.0f m".format(it.accuracy) } ?: ""
        return "GPS: $sp$acc"
    }

    override fun onLocationChanged(l: Location) {
        loc = l
        locAt = SystemClock.elapsedRealtime()
        // Tên đường / giới hạn tốc độ lấy từ HERE (HereGeo, HereNav): không tra Geocoder / OpenStreetMap nữa cho đỡ mạng và pin
    }

    private fun lookup(l: Location) {
        if (busy) return
        val now = SystemClock.elapsedRealtime()
        val moved = geoLoc?.distanceTo(l) ?: Float.MAX_VALUE
        val dueMove = moved >= 25f && now - geoAt >= 4_000L
        val dueTime = now - geoAt >= 20_000L
        if (!dueMove && !dueTime) return
        busy = true
        geoAt = now
        geoLoc = l
        exec.execute {
            var name: String? = null
            var note = ""
            var via = ""
            // 1) Geocoder của hệ thống (nhiều máy không có / trả rỗng)
            val g = geo
            if (g != null) {
                try {
                    @Suppress("DEPRECATION")
                    name = g.getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                        ?.thoroughfare?.takeIf { it.isNotBlank() }
                    if (name != null) via = "Geocoder"
                } catch (e: Throwable) {
                    note = "Geocoder lỗi: ${e.message}"
                }
            } else note = "máy không có Geocoder"
            // 2) OpenStreetMap Nominatim (cần mạng; gửi toạ độ lên máy chủ OSM): luôn tra để lấy giới hạn tốc độ,
            //    tên đường chỉ dùng khi Geocoder không có
            try {
                val osmName = nominatim(l.latitude, l.longitude)
                if (name == null) {
                    name = osmName
                    if (name != null) via = "OSM" else if (osmNote.isNotEmpty()) note = osmNote
                    else note += " · OSM: không có tên đường"
                }
            } catch (e: Throwable) {
                if (name == null) note += " · OSM lỗi: ${e.javaClass.simpleName}"
            }
            if (name != null) street = name
            geoNote = if (name != null) "tra bằng $via" else note.trim(' ', '·')
            Log.d(LTAG, "street=$name $geoNote")
            busy = false
        }
    }

    @Volatile private var osmNote = ""

    private fun nominatim(lat: Double, lon: Double): String? {
        osmNote = ""
        val c = URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&extratags=1&accept-language=vi&lat=$lat&lon=$lon")
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", "EVCarHUD/1.0 (personal car HUD)")
            if (c.responseCode != 200) { osmNote = "OSM: HTTP ${c.responseCode}"; return null }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val a = j.optJSONObject("address")
            readLimit(j, a)
            if (a == null) return null
            for (key in arrayOf("road", "pedestrian", "residential", "highway", "path", "footway")) {
                val v = a.optString(key, "")
                if (v.isNotBlank()) return v
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    // maxspeed: "50", "60 mph", "VN:urban"...; loại đường: category=highway, type=primary/residential...
    private fun readLimit(j: JSONObject, a: JSONObject?) {
        val isRoad = j.optString("category") == "highway"
        val type = if (isRoad) j.optString("type") else ""
        roadType = type
        val tag = j.optJSONObject("extratags")?.optString("maxspeed", "").orEmpty().trim()
        osmLimit = parseMaxspeed(tag)
        // Trong khu dân cư: địa chỉ có quận/phường/thành phố; ngoài: thôn/xã/làng
        val urban = a != null && (a.has("city") || a.has("suburb") || a.has("city_district") || a.has("quarter"))
        guessLimit = when (type) {
            "", "motorway", "motorway_link" -> 0   // cao tốc: giới hạn theo biển từng tuyến, không đoán
            "trunk", "primary" -> if (urban) 60 else 80
            "secondary", "tertiary" -> if (urban) 50 else 80
            "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> 50
            "residential", "unclassified", "living_street", "service" -> if (urban) 50 else 60
            else -> 0
        }
    }

    private fun parseMaxspeed(t: String): Int {
        if (t.isEmpty()) return 0
        when (t.uppercase()) {
            "VN:URBAN" -> return 50
            "VN:RURAL" -> return 80
            "VN:MOTORWAY" -> return 120
        }
        val n = Regex("^(\\d{2,3})").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return if (t.contains("mph", ignoreCase = true)) (n * 1.609f).toInt() else n
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
