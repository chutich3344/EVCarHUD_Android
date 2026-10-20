package com.kevin.evcarhud

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// Thời tiết hiện tại theo vị trí GPS, từ Open-Meteo (miễn phí, không cần khoá API; cần mạng).
// Tra lại mỗi 15 phút hoặc khi đi xa hơn ~5 km. Không có mạng/GPS thì để trống.
class WeatherInfo(private val gps: LocationFallback) {
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var line = ""
    // Mưa / mưa phùn / dông / sương mù: chế độ Cao tốc tăng khoảng cách an toàn
    @Volatile var wet = false
        private set
    @Volatile private var busy = false
    private var at = -1_000_000L
    private var lat0 = 0.0
    private var lon0 = 0.0

    // Gọi thường xuyên từ luồng hiển thị: trả dòng thời tiết gần nhất, tự hẹn lần tra kế tiếp
    fun text(): String {
        val p = gps.lastLatLon()
        if (p != null && !busy) {
            val now = SystemClock.elapsedRealtime()
            val far = Math.abs(p.first - lat0) + Math.abs(p.second - lon0) > 0.05
            if (now - at > 15 * 60_000L || (far && now - at > 60_000L)) {
                busy = true
                at = now
                lat0 = p.first; lon0 = p.second
                exec.execute { fetch(p.first, p.second); busy = false }
            }
        }
        return line
    }

    private fun fetch(lat: Double, lon: Double) {
        try {
            val u = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day",
                lat, lon)
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000
                c.readTimeout = 5000
                if (c.responseCode != 200) return
                val cur = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                val t = cur.getDouble("temperature_2m").roundToInt()
                val code = cur.optInt("weather_code", -1)
                val day = cur.optInt("is_day", 1) == 1
                line = "${icon(code, day)}  $t°C  ${desc(code)}"
                wet = code == 45 || code == 48 || code in 51..67 || code in 80..82 || code in 95..99
            } finally { c.disconnect() }
        } catch (e: Throwable) {
            Log.w("Weather", "không lấy được thời tiết", e)
            at = SystemClock.elapsedRealtime() - 13 * 60_000L   // lỗi: thử lại sau ~2 phút
        }
    }

    // Mã thời tiết WMO
    private fun icon(c: Int, day: Boolean) = when (c) {
        0 -> if (day) "☀" else "☾"
        1, 2 -> "⛅"
        3, 45, 48 -> "☁"
        in 51..67, in 80..82 -> "☂"
        in 95..99 -> "⚡"
        else -> "☁"
    }

    private fun desc(c: Int) = when (c) {
        0 -> "Trời quang"
        1 -> "Ít mây"
        2 -> "Mây rải rác"
        3 -> "Nhiều mây"
        45, 48 -> "Sương mù"
        in 51..57 -> "Mưa phùn"
        in 61..65, 66, 67 -> "Mưa"
        in 80..82 -> "Mưa rào"
        in 95..99 -> "Dông"
        else -> ""
    }
}
