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
  "data.traffic": "data.traffic.hereapi.com",             // kẹt xe quanh xe (Traffic Flow v7) khi chạy tự do
  "vector": "vector.hereapi.com",                         // ô bản đồ vector (trang Bản đồ 3D)
  "weather": "weather.hereapi.com",                       // thời tiết hiện tại (góc trái HUD)
  "gofa": "places-api.gofa.vn",                           // GOFA Place Search: tìm địa chỉ Việt Nam (key gửi qua header)
};
const TILES = new Set(["maps", "traffic.maps", "vector"]);
// Bộ nhớ đệm chung (giây): nhiều máy hỏi cùng URL (app đã làm tròn toạ độ theo ô lưới) chỉ tốn 1 lượt gọi HERE / GOFA.
// Không đệm tính đường (mỗi chuyến một khác) và tên đường theo toạ độ chính xác.
const TTL = {
  "weather": 900,              // thời tiết theo ô ~5 km
  "data.traffic": 120,         // kẹt xe theo ô ~2 km
  "traffic.maps": 300,         // ô lớp giao thông
  "autosuggest.search": 21600, // gợi ý tìm kiếm (cùng từ khoá, cùng khu ~1 km)
  "gofa": 21600,
  "maps": 86400,
  "vector": 86400,
};

// Đệm trong bộ nhớ của Worker (mỗi máy chủ Cloudflare giữ riêng, mất khi Worker khởi động lại) cho các kết quả JSON nhỏ.
// Lý do: Cache API (caches.default) không chạy trên tên miền *.workers.dev; khi gắn tên miền riêng thì cả hai cùng chạy.
const MEM = new Map();
const MEM_SVC = new Set(["weather", "data.traffic", "autosuggest.search", "gofa"]);
function memGet(k) {
  const e = MEM.get(k);
  if (!e) return null;
  if (e.exp < Date.now()) { MEM.delete(k); return null; }
  return new Response(e.body, { status: 200, headers: { "content-type": e.ct, "x-cache": "mem" } });
}
function memPut(k, body, ct, ttl) {
  if (MEM.size > 800) { const now = Date.now(); for (const [kk, e] of MEM) if (e.exp < now || MEM.size > 600) MEM.delete(kk); }
  MEM.set(k, { body, ct, exp: Date.now() + ttl * 1000 });
}

function text(status, msg) {
  return new Response(msg + "\n", { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}

async function allowed(limiter, key) {
  if (!limiter || !key) return true;   // chưa cấu hình giới hạn thì cho qua
  const { success } = await limiter.limit({ key });
  return success;
}

export default {
  async fetch(req, env, ctx) {
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
    const path = url.pathname.slice(slash);
    let up, hdr = { "User-Agent": "EVCarHUD-proxy" };
    if (service === "gofa") {
      // GOFA: chỉ cho tìm gợi ý + chi tiết, key nằm trong header X-API-Key
      if (!env.GOFA_KEY) return text(503, "chưa nạp GOFA_KEY");
      if (path !== "/v5/Place/AutoComplete" && path !== "/v5/Place/Detail") return text(404, "không có dịch vụ này");
      up = "https://" + host + path + q;
      hdr["X-API-Key"] = env.GOFA_KEY;
    } else {
      up = "https://" + host + path + (q ? q + "&" : "?") + "apiKey=" + encodeURIComponent(env.HERE_KEY);
    }

    // Có trong bộ nhớ đệm chung thì trả luôn (khoá = URL của app, không chứa key)
    const ttl = TTL[service] || 0;
    const ck = ttl ? new Request("https://cache.evcarhud/" + service + path + q) : null;
    if (ck) {
      if (MEM_SVC.has(service)) { const m = memGet(ck.url); if (m) return m; }
      const hit = await caches.default.match(ck);
      if (hit) { const hh = new Headers(hit.headers); hh.set("x-cache", "edge"); return new Response(hit.body, { status: hit.status, headers: hh }); }
    }

    const r = await fetch(up, { headers: hdr });
    // Chỉ chuyển các header cần thiết (không lộ gì về key)
    const h = new Headers();
    for (const k of ["content-type", "cache-control", "etag", "last-modified", "expires"]) {
      const v = r.headers.get(k);
      if (v) h.set(k, v);
    }
    if (ck && r.status === 200 && MEM_SVC.has(service)) {
      const body = await r.arrayBuffer();
      memPut(ck.url, body, h.get("content-type") || "application/json", ttl);
      h.set("cache-control", "public, max-age=" + ttl);
      ctx.waitUntil(caches.default.put(ck, new Response(body.slice(0), { status: 200, headers: h })));
      return new Response(body, { status: 200, headers: h });
    }
    if (ck && r.status === 200) {
      h.set("cache-control", "public, max-age=" + ttl);
      const res = new Response(r.body, { status: 200, headers: h });
      ctx.waitUntil(caches.default.put(ck, res.clone()));
      return res;
    }
    return new Response(r.body, { status: r.status, headers: h });
  },
};
