package com.kevin.evcarhud

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DTAG = "Dashcam"

// Nối Wi‑Fi của camera hành trình ngay trong app (WifiNetworkSpecifier, Android 10+).
// Mạng này chỉ app dùng, mạng mặc định của máy vẫn là 4G nên HERE / thời tiết vẫn chạy.
// Giữ callback suốt lúc app sống: bỏ callback là Android ngắt Wi‑Fi cam.
object DashcamWifi {
    const val DEFAULT_PASS = "12345678"   // mật khẩu mặc định của phần lớn cam hành trình
    @Volatile var network: Network? = null
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val HINTS = listOf("70mai", "cardv", "dvr", "dash", "cam", "novatek", "viofo", "vietmap", "ddpai",
        "xiaomi", "mi_", "nice", "road", "car", "wifi_", "hd_", "fhd", "4k")

    fun looksLikeCam(ssid: String): Boolean { val s = ssid.lowercase(); return HINTS.any { s.contains(it) } }

    // Wi‑Fi quanh xe, tên giống cam xếp trước. Cần quyền vị trí (Android bắt buộc khi quét Wi‑Fi).
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun nearby(ctx: Context): List<String> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try { wm.startScan() } catch (_: Throwable) {}
        val list = try { wm.scanResults } catch (_: Throwable) { emptyList() }
        return list.mapNotNull { r -> r.SSID?.trim('"')?.takeIf { it.isNotBlank() } }.distinct()
            .sortedWith(compareBy<String>({ !looksLikeCam(it) }, { it.lowercase() }))
    }

    // Nối Wi‑Fi cam. onResult chạy trên luồng của ConnectivityManager: null = không nối được / người dùng huỷ.
    fun connect(ctx: Context, ssid: String, pass: String, onResult: (Network?) -> Unit) {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        release(ctx)
        val req = try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(ssid)
                .apply { if (pass.length >= 8) setWpa2Passphrase(pass) }.build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
        } catch (e: Throwable) { Log.w(DTAG, "specifier", e); onResult(null); return }
        var done = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                if (!done) { done = true; onResult(n) }
            }
            override fun onUnavailable() { if (!done) { done = true; onResult(null) } }
            override fun onLost(n: Network) { if (network == n) network = null }
        }
        callback = cb
        try { cm.requestNetwork(req, cb, 60_000) } catch (e: Throwable) {
            Log.w(DTAG, "requestNetwork", e); callback = null; if (!done) { done = true; onResult(null) }
        }
    }

    // Gọi từ luồng nền khi mở nguồn Wi‑Fi: chưa nối mà đã lưu tên Wi‑Fi cam thì tự nối lại, chờ tối đa 20 s
    fun ensure(ctx: Context) {
        if (network != null) return
        val p = ctx.getSharedPreferences("evcarhud", Context.MODE_PRIVATE)
        val ssid = p.getString("camSsid", null) ?: return
        val pass = p.getString("camPass", DEFAULT_PASS) ?: DEFAULT_PASS
        val latch = CountDownLatch(1)
        connect(ctx, ssid, pass) { latch.countDown() }
        try { latch.await(20, TimeUnit.SECONDS) } catch (_: Throwable) {}
    }

    fun release(ctx: Context) {
        val cb = callback ?: return
        callback = null
        network = null
        try { ctx.applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (_: Throwable) {}
    }

    // Mạng Wi‑Fi để gắn socket: mạng cam app tự nối, không có thì Wi‑Fi máy đang nối
    fun current(ctx: Context): Network? = network ?: try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (_: Throwable) { null }
}

// Dò luồng video của cam: thử các IP / cổng / đường dẫn hay gặp (70mai, Novatek CarDV, SigmaStar, HiSilicon…),
// đường nào camera trả lời thì mở thử bằng NetStreamSource, ra được 1 khung hình mới nhận.
object DashcamProbe {
    private val HOSTS = listOf("192.168.0.1", "192.168.1.254", "192.168.169.1", "192.168.1.1",
        "192.168.42.1", "192.168.25.1", "192.168.10.1", "192.168.100.1", "192.168.8.1")
    private val RTSP_PATHS = listOf("/livestream/12", "/livestream/11", "/xxx.mov", "/live", "/live/main",
        "/stream1", "/live/ch00_0", "/av0_0", "/ch0", "/11", "/12", "/1", "/h264", "/")
    private val RTSP_PORTS = intArrayOf(554, 8554)
    private val HTTP = listOf(8192 to "/", 8080 to "/?action=stream", 80 to "/?action=stream",
        8081 to "/", 80 to "/stream", 80 to "/live", 8080 to "/")

