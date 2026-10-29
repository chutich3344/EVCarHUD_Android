package com.kevin.evcarhud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.util.Log
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

// Bản đồ nền tự dựng từ OpenStreetMap (file PMTiles cả nước, lược đồ OpenMapTiles), thay ô ảnh HERE: không tốn lượt gọi.
// - Đọc từng đoạn byte của file trên mạng (HTTP Range), không phải tải cả file
// - Vẽ ô ảnh 256/512 px bằng Canvas cho bản đồ Android Auto và bản đồ nhỏ trên HUD (HereNav lưu ô đã vẽ 30 ngày)
// - Tra tên + loại đường gần vị trí xe (HereGeo chỉ hỏi HERE giới hạn tốc độ khi sang đường khác)
// Dữ liệu © OpenStreetMap contributors (ODbL), lược đồ © OpenMapTiles: nơi hiện bản đồ phải ghi công.
object OsmTiles {
    const val URL_PM = "https://github.com/chutich3344/EVCarHUD_Android/releases/download/map/vietnam.pmtiles"
    const val ATTRIB = "© OpenStreetMap"
    private const val TAG = "OsmTiles"

    // ---------- đọc file PMTiles v3 ----------
    private class Entry(val id: Long, val off: Long, val len: Int, val run: Int)
    private class Header(val rootOff: Long, val rootLen: Int, val leafOff: Long, val dataOff: Long,
                         val inGz: Boolean, val tileGz: Boolean, val maxZ: Int)

