package com.kevin.evcarhud

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// f, cx, cy tính theo pixel ảnh nguồn; scale = kích thước hiển thị / kích thước nguồn
class Cam(val f: Float, val cx: Float, val cy: Float, val scale: Float)

object HudRenderer {
    private const val LANE = 0
    private const val LANE_PROB = 528
    private const val EDGES = 536
    private const val POSE = 887
    private const val PLAN = 917
    private const val LEAD = 1907
    private const val LEAD_PROB = 2051
    // Chiều cao camera so với mặt đường (m), cộng vào z của quỹ đạo và dùng để đo khoảng cách theo điểm tiếp đất của hộp.
    // Chỉnh trong Cài đặt (mặc định 1,22 m như xe openpilot); đo sai 10% thì khoảng cách theo hộp sai 10%.
    @Volatile var camH = 1.22f
    // Chế độ phản chiếu kính lái: nền đen, không vẽ hình camera / làn / hộp, chỉ chữ số và viền cảnh báo (UI lật gương)
    @Volatile var reflect = false
    private const val FOCUS_RANGE_M = 60f   // chỉ làm nổi bật đối tượng trong tầm này (m)
    private const val WARN_MIN_SPEED = 2f      // m/s (~7 km/h): xe đứng yên thì không cảnh báo
    private const val WARN_MIN_DIST = 4f        // m: khoảng cách bám đuôi tối thiểu (chỉ áp dụng khi chạy nhanh)
    private const val CITY_BOX_M = 50f          // m: chế độ Trong phố chỉ vẽ hộp vật gần hơn mức này
    private const val WARN_HEADWAY_S = 1.0f     // s: bám đuôi nguy hiểm khi (khoảng cách / tốc độ xe) nhỏ hơn mức này
    private const val CRAWL_SPEED = 5.5f        // m/s (~20 km/h): dưới mức này coi là kẹt xe / bò, không báo theo khoảng cách đơn thuần
    private const val MIN_CLOSING = 0.3f        // m/s: tốc độ áp sát tối thiểu để tính là "đang lao tới"
    private const val TTC_DANGER = 1.8f         // s: dưới mức này -> nguy hiểm (tiếng tít + viền đỏ)
    private const val TTC_CAUTION = 3.5f        // s: dưới mức này -> chú ý (chỉ viền vàng, không tiếng)
    private const val TTC_DANGER_JAM = 1.2f     // s: khi Waze báo kẹt xe phía trước và đang bò -> ngưỡng chặt hơn (đỡ báo nhầm)
    private const val TTC_CAUTION_JAM = 2.5f
    private const val NEAR_DIST_JAM = 1.0f
    private const val SIGN_MIN_H = 0.035f      // chiều cao tối thiểu của biển/đèn so với chiều cao ảnh
    private const val NEAR_DIST = 1.5f          // m: quá gần mà vẫn đang áp sát -> nguy hiểm
    private const val TIGHT_MARGIN = 0.15f      // nới dải quỹ đạo khi xét vật cản KHÔNG phải xe dẫn đầu (xe máy chạy sát bên không tính)
    private const val HIST = 16
    private const val CUTIN_MIN_SPEED = 2.8f    // m/s (~10 km/h): xe mình phải đang chạy
    private const val CUTIN_RANGE_M = 15f       // chỉ xét xe trong tầm 15 m
    private const val CUTIN_DANGER_M = 7f       // gần hơn: nguy hiểm (đỏ + tiếng)
    private const val CUTIN_LAT_MS = 0.6f       // vận tốc ngang tối thiểu hướng vào đầu xe (m/s)
    private const val CUTIN_T_S = 1.2f          // sẽ chạm dải đường của mình trong vòng 1,2 s
    private const val CUTIN_MAX_YAW = 0.06f     // đang ôm cua / đánh lái thì bỏ qua (mọi vật trôi ngang trong ảnh)
    private const val WIN_MIN_US = 300_000L     // cửa sổ tính tốc độ áp sát: 0,3 – 0,9 s
    private const val WIN_MAX_US = 900_000L
    private const val ZONE_MARGIN = 0.25f   // nới dải làn mỗi bên theo tỉ lệ bề rộng làn (xe lấn một nửa vẫn tính)
    private const val HOOD_EDGE = 0.02f     // hộp có đáy cách mép capo < 2% chiều cao ảnh coi là "chạm capo"
    private const val HOOD_WIDE = 0.6f      // hộp chạm mép dưới và rộng hơn 60% khung = chính capo / taplo, không phải xe
    private val X_IDXS = FloatArray(33) { val r = it / 32f; 192f * r * r }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

    // Chi tiết lần đánh giá nguy cơ gần nhất (cho nhật ký cảnh báo); chỉ luồng hiển thị ghi/đọc
    var riskDist = Float.NaN      // m, vật cản gần nhất trên quỹ đạo
        private set
    var riskClosing = 0f          // m/s, dương = đang lại gần
        private set
    var riskTtc = Float.NaN       // s, NaN nếu không áp sát
        private set
    var riskCls = -1              // lớp COCO của vật cản; -1 = xe dẫn đầu của model, không ghép được hộp YOLO
        private set
    var riskReason = ""           // luật nào kích hoạt
        private set
    var riskBox: Detection? = null
        private set
    // Loại cảnh báo để chọn câu đọc: "ttc", "near", "tail", "gap", "cutin"
    var riskKind = ""
        private set
    var cutInLeft = false          // xe cắt đầu từ bên trái (false = bên phải)
        private set
    // Xe trước đã chạy (đang đứng yên): bật một lần, luồng gọi đọc xong thì đặt lại bằng consumeLeadMoved()
    @Volatile private var leadMovedFlag = false
    private var seenCls = -1                     // biển STOP / đèn giao thông camera vừa thấy
    private var seenAt = 0L
    private const val SEEN_MS = 4000L
    private const val BOT_Y = 578f      // đáy ô bản đồ / hướng rẽ và cụm tốc độ (khung 1280)
    fun consumeLeadMoved(): Boolean { val v = leadMovedFlag; leadMovedFlag = false; return v }
    // Biển giới hạn tốc độ đọc được (model YOLO riêng có lớp biển tốc độ); 0 = chưa thấy
    @Volatile var signLimit = 0
        private set
    @Volatile var signAtMs = 0L
        private set
    // Xoá giới hạn đọc từ biển (khi rẽ sang đường khác)
    fun clearSignLimit() { signLimit = 0 }
    // Cho hiệu chỉnh chiều cao camera: khoảng cách theo điểm tiếp đất của xe phía trước ở khung gần nhất (NaN nếu không có)
    @Volatile var calibGround = Float.NaN
        private set

    // ---- Tốc độ áp sát của vật cản gần nhất (dùng pts video, không dùng đồng hồ thật) ----
    private val histT = LongArray(HIST)
    private val histD = FloatArray(HIST)
    private var histN = 0
    private var histHead = 0
    private var dSmooth = Float.NaN
    private var closingSmooth = 0f
    private var lastPts = -1L
    // Theo dõi "xe trước đã chạy"
    private var stopSince = -1L
    private var stopLeadD = 0f
    private var movedFired = false

    // Gọi khi bắt đầu chạy video mới
    fun resetRisk() {
        stopSince = -1L
        histN = 0; histHead = 0
        dSmooth = Float.NaN
        closingSmooth = 0f
        lastPts = -1L
    }

    // d = khoảng cách vật cản gần nhất (m), NaN nếu không có. Trả về m/s, dương = đang lại gần.
    private fun trackClosing(ptsUs: Long, d: Float): Float {
        if (d.isNaN() || (lastPts >= 0 && ptsUs < lastPts)) {
            resetRisk()
            lastPts = ptsUs
            return 0f
        }
        lastPts = ptsUs
        // Nhảy khoảng cách lớn = đổi sang vật cản khác: bỏ lịch sử cũ
        if (dSmooth.isNaN() || abs(d - dSmooth) > 4f) {
            histN = 0; histHead = 0
            closingSmooth = 0f
            dSmooth = d
        } else {
            dSmooth += 0.5f * (d - dSmooth)
        }
        histT[histHead] = ptsUs
        histD[histHead] = dSmooth
        histHead = (histHead + 1) % HIST
        if (histN < HIST) histN++

        var oldAge = 0L
        var oldD = dSmooth
        for (i in 0 until histN) {
            val age = ptsUs - histT[i]
            if (age in WIN_MIN_US..WIN_MAX_US && age > oldAge) { oldAge = age; oldD = histD[i] }
        }
        if (oldAge > 0L) {
            val raw = (oldD - dSmooth) / (oldAge / 1e6f)
            closingSmooth = 0.5f * closingSmooth + 0.5f * raw
        }
        return closingSmooth
    }