    fun find(ctx: Context, net: Network?, alive: () -> Boolean, progress: (String) -> Unit): String? {
        for (h in hosts(ctx, net)) {
            if (!alive()) return null
            progress("Đang dò $h…")
            val ports = HashMap<Int, Boolean>()
            fun isOpen(port: Int) = ports.getOrPut(port) { portOpen(net, h, port) }
            if (RTSP_PORTS.none { isOpen(it) } && HTTP.none { isOpen(it.first) }) continue
            // Cam chip Novatek chỉ phát luồng khi bật "live view" qua lệnh HTTP
            if (isOpen(80)) httpPing(net, "http://$h/?custom=1&cmd=2015&par=1")
            for (port in RTSP_PORTS) {
                if (!isOpen(port)) continue
                for (p in RTSP_PATHS) {
                    if (!alive()) return null
                    val url = if (port == 554) "rtsp://$h$p" else "rtsp://$h:$port$p"
                    if (!describeOk(net, h, port, p)) continue
                    progress("Mở thử $url…")
                    if (frameOk(ctx, url)) return url
                }
            }
            for ((port, p) in HTTP) {
                if (!alive()) return null
                if (!isOpen(port)) continue
                val url = if (port == 80) "http://$h$p" else "http://$h:$port$p"
                if (!mjpegOk(net, url)) continue
                progress("Mở thử $url…")
                if (frameOk(ctx, url)) return url
            }
        }
        return null
    }

    // Cổng mặc định của mạng Wi‑Fi trước, rồi tới các IP hay gặp cùng dải với máy
    private fun hosts(ctx: Context, net: Network?): List<String> {
        val out = ArrayList<String>()
        val prefixes = ArrayList<String>()
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(net ?: return HOSTS)?.let { lp ->
                for (r in lp.routes) {
                    val g = r.gateway
                    if (g is Inet4Address && !g.isAnyLocalAddress) out += g.hostAddress ?: continue
                }
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address) a.hostAddress?.let { prefixes += it.substringBeforeLast('.') + "." }
                }
            }
        } catch (_: Throwable) {}
        out += if (prefixes.isEmpty()) HOSTS else HOSTS.filter { h -> prefixes.any { h.startsWith(it) } }
        return out.distinct()
    }

    private fun socket(net: Network?, host: String, port: Int, timeout: Int): Socket {
        val s = Socket()
        try {
            net?.bindSocket(s)
            s.connect(InetSocketAddress(host, port), timeout)
        } catch (e: Throwable) { try { s.close() } catch (_: Throwable) {}; throw e }
        return s
    }

    private fun portOpen(net: Network?, host: String, port: Int): Boolean =
        try { socket(net, host, port, 600).close(); true } catch (_: Throwable) { false }

    private fun line(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 2000) return sb.toString()
            sb.append(c.toChar())
        }
    }

    // RTSP DESCRIBE: 200 + SDP có video mới tính
    private fun describeOk(net: Network?, host: String, port: Int, path: String): Boolean = try {
        socket(net, host, port, 1500).use { s ->
            s.soTimeout = 2500
            val o = s.getOutputStream()
            o.write("DESCRIBE rtsp://$host:$port$path RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: EVCarHUD\r\n\r\n".toByteArray())
            o.flush()
            val i = s.getInputStream()
            val status = line(i) ?: ""
            var len = 0
            while (true) {
                val l = line(i) ?: break
                if (l.isEmpty()) break
                if (l.startsWith("Content-Length", true)) len = l.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val body = ByteArray(len.coerceIn(0, 16_384))
            var n = 0
            while (n < body.size) { val r = i.read(body, n, body.size - n); if (r < 0) break; n += r }
            status.contains(" 200") && String(body, 0, n).contains("m=video")
        }
    } catch (_: Throwable) { false }

    private fun open(net: Network?, url: String): HttpURLConnection {
        val u = URL(url)
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 2500
        return c
    }

    private fun httpPing(net: Network?, url: String) {
        try { val c = open(net, url); c.responseCode; c.disconnect() } catch (_: Throwable) {}
    }

    private fun mjpegOk(net: Network?, url: String): Boolean = try {
        val c = open(net, url)
        val ok = c.responseCode == 200 && (c.contentType ?: "").lowercase().let { it.startsWith("multipart") || it.contains("jpeg") }
        c.disconnect()
        ok
    } catch (_: Throwable) { false }

    private fun frameOk(ctx: Context, url: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 8000
        val src = NetStreamSource(ctx, url) { SystemClock.elapsedRealtime() < deadline }
        return try { src.open(); src.nextFrame(0, YuvFrame()) } catch (e: Throwable) {
            Log.d(DTAG, "$url: ${e.message}"); false
        } finally { try { src.release() } catch (_: Throwable) {} }
    }
}
