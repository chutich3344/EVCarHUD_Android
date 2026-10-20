package com.kevin.evcarhud

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Nhật ký cảnh báo: mỗi lần bắt đầu kêu (mức 2) hoặc bắt đầu viền vàng (mức 1) ghi một dòng CSV,
// để sau chuyến đi xem lại vì sao app cảnh báo (vật gì, cách bao xa, tốc độ bao nhiêu) và chỉnh ngưỡng.
// File: Android/data/com.kevin.evcarhud/files/alerts/alerts_yyyyMMdd.csv (mỗi ngày một file, mở bằng Excel được).
class AlertLog(private val ctx: Context) {
    private var day = ""
    private var out: BufferedWriter? = null
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    var count = 0
        private set

    val dir: File get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "alerts")

    private fun writer(now: Date): BufferedWriter? {
        val d = dayFmt.format(now)
        if (d != day || out == null) {
            close()
            day = d
            try {
                dir.mkdirs()
                val f = File(dir, "alerts_$d.csv")
                val fresh = !f.exists()
                out = BufferedWriter(FileWriter(f, true)).also {
                    if (fresh) {
                        it.write("gio,nguon,t_video_s,muc,ly_do,vat,khoang_cach_m,ap_sat_m_s,ttc_s,toc_do_kmh,toc_do_canh_bao_kmh,hop_x1,hop_y1,hop_x2,hop_y2,che_capo_pct\n")
                    }
                }
            } catch (e: Throwable) {
                Log.w("AlertLog", "không mở được file nhật ký", e)
                out = null
            }
        }
        return out
    }

    private fun f1(v: Float) = if (v.isNaN() || v.isInfinite()) "" else "%.1f".format(Locale.US, v)

    // Gọi từ luồng hiển thị ngay khi một đợt cảnh báo bắt đầu
    fun log(level: Int, live: Boolean, ptsUs: Long, speedKmh: Float, warnSpeedKmh: Float, hoodCut: Float) {
        val now = Date()
        val w = writer(now) ?: return
        val obj = if (HudRenderer.riskCls < 0) "Xe phía trước" else clsName(HudRenderer.riskCls)
        val b = HudRenderer.riskBox
        val line = listOf(
            timeFmt.format(now),
            if (live) "camera" else "video",
            if (live) "" else "%.1f".format(Locale.US, ptsUs / 1_000_000.0),
            level.toString(),
            HudRenderer.riskReason,
            obj,
            f1(HudRenderer.riskDist),
            f1(HudRenderer.riskClosing),
            f1(HudRenderer.riskTtc),
            f1(speedKmh),
            f1(warnSpeedKmh),
            b?.let { "%.0f".format(Locale.US, it.x1) } ?: "",
            b?.let { "%.0f".format(Locale.US, it.y1) } ?: "",
            b?.let { "%.0f".format(Locale.US, it.x2) } ?: "",
            b?.let { "%.0f".format(Locale.US, it.y2) } ?: "",
            (hoodCut * 100).toInt().toString()
        ).joinToString(",")
        try {
            w.write(line); w.write("\n"); w.flush()
            count++
        } catch (e: Throwable) {
            Log.w("AlertLog", "ghi nhật ký lỗi", e)
            close()
        }
    }

    fun close() {
        try { out?.close() } catch (_: Throwable) {}
        out = null
    }
}
