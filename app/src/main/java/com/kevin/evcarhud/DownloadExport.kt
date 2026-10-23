package com.kevin.evcarhud

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File

// Chép mọi file app ghi (nhật ký nhiệt, nhật ký cảnh báo, ảnh cảnh báo, ảnh huấn luyện) vào Download/EVCarHUD,
// giữ nguyên thư mục con. File đã chép lần trước (cùng thư mục, cùng tên) thì bỏ qua. Gọi ngoài luồng UI.
object DownloadExport {
    private const val ROOT = "Download/EVCarHUD"

    // Trả về câu báo kết quả cho dòng trạng thái
    fun saveAll(ctx: Context): String {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val files = listOf("thermal", "alerts", "dataset").flatMap { sub ->
            File(base, sub).walkTopDown().filter { it.isFile }.toList()
        }
        if (files.isEmpty()) return "Chưa có file nào để lưu"
        val resolver = ctx.contentResolver
        val have = HashSet<String>()
        try {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$ROOT/%"), null)?.use { c ->
                while (c.moveToNext()) have.add(c.getString(0).trimEnd('/') + "/" + c.getString(1))
            }
        } catch (e: Throwable) { Log.w("DownloadExport", "không đọc được Download", e) }
        var saved = 0
        var failed = 0
        for (f in files) {
            val rel = f.parentFile!!.relativeTo(base).path.replace(File.separatorChar, '/')
            val dir = "$ROOT/$rel"
            // Nhật ký đang ghi (thay đổi liên tục) luôn chép lại: xoá bản cũ trước
            val live = f.extension == "csv"
            if ("$dir/${f.name}" in have) {
                if (!live) continue
                try {
                    resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf("$dir/", f.name))
                } catch (_: Throwable) {}
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, when (f.extension.lowercase()) {
                    "csv" -> "text/csv"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "txt" -> "text/plain"
                    else -> "application/octet-stream"
                })
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            }
            val uri = try { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (_: Throwable) { null }
            if (uri == null) { failed++; continue }
            try {
                resolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: throw IllegalStateException("no stream")
                saved++
            } catch (e: Throwable) {
                Log.w("DownloadExport", "lưu ${f.name} lỗi", e)
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                failed++
            }
        }
        return "Đã lưu $saved file vào $ROOT" + (if (failed > 0) ", $failed file lỗi" else "") +
            (if (saved == 0 && failed == 0) " (không có file mới)" else "")
    }
}
