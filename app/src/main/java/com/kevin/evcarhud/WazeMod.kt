package com.kevin.evcarhud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.wazemod.api.IWazeModData
import com.wazemod.api.IWazeModListener

private const val WTAG = "WazeMod"

// Một cảnh báo của Waze (type: 2 camera tốc độ, 6 kẹt xe, 13 xe dừng, 57 khu trường học, ...)
class WazeAlert(
    val type: Int,
    val distanceM: Int,
    val value: Int,
    val jamSeverity: Int,   // chỉ có ý nghĩa với type 6: 1 nhẹ -> 5 đóng đường
    val jamDelayMin: Int
)

class WazeSnapshot(
    val navigating: Boolean,
    val speedKmh: Int,        // tốc độ GPS theo Waze
    val limitKmh: Int,        // 0 = không rõ
    val overspeed: Boolean,
    val alerts: List<WazeAlert>,
    val street: String,       // tên đường hiện tại (rỗng nếu Waze chưa có)
    val nextStreet: String,
    val turn: TurnInfo?,      // hướng rẽ tiếp theo (null nếu không dẫn đường)
    val atMs: Long            // SystemClock.elapsedRealtime() lúc nhận
)

// Kết nối Waze Mod V14+ (Data API, AIDL). Toàn bộ logic Waze nằm trong lớp này.
// Cách dùng: prepare() -> Intent xin quyền (Activity chạy, RESULT_OK) -> start(); stop() khi không cần nữa.
class WazeModClient(ctx: Context, private val onChange: () -> Unit = {}) {
    private val context = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var api: IWazeModData? = null
    private var bound = false
    private var hostPackage: String? = null

    @Volatile var last: WazeSnapshot? = null
        private set
    @Volatile var state = "Waze: chưa kết nối"
        private set

    // Nguyên văn các khoá Waze gửi, tách theo nguồn: listener (onUpdate đẩy) và poll (getSnapshot hỏi)
    @Volatile var rawListener = ""
        private set
    @Volatile var rawPoll = ""
        private set
    @Volatile private var listenerCount = 0
    @Volatile private var pollCount = 0
    @Volatile private var listenerAt = 0L

    private fun dump(b: Bundle) = b.keySet().sorted().joinToString(" ") { k ->
        val v = b.get(k)
        if (v is Array<*>) "$k[${v.size}]" else "$k=$v"
    }

    val connected get() = api != null

    private fun setState(s: String) { state = s; main.post { onChange() } }

    // Tìm bản Waze Mod trên máy theo action (không hard-code tên package). Nhiều bản: lấy bản đầu tiên.
    fun findHost(): String? =
        context.packageManager
            .queryIntentServices(Intent(ACTION_BIND), 0)
            .firstOrNull()?.serviceInfo?.packageName

    // Intent hộp thoại xin phép (chạy bằng Activity, trên điện thoại). null nếu không thấy Waze Mod V14+.
    fun prepare(): Intent? {
        val h = findHost()
        hostPackage = h
        if (h == null) {
            setState("Waze: không thấy Waze Mod V14+ (kiểm tra đã cài và khai báo <queries>)")
            return null
        }
        return Intent(ACTION_REQUEST_ACCESS).setPackage(h)
    }

    fun denied() = setState("Waze: Waze Mod chưa cho phép kết nối")

