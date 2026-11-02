package com.kevin.evcarhud

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.floor

// Kẹt xe quanh xe khi KHÔNG dẫn đường (Android Auto vẽ vàng/đỏ lên bản đồ OSM), từ HERE Traffic Flow v7 qua Worker.
// Tiết kiệm lượt gọi:
//  - chỉ hỏi khi xe đang chạy (>= 5 km/h), tối thiểu 2 phút một lần
//  - tâm vùng hỏi làm tròn theo ô lưới ~2 km, bán kính 3 km: xe đi trong cùng ô thì dùng lại kết quả,
//    và mọi máy trong cùng ô gửi đúng một URL nên Worker trả từ bộ nhớ đệm chung (2 phút)
//  - chỉ giữ đoạn chậm / kẹt (jamFactor >= 4); đường thông thoáng không vẽ
object TrafficFlow {
    class Seg(val lat: DoubleArray, val lon: DoubleArray, val lvl: Int)   // lvl 1 chậm (vàng), 2 kẹt (đỏ)

    private const val GRID = 0.02            // độ (~2,2 km)
    private const val RADIUS_M = 3000
    private const val GAP_MS = 120_000L
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile var segs: List<Seg> = emptyList()
        private set
    @Volatile private var busy = false
    private var at = -1_000_000L
    private var cell = ""
    @Volatile var calls = 0
        private set

    // Gọi thường xuyên từ luồng vẽ; tự hẹn lần hỏi kế tiếp
    fun update(lat: Double, lon: Double, speedKmh: Float, navigating: Boolean) {
        if (navigating) return
        val now = SystemClock.elapsedRealtime()
        val cLat = (floor(lat / GRID) + 0.5) * GRID
        val cLon = (floor(lon / GRID) + 0.5) * GRID
        val key = String.format(Locale.US, "%.3f,%.3f", cLat, cLon)
        if (busy || speedKmh < 5f) return
        if (key == cell && now - at < GAP_MS) return
        if (key != cell && now - at < 15_000L) return
        busy = true; at = now; cell = key
        exec.execute {
            try { fetch(key) } catch (e: Throwable) { Log.w("TrafficFlow", "flow", e) } finally { busy = false }
        }
    }

    private fun fetch(center: String) {
        val u = "${HereApi.PROXY}/data.traffic/v7/flow?in=circle:$center;r=$RADIUS_M&locationReferencing=shape"
        val c = HereApi.open(u)
        try {
            c.connectTimeout = 6000; c.readTimeout = 8000
            calls++
            if (c.responseCode != 200) return
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val res = JSONObject(body).optJSONArray("results") ?: return
            val out = ArrayList<Seg>()
            for (i in 0 until res.length()) {
                val r = res.optJSONObject(i) ?: continue
                val jf = r.optJSONObject("currentFlow")?.optDouble("jamFactor", 0.0) ?: 0.0
                val lvl = if (jf >= 8.0) 2 else if (jf >= 4.0) 1 else 0
                if (lvl == 0) continue
                val links = r.optJSONObject("location")?.optJSONObject("shape")?.optJSONArray("links") ?: continue
                for (j in 0 until links.length()) {
                    val pts = links.optJSONObject(j)?.optJSONArray("points") ?: continue
                    if (pts.length() < 2) continue
                    val la = DoubleArray(pts.length()); val lo = DoubleArray(pts.length())
                    for (q in 0 until pts.length()) { val p = pts.optJSONObject(q); la[q] = p?.optDouble("lat") ?: 0.0; lo[q] = p?.optDouble("lng") ?: 0.0 }
                    out.add(Seg(la, lo, lvl))
                }
            }
            segs = out
        } finally { c.disconnect() }
    }
}
