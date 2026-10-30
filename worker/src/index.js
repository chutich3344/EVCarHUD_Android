// Máy chủ giữ key HERE cho app EVCarHUD.
// App gọi  https://<worker>/<dịch vụ>/<đường dẫn>?<tham số>
// Worker gọi https://<dịch vụ>.hereapi.com/<đường dẫn>?<tham số>&apiKey=<HERE_KEY> rồi trả nguyên kết quả về.
// Chỉ cho phép các dịch vụ app dùng; giới hạn lượt gọi theo máy (X-Device) và theo IP.

const HOSTS = {
  "revgeocode.search": "revgeocode.search.hereapi.com",   // tên đường + giới hạn tốc độ
  "autosuggest.search": "autosuggest.search.hereapi.com", // tìm điểm đến
  "router": "router.hereapi.com",                         // tính đường
  "maps": "maps.hereapi.com",                             // ô bản đồ / vệ tinh
  "traffic.maps": "traffic.maps.hereapi.com",             // lớp giao thông
  "vector": "vector.hereapi.com",                         // ô bản đồ vector (trang Bản đồ 3D)
  "weather": "weather.hereapi.com",                       // thời tiết hiện tại (góc trái HUD)
};
const TILES = new Set(["maps", "traffic.maps", "vector"]);

function text(status, msg) {
  return new Response(msg + "\n", { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}

async function allowed(limiter, key) {
  if (!limiter || !key) return true;   // chưa cấu hình giới hạn thì cho qua
  const { success } = await limiter.limit({ key });
  return success;
}

export default {
  async fetch(req, env) {
    if (req.method !== "GET") return text(405, "chỉ nhận GET");
    const url = new URL(req.url);
    if (url.pathname === "/" || url.pathname === "/health") return text(200, "EVCarHUD ok");
    if (!env.HERE_KEY) return text(500, "chưa nạp HERE_KEY");
    if (env.APP_TOKEN && req.headers.get("X-App") !== env.APP_TOKEN) return text(403, "sai mã app");

    const slash = url.pathname.indexOf("/", 1);
    const service = slash < 0 ? url.pathname.slice(1) : url.pathname.slice(1, slash);
    const host = HOSTS[service];
    if (!host || slash < 0) return text(404, "không có dịch vụ này");

    const device = (req.headers.get("X-Device") || "").slice(0, 64);
    if (!device) return text(400, "thiếu mã máy");
    const ip = req.headers.get("CF-Connecting-IP") || "";
    if (!(await allowed(TILES.has(service) ? env.TILE_RL : env.API_RL, device))) return text(429, "máy này gọi quá nhiều");
    if (!(await allowed(env.IP_RL, ip))) return text(429, "IP này gọi quá nhiều");

    // Giữ nguyên chuỗi tham số của app (không mã hoá lại), bỏ apiKey nếu có, gắn key thật
    let q = url.search.replace(/([?&])apiKey=[^&]*/gi, "$1").replace(/[?&]+$/, "").replace(/\?&+/, "?");
    const up = "https://" + host + url.pathname.slice(slash) + (q ? q + "&" : "?") + "apiKey=" + encodeURIComponent(env.HERE_KEY);

    const r = await fetch(up, { headers: { "User-Agent": "EVCarHUD-proxy" } });
    // Chỉ chuyển các header cần thiết (không lộ gì về key)
    const h = new Headers();
    for (const k of ["content-type", "cache-control", "etag", "last-modified", "expires"]) {
      const v = r.headers.get(k);
      if (v) h.set(k, v);
    }
    return new Response(r.body, { status: r.status, headers: h });
  },
};
