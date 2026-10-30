package com.kevin.evcarhud

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// Thời tiết hiện tại theo vị trí GPS, từ HERE Destination Weather (qua Worker; cần mạng).
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
        val p = gps.anyLatLon()
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

    // HERE Destination Weather qua Worker (key nằm ở máy chủ): trạm quan sát gần nhất, mô tả tiếng Việt
    private fun fetch(lat: Double, lon: Double) {
        try {
            val u = String.format(Locale.US,
                "%s/weather/v3/report?products=observation&oneObservation=true&location=%.4f,%.4f&lang=vi-VN&units=metric",
                HereApi.PROXY, lat, lon)
            val c = HereApi.open(u)
            try {
                c.connectTimeout = 5000
                c.readTimeout = 5000
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val ob = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    .getJSONArray("places").getJSONObject(0).getJSONArray("observations").getJSONObject(0)
                val t = ob.optString("temperature").toDoubleOrNull()?.roundToInt()
                val name = ob.optString("iconName").lowercase(Locale.US)
                val day = ob.optString("daylight", "day") != "night"
                val sky = ob.optString("skyDesc").ifBlank { ob.optString("description") }
                line = listOfNotNull(icon(name, day), t?.let { "$it°C" }, sky.ifBlank { null }).joinToString("  ")
                wet = listOf("rain", "shower", "drizzle", "storm", "thunder", "fog", "sleet").any { it in name } ||
                    (ob.optString("rainFall").toDoubleOrNull() ?: 0.0) > 0.0
            } finally { c.disconnect() }
        } catch (e: Throwable) {
            Log.w("Weather", "không lấy được thời tiết", e)
            at = SystemClock.elapsedRealtime() - 13 * 60_000L   // lỗi: thử lại sau ~2 phút
        }
    }

    // iconName của HERE (sunny, mostly_cloudy, light_rain, tstorms, fog...)
    private fun icon(n: String, day: Boolean) = when {
        "storm" in n || "thunder" in n -> "⚡"
        "rain" in n || "shower" in n || "drizzle" in n || "sleet" in n -> "☂"
        "fog" in n || "haze" in n || "overcast" in n || n == "cloudy" -> "☁"
        "cloud" in n || "partly" in n || "mostly_sunny" in n || "mostly_clear" in n -> "⛅"
        "sunny" in n || "clear" in n -> if (day) "☀" else "☾"
        else -> "☁"
    }
}