    private fun proj(c: Cam, x: Float, y: Float, z: Float): PointF? {
        if (x < 3f) return null
        return PointF((c.cx + c.f * y / x) * c.scale, (c.cy + c.f * z / x) * c.scale)
    }

    private fun pathOf(pts: List<PointF?>): Path {
        val p = Path()
        var open = false
        for (q in pts) {
            if (q == null) { open = false; continue }
            if (!open) { p.moveTo(q.x, q.y); open = true } else p.lineTo(q.x, q.y)
        }
        return p
    }

    private fun stroke(argb: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    private fun fillP(argb: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb.toInt(); style = Paint.Style.FILL
    }

    private val greenLine = stroke(0xFF4FC3F7)
    private val laneGlow = stroke(0x554FC3F7)
    private val farLine = stroke(0xAAFFFFFF)
    private val edgeLine = stroke(0xFFFFFFFF)
    private val boxLine = stroke(0xFFFFFFFF)
    private val zoneFill = fillP(0x6600A060)
    private val pathFill = fillP(0x7799F0C0)
    private val leadFill = fillP(0xFFFFC107)
    private val boxFill = fillP(0xAA202020)
    private val focusFill = fillP(0x26FFC107)
    private val pillFill = fillP(0xFFFFC107)
    private val hoodFill = fillP(0x99000000)
    private val hoodLine = stroke(0x88FFFFFF)
    private val badgeFill = fillP(0xCC202020)
    private val redFill = fillP(0xFFD32F2F)
    private val bodyFill = fillP(0xFF37474F)
    private val lampFill = fillP(0xFF78909C)
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgeNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val badgeSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFFB0B0B0.toInt(); textAlign = Paint.Align.CENTER
    }
    private val limitText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFF202020.toInt(); textAlign = Paint.Align.CENTER
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt()
    }
    private val streetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFF90CAF9.toInt()
    }
    private val bracketLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt(); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFF9BE89B.toInt()
    }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt()
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val panelLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFFFFC107.toInt()
        textAlign = Paint.Align.RIGHT
    }

    private fun classColor(c: Int): Int = when (c) {
        2 -> 0xFF4FC3F7.toInt()   // ô tô
        3 -> 0xFFFFA726.toInt()   // xe máy
        5, 7 -> 0xFFBA68C8.toInt() // xe buýt, xe tải
        0 -> 0xFFF06292.toInt()   // người
        else -> 0xFFC6FF00.toInt() // xe đạp
    }

    // ---- Làn vàng AR khi sắp rẽ (giống trang giả lập ar_lane) ----
    private const val AR_RANGE_M = 150
    private val arFill = fillP(0xFFFFC107)
    private val arEdge = stroke(0xE6FFD54F)
    private val arChev = fillP(0xF2FFFFFF)

    // Tuyến trên mặt đường (lat: ngang, phải dương; fwd: phía trước, m): thẳng tới chỗ rẽ rồi cong. null = không vẽ (đi thẳng, vòng xuyến, tới nơi)
    private fun arLanePath(code: Int, dist: Float): List<FloatArray>? {
        val p = ArrayList<FloatArray>()
        val z0 = 3.2f
        val zt = max(z0 + 1f, dist)
        var z = z0
        while (z < zt) { p.add(floatArrayOf(0f, z)); z += 0.5f }
        fun arc(r: Float, deg: Float, side: Float) {
            val a = Math.toRadians(deg.toDouble()).toFloat()
            for (i in 0..40) {
                val t = a * i / 40f
                p.add(floatArrayOf(side * (r - r * cos(t)), zt + r * sin(t)))
            }
            val ex = side * (r - r * cos(a)); val ez = zt + r * sin(a)
            val dx = side * sin(a); val dz = cos(a)
            for (s in 1..40) p.add(floatArrayOf(ex + dx * s, ez + dz * s))
        }
        when (code) {
            3 -> arc(7f, 90f, 1f)
            2 -> arc(13f, 90f, -1f)
            5, 14, 16 -> arc(25f, 32f, 1f)
            4, 13, 15 -> arc(25f, 32f, -1f)
            7 -> arc(6f, 135f, 1f)
            6 -> arc(10f, 135f, -1f)
            8 -> {                       // quay đầu (bên trái)
                val r = 4.5f
                for (i in 0..50) {
                    val t = (Math.PI * i / 50).toFloat()
                    p.add(floatArrayOf(-(r - r * cos(t)), zt + r * sin(t)))
                }
                var s = 1
                while (s <= 25 && zt - s >= max(4f, zt - 14f)) { p.add(floatArrayOf(-2f * r, zt - s)); s++ }
            }
            else -> return null
        }
        val q = ArrayList<FloatArray>()
        for (a in p) { if (a[1] < 3.2f && q.size > 5) break; q.add(a) }
        return q
    }

    private fun drawArLane(canvas: Canvas, cam: Cam, p: List<FloatArray>, dist: Float, animSec: Float, k: Float) {
        val h = camH
        fun pr(lat: Float, fwd: Float) = proj(cam, fwd, lat, h)
        // Mép trái / phải của dải rộng 3,2 m
        val wd = 1.6f
        val l = ArrayList<PointF>(); val r = ArrayList<PointF>()
        for (i in p.indices) {
            val a = p[max(0, i - 1)]; val b = p[min(p.size - 1, i + 1)]
            var dx = b[0] - a[0]; var dz = b[1] - a[1]
            val n = kotlin.math.hypot(dx, dz).takeIf { it > 0f } ?: 1f
            dx /= n; dz /= n
            val pl = pr(p[i][0] - dz * wd, p[i][1] + dx * wd)
            val pq = pr(p[i][0] + dz * wd, p[i][1] - dx * wd)
            if (pl == null || pq == null) { if (l.size > 1) break else { l.clear(); r.clear(); continue } }
            l.add(pl); r.add(pq)
        }
        if (l.size < 2) return
        var top = Float.MAX_VALUE; var bot = 0f
        for (q in l) { top = min(top, q.y); bot = max(bot, q.y) }
        for (q in r) { top = min(top, q.y); bot = max(bot, q.y) }
        if (bot - top < 1f) bot = top + 1f
        arFill.shader = LinearGradient(0f, bot, 0f, top,
            intArrayOf(0x9EFFC107.toInt(), 0x61FFC107, 0x26FFC107), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        polygon(canvas, l, r, arFill)
        arEdge.strokeWidth = 2.5f * k
        canvas.drawPath(pathOf(l), arEdge)
        canvas.drawPath(pathOf(r), arEdge)

        // Mũi tên (chevron) trắng trên mặt đường, chạy dọc tuyến 9 m/s, cách nhau 6 m
        val cum = FloatArray(p.size)
        for (i in 1 until p.size) cum[i] = cum[i - 1] + kotlin.math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1])
        val tot = cum[p.size - 1]
        val sp = 6f
        var s = (animSec * 9f) % sp
        var i = 1
        val chev = Path()
        while (s < min(tot, dist + 40f)) {
            while (i < p.size - 1 && cum[i] < s) i++
            val a = p[i - 1]; val b = p[i]
            val seg = (cum[i] - cum[i - 1]).takeIf { it > 0f } ?: 1f
            val f = (s - cum[i - 1]) / seg
            val x = a[0] + (b[0] - a[0]) * f; val z = a[1] + (b[1] - a[1]) * f
            val dx = (b[0] - a[0]) / seg; val dz = (b[1] - a[1]) / seg
            s += sp
            if (z < 3.3f) continue
            val nx = dz; val nz = -dx
            val len = 1.3f; val w = 0.85f; val th = 0.5f
            val tip = floatArrayOf(x + dx * len, z + dz * len)
            val cl = floatArrayOf(x - dx * 0.2f + nx * w, z - dz * 0.2f + nz * w)
            val cr = floatArrayOf(x - dx * 0.2f - nx * w, z - dz * 0.2f - nz * w)
            val pts = listOf(cl, tip, cr,
                floatArrayOf(cr[0] + dx * th, cr[1] + dz * th),
                floatArrayOf(tip[0] - dx * th, tip[1] - dz * th),
                floatArrayOf(cl[0] + dx * th, cl[1] + dz * th)).map { pr(it[0], it[1]) }
            if (pts.any { it == null }) continue
            chev.reset()
            pts.forEachIndexed { j, q -> if (j == 0) chev.moveTo(q!!.x, q.y) else chev.lineTo(q!!.x, q.y) }
            chev.close()
            val fade = ((dist + 30f - z) / 30f).coerceIn(0f, 1f)
            arChev.alpha = (242 * fade).toInt()
            canvas.drawPath(chev, arChev)
        }
    }

    private fun polygon(canvas: Canvas, a: List<PointF>, b: List<PointF>, paint: Paint) {
        if (a.size < 2) return
        val p = Path()
        p.moveTo(a[0].x, a[0].y)
        for (q in a) p.lineTo(q.x, q.y)
        for (q in b.asReversed()) p.lineTo(q.x, q.y)
        p.close()
        canvas.drawPath(p, paint)
    }

    // Ghép "điểm tiếp đất" của xe dẫn đầu (từ supercombo) với hộp YOLO gần nhất (chỉ phương tiện)
    private fun matchLead(dets: List<Detection>, qx: Float, qy: Float, s: Float): Detection? {
        var best: Detection? = null
        var bestD = Float.MAX_VALUE
        for (d in dets) {
            val w = (d.x2 - d.x1) * s
            val h = (d.y2 - d.y1) * s
            if (w < 1f || h < 1f) continue
            val bx = (d.x1 + d.x2) * 0.5f * s
            val dx = abs(qx - bx) / w
            val dy = (qy - d.y2 * s) / h
            if (dx > 0.7f || dy < -0.7f || dy > 0.5f) continue
            // Ưu tiên phương tiện; hộp "người" chỉ là phương án dự phòng (thường là người lái xe máy)
            val dist = dx + abs(dy) + (if (d.cls == 0) 0.4f else 0f)
            if (dist < bestD) { bestD = dist; best = d }
        }
        return best
    }

    // Khoảng cách ước lượng từ điểm tiếp đất của hộp (mặt đường phẳng, camera cao camH)
    private fun groundDist(cam: Cam, d: Detection): Float {
        val dy = d.y2 - cam.cy
        return if (dy < 2f) Float.MAX_VALUE else cam.f * camH / dy
    }

    // Bề ngang thật theo loại (m), để đo khoảng cách theo bề rộng hộp (nhìn từ phía sau)
    private fun classWidth(c: Int): Float = when (c) {
        0 -> 0.5f; 1 -> 0.6f; 2 -> 1.8f; 3 -> 0.75f; 5 -> 2.5f; 7 -> 2.3f
        else -> 0f
    }
    private var lastSrcW = 0f

    // Khoảng cách kết hợp: điểm tiếp đất (60%) + bề ngang hộp theo loại xe (40%). Hộp bị cắt ở mép khung thì chỉ dùng điểm tiếp đất.
    private fun objDist(cam: Cam, d: Detection): Float {
        val g = groundDist(cam, d)
        val wr = classWidth(d.cls)
        val bw = d.x2 - d.x1
        val edge = d.x1 < 2f || (lastSrcW > 0f && d.x2 > lastSrcW - 2f)
        val wd = if (wr > 0f && bw > 4f && !edge) cam.f * wr / bw else Float.NaN
        return when {
            wd.isNaN() -> g
            g == Float.MAX_VALUE -> wd
            else -> 0.6f * g + 0.4f * wd
        }
    }

    // Hai mép (x trái, x phải) của dải tại hàng ảnh py (toạ độ hiển thị); null nếu ngoài dải
    private fun bandAt(l: List<PointF>, r: List<PointF>, py: Float): FloatArray? {
        val n = minOf(l.size, r.size)
        if (n < 2) return null
        fun yAt(i: Int) = (l[i].y + r[i].y) * 0.5f
        var seg = -1
        for (i in 0 until n - 1) {
            if (yAt(i) > yAt(i + 1) && yAt(i) >= py && py >= yAt(i + 1)) { seg = i; break }
        }
        if (seg < 0) { if (yAt(0) > yAt(1) && py > yAt(0)) seg = 0 else return null }
        val ya = yAt(seg); val yb = yAt(seg + 1)
        val t = ((ya - py) / (ya - yb)).coerceIn(-2f, 1f)
        val lx = l[seg].x + t * (l[seg + 1].x - l[seg].x)
        val rx = r[seg].x + t * (r[seg + 1].x - r[seg].x)
        return if (rx > lx) floatArrayOf(lx, rx) else null
    }

    // Điểm (px, py) có nằm trong dải giữa hai đường biên (đã chiếu lên ảnh) không.
    // Nội suy bề rộng tại hàng ảnh py rồi nới thêm margin * bề rộng mỗi bên.
    private fun inBand(l: List<PointF>, r: List<PointF>, px: Float, py: Float, margin: Float): Boolean {
        val n = minOf(l.size, r.size)
        if (n < 2) return false
        fun yAt(i: Int) = (l[i].y + r[i].y) * 0.5f
        var seg = -1
        for (i in 0 until n - 1) {
            if (yAt(i) > yAt(i + 1) && yAt(i) >= py && py >= yAt(i + 1)) { seg = i; break }
        }
        if (seg < 0) {
            // Gần hơn điểm đầu tiên (< ~3 m): ngoại suy theo đoạn đầu; xa hơn điểm cuối: bỏ
            if (yAt(0) > yAt(1) && py > yAt(0)) seg = 0 else return false
        }
        val ya = yAt(seg)
        val yb = yAt(seg + 1)
        val t = ((ya - py) / (ya - yb)).coerceIn(-2f, 1f)
        val lx = l[seg].x + t * (l[seg + 1].x - l[seg].x)
        val rx = r[seg].x + t * (r[seg + 1].x - r[seg].x)
        val wd = rx - lx
        if (wd <= 0f) return false
        return px >= lx - margin * wd && px <= rx + margin * wd
    }

    // Viền bốn góc (chữ L) như khung AEB
    private fun brackets(canvas: Canvas, r: RectF, len: Float, paint: Paint) {
        val l = minOf(len, r.width() / 2.5f, r.height() / 2.5f)
        val p = Path()
        p.moveTo(r.left, r.top + l); p.lineTo(r.left, r.top); p.lineTo(r.left + l, r.top)
        p.moveTo(r.right - l, r.top); p.lineTo(r.right, r.top); p.lineTo(r.right, r.top + l)
        p.moveTo(r.right, r.bottom - l); p.lineTo(r.right, r.bottom); p.lineTo(r.right - l, r.bottom)
        p.moveTo(r.left + l, r.bottom); p.lineTo(r.left, r.bottom); p.lineTo(r.left, r.bottom - l)
        canvas.drawPath(p, paint)
    }

    // Màu hộp theo trạng thái (không theo loại xe): xanh lá = an toàn, vàng = chú ý, đỏ = dễ va chạm,
    // xanh dương mờ = đối tượng xung quanh, ngoài dải đường phía trước
    private const val SAFE_C = 0xFF00E676.toInt()
    private const val CAUTION_C = 0xFFFFC107.toInt()
    private const val DANGER_C = 0xFFFF1744.toInt()
    private const val SIDE_C = 0x9940A9FF.toInt()

    // Đối tượng xung quanh: viền mảnh xanh dương mờ, không nhãn
    private fun drawDim(canvas: Canvas, d: Detection, s: Float, k: Float) {
        val r = RectF(d.x1 * s, d.y1 * s, d.x2 * s, d.y2 * s)
        boxLine.color = SIDE_C
        boxLine.strokeWidth = 2f * k
        canvas.drawRect(r, boxLine)
    }

    // Đối tượng trong dải đường phía trước: khung bo góc viền sáng + nền mờ cùng màu trạng thái, không nhãn chữ
    // (loại xe và khoảng cách đã hiện ở giữa dải trên cùng). Quầng sáng = nét rộng trong mờ, không dùng blur cho nhẹ CPU
    private val focusGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private fun drawFocus(canvas: Canvas, d: Detection, s: Float, k: Float, isLead: Boolean, color: Int) {
        val pad = 6f * k
        val r = RectF(d.x1 * s - pad, d.y1 * s - pad, d.x2 * s + pad, d.y2 * s + pad)
        val rad = minOf(14f * k, minOf(r.width(), r.height()) * 0.18f)
        focusFill.color = (color and 0x00FFFFFF) or 0x26000000
        canvas.drawRoundRect(r, rad, rad, focusFill)
        focusGlow.color = (color and 0x00FFFFFF) or 0x55000000
        focusGlow.strokeWidth = (if (isLead) 12f else 9f) * k
        canvas.drawRoundRect(r, rad, rad, focusGlow)
        boxLine.color = color
        boxLine.strokeWidth = (if (isLead) 3.5f else 2.5f) * k
        canvas.drawRoundRect(r, rad, rad, boxLine)
    }

    // Khoảng cách an toàn tối thiểu khi chạy cao tốc (mặt đường khô, bằng phẳng), theo quy định hiện hành:
    // 60 km/h: 35 m; >60–80: 55 m; >80–100: 70 m; >100–120: 100 m. Dưới 60 km/h: không có mức cố định (trả 0).
    // Trời mưa / sương mù: tăng thêm 30%.
    fun highwayGap(kmh: Float, wet: Boolean): Float {
        val v = kmh.roundToInt()
        val g = when {
            v < 60 -> return 0f
            v <= 60 -> 35f
            v <= 80 -> 55f
            v <= 100 -> 70f
            else -> 100f
        }
        return if (wet) g * WET_GAIN else g
    }
    private const val WET_GAIN = 1.3f
    // Chế độ đường
    const val MODE_CITY = 0      // Trong phố: TTC + bám đuôi, kẹt xe không kêu
    const val MODE_HIGHWAY = 1   // Cao tốc: thêm khoảng cách tối thiểu theo luật từ 60 km/h
    const val MODE_RAIN = 2      // Mưa: phanh dài hơn -> báo sớm hơn (TTC dài hơn, giãn cách 1,5 s, khoảng cách luật +30%)
    private const val TTC_DANGER_RAIN = 2.5f
    private const val TTC_CAUTION_RAIN = 4.5f
    private const val HEADWAY_RAIN_S = 1.5f
    private const val HW_DANGER = 0.5f   // cao tốc: gần hơn 50% khoảng cách tối thiểu -> nguy hiểm (tiếng tít)

    // ---- Kiểu chữ HUD: mảnh, hình học. Có file assets/fonts/hud.ttf thì dùng (gợi ý: Exo 2 ExtraLight), không thì Roboto Thin ----
    private var thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private var light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    // Số khoảng cách ở giữa: chữ đậm. Có assets/fonts/hud_bold.ttf thì dùng (gợi ý: Exo 2 Bold)
    private var bold: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
    fun setFont(t: Typeface?) { if (t != null) { thin = t; light = t } }
    fun setBoldFont(t: Typeface?) { if (t != null) bold = t }

    private val tBig = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tSmall = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topShade = Paint()
    private var shadeKey = -1f

    // Dải tối mờ phía trên để chữ đọc được trên mọi khung hình
    private fun drawTopShade(canvas: Canvas, w: Int, k: Float) {
        val h = 330f * k
        if (shadeKey != h) {
            shadeKey = h
            topShade.shader = LinearGradient(0f, 0f, 0f, h, 0xB0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h, topShade)
    }

    // Chữ có khoảng cách ký tự (nhãn nhỏ in hoa)
    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align,
                      face: Typeface = light) {
        tSmall.typeface = face
        tSmall.textSize = size
        tSmall.color = color
        tSmall.letterSpacing = 0.18f
        tSmall.textAlign = align
        canvas.drawText(text, x, y, tSmall)
        tSmall.letterSpacing = 0f
    }

    // ---- Đồng hồ tốc độ (góc trên phải), kiểu vòng tròn tối ----
    // Đĩa tối, rãnh tròn xám đậm có vạch chia nhỏ; cung giá trị bắt đầu ở đáy (6 giờ) chạy theo chiều kim đồng hồ
    // qua trái lên đỉnh, đầu cung hồng đỏ chuyển nhanh sang xanh ngọc rồi xanh lá neon; số tốc độ chữ mảnh, "km/h" bên dưới.
    // Vượt giới hạn > 3 km/h: cả cung và số chuyển đỏ, cung chớp.
    private const val NEON = 0xFF39FF6A.toInt()
    private const val G_SWEEP = 340f        // cung đầy = 340°, chừa khe nhỏ ở đáy
    private val gArc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val gLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val gBg = fillP(0xE60F131A)
    private val gArcRect = RectF()
    private var gKey = ""
    private var gGrad: Shader? = null

    private fun gaugeShader(cx: Float, cy: Float, over: Boolean) {
        val key = "$cx|$cy|$over"
        if (key == gKey) return
        gKey = key
        val c = if (over) intArrayOf(0xFFB0002A.toInt(), 0xFFFF1744.toInt(), 0xFFFF5252.toInt(), 0xFFFF5252.toInt())
                else intArrayOf(0xFFFF2E63.toInt(), 0xFF2EE6A6.toInt(), NEON, NEON)
        // SweepGradient bắt đầu ở 0° (3 giờ); xoay 90° để màu đầu nằm ở đáy (điểm bắt đầu cung)
        val sg = SweepGradient(cx, cy, c, floatArrayOf(0f, 0.22f, 0.6f, 1f))
        sg.setLocalMatrix(android.graphics.Matrix().apply { setRotate(90f, cx, cy) })
        gGrad = sg
    }

    private fun drawSpeedBadge(canvas: Canvas, cx: Float, cy: Float, r: Float, speedKmh: Float, limitKmh: Int, k: Float, animSec: Float) {
        val over = limitKmh > 0 && speedKmh > limitKmh + 3f
        val flashOff = over && ((animSec * 4f).toInt() % 2 == 1)
        canvas.drawCircle(cx, cy, r, gBg)

        // rãnh tròn
        val wA = 12f * k
        val rA = r - 18f * k
        gArcRect.set(cx - rA, cy - rA, cx + rA, cy + rA)
        gArc.shader = null
        gArc.strokeWidth = wA
        gArc.color = 0xFF262C36.toInt()
        canvas.drawCircle(cx, cy, rA, gArc)
        // vạch chia nhỏ trên rãnh (mỗi 30°)
        gLine.color = 0x55FFFFFF
        gLine.strokeWidth = 2f * k
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            canvas.drawLine(cx + (rA - 3f * k) * c, cy + (rA - 3f * k) * s, cx + (rA + 3f * k) * c, cy + (rA + 3f * k) * s, gLine)
        }

        val scale = max(120f, limitKmh + 20f)
        val frac = (speedKmh / scale).coerceIn(0f, 1f)
        if (frac > 0.005f) {
            gaugeShader(cx, cy, over)
            gArc.shader = gGrad
            // quầng sáng mờ phía dưới
            gArc.alpha = if (flashOff) 20 else 55
            gArc.strokeWidth = wA * 2.4f
            canvas.drawArc(gArcRect, 90f, G_SWEEP * frac, false, gArc)
            gArc.alpha = if (flashOff) 70 else 255
            gArc.strokeWidth = wA
            canvas.drawArc(gArcRect, 90f, G_SWEEP * frac, false, gArc)
            gArc.shader = null
            gArc.alpha = 255
        }
        // vạch giới hạn tốc độ
        if (limitKmh > 0) {
            val a = Math.toRadians(90.0 + G_SWEEP * (limitKmh / scale).coerceIn(0f, 1f))
            gLine.color = 0xEEFFFFFF.toInt()
            gLine.strokeWidth = 3f * k
            canvas.drawLine(cx + (rA - wA) * cos(a).toFloat(), cy + (rA - wA) * sin(a).toFloat(),
                cx + (rA + wA) * cos(a).toFloat(), cy + (rA + wA) * sin(a).toFloat(), gLine)
        }

        // số tốc độ
        val txt = if (speedKmh > 1f) speedKmh.roundToInt().toString() else "--"
        tBig.typeface = thin
        tBig.textAlign = Paint.Align.CENTER
        tBig.color = if (over) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt()
        tBig.textSize = (if (txt.length >= 3) 76f else 90f) * k
        canvas.drawText(txt, cx, cy + 22f * k, tBig)
        tSmall.typeface = medium; tSmall.letterSpacing = 0f; tSmall.textAlign = Paint.Align.CENTER
        tSmall.textSize = 22f * k; tSmall.color = 0xFFFFFFFF.toInt()
        canvas.drawText("km/h", cx, cy + 62f * k, tSmall)
    }

    // Viền cảnh báo mảnh bao cả khung hình, từ mép trên xuống tới vạch capo (không tính capo)
    private val alertLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val alertFill = fillP(0x14FF1744)
    private fun drawAlertFrame(canvas: Canvas, w: Int, top: Float, bottom: Float, k: Float, state: Int, animSec: Float) {
        if (state <= 0 || bottom - top < 40f * k) return
        val inset = 6f * k
        val r = RectF(inset, top + inset, w - inset, bottom - inset)
        if (state >= 2) {
            if ((animSec / 0.15f).toInt() % 2 == 1) return      // chớp
            canvas.drawRoundRect(r, 16f * k, 16f * k, alertFill)
            alertLine.color = DANGER_C
            alertLine.strokeWidth = 5f * k
        } else {
            alertLine.color = CAUTION_C
            alertLine.strokeWidth = 3.5f * k
        }
        canvas.drawRoundRect(r, 16f * k, 16f * k, alertLine)
    }

    // Khoảng cách tối thiểu theo chế độ: Cao tốc theo luật (+30% nếu thời tiết báo mưa), Mưa luôn +30%, Trong phố không có
    private fun modeGap(mode: Int, kmh: Float, wet: Boolean): Float = when (mode) {
        MODE_HIGHWAY -> highwayGap(kmh, wet)
        MODE_RAIN -> highwayGap(kmh, true)
        else -> 0f
    }

    // Nhãn chế độ kiểu "LANE ASSIST": hai vạch làn đứt khúc chụm lên trên + chữ in hoa giãn
    private val modeIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    // Biển giới hạn tốc độ kiểu P.127: nền trắng, viền đỏ, số đen; vượt giới hạn thì viền chớp
    private val signWhite = fillP(0xFFFFFFFF)
    private fun drawLimitSign(canvas: Canvas, cx: Float, cy: Float, r: Float, limit: Int, over: Boolean, k: Float, animSec: Float) {
        canvas.drawCircle(cx, cy, r, signWhite)
        badgeRing.color = 0xFFE53935.toInt()
        badgeRing.strokeWidth = r * 0.2f
        if (!over || (animSec / 0.4f).toInt() % 2 == 0) canvas.drawCircle(cx, cy, r - r * 0.1f, badgeRing)
        val txt = limit.toString()
        limitText.typeface = bold
        limitText.textSize = if (txt.length >= 3) r * 0.72f else r * 0.95f
        val fm = limitText.fontMetrics
        canvas.drawText(txt, cx, cy - (fm.ascent + fm.descent) / 2f, limitText)
    }

    // Biển STOP (bát giác đỏ) hoặc đèn giao thông camera vừa thấy, hiện vài giây
    private fun drawSeenSign(canvas: Canvas, cx: Float, cy: Float, r: Float, cls: Int, k: Float) {
        if (cls == 11) {
            val p = Path()
            for (i in 0 until 8) {
                val a = Math.toRadians(22.5 + 45.0 * i)
                val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            canvas.drawPath(p, redFill)
            badgeNum.textSize = r * 0.62f
            badgeNum.typeface = bold
            val fm = badgeNum.fontMetrics
            canvas.drawText("STOP", cx, cy - (fm.ascent + fm.descent) / 2f, badgeNum)
        } else {
            val bw = r * 0.95f
            canvas.drawRoundRect(RectF(cx - bw / 2f, cy - r, cx + bw / 2f, cy + r), r * 0.25f, r * 0.25f, bodyFill)
            for (i in -1..1) canvas.drawCircle(cx, cy + i * r * 0.62f, r * 0.24f, lampFill)
        }
    }

    // Góc phải dưới nút Cài đặt: 3 icon cùng cỡ, chữ nhỏ bên dưới: chế độ đường, GPS, CAMERA.
    // GPS / CAMERA: 0 = tốt (xanh), 1 = yếu / chậm (cam), 2 = mất (đỏ)
    private val stColor = intArrayOf(0xFF00E676.toInt(), 0xFFFFA726.toInt(), 0xFFFF1744.toInt())
    private val iconFill = fillP(0xFFFFFFFF)
    private val iconRect = RectF()
    private fun drawStatusBar(canvas: Canvas, right: Float, y: Float, k: Float, mode: Int, gps: Int, camSt: Int) {
        val (text, mc) = when (mode) {
            MODE_HIGHWAY -> "CAO TỐC" to 0xFFFFFFFF.toInt()
            MODE_RAIN -> "TRỜI MƯA" to 0xFF8FD8FF.toInt()
            else -> "TRONG PHỐ" to 0xFFFFFFFF.toInt()
        }
        val gc = stColor[gps.coerceIn(0, 2)]
        val cc = stColor[camSt.coerceIn(0, 2)]
        modeIcon.strokeWidth = 3f * k
        modeIcon.strokeJoin = Paint.Join.ROUND
        // CAMERA
        var x = right - 26f * k
        modeIcon.color = cc; iconFill.color = cc
        iconRect.set(x - 16f * k, y - 9f * k, x + 16f * k, y + 13f * k)
        canvas.drawRoundRect(iconRect, 5f * k, 5f * k, modeIcon)
        canvas.drawRect(x - 7f * k, y - 14f * k, x + 5f * k, y - 9f * k, iconFill)
        canvas.drawCircle(x, y + 2f * k, 6f * k, modeIcon)
        label(canvas, "CAMERA", x, y + 38f * k, 13f * k, cc, Paint.Align.CENTER, medium)
        // GPS: ghim vị trí
        x -= 76f * k
        modeIcon.color = gc; iconFill.color = gc
        iconRect.set(x - 10f * k, y - 15f * k, x + 10f * k, y + 5f * k)
        val pin = Path()
        pin.arcTo(iconRect, 153f, 234f, true)
        pin.lineTo(x, y + 15f * k)
        pin.close()
        canvas.drawPath(pin, modeIcon)
        canvas.drawCircle(x, y - 5f * k, 3.5f * k, iconFill)
        label(canvas, "GPS", x, y + 38f * k, 13f * k, gc, Paint.Align.CENTER, medium)
        // Chế độ đường
        x -= 84f * k
        modeIcon.color = mc
        when (mode) {
            MODE_HIGHWAY -> {
                val h = 26f * k
                val base = y + 13f * k
                for (side in 0..1) {
                    val sgn = if (side == 0) -1f else 1f
                    for (i in 0 until 3) {
                        val t0 = i / 3f; val t1 = t0 + 0.2f
                        canvas.drawLine(x + sgn * (13f - 8f * t0) * k, base - h * t0, x + sgn * (13f - 8f * t1) * k, base - h * t1, modeIcon)
                    }
                }
            }
            MODE_RAIN -> {
                val cloud = Path()
                iconRect.set(x - 15f * k, y - 10f * k, x + 1f * k, y + 6f * k); cloud.arcTo(iconRect, 90f, 180f, true)
                iconRect.set(x - 8f * k, y - 17f * k, x + 12f * k, y + 3f * k); cloud.arcTo(iconRect, 180f, 160f, false)
                iconRect.set(x + 3f * k, y - 7f * k, x + 17f * k, y + 7f * k); cloud.arcTo(iconRect, 270f, 180f, false)
                cloud.close()
                canvas.drawPath(cloud, modeIcon)
                for (dx in intArrayOf(-8, 0, 8)) canvas.drawLine(x + dx * k, y + 11f * k, x + (dx - 3) * k, y + 18f * k, modeIcon)
            }
            else -> {
                modeIcon.strokeWidth = 2.5f * k
                canvas.drawRect(x - 15f * k, y - 4f * k, x - 6f * k, y + 14f * k, modeIcon)
                canvas.drawRect(x - 5f * k, y - 15f * k, x + 5f * k, y + 14f * k, modeIcon)
                canvas.drawRect(x + 6f * k, y - 8f * k, x + 15f * k, y + 14f * k, modeIcon)
            }
        }
        label(canvas, text, x, y + 38f * k, 13f * k, mc, Paint.Align.CENTER, medium)
    }

    // Tốc độ: số lớn chữ đậm vừa canh giữa tại cx, "km/h" bên phải cùng chân chữ (giống "m" của khoảng cách).
    // Vượt giới hạn > 3 km/h: số đỏ nhấp nháy
    private fun drawSpeedText(canvas: Canvas, cx: Float, baseline: Float, speedKmh: Float, limitKmh: Int, k: Float, animSec: Float) {
        val over = limitKmh > 0 && speedKmh > limitKmh + 3f
        val off = over && ((animSec * 4f).toInt() % 2 == 1)
        val txt = if (speedKmh > 1f) speedKmh.roundToInt().toString() else "--"
        tBig.typeface = medium
        tBig.textAlign = Paint.Align.CENTER
        tBig.textSize = 136f * k
        tBig.color = if (over) (if (off) 0x59FF5252 else 0xFFFF5252.toInt()) else 0xFFFFFFFF.toInt()
        canvas.drawText(txt, cx, baseline, tBig)
        val nw = tBig.measureText(txt)
        label(canvas, "km/h", cx + nw / 2f + 10f * k, baseline, 38f * k, 0xFFE0E6EA.toInt(), Paint.Align.LEFT, medium)
    }

    // Trả về mức cảnh báo: 0 = bình thường, 1 = chú ý (viền vàng, không tiếng), 2 = nguy hiểm (tiếng tít + viền đỏ)
    // ptsUs: thời điểm khung video (µs). limitKmh: giới hạn tốc độ từ bản đồ/Waze (0 = không có dữ liệu).
    // jamAhead: Waze báo kẹt xe phía trước -> khi đang bò chậm thì nới để bớt báo nhầm.
    fun draw(
        canvas: Canvas, o: FloatArray, cam: Cam, w: Int, speedKmh: Float, dets: List<Detection>,
        ptsUs: Long, limitKmh: Int = 0, jamAhead: Boolean = false,
        clockText: String = "", streetText: String = "", weatherText: String = "",
        turn: TurnInfo? = null, animSec: Float = 0f,
        warnSpeedKmh: Float = speedKmh,  // tốc độ dùng cho cảnh báo va chạm (khác tốc độ hiển thị)
        srcH: Int = 0,                   // chiều cao ảnh nguồn (pixel gốc)
        hoodFrac: Float = 0f,            // tỉ lệ đáy khung bị capo che (0 = không che): bỏ qua cho hộp, cảnh báo và lớp vẽ đường
        roadMode: Int = MODE_CITY,       // chế độ đường: Trong phố / Cao tốc / Mưa (xem MODE_*)
        wet: Boolean = false,            // thời tiết báo mưa / sương mù: chế độ Cao tốc tăng khoảng cách tối thiểu
        alertFrame: Int = 0,             // viền cảnh báo đang bật (do luồng gọi giữ nhịp): 0 = không, 1 = vàng, 2 = đỏ chớp
        nav: HereNav? = null,            // chỉ đường HERE: ô bản đồ bên trái
        gpsState: Int = 0,               // icon GPS: 0 tốt, 1 yếu, 2 mất
        camState: Int = 0,               // icon CAMERA: 0 tốt, 1 chậm, 2 mất kết quả nhận diện
        perfText: String = "",           // góc trái dưới, trên vạch capo: nhiệt CPU, pin, nhịp NPU / YOLO
        perfHot: Boolean = false         // CPU >= 70°C: dòng trên chuyển đỏ
    ): Int {
        if (o.size < 2054) return 0
        val k = w / 1280f
        val srcW = w / cam.scale
        val imgH = if (srcH > 0) srcH.toFloat() else cam.cy * 2f
        val hoodY = imgH * (1f - hoodFrac.coerceIn(0f, 0.6f))   // mép trên vùng capo (pixel gốc)
        val hoodYd = hoodY * cam.scale
        lastSrcW = srcW
        val show = !reflect
        if (reflect) canvas.drawColor(0xFF000000.toInt())

        // Vùng capo: tô tối + vạch mảnh để biết phần nào đang bị bỏ qua; lớp vẽ đường chỉ vẽ phía trên vạch này
        if (show && hoodFrac > 0f) {
            canvas.drawRect(0f, hoodYd, w.toFloat(), imgH * cam.scale, hoodFill)
            hoodLine.strokeWidth = 2f * k
            canvas.drawLine(0f, hoodYd, w.toFloat(), hoodYd, hoodLine)
        }
        canvas.save()
        canvas.clipRect(0f, 0f, w.toFloat(), hoodYd)

        // Vùng giữa hai vạch gần
        val lane = Array(4) { l ->
            List(33) { i -> proj(cam, X_IDXS[i], o[LANE + l * 66 + i * 2], o[LANE + l * 66 + i * 2 + 1]) }
        }
        val left = ArrayList<PointF>(); val right = ArrayList<PointF>()
        for (i in 0 until 33) {
            val a = lane[1][i]; val b = lane[2][i]
            if (a != null && b != null) { left.add(a); right.add(b) }
        }
        if (show) polygon(canvas, left, right, zoneFill)

        // Dải quỹ đạo dự kiến (rộng ~1,8 m); z của quỹ đạo là tương đối nên cộng camH
        val pl = ArrayList<PointF>(); val pr = ArrayList<PointF>()
        for (p in 0 until 33) {
            val b = PLAN + p * 15
            val x = o[b]
            if (x > 80f) break
            val z = o[b + 2] + camH
            val a = proj(cam, x, o[b + 1] - 0.9f, z)
            val c = proj(cam, x, o[b + 1] + 0.9f, z)
            if (a != null && c != null) { pl.add(a); pr.add(c) }
        }
        // Sắp rẽ (≤150 m): làn vàng AR + mũi tên chạy theo hướng rẽ thay cho dải xanh; đi thẳng giữ dải xanh
        val arPath = if (show && turn != null && turn.distanceM in 0..AR_RANGE_M) arLanePath(turn.turn, turn.distanceM.toFloat()) else null
        if (arPath != null) drawArLane(canvas, cam, arPath, turn!!.distanceM.toFloat(), animSec, k)
        else if (show) polygon(canvas, pl, pr, pathFill)

        // Mép đường (trắng)
        edgeLine.strokeWidth = 3f * k
        for (e in 0..1) {
            if (!show) break
            val pts = List(33) { i ->
                proj(cam, X_IDXS[i], o[EDGES + e * 66 + i * 2], o[EDGES + e * 66 + i * 2 + 1])
            }
            canvas.drawPath(pathOf(pts), edgeLine)
        }

        // Vạch làn
        for (l in 0..3) {
            if (!show) break
            val prob = sigmoid(o[LANE_PROB + l * 2 + 1])
            if (prob < 0.3f) continue
            val near = l == 1 || l == 2
            val lp = pathOf(lane[l])
            if (near) { laneGlow.strokeWidth = 16f * k; canvas.drawPath(lp, laneGlow) }
            val paint = if (near) greenLine else farLine
            paint.strokeWidth = (if (near) 5f else 3f) * k
            canvas.drawPath(lp, paint)
        }
        canvas.restore()

        // Xe phía trước (supercombo)
        val leadProb = sigmoid(o[LEAD_PROB])
        val hasLead = leadProb > 0.5f
        var lx = 0f
        var sx = 0f
        var leadQ: PointF? = null
        if (hasLead) {
            lx = o[LEAD]
            val ly = o[LEAD + 1]
            sx = exp(o[LEAD + 72].coerceIn(-5f, 6f))
            var zg = camH
            var best = Float.MAX_VALUE
            for (p in 0 until 33) {
                val d = abs(o[PLAN + p * 15] - lx)
                if (d < best) { best = d; zg = o[PLAN + p * 15 + 2] + camH }
            }
            leadQ = proj(cam, lx, ly, zg)
        }

        // Tách biển báo (đèn giao thông, STOP) khỏi các vật cản; biển chỉ hiện ở huy hiệu góc phải
        val objs = ArrayList<Detection>()
        var sign: Detection? = null
        val midW = cam.cx * 2f
        for (d in dets) {
            // Capo / taplo lọt khung bị YOLO nhận nhầm thành xe: nằm hẳn trong vùng capo,
            // hoặc chạm mép capo mà rộng gần hết khung (xe thật gần cỡ đó thì xe dẫn đầu của model đã tính rồi)
            if (d.y1 >= hoodY - HOOD_EDGE * imgH) continue
            if (d.y2 >= hoodY - HOOD_EDGE * imgH && d.x2 - d.x1 > HOOD_WIDE * srcW) continue
            if (isSpeedSign(d.cls)) {
                // Biển giới hạn tốc độ (model riêng): phía trên đường chân trời, đủ lớn -> ghi nhận làm giới hạn hiện tại
                val bh = d.y2 - d.y1
                if (bh >= SIGN_MIN_H * imgH * 0.8f && d.y2 <= cam.cy + 0.05f * imgH) {
                    signLimit = d.cls - SIGN_BASE
                    signAtMs = SystemClock.elapsedRealtime()
                }
                continue
            }
            if (d.cls == 9 || d.cls == 11) {
                val bx = (d.x1 + d.x2) * 0.5f
                val bw = d.x2 - d.x1
                val bh = d.y2 - d.y1
                // Lọc nhận nhầm: biển/đèn thật nằm phía trên đường chân trời, đủ lớn, đúng tỉ lệ
                if (bh < SIGN_MIN_H * imgH || bx < 0.15f * midW || bx > 0.85f * midW) continue
                if (d.y2 > cam.cy + 0.03f * imgH) continue            // đèn hậu, biển trên xe, vật đỏ dưới tầm nhìn
                if (d.cls == 11 && (bw / bh < 0.7f || bw / bh > 1.4f)) continue   // STOP gần vuông
                val cur = sign
                val better = cur == null || (d.cls == 11 && cur.cls != 11) ||
                        (d.cls == cur.cls && (d.x2 - d.x1) * (d.y2 - d.y1) > (cur.x2 - cur.x1) * (cur.y2 - cur.y1))
                if (better) sign = d
            } else {
                objs.add(d)
            }
        }

        // Hộp YOLO: đối tượng trên đường đi phía trước (trong làn/quỹ đạo, trong tầm FOCUS_RANGE_M)
        // được viền góc dày màu vàng kèm khoảng cách; đối tượng xung quanh chỉ viền mảnh, mờ.
        val leadDet = leadQ?.let { matchLead(objs, it.x, it.y, cam.scale) }
        val focus = HashSet<Detection>()
        for (d in objs) {
            if (objDist(cam, d) > FOCUS_RANGE_M) continue
            val px = (d.x1 + d.x2) * 0.5f * cam.scale
            val py = d.y2 * cam.scale
            if (inBand(left, right, px, py, ZONE_MARGIN) || inBand(pl, pr, px, py, 0.3f)) focus.add(d)
        }
        if (leadDet != null) focus.add(leadDet)

        // Nguy cơ va chạm dựa trên tốc độ áp sát và TTC (thời gian tới khi va chạm), không chỉ khoảng cách:
        // kẹt xe (bò sát nhau nhưng khoảng cách ổn định/giãn ra) -> im lặng; xe đang lao tới -> cảnh báo.
        val vEgo = warnSpeedKmh / 3.6f
        var dNear = Float.NaN
        var nearDet: Detection? = null
        if (hasLead) { dNear = lx; nearDet = leadDet }
        for (d in focus) {
            if (d === leadDet) continue
            val px = (d.x1 + d.x2) * 0.5f * cam.scale
            val py = d.y2 * cam.scale
            if (!inBand(pl, pr, px, py, TIGHT_MARGIN)) continue   // chỉ vật cản thật sự trên quỹ đạo
            val gd = objDist(cam, d)
            if (dNear.isNaN() || gd < dNear) { dNear = gd; nearDet = d }
        }
        val closing = trackClosing(ptsUs, dNear)
        var level = 0
        riskDist = dNear
        riskClosing = closing
        riskTtc = Float.NaN
        riskCls = nearDet?.cls ?: -1
        riskBox = nearDet
        riskReason = ""
        riskKind = ""
        calibGround = (leadDet ?: nearDet)?.let { groundDist(cam, it) }?.takeIf { it < 200f } ?: Float.NaN
        if (!dNear.isNaN() && vEgo >= WARN_MIN_SPEED) {
            val ttc = if (closing > MIN_CLOSING) dNear / closing else Float.MAX_VALUE
            if (ttc < Float.MAX_VALUE) riskTtc = ttc
            val crawl = vEgo < CRAWL_SPEED
            val rain = roadMode == MODE_RAIN
            val limit = max(WARN_MIN_DIST, vEgo * (if (rain) HEADWAY_RAIN_S else WARN_HEADWAY_S))
            val gap = modeGap(roadMode, warnSpeedKmh, wet)
            val jam = jamAhead && crawl
            val ttcDanger = if (jam) TTC_DANGER_JAM else if (rain) TTC_DANGER_RAIN else TTC_DANGER
            val ttcCaution = if (jam) TTC_CAUTION_JAM else if (rain) TTC_CAUTION_RAIN else TTC_CAUTION
            val nearDist = if (jam) NEAR_DIST_JAM else NEAR_DIST
            level = when {
                ttc < ttcDanger -> 2
                dNear < nearDist && closing > MIN_CLOSING -> 2
                !crawl && dNear < limit * 0.6f && closing > -0.5f -> 2   // bám đuôi rất sát khi chạy nhanh
                ttc < ttcCaution -> 1
                !crawl && dNear < limit && closing > -0.5f -> 1          // bám đuôi khi chạy nhanh
                else -> 0
            }
            // Cao tốc: dưới khoảng cách tối thiểu theo luật -> chú ý; dưới một nửa mức đó -> nguy hiểm
            if (gap > 0f && level < 2) {
                if (dNear < gap * HW_DANGER && closing > -0.5f) level = 2
                else if (dNear < gap && level < 1) level = 1
            }
            riskReason = when {
                level == 0 -> ""
                ttc < ttcCaution -> "TTC"
                dNear < nearDist && closing > MIN_CLOSING -> "quá gần"
                gap > 0f && dNear < gap -> "dưới khoảng cách tối thiểu ${gap.roundToInt()} m"
                else -> "bám đuôi"
            }
            riskKind = when {
                level == 0 -> ""
                ttc < ttcCaution -> "ttc"
                dNear < nearDist && closing > MIN_CLOSING -> "near"
                gap > 0f && dNear < gap -> "gap"
                else -> "tail"
            }
        }

        // Xe cắt đầu: xe đang ở ngoài dải đường của mình nhưng di chuyển ngang vào trong, sẽ chạm dải trong ~1,2 s
        cutInLeft = false
        if (vEgo >= CUTIN_MIN_SPEED && abs(o[POSE + 5]) < CUTIN_MAX_YAW) {
            var cut: Detection? = null
            var cutD = Float.MAX_VALUE
            var cutLeft = false
            val s = cam.scale
            for (d in objs) {
                if (d.id < 0 || d === nearDet) continue
                val gd = objDist(cam, d)
                if (gd > CUTIN_RANGE_M) continue
                val band = bandAt(pl, pr, d.y2 * s) ?: continue
                val bx1 = d.x1 * s
                val bx2 = d.x2 * s
                val fromLeft = bx2 < band[0]
                if (!fromLeft && bx1 <= band[1]) continue          // đã nằm trong dải: luật thường xử lý
                val gapPx = if (fromLeft) band[0] - bx2 else bx1 - band[1]
                val toward = (if (fromLeft) d.vx else -d.vx) * s    // px hiển thị / s, dương = đang vào
                if (toward <= 0f) continue
                val latMs = toward / s * gd / cam.f
                if (latMs >= CUTIN_LAT_MS && gapPx / toward <= CUTIN_T_S && gd < cutD) { cut = d; cutD = gd; cutLeft = fromLeft }
            }
            if (cut != null) {
                val lv = if (cutD < CUTIN_DANGER_M) 2 else 1
                if (lv > level) {
                    level = lv
                    riskKind = "cutin"
                    riskReason = "xe cắt đầu " + (if (cutLeft) "bên trái" else "bên phải")
                    riskDist = cutD
                    riskCls = cut.cls
                    riskBox = cut
                    cutInLeft = cutLeft
                    nearDet = cut
                }
            }
        }

        // Xe trước đã chạy: xe mình đứng yên sau một xe gần (< 15 m) hơn 2 s, xe đó đi xa thêm hơn 4 m
        if (warnSpeedKmh < 3f) {
            if (hasLead && lx < 15f) {
                if (stopSince < 0L) { stopSince = ptsUs; stopLeadD = lx; movedFired = false }
                else {
                    if (!movedFired && ptsUs - stopSince > 2_000_000L && lx - stopLeadD > 4f) { leadMovedFlag = true; movedFired = true }
                    if (!movedFired) stopLeadD = minOf(stopLeadD, lx)
                }
            }
        } else stopSince = -1L

        // Hộp YOLO: chỉ đối tượng trong dải đường phía trước được tô màu trạng thái; xung quanh viền xanh dương mờ.
        // Vật cản gần nhất theo mức cảnh báo; các vật khác trong dải vàng nếu gần hơn khoảng cách an toàn, còn lại xanh lá.
        val safeGap = modeGap(roadMode, warnSpeedKmh, wet).takeIf { it > 0f }
            ?: max(WARN_MIN_DIST, vEgo * (if (roadMode == MODE_RAIN) HEADWAY_RAIN_S else WARN_HEADWAY_S))
        fun stateColor(d: Detection): Int {
            if (d === nearDet) return when (level) { 2 -> DANGER_C; 1 -> CAUTION_C; else -> SAFE_C }
            val gd = if (d === leadDet) lx else objDist(cam, d)
            return if (vEgo >= WARN_MIN_SPEED && gd < safeGap) CAUTION_C else SAFE_C
        }
        // Trong phố: chỉ vẽ hộp vật trong vòng 50 m cho đỡ rối (vật gần nhất / xe phía trước luôn vẽ; cảnh báo vẫn tính mọi vật)
        fun near(d: Detection) = roadMode != MODE_CITY || d === nearDet || d === leadDet || !(objDist(cam, d) > CITY_BOX_M)
        if (show) {
            for (d in objs) if (d !in focus && d !== nearDet && near(d)) drawDim(canvas, d, cam.scale, k)
            for (d in objs) if ((d in focus || d === nearDet) && d !== leadDet && near(d)) drawFocus(canvas, d, cam.scale, k, d === nearDet, stateColor(d))
            if (leadDet != null) drawFocus(canvas, leadDet, cam.scale, k, true, stateColor(leadDet))
        }

        // Viền cảnh báo: mảnh, bao cả khung hình từ mép trên xuống vạch capo (vùng capo là vùng chết, không tính)
        drawAlertFrame(canvas, w, 0f, hoodYd, k, alertFrame, animSec)

        // ---- Dải trên cùng: trái = giờ + thời tiết + tên đường, giữa = khoảng cách xe phía trước, phải = tốc độ ----
        drawTopShade(canvas, w, k)

        // Trái
        tBig.typeface = thin
        tBig.textAlign = Paint.Align.LEFT
        tBig.color = 0xFFFFFFFF.toInt()
        tBig.textSize = 76f * k
        if (clockText.isNotEmpty()) canvas.drawText(clockText, 48f * k, 96f * k, tBig)
        if (weatherText.isNotEmpty()) {
            tSmall.typeface = light; tSmall.letterSpacing = 0f; tSmall.textAlign = Paint.Align.LEFT
            tSmall.textSize = 32f * k; tSmall.color = 0xFFE0E6EA.toInt()
            canvas.drawText(weatherText, 52f * k, 144f * k, tSmall)
        }
        // Cạnh đồng hồ, cùng hàng: chế độ đường + GPS + CAMERA. Góc phải dưới nút Cài đặt để dành cho icon cảnh báo trên đường.
        drawStatusBar(canvas, 500f * k, 66f * k, k, roadMode, gpsState, camState)

        // Giữa: khoảng cách số to; màu theo mức cảnh báo
        val midX = w / 2f
        val distColor = when (level) { 2 -> 0xFFFF5252.toInt(); 1 -> 0xFFFFC107.toInt(); else -> 0xFFFFFFFF.toInt() }
        val what = when {
            leadDet != null -> if (leadDet.cls == 0) "NGƯỜI / XE MÁY" else clsName(leadDet.cls).uppercase()
            hasLead -> "XE PHÍA TRƯỚC"
            else -> "KHOẢNG CÁCH"
        }
        label(canvas, what, midX, 74f * k, 26f * k, 0xFFF2F6F8.toInt(), Paint.Align.CENTER, medium)
        tBig.typeface = bold
        tBig.textAlign = Paint.Align.CENTER
        tBig.color = distColor
        tBig.textSize = 150f * k
        val num = if (hasLead) lx.roundToInt().toString() else "--"
        canvas.drawText(num, midX, 220f * k, tBig)
        val numW = tBig.measureText(num)
        if (hasLead) label(canvas, "m", midX + numW / 2f + 10f * k, 220f * k, 40f * k, 0xFFDDE4E8.toInt(), Paint.Align.LEFT, medium)
        // Cao tốc: nhắc khoảng cách tối thiểu đang áp dụng
        val g = modeGap(roadMode, warnSpeedKmh, wet)
        if (g > 0f) label(canvas, "TỐI THIỂU ${g.roundToInt()} m", midX, 262f * k, 20f * k, 0xFFB8C4CC.toInt(), Paint.Align.CENTER, medium)

        // Biển giới hạn: chỗ cố định bên phải số khoảng cách (số đổi 1 -> 3 chữ số biển không nhảy).
        // Biển STOP / đèn camera vừa thấy: ngay dưới (vài giây)
        val limR = 72f * k
        val signX = midX + 250f * k
        if (limitKmh > 0) drawLimitSign(canvas, signX, 160f * k, limR, limitKmh, speedKmh > limitKmh + 3f, k, animSec)
        if (sign != null) { seenCls = sign.cls; seenAt = SystemClock.elapsedRealtime() }
        if (seenCls >= 0 && SystemClock.elapsedRealtime() - seenAt < SEEN_MS) {
            drawSeenSign(canvas, signX, if (limitKmh > 0) 160f * k + limR + 52f * k else 160f * k, 44f * k, seenCls, k)
        }
        // Dưới, giữa: tốc độ thẳng cột với số khoảng cách, chân chữ bám vạch capo (hạ vạch thì số xuống theo)
        val frameBot = imgH * cam.scale
        drawSpeedText(canvas, midX, min(hoodYd - 20f * k, frameBot - 14f * k), speedKmh, limitKmh, k, animSec)
        // Hiệu năng máy: chữ trắng nhỏ có bóng, góc trái ngay trên vạch capo
        if (perfText.isNotEmpty()) {
            tSmall.setShadowLayer(4f * k, 0f, 0f, 0xCC000000.toInt())
            label(canvas, perfText, 24f * k, min(hoodYd, frameBot) - 12f * k, 24f * k,
                if (perfHot) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt(), Paint.Align.LEFT, medium)
            tSmall.clearShadowLayer()
        }
        // Chỉ đường: hướng rẽ ở góc phải trên (không khung), ô bản đồ to bên trái
        canvas.save()
        canvas.translate(w - 40f * k - 250f * k, 0f)
        TurnGuide.draw(canvas, k, turn, animSec)
        canvas.restore()
        nav?.drawPanel(canvas, k, 40f * k, 176f * k, 340f * k, 384f * k, speedKmh)
        return level
    }
}
