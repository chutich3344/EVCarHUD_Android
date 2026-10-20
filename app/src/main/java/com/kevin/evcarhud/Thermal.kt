package com.kevin.evcarhud

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlin.math.abs

class ThermalInfo(
    val batteryC: Float,     // nhiệt độ pin (°C), NaN nếu không đọc được
    val status: Int,         // PowerManager.THERMAL_STATUS_* (0 = NONE ... 6 = SHUTDOWN)
    val headroom: Float,     // 1.0 = bắt đầu bị hạ xung nghiêm trọng, NaN nếu máy không hỗ trợ
    val plugged: Boolean,    // đang cắm sạc
    val powerW: Float        // công suất ước tính (W), chỉ có ý nghĩa khi không sạc
)

class ThermalMonitor(private val ctx: Context) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun read(): ThermalInfo {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val batt = if (t >= 0) t / 10f else Float.NaN
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val status = pm.currentThermalStatus
        val head = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else Float.NaN

        // Dòng điện tức thời: đơn vị và dấu khác nhau theo hãng, nên chỉ là ước lượng
        var w = Float.NaN
        val cur = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        if (cur > 0 && mv > 0) {
            val mA = if (cur > 20_000) cur / 1000f else cur.toFloat()
            w = mA * mv / 1_000_000f
        }
        return ThermalInfo(batt, status, head, plugged, w)
    }

    // Mức giảm tải: 0 bình thường, 1 nhẹ, 2 vừa, 3 nặng
    fun level(i: ThermalInfo): Int {
        val h = i.headroom
        var lv = when {
            i.status >= 3 || (!h.isNaN() && h >= 1.0f) -> 3
            i.status >= 2 || (!h.isNaN() && h >= 0.9f) -> 2
            i.status >= 1 || (!h.isNaN() && h >= 0.75f) -> 1
            else -> 0
        }
        // Máy không báo headroom: dự phòng theo nhiệt độ pin
        if (h.isNaN() && !i.batteryC.isNaN()) {
            val b = when {
                i.batteryC >= 45f -> 3
                i.batteryC >= 42f -> 2
                i.batteryC >= 40f -> 1
                else -> 0
            }
            if (b > lv) lv = b
        }
        return lv
    }

    companion object {
        fun statusName(s: Int) = when (s) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "?"
        }
    }
}
