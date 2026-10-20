package com.kevin.evcarhud

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// Hướng rẽ tiếp theo từ Waze (mã theo tài liệu Waze Mod Data API)
class TurnInfo(val turn: Int, val distanceM: Int, val exit: Int, val nextStreet: String)

// Hoạt ảnh hướng rẽ vẽ sẵn: đường đến ngã rẽ + nhánh phụ mờ, mũi tên vàng chạy dọc tuyến sắp đi
object TurnGuide {
    // Vị trí ô trên HUD (toạ độ theo khung rộng 1280, nhân với k)
    // Ô gọn, nằm hẳn phía trên vạch capo (capo mặc định che 15% đáy = y 612): chữ "300 m" + tên đường không bị cắt
    private const val PX1 = 40f
    private const val PY1 = 300f
    private const val PX2 = 250f
    private const val PY2 = 578f
    private const val ROAD_BOTTOM = 492f
    private const val DIST_Y = 534f
    private const val STREET_Y = 564f

    private const val JX = 0.5f      // ngã rẽ (toạ độ đơn vị 0..1 trong vùng đường)
    private const val JY = 0.62f
    private const val RING_CX = 0.5f
    private const val RING_CY = 0.36f
    private const val RING_R = 0.2f

    private const val STRAIGHT = 1
    private const val LEFT = 2
    private const val RIGHT = 3
    private const val SLIGHT_L = 4
    private const val SLIGHT_R = 5
    private const val SHARP_L = 6
    private const val SHARP_R = 7
    private const val UTURN = 8
    private const val ROUNDABOUT = 10
    private const val ARRIVE = 17

    private const val CYCLE_S = 1.9f   // chu kỳ lặp của mũi tên
    private const val RUN_S = 1.5f     // thời gian mũi tên chạy hết tuyến