    @Volatile private var head: Header? = null
    private var root: List<Entry> = emptyList()
    private val leaves = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 32
    }
    @Volatile private var failAt = -1_000_000L
    @Volatile var note = ""
        private set

    private fun range(off: Long, len: Int): ByteArray? {
        val c = URL(URL_PM).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 10000
            c.setRequestProperty("Range", "bytes=$off-${off + len - 1}")
            val code = c.responseCode
            if (code != 206 && code != 200) { note = "Bản đồ OSM: HTTP $code"; return null }
            // máy chủ bỏ qua Range (200) thì chỉ đọc phần đầu cần dùng
            val out = ByteArray(len)
            var n = 0
            c.inputStream.use { s ->
                if (code == 200 && off > 0) { var skip = off; while (skip > 0) { val k = s.skip(skip); if (k <= 0) return null; skip -= k } }
                while (n < len) { val k = s.read(out, n, len - n); if (k < 0) break; n += k }
            }
            return if (n == len) out else out.copyOf(n)
        } finally { c.disconnect() }
    }

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    private fun u64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun header(): Header? {
        head?.let { return it }
        synchronized(this) {
            head?.let { return it }
            if (android.os.SystemClock.elapsedRealtime() - failAt < 30_000L) return null
            try {
                val b = range(0, 16384) ?: run { failAt = android.os.SystemClock.elapsedRealtime(); return null }
                if (b.size < 127 || String(b, 0, 7, Charsets.US_ASCII) != "PMTiles" || b[7].toInt() != 3) {
                    note = "Bản đồ OSM: file không đúng định dạng"; failAt = android.os.SystemClock.elapsedRealtime(); return null
                }
                val h = Header(u64(b, 8), u64(b, 16).toInt(), u64(b, 40), u64(b, 56),
                    b[97].toInt() == 2, b[98].toInt() == 2, b[101].toInt() and 0xFF)
                val rb = if (h.rootOff + h.rootLen <= b.size) b.copyOfRange(h.rootOff.toInt(), (h.rootOff + h.rootLen).toInt())
                    else range(h.rootOff, h.rootLen) ?: return null
                root = dir(if (h.inGz) gunzip(rb) else rb)
                head = h
                note = ""
                return h
            } catch (e: Throwable) {
                Log.w(TAG, "header", e); note = "Bản đồ OSM lỗi: ${e.javaClass.simpleName}"
                failAt = android.os.SystemClock.elapsedRealtime()
                return null
            }
        }
    }

    private fun dir(b: ByteArray): List<Entry> {
        val r = Pb(b)
        val n = r.varint().toInt()
        val ids = LongArray(n); val runs = IntArray(n); val lens = IntArray(n); val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += r.varint(); ids[i] = last }
        for (i in 0 until n) runs[i] = r.varint().toInt()
        for (i in 0 until n) lens[i] = r.varint().toInt()
        for (i in 0 until n) {
            val v = r.varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    private fun find(es: List<Entry>, id: Long): Entry? {
        var lo = 0; var hi = es.size - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            val c = id.compareTo(es[m].id)
            if (c > 0) lo = m + 1 else if (c < 0) hi = m - 1 else return es[m]
        }
        if (hi >= 0) { val e = es[hi]; if (e.run == 0 || id - e.id < e.run) return e }
        return null
    }

    // Số thứ tự ô theo đường cong Hilbert (giống thư viện pmtiles)
    private fun tileId(z: Int, x: Int, y: Int): Long {
        var acc = 0L
        for (i in 0 until z) acc += 1L shl (2 * i)
        var xx = x.toLong(); var yy = y.toLong(); var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if ((xx and s) > 0) 1L else 0L
            val ry = if ((yy and s) > 0) 1L else 0L
            d += s * s * ((3L * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { xx = s - 1 - xx; yy = s - 1 - yy }
                val t = xx; xx = yy; yy = t
            }
            s /= 2
        }
        return acc + d
    }

    // Dữ liệu ô vector (đã giải nén), null = không có / lỗi mạng
    private fun raw(z: Int, x: Int, y: Int): ByteArray? {
        val h = header() ?: return null
        val id = tileId(z, x, y)
        var es = root
        for (depth in 0..3) {
            val e = find(es, id) ?: return null
            if (e.run > 0) {
                val b = range(h.dataOff + e.off, e.len) ?: return null
                return if (h.tileGz) gunzip(b) else b
            }
            val key = h.leafOff + e.off
            val cached = synchronized(leaves) { leaves[key] }
            es = cached ?: run {
                val b = range(key, e.len) ?: return null
                val d = dir(if (h.inGz) gunzip(b) else b)
                synchronized(leaves) { leaves[key] = d }
                d
            }
        }
        return null
    }

    // ---------- giải mã Mapbox Vector Tile (protobuf) ----------
    private class Pb(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun more() = p < end
        fun varint(): Long {
            var r = 0L; var sh = 0
            while (true) { val c = b[p++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl sh); if (c < 0x80) return r; sh += 7 }
        }
        fun bytes(): Pb { val n = varint().toInt(); val s = Pb(b, p, p + n); p += n; return s }
        fun str(): String { val n = varint().toInt(); val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
        fun skip(wire: Int) {
            when (wire) { 0 -> varint(); 1 -> p += 8; 2 -> p += varint().toInt(); 5 -> p += 4; else -> p = end }
        }
    }

    class Feat(val type: Int, val tags: Map<String, String>, val geom: List<FloatArray>)
    class Tile(val extent: Int, val layers: Map<String, List<Feat>>)

    private val WANT = setOf("water", "waterway", "landcover", "park", "building", "transportation", "transportation_name")

    private fun decode(b: ByteArray): Tile {
        val layers = HashMap<String, List<Feat>>()
        var extent = 4096
        val t = Pb(b)
        while (t.more()) {
            val k = t.varint(); val f = (k shr 3).toInt(); val w = (k and 7).toInt()
            if (f != 3 || w != 2) { t.skip(w); continue }
            val l = t.bytes()
            var name = ""; val keys = ArrayList<String>(); val vals = ArrayList<String>()
            val raws = ArrayList<Pb>()
            while (l.more()) {
                val lk = l.varint(); val lf = (lk shr 3).toInt(); val lw = (lk and 7).toInt()
                when {
                    lf == 1 && lw == 2 -> name = l.str()
                    lf == 2 && lw == 2 -> raws.add(l.bytes())
                    lf == 3 && lw == 2 -> keys.add(l.str())
                    lf == 4 && lw == 2 -> vals.add(value(l.bytes()))
                    lf == 5 && lw == 0 -> extent = l.varint().toInt()
                    else -> l.skip(lw)
                }
            }
            if (name !in WANT) continue
            layers[name] = raws.mapNotNull { feat(it, keys, vals) }
        }
        return Tile(extent, layers)
    }

    private fun value(v: Pb): String {
        var s = ""
        while (v.more()) {
            val k = v.varint(); val f = (k shr 3).toInt(); val w = (k and 7).toInt()
            when (f) {
                1 -> s = v.str()
                2 -> { val i = v.p; v.p += 4; s = java.lang.Float.intBitsToFloat(
                    (v.b[i].toInt() and 0xFF) or ((v.b[i + 1].toInt() and 0xFF) shl 8) or ((v.b[i + 2].toInt() and 0xFF) shl 16) or ((v.b[i + 3].toInt() and 0xFF) shl 24)).toString() }
                4, 5 -> s = v.varint().toString()
                6 -> { val z = v.varint(); s = ((z ushr 1) xor -(z and 1)).toString() }
                7 -> s = (v.varint() != 0L).toString()
                else -> v.skip(w)
            }
        }
        return s
    }

    private fun feat(f: Pb, keys: List<String>, vals: List<String>): Feat? {
        var type = 0; var tags: IntArray? = null; var geo: Pb? = null
        while (f.more()) {
            val k = f.varint(); val fn = (k shr 3).toInt(); val w = (k and 7).toInt()
            when {
                fn == 2 && w == 2 -> { val s = f.bytes(); val l = ArrayList<Int>(); while (s.more()) l.add(s.varint().toInt()); tags = l.toIntArray() }
                fn == 3 && w == 0 -> type = f.varint().toInt()
                fn == 4 && w == 2 -> geo = f.bytes()
                else -> f.skip(w)
            }
        }
        val g = geo ?: return null
        if (type !in 2..3) return null
        val m = HashMap<String, String>()
        tags?.let { t -> var i = 0; while (i + 1 < t.size) { val kk = keys.getOrNull(t[i]); val vv = vals.getOrNull(t[i + 1]); if (kk != null && vv != null) m[kk] = vv; i += 2 } }
        // lệnh vẽ: MoveTo (1), LineTo (2), ClosePath (7); toạ độ zigzag cộng dồn
        val parts = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()
        var x = 0; var y = 0
        while (g.more()) {
            val ci = g.varint().toInt(); val cmd = ci and 7; val cnt = ci shr 3
            when (cmd) {
                1, 2 -> repeat(cnt) {
                    val dx = g.varint(); val dy = g.varint()
                    x += ((dx ushr 1) xor -(dx and 1)).toInt(); y += ((dy ushr 1) xor -(dy and 1)).toInt()
                    if (cmd == 1 && cur.isNotEmpty()) { parts.add(cur.toFloatArray()); cur = ArrayList() }
                    cur.add(x.toFloat()); cur.add(y.toFloat())
                }
                7 -> if (cur.size >= 2) { cur.add(cur[0]); cur.add(cur[1]) }
                else -> return null
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toFloatArray())
        return Feat(type, m, parts)
    }

    private val decoded = object : LinkedHashMap<Long, Tile>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Tile>?) = size > 12
    }

    // Ô đã giải mã ở mức z (tối đa mức có trong file), dùng lại cho cả vẽ lẫn tra đường
    fun tile(z: Int, x: Int, y: Int): Tile? {
        val key = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()
        synchronized(decoded) { decoded[key]?.let { return it } }
        val b = raw(z, x, y) ?: return null
        val t = try { decode(b) } catch (e: Throwable) { Log.w(TAG, "decode $z/$x/$y", e); head = null; return null }
        synchronized(decoded) { decoded[key] = t }
        return t
    }

    fun maxZoom(): Int = header()?.maxZ ?: 14

    // ---------- vẽ ô ảnh ----------
    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }
    private fun stroke(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private const val BG = 0xFF0F1620.toInt()
    private val waterF = fill(0xFF0C2536.toInt())
    private val waterL = stroke(0xFF0C2536.toInt())
    private val parkF = fill(0xFF11251D.toInt())
    private val bldF = fill(0xFF1A2430.toInt())
    private val roadMinor = stroke(0xFF2A3644.toInt())
    private val roadMid = stroke(0xFF3A4756.toInt())
    private val roadMajor = stroke(0xFF56657A.toInt())
    private val roadMotor = stroke(0xFF8A7442.toInt())
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC8D2DC.toInt(); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER
    }
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG; typeface = labelP.typeface; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeWidth = 4f
    }

    // Vẽ ô (z, x, y) cỡ size px. Mức sâu hơn mức có trong file thì phóng to phần tương ứng của ô cha.
    fun render(z: Int, x: Int, y: Int, size: Int): Bitmap? {
        val sz = min(z, maxZoom())
        val dz = z - sz
        val t = tile(sz, x shr dz, y shr dz) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val sc = size.toFloat() * (1 shl dz) / t.extent
        val ox = -((x - ((x shr dz) shl dz)) * size).toFloat()
        val oy = -((y - ((y shr dz) shl dz)) * size).toFloat()
        val path = Path()
        fun build(f: Feat) {
            path.reset()
            for (part in f.geom) {
                var i = 0
                while (i + 1 < part.size) {
                    val px = ox + part[i] * sc; val py = oy + part[i + 1] * sc
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    i += 2
                }
            }
        }
        // bề rộng nét theo mức zoom: chuẩn ở z16 với ô 512 px
        val zf = (1 shl max(0, z - 10)).toFloat() / 64f * (size / 512f)
        fun w(base: Float) = max(0.8f, base * zf)
        for (f in t.layers["landcover"].orEmpty()) if (f.type == 3 && f.tags["class"] in GREEN) { build(f); c.drawPath(path, parkF) }
        for (f in t.layers["park"].orEmpty()) if (f.type == 3) { build(f); c.drawPath(path, parkF) }
        for (f in t.layers["water"].orEmpty()) if (f.type == 3) { build(f); c.drawPath(path, waterF) }
        waterL.strokeWidth = w(4f)
        for (f in t.layers["waterway"].orEmpty()) if (f.type == 2) { build(f); c.drawPath(path, waterL) }
        if (z >= 15) for (f in t.layers["building"].orEmpty()) if (f.type == 3) { build(f); c.drawPath(path, bldF) }
        roadMinor.strokeWidth = w(5f); roadMid.strokeWidth = w(8f); roadMajor.strokeWidth = w(11f); roadMotor.strokeWidth = w(13f)
        val roads = t.layers["transportation"].orEmpty().filter { it.type == 2 }
        for (pass in 0..3) for (f in roads) {
            val p = when (f.tags["class"]) {
                "minor", "service" -> if (pass == 0) roadMinor else null
                "tertiary", "secondary" -> if (pass == 1) roadMid else null
                "primary", "trunk" -> if (pass == 2) roadMajor else null
                "motorway" -> if (pass == 3) roadMotor else null
                else -> null
            } ?: continue
            build(f); c.drawPath(path, p)
        }
        // tên đường dọc theo đường (từ z15), chữ luôn xuôi chiều đọc
        if (z >= 15) {
            val ts = 11f * size / 256f * (if (z >= 17) 1.15f else 1f)
            labelP.textSize = ts; haloP.textSize = ts; haloP.strokeWidth = ts * 0.3f
            val pm = PathMeasure()
            for (f in t.layers["transportation_name"].orEmpty()) {
                val name = f.tags["name:vi"]?.ifBlank { null } ?: f.tags["name"]?.ifBlank { null } ?: continue
                for (part in f.geom) {
                    if (part.size < 4) continue
                    val flip = part[part.size - 2] < part[0]
                    path.reset()
                    var i = if (flip) part.size - 2 else 0
                    var first = true
                    while (i >= 0 && i + 1 < part.size) {
                        val px = ox + part[i] * sc; val py = oy + part[i + 1] * sc
                        if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
                        i += if (flip) -2 else 2
                    }
                    pm.setPath(path, false)
                    if (pm.length < labelP.measureText(name) * 1.3f) continue
                    c.drawTextOnPath(name, path, 0f, ts * 0.35f, haloP)
                    c.drawTextOnPath(name, path, 0f, ts * 0.35f, labelP)
                }
            }
        }
        return bmp
    }
    private val GREEN = setOf("grass", "wood", "farmland", "park")

    // ---------- tra đường gần vị trí xe ----------
    class Road(val name: String, val cls: String, val distM: Double)

    // Đường gần nhất (trong 30 m), ưu tiên đoạn cùng hướng xe chạy. brg = hướng xe (độ) hoặc NaN
    fun roadAt(lat: Double, lon: Double, brg: Double): Road? {
        val z = maxZoom()
        val n = (1 shl z).toDouble()
        val fx = (lon + 180.0) / 360.0 * n
        val latR = Math.toRadians(lat)
        val fy = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n
        val tx = floor(fx).toInt(); val ty = floor(fy).toInt()
        val t = tile(z, tx, ty) ?: return null
        val px = ((fx - tx) * t.extent).toFloat(); val py = ((fy - ty) * t.extent).toFloat()
        val mPerUnit = 40_075_016.0 * cos(latR) / n / t.extent
        var best: Road? = null; var bestScore = Double.MAX_VALUE
        fun scan(layer: String, named: Boolean) {
            for (f in t.layers[layer].orEmpty()) {
                if (f.type != 2) continue
                val cls = f.tags["class"] ?: continue
                if (cls == "path" || cls == "track" || cls == "rail" || cls == "transit" || cls == "ferry") continue
                val name = if (named) (f.tags["name:vi"]?.ifBlank { null } ?: f.tags["name"].orEmpty()) else ""
                for (part in f.geom) {
                    var i = 0
                    while (i + 3 < part.size) {
                        val ax = part[i]; val ay = part[i + 1]; val bx = part[i + 2]; val by = part[i + 3]
                        val dx = bx - ax; val dy = by - ay
                        val l2 = dx * dx + dy * dy
                        val u = if (l2 > 0f) (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f) else 0f
                        val ex = ax + u * dx - px; val ey = ay + u * dy - py
                        val d = sqrt((ex * ex + ey * ey).toDouble()) * mPerUnit
                        if (d <= 30.0) {
                            var score = d
                            if (!brg.isNaN() && l2 > 0f) {
                                val segBrg = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360.0) % 360.0
                                var a = abs(segBrg - brg) % 180.0; if (a > 90.0) a = 180.0 - a
                                score += a / 6.0   // lệch 90° ~ thêm 15 m
                            }
                            if (!named) score += 3.0   // cùng một đường thì lấy bản có tên
                            if (score < bestScore) { bestScore = score; best = Road(name, cls, d) }
                        }
                        i += 2
                    }
                }
            }
        }
        scan("transportation_name", true)
        scan("transportation", false)
        return best
    }
}
