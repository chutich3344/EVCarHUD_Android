package com.kevin.evcarhud

import java.net.HttpURLConnection
import java.net.URL

// Mọi lượt gọi HERE đi qua máy chủ riêng (Cloudflare Worker, thư mục worker/ trong repo):
// key HERE nằm trên máy chủ, không nằm trong APK, người dùng cài xong là dùng được.
// Worker đổi https://<PROXY>/<dịch vụ>/... thành https://<dịch vụ>.hereapi.com/... rồi gắn apiKey,
// và giới hạn số lượt gọi theo từng máy (mã cài đặt gửi trong X-Device).
object HereApi {
    const val PROXY = "https://evcarhud-api.evcar-vf7.workers.dev"
    // Mã nhận app (không phải bí mật thật, chỉ chặn người lạ gọi thẳng vào Worker); trùng APP_TOKEN trong worker/wrangler.toml
    private const val APP_TOKEN = "NMuIzilyzSUd-3jRFiDmIjhp"
    @Volatile var device = ""                // mã cài đặt, MainActivity đặt lúc mở app
    fun appToken() = APP_TOKEN                // cho bản đồ vector (MapLibre tự tải ô, gắn header qua OkHttp)

    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("X-App", APP_TOKEN)
        setRequestProperty("X-Device", device)
    }
}