    private var cacheKey = -1
    private var kind = STRAIGHT
    private var pts = FloatArray(0)    // x, y xen kẽ
    private var cum = FloatArray(0)    // chiều dài tích luỹ theo từng điểm
    private var total = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99101018.toInt(); style = Paint.Style.FILL }
    private val branchP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); style = Paint.Style.FILL }
    private val endP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt(); style = Paint.Style.STROKE }
    private val distP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFC107.toInt(); textAlign = Paint.Align.CENTER
    }
    private val streetP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val arrow = Path()

    fun name(code: Int) = when (code) {
        1 -> "Đi thẳng"
        2 -> "Rẽ trái"
        3 -> "Rẽ phải"
        4 -> "Chếch trái"
        5 -> "Chếch phải"
        6 -> "Ngoặt trái"
        7 -> "Ngoặt phải"
        8 -> "Quay đầu"
        10 -> "Vòng xuyến"
        11 -> "Vòng xuyến trái"
        12 -> "Vòng xuyến phải"
        13, 15 -> "Giữ/ra trái"
        14, 16 -> "Giữ/ra phải"
        17 -> "Đến nơi"
        19 -> "Vòng xuyến thẳng"
        20 -> "Vòng xuyến quay đầu"
        else -> "Mã $code"
    }

    private class B {
        val l = ArrayList<Float>()
        fun p(x: Float, y: Float) { l.add(x); l.add(y) }
        fun quad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, n: Int = 16) {
            for (i in 1..n) {
                val t = i / n.toFloat()
                val u = 1f - t
                p(u * u * x0 + 2f * u * t * cx + t * t * x1, u * u * y0 + 2f * u * t * cy + t * t * y1)
            }
        }
    }

    // Quy mã Waze về dạng vẽ + số lối ra vòng xuyến
    private fun kindOf(turn: Int): Int = when (turn) {
        1 -> STRAIGHT
        2 -> LEFT
        3 -> RIGHT
        4, 13, 15 -> SLIGHT_L
        5, 14, 16 -> SLIGHT_R
        6 -> SHARP_L
        7 -> SHARP_R
        8 -> UTURN
        10, 11, 12, 19, 20 -> ROUNDABOUT
        17 -> ARRIVE
        else -> STRAIGHT
    }

    private fun exitOf(turn: Int, exit: Int): Int = when (turn) {
        11 -> 3
        12 -> 1
        19 -> 2
        20 -> 4
        else -> exit.coerceIn(1, 4).let { if (exit <= 0) 1 else it }
    }

    private fun ensure(info: TurnInfo) {
        val k = kindOf(info.turn)
        val ex = if (k == ROUNDABOUT) exitOf(info.turn, info.exit) else 0
        val key = k * 10 + ex
        if (key == cacheKey) return
        cacheKey = key
        kind = k
        val b = B()
        b.p(JX, 1.06f)
        when (k) {
            STRAIGHT -> { b.p(JX, JY); b.p(JX, -0.06f) }
            LEFT -> { b.p(JX, JY); b.quad(JX, JY, JX, 0.30f, -0.06f, 0.30f) }
            RIGHT -> { b.p(JX, JY); b.quad(JX, JY, JX, 0.30f, 1.06f, 0.30f) }
            SLIGHT_L -> { b.p(JX, JY); b.quad(JX, JY, JX, 0.32f, 0.10f, -0.06f) }
            SLIGHT_R -> { b.p(JX, JY); b.quad(JX, JY, JX, 0.32f, 0.90f, -0.06f) }
            SHARP_L -> { b.p(JX, JY); b.quad(JX, JY, 0.42f, 0.28f, -0.06f, 0.72f) }
            SHARP_R -> { b.p(JX, JY); b.quad(JX, JY, 0.58f, 0.28f, 1.06f, 0.72f) }
            UTURN -> {
                b.p(JX, 0.34f)
                for (i in 1..16) {
                    val a = PI.toFloat() * i / 16f
                    b.p(0.3f + 0.2f * cos(a), 0.34f - 0.2f * sin(a))
                }
                b.p(0.1f, 1.06f)
            }
            ROUNDABOUT -> {
                val entryY = RING_CY + RING_R
                b.p(JX, entryY)
                val te = ex * PI.toFloat() / 2f            // ngược chiều kim đồng hồ (lái bên phải)
                val n = 8 * ex
                for (i in 1..n) {
                    val th = te * i / n
                    b.p(RING_CX + RING_R * sin(th), RING_CY + RING_R * cos(th))
                }
                val ox = sin(te)
                val oy = cos(te)
                b.p(RING_CX + RING_R * ox + 0.55f * ox, RING_CY + RING_R * oy + 0.55f * oy)
            }
            ARRIVE -> { b.p(JX, JY); b.p(JX, 0.22f) }
        }
        pts = b.l.toFloatArray()
        val n = pts.size / 2
        cum = FloatArray(n)
        for (i in 1 until n) {
            cum[i] = cum[i - 1] + hypot(pts[2 * i] - pts[2 * i - 2], pts[2 * i + 1] - pts[2 * i - 1])
        }
        total = cum[n - 1]
    }

    private fun fmtDist(m: Int) =
        if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000f) else "$m m"

    fun draw(canvas: Canvas, k: Float, info: TurnInfo?, tSec: Float) {
        if (info == null || info.turn <= 0) return
        ensure(info)

        canvas.drawRoundRect(RectF(PX1 * k, PY1 * k, PX2 * k, PY2 * k), 28f * k, 28f * k, bg)
        val road = RectF((PX1 + 12f) * k, (PY1 + 12f) * k, (PX2 - 12f) * k, ROAD_BOTTOM * k)
        val rw = road.width()
        val rh = road.height()
        fun ux(u: Float) = road.left + u * rw
        fun uy(v: Float) = road.top + v * rh

        canvas.save()
        canvas.clipRect(road)

        // Nhánh phụ mờ tại ngã rẽ (cho giống ngã ba/ngã tư), vòng xuyến vẽ nguyên vòng
        branchP.strokeWidth = 6f * k
        path.reset()
        if (kind == ROUNDABOUT) {
            canvas.drawCircle(ux(RING_CX), uy(RING_CY), RING_R * rw, branchP)
            path.moveTo(ux(JX), uy(1.06f)); path.lineTo(ux(JX), uy(RING_CY + RING_R))
            for (ex in 0..3) {
                val th = ex * PI.toFloat() / 2f + PI.toFloat() / 2f
                val ox = sin(th); val oy = cos(th)
                path.moveTo(ux(RING_CX + RING_R * ox), uy(RING_CY + RING_R * oy))
                path.lineTo(ux(RING_CX + (RING_R + 0.55f) * ox), uy(RING_CY + (RING_R + 0.55f) * oy))
            }
        } else if (kind != UTURN && kind != ARRIVE) {
            path.moveTo(ux(JX), uy(1.06f)); path.lineTo(ux(JX), uy(JY))
            path.moveTo(ux(JX), uy(JY)); path.lineTo(ux(JX), uy(-0.06f))
            path.moveTo(ux(JX), uy(JY)); path.quadTo(ux(JX), uy(0.30f), ux(-0.06f), uy(0.30f))
            path.moveTo(ux(JX), uy(JY)); path.quadTo(ux(JX), uy(0.30f), ux(1.06f), uy(0.30f))
        }
        canvas.drawPath(path, branchP)

        // Tuyến sắp đi
        routeP.strokeWidth = 10f * k
        path.reset()
        val n = pts.size / 2
        path.moveTo(ux(pts[0]), uy(pts[1]))
        for (i in 1 until n) path.lineTo(ux(pts[2 * i]), uy(pts[2 * i + 1]))
        canvas.drawPath(path, routeP)

        // Mũi tên chạy dọc tuyến, lặp lại
        val t = tSec % CYCLE_S
        val p = min(1f, t / RUN_S)
        val alpha = when {
            p < 0.1f -> p / 0.1f
            p > 0.85f -> (1f - p) / 0.15f
            else -> 1f
        }.coerceIn(0f, 1f)
        val target = total * p
        var i = 1
        while (i < n - 1 && cum[i] < target) i++
        val seg = cum[i] - cum[i - 1]
        val f = if (seg > 0f) ((target - cum[i - 1]) / seg).coerceIn(0f, 1f) else 0f
        val x0 = pts[2 * i - 2]; val y0 = pts[2 * i - 1]
        val x1 = pts[2 * i]; val y1 = pts[2 * i + 1]
        val ax = ux(x0 + (x1 - x0) * f)
        val ay = uy(y0 + (y1 - y0) * f)
        var dx = (x1 - x0) * rw
        var dy = (y1 - y0) * rh
        val len = hypot(dx, dy).let { if (it < 1e-3f) 1f else it }
        dx /= len; dy /= len
        val a = 24f * k
        val px = -dy; val py = dx
        arrow.reset()
        arrow.moveTo(ax + dx * a, ay + dy * a)
        arrow.lineTo(ax - dx * a * 0.8f + px * a * 0.75f, ay - dy * a * 0.8f + py * a * 0.75f)
        arrow.lineTo(ax - dx * a * 0.35f, ay - dy * a * 0.35f)
        arrow.lineTo(ax - dx * a * 0.8f - px * a * 0.75f, ay - dy * a * 0.8f - py * a * 0.75f)
        arrow.close()
        arrowP.alpha = (255 * alpha).toInt()
        canvas.drawPath(arrow, arrowP)

        // Điểm đến: vòng tròn vàng ở cuối tuyến
        if (kind == ARRIVE) {
            endP.strokeWidth = 5f * k
            canvas.drawCircle(ux(JX), uy(0.22f), 14f * k, endP)
        }
        canvas.restore()

        // Khoảng cách đến ngã rẽ + tên đường sau khi rẽ
        if (info.distanceM > 0) {
            distP.textSize = 40f * k
            canvas.drawText(fmtDist(info.distanceM), (PX1 + PX2) / 2f * k, DIST_Y * k, distP)
        }
        if (info.nextStreet.isNotBlank()) {
            streetP.textSize = 21f * k
            val w = (PX2 - PX1 - 24f) * k
            val cnt = streetP.breakText(info.nextStreet, true, w, null)
            val txt = if (cnt < info.nextStreet.length) info.nextStreet.substring(0, maxOf(0, cnt - 1)) + "…" else info.nextStreet
            canvas.drawText(txt, (PX1 + PX2) / 2f * k, STREET_Y * k, streetP)
        }
    }
}
