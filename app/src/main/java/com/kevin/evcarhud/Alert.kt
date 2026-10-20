package com.kevin.evcarhud

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock

// Tiếng "tít" cảnh báo. ToneGenerator phát bất đồng bộ nên gọi từ luồng hiển thị cũng không bị chặn.
// Dùng luồng nhạc (STREAM_MUSIC) để ra loa xe / Bluetooth như âm thanh bình thường.
// volumePct (0-100) là tỉ lệ so với âm lượng "phương tiện" của hệ thống, nên vẫn cần mở âm lượng máy.
class BeepPlayer(volumePct: Int = 100) {
    @Volatile private var tg: ToneGenerator? = make(volumePct)
    private var last = 0L

    private fun make(v: Int): ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, v.coerceIn(0, 100))
    } catch (_: Throwable) {
        null
    }

    // Đổi âm lượng khi đang chạy (ToneGenerator chỉ nhận âm lượng lúc tạo nên phải tạo lại)
    @Synchronized
    fun setVolume(v: Int) {
        try { tg?.release() } catch (_: Throwable) {}
        tg = make(v)
    }

    fun beep() {
        val now = SystemClock.elapsedRealtime()
        if (now - last < INTERVAL_MS) return
        last = now
        try { tg?.startTone(ToneGenerator.TONE_DTMF_A, 110) } catch (_: Throwable) {}
    }

    // Hai tiếng nhẹ cho thông báo không khẩn (xe trước đã chạy, vượt tốc độ); không theo nhịp tiếng tít
    fun chime() {
        try { tg?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) } catch (_: Throwable) {}
    }

    // Phát thử, bỏ qua khoảng chờ
    fun test() {
        try { tg?.startTone(ToneGenerator.TONE_DTMF_A, 200) } catch (_: Throwable) {}
    }

    @Synchronized
    fun release() {
        try { tg?.release() } catch (_: Throwable) {}
        tg = null
    }

    companion object {
        const val INTERVAL_MS = 300L   // khoảng cách giữa hai tiếng tít
    }
}
