package com.kevin.evcarhud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Model không nằm trong APK (APK quá nặng để tải trên điện thoại): lần đầu mở app thì tải từ Release "models" trên GitHub.
// Bản build ở máy nhà vẫn có thể để model trong assets: có trong assets thì dùng assets, không tải.
// Tải vào file .part, đứt mạng thì tải tiếp từ chỗ đã có (HTTP Range), đủ byte mới đổi tên thành file thật.
// Bản thương mại: đổi BASE sang máy chủ riêng (hoặc Play Asset Delivery).
object ModelFiles {
    private const val TAG = "ModelFiles"
    private const val BASE = "https://github.com/chutich3344/EVCarHUD_Android/releases/download/models/"
    private const val MAX_TRIES = 30
    private const val WAIT = "Xin vui lòng kết nối mạng ổn định để tải models về."

    // Dòng trạng thái tải (Runner gán để hiện lên màn hình)
    @Volatile var onProgress: ((String) -> Unit)? = null

    fun inAssets(ctx: Context, name: String): Boolean =
        try { ctx.assets.open(name).close(); true } catch (_: Throwable) { false }

    fun ensure(ctx: Context, name: String): File {
        val file = File(ctx.filesDir, name)
        if (file.exists() && file.length() > 0) return file
        if (inAssets(ctx, name)) {
            ctx.assets.open(name).use { i -> file.outputStream().use { i.copyTo(it) } }
            return file
        }
        download(BASE + name, file, name)
        onProgress?.invoke("Đang chuẩn bị, lần đầu mất vài phút...")
        return file
    }

    private fun download(url: String, dst: File, label: String) {
        val part = File(dst.parentFile, dst.name + ".part")
        var lastErr: Throwable? = null
        for (attempt in 1..MAX_TRIES) {
            try {
                var have = if (part.exists()) part.length() else 0L
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = c.responseCode
                if (code == 416 && have > 0) {   // đã đủ byte từ lần trước
                    c.disconnect()
                    if (!part.renameTo(dst)) throw IllegalStateException("không đổi tên được file model")
                    return
                }
                if (code != 200 && code != 206) { c.disconnect(); throw IllegalStateException("máy chủ trả mã $code") }
                if (code == 200) have = 0L       // máy chủ không hỗ trợ tải tiếp: tải lại từ đầu
                val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val total = if (len > 0) have + len else -1L
                var got = have
                var lastShow = 0L
                c.inputStream.use { ins ->
                    FileOutputStream(part, code == 206).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            got += r
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastShow > 500) {
                                lastShow = now
                                // Chỉ hiện %, không hiện tên file / số MB cho đỡ cảm giác app nặng
                                onProgress?.invoke(if (total > 0) "$WAIT ${got * 100 / total}%" else WAIT)
                            }
                        }
                    }
                }
                c.disconnect()
                if (total > 0 && part.length() != total) throw IllegalStateException("tải thiếu (${part.length()}/$total byte)")
                if (!part.renameTo(dst)) throw IllegalStateException("không đổi tên được file model")
                return
            } catch (e: Throwable) {
                lastErr = e
                Log.w(TAG, "tải $label lỗi (lần $attempt)", e)
                onProgress?.invoke("Mạng chập chờn, đang thử tải lại... $WAIT")
                SystemClock.sleep((2000L * attempt).coerceAtMost(20_000L))
            }
        }
        throw IllegalStateException("Chưa tải xong, xin vui lòng kết nối mạng ổn định rồi mở lại app (phần đã tải được giữ lại).", lastErr)
    }
}
