package com.kevin.evcarhud

import kotlin.math.abs

// Tự tìm mép capo / taplo / hốc HUD. Khi xe chạy, mặt đường phía trên capo đổi liên tục, còn capo, taplo
// đứng yên so với camera. Đo độ đổi của từng dải ngang theo độ chênh sáng giữa các cột (ít bị ảnh hưởng khi
// trời sáng tối thay đổi); các dải đứng yên liền nhau tính từ đáy khung = vùng che.
class HoodFinder {
    private val bands = 60
    private val cols = 40
    private var prev: FloatArray? = null
    private val mot = FloatArray(bands)
    private var n = 0
    private var lastAt = 0L

    fun reset() { prev = null; mot.fill(0f); n = 0 }

    // Gọi mỗi khung khi xe đang chạy. Đủ mẫu (~15 giây) thì trả tỉ lệ che (0..0,55), chưa đủ trả NaN
    fun feed(f: YuvFrame, nowMs: Long): Float {
        if (nowMs - lastAt < 250L) return Float.NaN
        lastAt = nowMs
        val w = f.w; val h = f.h
        if (w < 64 || h < 64 || f.yArr.isEmpty()) return Float.NaN
        val cur = FloatArray(bands * cols)
        for (b in 0 until bands) {
            for (r in 0 until 3) {
                val y = ((b + (r + 0.5f) / 3f) * h / bands).toInt().coerceIn(0, h - 1)
                val row = y * f.yRow
                var last = 0
                for (c in 0..cols) {
                    val x = ((c + 0.5f) * (w - 1) / (cols + 1)).toInt()
                    val v = f.yArr[row + x].toInt() and 0xFF
                    if (c > 0) cur[b * cols + c - 1] += (v - last).toFloat()
                    last = v
                }
            }
        }
        val p = prev
        prev = cur
        if (p == null) return Float.NaN
        for (b in 0 until bands) {
            var s = 0f
            for (c in 0 until cols) s += abs(cur[b * cols + c] - p[b * cols + c])
            mot[b] += s / cols
        }
        if (++n < 60) return Float.NaN
        // mức đổi chuẩn: trung vị các dải giữa khung (đường, xe cộ, nhà cửa)
        val mid = mot.copyOfRange((bands * 0.3f).toInt(), (bands * 0.6f).toInt()).sorted()
        val ref = mid[mid.size / 2]
        val avg = ref / n
        var out = Float.NaN
        if (avg >= 4f) {   // cảnh trước mặt đủ thay đổi (xe chạy thật) mới tin kết quả
            var b0 = -1
            for (b in bands - 1 downTo 0) if (mot[b] >= 0.3f * ref) { b0 = b; break }
            if (b0 >= 0) {
                val stillBands = bands - 1 - b0
                out = if (stillBands == 0) 0f else (stillBands.toFloat() / bands + 0.015f).coerceAtMost(0.55f)
            }
        }
        mot.fill(0f); n = 0
        return out
    }
}