    private val listener = object : IWazeModListener.Stub() {
        override fun onUpdate(snapshot: Bundle) {
            rawListener = dump(snapshot)
            listenerCount++
            listenerAt = SystemClock.elapsedRealtime()
            Log.d(WTAG, "onUpdate: $rawListener")
            val s = parse(snapshot)
            last = s
            main.post { onChange() }
        }
        override fun onRevoked() {
            main.post { stop(); setState("Waze: quyền đã bị thu hồi") }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IWazeModData.Stub.asInterface(binder)
            try {
                if (service.apiVersion < 1) { stop(); setState("Waze: cần Waze Mod V14+"); return }
                api = service
                last = parse(service.snapshot)
                service.registerListener(listener)
                main.removeCallbacks(poll); main.postDelayed(poll, POLL_MS)
                setState("Waze: đã kết nối")
            } catch (e: SecurityException) {
                stop(); setState("Waze: chưa được cấp quyền, hãy kết nối lại")
            } catch (e: RemoteException) {
                api = null
                setState("Waze: mất kết nối, đang thử lại")
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            api = null
            setState("Waze: Waze Mod đã dừng, chờ nối lại")
        }
    }

    // Waze chỉ đẩy khi dữ liệu thay đổi -> đứng yên / chưa dẫn đường thì dữ liệu "cũ" và HUD mất chữ.
    // Hỏi lại snapshot mỗi giây (tài liệu cho phép tối đa 1 lần/giây) để dữ liệu luôn được coi là mới.
    private val poll = object : Runnable {
        override fun run() {
            val a = api
            if (a != null) {
                try {
                    val b = a.snapshot
                    rawPoll = dump(b)
                    pollCount++
                    // Chỉ để poll ghi đè khi listener im quá 3 s: tránh poll đè mất dữ liệu listener vừa đẩy
                    if (SystemClock.elapsedRealtime() - listenerAt > 3000L) last = parse(b)
                    onChange()
                } catch (_: Exception) {}
            }
            if (bound) main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        val h = hostPackage ?: findHost() ?: run { setState("Waze: không thấy Waze Mod V14+"); return }
        if (bound) return
        hostPackage = h
        bound = try {
            context.bindService(Intent(ACTION_BIND).setPackage(h), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(WTAG, "bind lỗi", e); false
        }
        if (!bound) setState("Waze: không bind được dịch vụ")
    }

    fun stop() {
        main.removeCallbacks(poll)
        try { api?.unregisterListener(listener) } catch (_: Exception) {}
        api = null
        if (bound) {
            try { context.unbindService(connection) } catch (_: IllegalArgumentException) {}
            bound = false
        }
        last = null
        setState("Waze: chưa kết nối")
    }

    @Suppress("DEPRECATION")
    private fun parse(b: Bundle): WazeSnapshot {
        val alerts = ArrayList<WazeAlert>()
        b.getParcelableArray("alerts")?.forEach { p ->
            if (p is Bundle) alerts.add(
                WazeAlert(
                    p.getInt("type", 0), p.getInt("distance_m", 0), p.getInt("value", 0),
                    p.getInt("jam_severity", 0), p.getInt("jam_delay_min", 0)
                )
            )
        }
        val nav = b.getBoolean("navigating", false)
        val nextStreet = b.getString("next_street", "") ?: ""
        val turnCode = b.getInt("turn", 0)
        val turnDist = b.getInt("turn_distance_m", -1)
        val turnExit = b.getInt("roundabout_exit", 0)
        val turn = if (nav && turnCode > 0) TurnInfo(turnCode, turnDist, turnExit, nextStreet) else null
        return WazeSnapshot(
            nav, b.getInt("speed", 0), b.getInt("speed_limit", 0),
            b.getBoolean("overspeed", false), alerts,
            b.getString("current_street", "") ?: "", nextStreet, turn,
            SystemClock.elapsedRealtime()
        )
    }

    // Dữ liệu còn mới (Waze chỉ đẩy khi có thay đổi nên cho phép khá lâu, ví dụ lúc dừng đèn đỏ)
    private fun fresh(): WazeSnapshot? =
        last?.takeIf { SystemClock.elapsedRealtime() - it.atMs < STALE_MS }

    // Hướng rẽ tiếp theo cho hoạt ảnh trên HUD (null nếu không có / dữ liệu cũ)
    fun turn(): TurnInfo? = fresh()?.turn

    // Tên đường hiện tại do Waze cấp (rỗng nếu không có)
    fun streetName(): String = fresh()?.street.orEmpty()

    // Giới hạn tốc độ (km/h), 0 nếu không rõ hoặc dữ liệu cũ
    fun limitKmh(): Int = fresh()?.limitKmh ?: 0

    // Waze báo có kẹt xe (type 6) trong phạm vi maxDistM phía trước
    fun jamAhead(maxDistM: Int = 300): Boolean =
        fresh()?.alerts?.any { it.type == ALERT_JAM && it.distanceM <= maxDistM && it.jamSeverity >= 1 } == true

    // Dòng tên đường cho HUD; khi chưa có thì báo rõ lý do để biết Waze có đang cấp dữ liệu hay không
    fun streetLine(): String {
        if (!connected) return "Waze: chưa kết nối"
        val s = fresh() ?: return "Waze: chưa có dữ liệu"
        return if (s.street.isNotBlank()) s.street else "Waze: chưa có tên đường"
    }

    // Bản Waze Mod đang bind + số bản tìm thấy (nếu >1 có thể đang nối nhầm bản)
    fun hostInfo(): String {
        val n = try { context.packageManager.queryIntentServices(Intent(ACTION_BIND), 0).map { it.serviceInfo.packageName } } catch (_: Exception) { emptyList() }
        return "host=${hostPackage ?: "?"} (${n.size} bản: ${n.joinToString()})"
    }

    // Dòng tóm tắt để hiện trên giao diện
    fun summary(): String {
        val s = fresh() ?: return state
        val sb = StringBuilder("Waze: ")
        sb.append(if (s.limitKmh > 0) "giới hạn ${s.limitKmh} km/h" else "giới hạn --")
        s.alerts.firstOrNull { it.type == ALERT_JAM }?.let {
            sb.append(" · kẹt xe ${it.distanceM} m (mức ${it.jamSeverity}, trễ ${it.jamDelayMin} ph)")
        }
        sb.append(" · nav=${s.navigating} rẽ=${s.turn?.turn ?: 0} ${s.turn?.distanceM ?: 0}m")
        sb.append(" · đường: ").append(s.street.ifBlank { "--" })
        sb.append("\n").append(hostInfo())
        sb.append("\n[listener x$listenerCount] ").append(rawListener.ifEmpty { "(chưa nhận onUpdate nào)" })
        sb.append("\n[poll x$pollCount] ").append(rawPoll)
        val other = s.alerts.count { it.type != ALERT_JAM }
        if (other > 0) sb.append(" · $other cảnh báo khác")
        return sb.toString()
    }

    companion object {
        const val ACTION_BIND = "com.wazemod.api.action.BIND"
        const val ACTION_REQUEST_ACCESS = "com.wazemod.api.action.REQUEST_ACCESS"
        const val ALERT_JAM = 6
        const val STALE_MS = 20_000L
        const val POLL_MS = 1000L
    }
}
