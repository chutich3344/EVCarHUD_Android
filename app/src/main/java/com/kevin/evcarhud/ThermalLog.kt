package com.kevin.evcarhud

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Nhật ký nhiệt: mỗi 10 s ghi một dòng CSV (nhiệt pin, CPU nếu đọc được, mức Android báo, mức giảm tải, nhịp YOLO/NPU,
// thời gian NPU, tốc độ...) để sau chuyến đi xem máy nóng lên thế nào và app đã giảm tải ra sao.
// File: Android/data/com.kevin.evcarhud/files/thermal/thermal_yyyyMMdd_HHmm.csv (mỗi lần mở app một file, mở bằng Excel được).
class ThermalLog(private val ctx: Context) {
    private var out: BufferedWriter? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    var file: File? = null
        private set
    var rows = 0
        private set

    val dir: File get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "thermal")

    // Vùng nhiệt CPU trong /sys (nhiều máy chặn quyền đọc: khi đó cột cpu_C để trống)
    private val cpuZones: List<File> by lazy {
        try {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().filter { z ->
                val t = try { File(z, "type").readText().trim().lowercase() } catch (_: Throwable) { "" }
                (t.startsWith("cpu") || t.contains("cpuss") || t.contains("apc")) && File(z, "temp").canRead()
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun cpuC(): Float {
        var best = Float.NaN
        for (z in cpuZones) {
            val v = try { File(z, "temp").readText().trim().toFloat() } catch (_: Throwable) { continue }
            val c = if (v > 1000f) v / 1000f else v
            if (c in 1f..150f && (best.isNaN() || c > best)) best = c
        }
        return best
    }

    private fun writer(): BufferedWriter? {
        out?.let { return it }
        return try {
            dir.mkdirs()
            val f = File(dir, "thermal_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".csv")
            file = f
            BufferedWriter(FileWriter(f, true)).also {
                it.write("gio,pin_C,cpu_C,android_bao,headroom,cong_suat_W,dang_sac,muc_giam_tai,yolo_hz,npu_hz,man_hinh_moi_n,npu_ms,tam_nghi,toc_do_kmh\n")
                out = it
            }
        } catch (e: Throwable) {
            Log.w("ThermalLog", "không mở được file nhật ký nhiệt", e)
            null
        }
    }

    fun log(i: ThermalInfo, level: Int, yoloHz: Float, npuHz: Int, displayEvery: Int, npuMs: Long, paused: Boolean, speedKmh: Float?) {
        val w = writer() ?: return
        fun f1(v: Float) = if (v.isNaN()) "" else String.format(Locale.US, "%.1f", v)
        try {
            w.write(listOf(
                timeFmt.format(Date()), f1(i.batteryC), f1(cpuC()), ThermalMonitor.statusName(i.status),
                if (i.headroom.isNaN()) "" else String.format(Locale.US, "%.2f", i.headroom),
                f1(i.powerW), if (i.plugged) "1" else "0", level.toString(), f1(yoloHz),
                npuHz.toString(), displayEvery.toString(), if (npuMs >= 0) npuMs.toString() else "",
                if (paused) "1" else "0", speedKmh?.let { f1(it) } ?: ""
            ).joinToString(",") + "\n")
            w.flush()
            rows++
        } catch (e: Throwable) {
            Log.w("ThermalLog", "ghi nhật ký nhiệt lỗi", e)
        }
    }

    fun close() {
        try { out?.close() } catch (_: Throwable) {}
        out = null
    }
}
