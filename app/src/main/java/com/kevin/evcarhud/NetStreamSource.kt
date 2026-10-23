package com.kevin.evcarhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest

private const val NTAG = "NetStream"
private const val CONNECT_MS = 5000
private const val READ_MS = 5000

// Camera hành trình qua Wi‑Fi. Không cần thư viện ngoài.
//  - rtsp://…  : RTSP, RTP qua TCP (interleaved), H.264 hoặc H.265, giải mã phần cứng bằng MediaCodec.
//                Hỗ trợ đăng nhập Basic/Digest qua rtsp://user:pass@host/…
//  - http://…  : luồng MJPEG (multipart), kiểu "live view" của nhiều camera chip Novatek (cổng 8192).
// Socket được gắn vào mạng Wi‑Fi (Wi‑Fi camera không có Internet nên Android hay đẩy kết nối sang 4G).
// Giống CameraSource: luôn giữ khung mới nhất, khung cũ bị bỏ khi suy luận chậm hơn camera.
class NetStreamSource(
    private val context: Context,
    private val url: String,
    private val alive: () -> Boolean
) : FrameSource {
    private val lock = Object()
    private val back = YuvFrame()
    private var ready = false
    @Volatile private var closed = false
    @Volatile private var error: Throwable? = null
    private var reader: Thread? = null
    private var socket: Socket? = null
    private var codec: MediaCodec? = null

    override var frameUs = 33_333L
        private set
    private var lastPts = -1L

    private fun live() = !closed && alive()

    override fun open() {
        val u = try { URI(url) } catch (e: Throwable) { throw IllegalStateException("Địa chỉ luồng không hợp lệ: $url") }
        val scheme = u.scheme?.lowercase()
        if (scheme != "rtsp" && scheme != "http") throw IllegalStateException("Chỉ hỗ trợ rtsp:// hoặc http:// (MJPEG)")
        val host = u.host ?: throw IllegalStateException("Thiếu địa chỉ IP camera trong $url")
        val port = if (u.port > 0) u.port else if (scheme == "rtsp") 554 else 80
        val s = Socket()
        wifiNetwork()?.let { try { it.bindSocket(s) } catch (e: Throwable) { Log.w(NTAG, "không gắn được vào Wi‑Fi", e) } }
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            throw IllegalStateException("Không kết nối được $host:$port (đã nối Wi‑Fi của camera chưa?)")
        }
        s.soTimeout = READ_MS
        s.tcpNoDelay = true
        socket = s
        val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val out = s.getOutputStream()
        if (scheme == "rtsp") {
            val rtsp = Rtsp(u, input, out)
            rtsp.handshake()   // lỗi đăng nhập / sai đường dẫn báo ngay khi mở
            reader = Thread({ guard { rtsp.stream() } }, "rtsp-src").also { it.start() }
        } else {
            val path = (u.rawPath ?: "/").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
            out.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: keep-alive\r\n\r\n".toByteArray())
            out.flush()
            val status = readLine(input) ?: throw IllegalStateException("Camera không trả lời")
            if (!status.contains(" 200")) throw IllegalStateException("Camera trả về: $status")
            while (true) { val l = readLine(input) ?: break; if (l.isEmpty()) break }
            reader = Thread({ guard { mjpeg(input) } }, "mjpeg-src").also { it.start() }
        }
    }

    // Ưu tiên Wi‑Fi cam do app tự nối (DashcamScan.kt), không có thì Wi‑Fi máy đang nối
    private fun wifiNetwork(): Network? = DashcamWifi.current(context)

    private fun guard(body: () -> Unit) {
        try { body() } catch (e: Throwable) {
            if (!closed) {
                Log.e(NTAG, "luồng lỗi", e)
                synchronized(lock) { error = if (e is java.net.SocketTimeoutException) IllegalStateException("Camera Wi‑Fi không gửi hình") else e; lock.notifyAll() }
            }
        }
    }

    private fun publish(ptsUs: Long) {
        // gọi trong synchronized(lock) sau khi đã ghi back
        if (lastPts >= 0) {
            val d = ptsUs - lastPts
            if (d in 5_000L..200_000L) frameUs = (frameUs * 9 + d) / 10
        }
        lastPts = ptsUs
        back.ptsUs = ptsUs
        ready = true
        lock.notifyAll()
    }

    override fun nextFrame(minPtsUs: Long, dst: YuvFrame): Boolean {
        return synchronized(lock) {
            while (!ready) {
                error?.let { throw it }
                if (!live()) return false
                lock.wait(100)
            }
            ready = false
            dst.copyFromSwap(back)
            true
        }
    }

    override fun release() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        try { socket?.close() } catch (_: Throwable) {}
        try { reader?.join(2000) } catch (_: Throwable) {}
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null; socket = null; reader = null
    }

    // ---------------- MJPEG ----------------

    private fun mjpeg(input: InputStream) {
        val jpg = ByteArrayOutputStream(256 * 1024)
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var px = IntArray(0)
        val t0 = System.nanoTime()
        var prev = -1
        var inJpeg = false
        while (live()) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("Camera đóng luồng")
            if (!inJpeg) {
                if (prev == 0xFF && b == 0xD8) { inJpeg = true; jpg.reset(); jpg.write(0xFF); jpg.write(0xD8) }
            } else {
                jpg.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    inJpeg = false
                    val data = jpg.toByteArray()
                    val bmp = try { BitmapFactory.decodeByteArray(data, 0, data.size, opts) } catch (_: Throwable) { null }
                    if (bmp != null) {
                        opts.inBitmap = bmp
                        val w = bmp.width and 1.inv()
                        val h = bmp.height and 1.inv()
                        if (px.size != w * h) px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        synchronized(lock) {
                            if (closed) return
                            rgbToYuv(px, w, h, back)
                            publish((System.nanoTime() - t0) / 1000L)
                        }
                    }
                }
            }
            prev = b
        }
    }

    // ARGB -> YUV420 phẳng (BT.709 dải giới hạn, khớp DisplayConverter); chroma lấy điểm góc trên trái mỗi ô 2x2
    private fun rgbToYuv(px: IntArray, w: Int, h: Int, f: YuvFrame) {
        val cw = w / 2
        val ch = h / 2
        if (f.yArr.size != w * h) f.yArr = ByteArray(w * h)
        if (f.uArr.size != cw * ch) { f.uArr = ByteArray(cw * ch); f.vArr = ByteArray(cw * ch) }
        f.w = w; f.h = h; f.yRow = w; f.uvRow = cw; f.uvPix = 1
        val yA = f.yArr; val uA = f.uArr; val vA = f.vArr
        for (y in 0 until h) {
            val row = y * w
            val crow = (y shr 1) * cw
            for (x in 0 until w) {
                val c = px[row + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                yA[row + x] = (16 + ((47 * r + 157 * g + 16 * b) shr 8)).toByte()
                if ((x or y) and 1 == 0) {
                    val i = crow + (x shr 1)
                    uA[i] = (128 + ((-26 * r - 87 * g + 112 * b) shr 8)).toByte()
                    vA[i] = (128 + ((112 * r - 102 * g - 10 * b) shr 8)).toByte()
                }
            }
        }
    }

    // ---------------- RTSP ----------------

    private inner class Rtsp(private val u: URI, private val input: BufferedInputStream, private val out: OutputStream) {
        private val base = URI(u.scheme, null, u.host, u.port, u.path, u.query, null).toString()
        private val user = u.userInfo?.substringBefore(':')
        private val pass = u.userInfo?.substringAfter(':', "")
        private var cseq = 0
        private var session: String? = null
        private var timeoutS = 60
        private var authHeader: ((String, String) -> String)? = null
        private var h265 = false
        private var pt = -1
        private val params = ArrayList<ByteArray>()   // VPS/SPS/PPS lấy từ SDP

        private inner class Resp(val code: Int, val headers: Map<String, String>, val body: String)

        private fun request(method: String, uri: String, extra: String = ""): Resp {
            var r = send(method, uri, extra)
            if (r.code == 401 && user != null && authHeader == null) {
                val wa = r.headers["www-authenticate"] ?: throw IllegalStateException("Camera đòi đăng nhập")
                authHeader = makeAuth(wa)
                r = send(method, uri, extra)
            }
            if (r.code == 401) throw IllegalStateException("Sai tài khoản/mật khẩu camera (thêm rtsp://user:pass@… vào địa chỉ)")
            if (r.code != 200) throw IllegalStateException("$method lỗi ${r.code}")
            return r
        }

        private fun send(method: String, uri: String, extra: String): Resp {
            val sb = StringBuilder()
            sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
            sb.append("CSeq: ").append(++cseq).append("\r\n")
            sb.append("User-Agent: EVCarHUD\r\n")
            session?.let { sb.append("Session: ").append(it).append("\r\n") }
            authHeader?.let { sb.append("Authorization: ").append(it(method, uri)).append("\r\n") }
            sb.append(extra)
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
            while (true) {
                // Bỏ qua gói RTP lọt vào giữa (sau PLAY)
                input.mark(1)
                val first = input.read()
                if (first < 0) throw IllegalStateException("Camera đóng kết nối")
                if (first == '$'.code) { skipInterleaved(); continue }
                input.reset()
                return readResponse()
            }
        }

        private fun readResponse(): Resp {
            val status = readLine(input) ?: throw IllegalStateException("Camera đóng kết nối")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val hs = HashMap<String, String>()
            while (true) {
                val l = readLine(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) hs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val n = hs["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(n)
            readFully(input, body, 0, n)
            return Resp(code, hs, String(body))
        }

        private fun skipInterleaved() {
            input.read()
            val len = (input.read() shl 8) or input.read()
            var left = len
            while (left > 0) { val k = input.skip(left.toLong()).toInt(); if (k <= 0) break; left -= k }
        }

        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun makeAuth(wa: String): (String, String) -> String {
            val p = pass ?: ""
            if (wa.startsWith("Basic", ignoreCase = true)) {
                val tok = Base64.encodeToString("$user:$p".toByteArray(), Base64.NO_WRAP)
                return { _, _ -> "Basic $tok" }
            }
            fun field(k: String) = Regex("$k=\"([^\"]*)\"").find(wa)?.groupValues?.get(1) ?: ""
            val realm = field("realm")
            val nonce = field("nonce")
            val ha1 = md5("$user:$realm:$p")
            return { m, uri ->
                val resp = md5("$ha1:$nonce:${md5("$m:$uri")}")
                "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\""
            }
        }

        fun handshake() {
            val d = request("DESCRIBE", base, "Accept: application/sdp\r\n")
            val contentBase = d.headers["content-base"] ?: d.headers["content-location"] ?: base
            // Chỉ lấy phần m=video của SDP
            var inVideo = false
            var control: String? = null
            for (raw in d.body.lines()) {
                val l = raw.trim()
                if (l.startsWith("m=")) {
                    inVideo = l.startsWith("m=video")
                    if (inVideo) pt = l.split(' ').getOrNull(3)?.toIntOrNull() ?: -1
                    continue
                }
                if (!inVideo) continue
                when {
                    l.startsWith("a=rtpmap:") -> {
                        val enc = l.substringAfter(' ').uppercase()
                        if (enc.startsWith("H265") || enc.startsWith("HEVC")) h265 = true
                        else if (!enc.startsWith("H264")) throw IllegalStateException("Camera gửi $enc, chỉ hỗ trợ H.264/H.265")
                    }
                    l.startsWith("a=control:") -> control = l.substringAfter("a=control:").trim()
                    l.startsWith("a=fmtp:") -> {
                        for (kv in l.substringAfter(' ').split(';')) {
                            val k = kv.substringBefore('=').trim()
                            val v = kv.substringAfter('=', "").trim()
                            when (k) {
                                "sprop-parameter-sets" -> v.split(',').forEach { addParam(it) }
                                "sprop-vps", "sprop-sps", "sprop-pps" -> addParam(v)
                            }
                        }
                    }
                }
            }
            if (pt < 0) throw IllegalStateException("Luồng không có hình (SDP không có m=video)")
            val ctl = control
            val track = when {
                ctl == null || ctl == "*" -> contentBase
                ctl.startsWith("rtsp://", ignoreCase = true) -> ctl
                else -> contentBase.trimEnd('/') + "/" + ctl
            }
            val s = request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
            val sh = s.headers["session"] ?: throw IllegalStateException("Camera không trả Session")
            session = sh.substringBefore(';').trim()
            Regex("timeout=(\\d+)").find(sh)?.groupValues?.get(1)?.toIntOrNull()?.let { timeoutS = it.coerceIn(10, 120) }
            request("PLAY", contentBase, "Range: npt=0.000-\r\n")
            Log.d(NTAG, "RTSP ${if (h265) "H.265" else "H.264"} pt=$pt track=$track")
        }

        private fun addParam(b64: String) {
            if (b64.isEmpty()) return
            try { params.add(Base64.decode(b64, Base64.DEFAULT)) } catch (_: Throwable) {}
        }

        // ---- RTP -> NAL -> MediaCodec ----
        private val au = ByteArrayOutputStream(512 * 1024)   // access unit đang gom (Annex‑B)
        private var auTs = -1L
        private var auKey = false
        private val fu = ByteArrayOutputStream(512 * 1024)
        private var fuOn = false
        private var vps: ByteArray? = null
        private var sps: ByteArray? = null
        private var pps: ByteArray? = null
        private var ts0 = -1L
        private var tsHigh = 0L
        private var lastTs = -1L
        private var gotKey = false
        private val info = MediaCodec.BufferInfo()
        private val start = byteArrayOf(0, 0, 0, 1)

        private fun nalType(n: ByteArray) = if (h265) (n[0].toInt() shr 1) and 0x3F else n[0].toInt() and 0x1F

        private fun onNal(n: ByteArray) {
            if (n.isEmpty()) return
            val t = nalType(n)
            if (h265) {
                when (t) { 32 -> vps = n; 33 -> sps = n; 34 -> pps = n }
                if (t in 16..21) auKey = true
            } else {
                when (t) { 7 -> sps = n; 8 -> pps = n }
                if (t == 5) auKey = true
            }
            au.write(start); au.write(n)
        }

        fun stream() {
            for (p in params) onNal(p)   // SPS/PPS từ SDP (nếu có)
            au.reset(); auKey = false
            var lastKeep = System.currentTimeMillis()
            val hdr = ByteArray(3)
            var pkt = ByteArray(65536)
            while (live()) {
                // Giữ phiên: nhiều camera ngắt nếu không có yêu cầu nào trong khoảng timeout
                val now = System.currentTimeMillis()
                if (now - lastKeep > timeoutS * 500L) {
                    lastKeep = now
                    val sb = "GET_PARAMETER $base RTSP/1.0\r\nCSeq: ${++cseq}\r\nSession: $session\r\n" +
                            (authHeader?.let { "Authorization: ${it("GET_PARAMETER", base)}\r\n" } ?: "") + "\r\n"
                    out.write(sb.toByteArray()); out.flush()
                }
                val first = input.read()
                if (first < 0) throw IllegalStateException("Camera đóng luồng")
                if (first != '$'.code) {
                    // Trả lời RTSP (giữ phiên) xen giữa: đọc bỏ
                    if (first == 'R'.code) { readLine(input); while (true) { val l = readLine(input) ?: break; if (l.isEmpty()) break } }
                    continue
                }
                readFully(input, hdr, 0, 3)
                val ch = hdr[0].toInt() and 0xFF
                val len = ((hdr[1].toInt() and 0xFF) shl 8) or (hdr[2].toInt() and 0xFF)
                if (pkt.size < len) pkt = ByteArray(len)
                readFully(input, pkt, 0, len)
                if (ch != 0 || len < 12) continue   // kênh 1 = RTCP
                onRtp(pkt, len)
                drain()
            }
        }

        private fun onRtp(p: ByteArray, len: Int) {
            val b0 = p[0].toInt() and 0xFF
            if (b0 shr 6 != 2) return
            val marker = (p[1].toInt() and 0x80) != 0
            if ((p[1].toInt() and 0x7F) != pt) return
            val ts = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or
                    ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
            var off = 12 + 4 * (b0 and 0x0F)
            if ((b0 and 0x10) != 0) {
                if (off + 4 > len) return
                off += 4 + 4 * (((p[off + 2].toInt() and 0xFF) shl 8) or (p[off + 3].toInt() and 0xFF))
            }
            var end = len
            if ((b0 and 0x20) != 0) end -= p[len - 1].toInt() and 0xFF
            if (off >= end) return

            if (auTs >= 0 && ts != auTs && au.size() > 0) flushAu()   // đổi timestamp = sang khung mới
            auTs = ts
            if (h265) depay265(p, off, end) else depay264(p, off, end)
            if (marker) flushAu()
        }

        private fun depay264(p: ByteArray, off: Int, end: Int) {
            val type = p[off].toInt() and 0x1F
            when (type) {
                in 1..23 -> onNal(p.copyOfRange(off, end))
                24 -> {   // STAP‑A
                    var i = off + 1
                    while (i + 2 <= end) {
                        val n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                        i += 2
                        if (n <= 0 || i + n > end) break
                        onNal(p.copyOfRange(i, i + n)); i += n
                    }
                }
                28 -> {   // FU‑A
                    if (off + 2 > end) return
                    val h = p[off + 1].toInt()
                    if (h and 0x80 != 0) {
                        fu.reset(); fuOn = true
                        fu.write((p[off].toInt() and 0xE0) or (h and 0x1F))
                    }
                    if (!fuOn) return
                    fu.write(p, off + 2, end - off - 2)
                    if (h and 0x40 != 0) { fuOn = false; onNal(fu.toByteArray()) }
                }
            }
        }

        private fun depay265(p: ByteArray, off: Int, end: Int) {
            if (off + 2 > end) return
            val type = (p[off].toInt() shr 1) and 0x3F
            when (type) {
                48 -> {   // AP
                    var i = off + 2
                    while (i + 2 <= end) {
                        val n = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                        i += 2
                        if (n <= 0 || i + n > end) break
                        onNal(p.copyOfRange(i, i + n)); i += n
                    }
                }
                49 -> {   // FU
                    if (off + 3 > end) return
                    val h = p[off + 2].toInt()
                    if (h and 0x80 != 0) {
                        fu.reset(); fuOn = true
                        fu.write((p[off].toInt() and 0x81) or ((h and 0x3F) shl 1))
                        fu.write(p[off + 1].toInt())
                    }
                    if (!fuOn) return
                    fu.write(p, off + 3, end - off - 3)
                    if (h and 0x40 != 0) { fuOn = false; onNal(fu.toByteArray()) }
                }
                else -> onNal(p.copyOfRange(off, end))
            }
        }

        private fun ensureCodec(): MediaCodec? {
            codec?.let { return it }
            val s = sps ?: return null
            val pp = pps ?: return null
            if (h265 && vps == null) return null
            val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val fmt = MediaFormat.createVideoFormat(mime, 1920, 1080)
            fun annexB(vararg ns: ByteArray): ByteBuffer {
                val o = ByteArrayOutputStream()
                for (n in ns) { o.write(start); o.write(n) }
                return ByteBuffer.wrap(o.toByteArray())
            }
            if (h265) fmt.setByteBuffer("csd-0", annexB(vps!!, s, pp))
            else { fmt.setByteBuffer("csd-0", annexB(s)); fmt.setByteBuffer("csd-1", annexB(pp)) }
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)   // trả khung ngay, không giữ lại
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            return c
        }

        private fun flushAu() {
            if (au.size() == 0) return
            val key = auKey
            val data = au.toByteArray()
            au.reset(); auKey = false
            val ts = auTs
            if (!gotKey && !key) return          // chờ khung khoá đầu tiên
            val c = ensureCodec() ?: return
            gotKey = true
            // Timestamp RTP 32 bit, 90 kHz -> µs, có xử lý tràn số
            if (lastTs >= 0 && ts < lastTs && lastTs - ts > 0x80000000L) tsHigh += 0x100000000L
            lastTs = ts
            val full = tsHigh + ts
            if (ts0 < 0) ts0 = full
            val pts = (full - ts0) * 1000L / 90L
            val ii = c.dequeueInputBuffer(20_000)
            if (ii < 0) return                   // bộ giải mã đầy: bỏ khung (luồng trực tiếp, ưu tiên độ trễ thấp)
            val buf = c.getInputBuffer(ii) ?: return
            buf.clear()
            if (data.size > buf.capacity()) { c.queueInputBuffer(ii, 0, 0, pts, 0); return }
            buf.put(data)
            c.queueInputBuffer(ii, 0, data.size, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }

        private fun drain() {
            val c = codec ?: return
            while (true) {
                val oi = c.dequeueOutputBuffer(info, 0)
                if (oi < 0) return
                if (info.size > 0) {
                    val img = c.getOutputImage(oi)
                    if (img != null) {
                        try {
                            if (img.format == ImageFormat.YUV_420_888) synchronized(lock) {
                                if (!closed) {
                                    val pl = img.planes
                                    back.w = img.width
                                    back.h = img.height
                                    back.yArr = grab(pl[0].buffer, back.yArr)
                                    back.uArr = grab(pl[1].buffer, back.uArr)
                                    back.vArr = grab(pl[2].buffer, back.vArr)
                                    back.yRow = pl[0].rowStride
                                    back.uvRow = pl[1].rowStride
                                    back.uvPix = pl[1].pixelStride
                                    publish(info.presentationTimeUs)
                                }
                            }
                        } finally { img.close() }
                    }
                }
                c.releaseOutputBuffer(oi, false)
            }
        }

        private fun grab(b: ByteBuffer, old: ByteArray): ByteArray {
            val n = b.remaining()
            val arr = if (old.size == n) old else ByteArray(n)
            b.get(arr, 0, n)
            return arr
        }
    }
}

private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val c = input.read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(c.toChar())
        if (sb.length > 8192) return sb.toString()
    }
}

private fun readFully(input: InputStream, b: ByteArray, off: Int, n: Int) {
    var got = 0
    while (got < n) {
        val k = input.read(b, off + got, n - got)
        if (k < 0) throw IllegalStateException("Camera đóng luồng")
        got += k
    }
}

// Hoán đổi bộ đệm giữa hai YuvFrame (không sao chép dữ liệu)
internal fun YuvFrame.copyFromSwap(o: YuvFrame) {
    val w0 = w; w = o.w; o.w = w0
    val h0 = h; h = o.h; o.h = h0
    val y0 = yArr; yArr = o.yArr; o.yArr = y0
    val u0 = uArr; uArr = o.uArr; o.uArr = u0
    val v0 = vArr; vArr = o.vArr; o.vArr = v0
    val yr = yRow; yRow = o.yRow; o.yRow = yr
    val ur = uvRow; uvRow = o.uvRow; o.uvRow = ur
    val up = uvPix; uvPix = o.uvPix; o.uvPix = up
    val pt = ptsUs; ptsUs = o.ptsUs; o.ptsUs = pt
}
