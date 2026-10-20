package com.kevin.evcarhud

import kotlin.math.max
import kotlin.math.min

// Giữ hộp ổn định giữa các lần chạy YOLO: làm mượt vị trí, giữ thêm vài lần bị lỡ,
// và chỉ hiện hộp khi đã được xác nhận (2 lần liên tiếp hoặc điểm tin cậy cao).
// Mỗi đối tượng có mã riêng (id) và vận tốc tâm hộp (pixel/giây) để biết xe nào đang tạt ngang vào đầu xe.
class DetTracker {
    private class Track(val id: Int, var cls: Int, var score: Float, var x1: Float, var y1: Float, var x2: Float, var y2: Float, var tUs: Long) {
        var hits = 1
        var misses = 0
        var vx = 0f
        var vy = 0f
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    fun reset() { tracks.clear() }

    private fun iou(ax1: Float, ay1: Float, ax2: Float, ay2: Float, bx1: Float, by1: Float, bx2: Float, by2: Float): Float {
        val ix = max(0f, min(ax2, bx2) - max(ax1, bx1))
        val iy = max(0f, min(ay2, by2) - max(ay1, by1))
        val inter = ix * iy
        val u = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
        return if (u <= 0f) 0f else inter / u
    }

    // tUs: thời điểm khung (µs, theo pts video / camera) để tính vận tốc
    fun update(dets: List<Detection>, tUs: Long = 0L): List<Detection> {
        val used = BooleanArray(dets.size)
        for (t in tracks.sortedByDescending { it.hits }) {
            var bi = -1
            var bv = MATCH_IOU
            for (i in dets.indices) {
                if (used[i] || dets[i].cls != t.cls) continue
                val d = dets[i]
                val v = iou(t.x1, t.y1, t.x2, t.y2, d.x1, d.y1, d.x2, d.y2)
                if (v > bv) { bv = v; bi = i }
            }
            if (bi >= 0) {
                used[bi] = true
                val d = dets[bi]
                // Vận tốc tâm hộp từ vị trí đo được (chưa làm mượt) giữa hai lần thấy
                val dt = (tUs - t.tUs) / 1e6f
                if (dt in 0.02f..1.0f) {
                    val rvx = ((d.x1 + d.x2) - (t.x1 + t.x2)) * 0.5f / dt
                    val rvy = ((d.y1 + d.y2) - (t.y1 + t.y2)) * 0.5f / dt
                    t.vx = if (t.hits <= 1) rvx else t.vx + V_ALPHA * (rvx - t.vx)
                    t.vy = if (t.hits <= 1) rvy else t.vy + V_ALPHA * (rvy - t.vy)
                }
                t.tUs = tUs
                t.x1 += ALPHA * (d.x1 - t.x1)
                t.y1 += ALPHA * (d.y1 - t.y1)
                t.x2 += ALPHA * (d.x2 - t.x2)
                t.y2 += ALPHA * (d.y2 - t.y2)
                t.score = d.score
                t.hits++
                t.misses = 0
            } else {
                t.misses++
            }
        }
        tracks.removeAll { it.misses > MAX_MISS }
        for (i in dets.indices) {
            if (!used[i]) {
                val d = dets[i]
                tracks.add(Track(nextId++, d.cls, d.score, d.x1, d.y1, d.x2, d.y2, tUs))
            }
        }
        return tracks
            .filter {
                // biển/đèn: luôn chờ xác nhận, kể cả điểm cao
                if (it.cls == 9 || it.cls == 11 || isSpeedSign(it.cls)) it.hits >= SIGN_HITS
                else it.hits >= 2 || it.score >= STRONG
            }
            .map { Detection(it.cls, it.score, it.x1, it.y1, it.x2, it.y2, it.id, it.vx, it.vy) }
    }

    companion object {
        const val MATCH_IOU = 0.3f   // ngưỡng ghép hộp mới với hộp đang theo dõi
        const val ALPHA = 0.6f       // trọng số của hộp mới khi làm mượt
        const val V_ALPHA = 0.5f     // làm mượt vận tốc
        const val MAX_MISS = 2       // số lần YOLO liên tiếp bị lỡ vẫn còn giữ hộp
        const val SIGN_HITS = 3      // số lần YOLO liên tiếp để hiện biển STOP / đèn giao thông / biển tốc độ
        const val STRONG = 0.5f      // đủ tin cậy thì hiện ngay, không cần chờ lần thứ 2
    }
}
