package com.kevin.evcarhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

// Ảnh quanh mỗi lần cảnh báo để xem lại và đánh dấu đúng / nhầm, đồng thời làm dữ liệu huấn luyện YOLO.
// Mỗi lần cảnh báo: 8 ảnh 2 s trước + 8 ảnh 2 s sau (ảnh gốc chưa vẽ HUD, rộng 640) + 1 ảnh có HUD lúc cảnh báo.
// Thư mục: Android/data/com.kevin.evcarhud/files/alerts/clips/<ngày_giờ>/ (f00.jpg … f15.jpg, hud.jpg, info.txt, verdict.txt)
// Thu ảnh huấn luyện (tuỳ chọn): 1 ảnh gốc mỗi 5 s khi xe chạy trên 10 km/h, vào files/dataset/.
class AlertClips(private val ctx: Context) {
    class Event(val dir: File, val title: String, val info: String, val verdict: String)

    private val root: File get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "alerts/clips")
    val datasetDir: File get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "dataset")
    private val io = Executors.newSingleThreadExecutor()
    private val nameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // Bộ đệm vòng ảnh nhỏ (chỉ luồng hiển thị dùng)
    private val ring = arrayOfNulls<Bitmap>(PRE)
    private val scalePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private var ringHead = 0
    private var lastFeed = 0L
    private var post: ArrayList<Bitmap>? = null
    private var pre: List<Bitmap> = emptyList()
    private var evInfo = ""
    private var evHud: Bitmap? = null
    private var lastTrigger = -1_000_000L
    private var lastShot = 0L
    @Volatile var datasetCount = -1
        private set

    // Gọi mỗi khung TRƯỚC khi vẽ HUD lên ảnh
    fun feed(src: Bitmap, nowMs: Long) {
        if (nowMs - lastFeed < STEP_MS) return
        lastFeed = nowMs
        val h = OUT_W * src.height / src.width
        val p = post
        if (p != null) {
            p.add(Bitmap.createScaledBitmap(src, OUT_W, h, true))
            if (p.size >= POST) flush()
            return
        }
        val old = ring[ringHead]
        ring[ringHead] = if (old != null && old.width == OUT_W && old.height == h && old.isMutable) {
            android.graphics.Canvas(old).drawBitmap(src, null, android.graphics.Rect(0, 0, OUT_W, h), scalePaint); old
        } else Bitmap.createScaledBitmap(src, OUT_W, h, true).copy(Bitmap.Config.ARGB_8888, true)
        ringHead = (ringHead + 1) % PRE
    }

    // Bắt đầu lưu một lần cảnh báo; hud = khung đã vẽ HUD (được sao chép), info = mô tả một dòng
    fun trigger(hud: Bitmap, info: String, nowMs: Long) {
        if (post != null || nowMs - lastTrigger < COOLDOWN_MS) return
        lastTrigger = nowMs
        // sao chép bộ đệm vòng theo thứ tự thời gian (các bitmap trong vòng sẽ bị ghi đè nên phải copy)
        val list = ArrayList<Bitmap>()
        for (i in 0 until PRE) {
            ring[(ringHead + i) % PRE]?.let { list.add(it.copy(Bitmap.Config.ARGB_8888, false)) }
        }
        pre = list
        evHud = Bitmap.createScaledBitmap(hud, OUT_W * 2, OUT_W * 2 * hud.height / hud.width, true)
        evInfo = info
        post = ArrayList()
    }

    private fun flush() {
        val frames = pre + (post ?: emptyList())
        val hud = evHud
        val info = evInfo
        post = null; pre = emptyList(); evHud = null
        val name = nameFmt.format(Date())
        io.execute {
            try {
                val d = File(root, name).apply { mkdirs() }
                frames.forEachIndexed { i, b -> save(b, File(d, "f%02d.jpg".format(i)), 85); b.recycle() }
                hud?.let { save(it, File(d, "hud.jpg"), 80); it.recycle() }
                File(d, "info.txt").writeText(info)
                prune()
            } catch (e: Throwable) {
                Log.w("AlertClips", "lưu ảnh cảnh báo lỗi", e)
            }
        }
    }

    // Thu ảnh huấn luyện: gọi mỗi khung trước khi vẽ HUD
    fun collect(src: Bitmap, nowMs: Long, speedKmh: Float) {
        if (speedKmh < 10f || nowMs - lastShot < DATASET_EVERY_MS) return
        lastShot = nowMs
        val copy = src.copy(Bitmap.Config.ARGB_8888, false)
        io.execute {
            try {
                val d = datasetDir.apply { mkdirs() }
                val n = d.list()?.size ?: 0
                datasetCount = n
                if (n < DATASET_MAX) {
                    save(copy, File(d, "img_${nameFmt.format(Date())}_${SystemClock.elapsedRealtime() % 1000}.jpg"), 90)
                    datasetCount = n + 1
                }
            } catch (_: Throwable) {
            } finally { copy.recycle() }
        }
    }

    private fun save(b: Bitmap, f: File, q: Int) {
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, q, it) }
    }

    // Giữ tối đa MAX_EVENTS lần gần nhất
    private fun prune() {
        val all = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        if (all.size <= MAX_EVENTS) return
        for (d in all.take(all.size - MAX_EVENTS)) d.deleteRecursively()
    }

    // Danh sách để xem lại (mới nhất trước); gọi ngoài luồng UI
    fun list(): List<Event> {
        val all = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return emptyList()
        return all.map { d ->
            val n = d.name   // yyyyMMdd_HHmmss
            val title = if (n.length >= 15) "${n.substring(9, 11)}:${n.substring(11, 13)}:${n.substring(13, 15)}  ${n.substring(6, 8)}/${n.substring(4, 6)}" else n
            Event(d, title,
                try { File(d, "info.txt").readText() } catch (_: Throwable) { "" },
                try { File(d, "verdict.txt").readText().trim() } catch (_: Throwable) { "" })
        }
    }

    fun mark(e: Event, correct: Boolean) {
        io.execute { try { File(e.dir, "verdict.txt").writeText(if (correct) "dung" else "nham") } catch (_: Throwable) {} }
    }

    fun thumb(e: Event): Bitmap? = try {
        val f = File(e.dir, "hud.jpg").takeIf { it.exists() } ?: File(e.dir, "f08.jpg")
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
    } catch (_: Throwable) { null }

    fun summary(): String {
        val l = list()
        val ok = l.count { it.verdict == "dung" }
        val bad = l.count { it.verdict == "nham" }
        return "${l.size} lần đã lưu · $ok đúng · $bad nhầm · ${l.size - ok - bad} chưa xem"
    }

    companion object {
        const val OUT_W = 640
        const val STEP_MS = 250L        // 4 ảnh / giây
        const val PRE = 8               // 2 s trước
        const val POST = 8              // 2 s sau
        const val COOLDOWN_MS = 5000L
        const val MAX_EVENTS = 300
        const val DATASET_EVERY_MS = 5000L
        const val DATASET_MAX = 5000
    }
}
