package com.kevin.evcarhud

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

// Giọng đọc cảnh báo tiếng Việt (TextToSpeech có sẵn của Android, không cần mạng nếu máy đã có gói giọng Việt).
// Câu ngắn nói hành động cần làm, không đọc tên xe (đường đông, đọc tên không kịp).
// Máy chưa có giọng tiếng Việt thì ready = false và app quay về tiếng tít.
class AlertVoice(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    @Volatile var ready = false
        private set
    @Volatile var note = "đang khởi tạo giọng đọc"
        private set
    @Volatile var volume = 1f          // 0..1, theo thanh âm lượng cảnh báo
    private val lastSaid = HashMap<String, Long>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { note = "máy không có TextToSpeech"; return }
        val r = tts.setLanguage(Locale("vi", "VN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            note = "máy chưa có giọng tiếng Việt (Cài đặt > Chuyển văn bản thành giọng nói > tải tiếng Việt)"
            return
        }
        tts.setSpeechRate(1.15f)
        // Ra loa xe / Bluetooth như giọng chỉ đường
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
        note = "giọng tiếng Việt sẵn sàng"
        Log.d("AlertVoice", note)
    }

    // urgent = true: cắt câu đang đọc để đọc ngay. minGapMs: không lặp lại cùng một câu trong khoảng này.
    @Synchronized
    fun say(text: String, urgent: Boolean = false, minGapMs: Long = 4000L): Boolean {
        if (!ready) return false
        val now = SystemClock.elapsedRealtime()
        val last = lastSaid[text] ?: -1_000_000L
        if (now - last < minGapMs) return true
        lastSaid[text] = now
        val p = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        tts.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, p, text)
        return true
    }

    fun release() {
        try { tts.stop(); tts.shutdown() } catch (_: Throwable) {}
        ready = false
    }
}
