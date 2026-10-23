package com.kevin.evcarhud

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

// Trang Bản đồ vẽ bằng MapLibre (bản đồ vector, có nhà 3D) với dữ liệu ô vector HERE đi qua Worker như mọi lượt gọi khác.
// Bớt lượt gọi: ô vector chỉ tải tới mức 16 (sát hơn thì phóng to ô sẵn có), mọi ô bản đồ / vệ tinh giữ 30 ngày trong bộ nhớ
// đệm của MapLibre (200 MB), ô giao thông giữ 5 phút. Phông chữ tên đường nằm sẵn trong app (assets/glyphs), không tải mạng.
object VectorMap {
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            MapLibre.getInstance(ctx.applicationContext)
            val client = OkHttpClient.Builder()
                .addInterceptor { ch ->
                    val rq = ch.request()
                    val r = if (rq.url.toString().startsWith(HereApi.PROXY))
                        rq.newBuilder().header("X-App", HereApi.appToken()).header("X-Device", HereApi.device).build() else rq
                    ch.proceed(r)
                }
                .addNetworkInterceptor { ch ->
                    val res = ch.proceed(ch.request())
                    val u = ch.request().url.toString()
                    if (!res.isSuccessful || !u.startsWith(HereApi.PROXY)) res
                    else res.newBuilder()
                        .removeHeader("Pragma").removeHeader("Expires")
                        .header("Cache-Control", if ("/traffic.maps/" in u) "max-age=300" else "max-age=2592000")
                        .build()
                }
                .build()
            HttpRequestUtil.setOkHttpClient(client)
            OfflineManager.getInstance(ctx.applicationContext).setMaximumAmbientCacheSize(200L * 1024 * 1024,
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {}
                    override fun onError(message: String) { Log.w("VectorMap", "cache: $message") }
                })
            ready = true
        }
    }

    // Kiểu bản đồ đêm (hợp với HUD). Tên lớp / thuộc tính theo ô vector HERE (đã đọc thử ô ở TP.HCM):
    // roads, road_labels, water, landuse, buildings (height), places
    fun style(): String {
        val p = HereApi.PROXY
        return """
{
 "version": 8,
 "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
 "sources": {
  "here": {"type": "vector", "tiles": ["$p/vector/v2/vectortiles/base/mc/{z}/{x}/{y}/omv"], "minzoom": 1, "maxzoom": 16},
  "sat": {"type": "raster", "tiles": ["$p/maps/v3/base/mc/{z}/{x}/{y}/jpeg?style=satellite.day&size=512"], "tileSize": 512, "maxzoom": 19},
  "traffic": {"type": "raster", "tiles": ["$p/traffic.maps/v3/flow/mc/{z}/{x}/{y}/png?size=512"], "tileSize": 512, "maxzoom": 18}
 },
 "layers": [
  {"id": "bg", "type": "background", "paint": {"background-color": "#0a1620"}},
  {"id": "sat", "type": "raster", "source": "sat", "layout": {"visibility": "none"}},
  {"id": "park", "type": "fill", "source": "here", "source-layer": "landuse",
   "filter": ["match", ["get", "kind"], ["park", "forest", "wood", "nature_reserve", "garden", "grass", "golf_course", "cemetery", "zoo", "recreation_ground"], true, false],
   "paint": {"fill-color": "#0f3324", "fill-opacity": 0.85}},
  {"id": "landuse", "type": "fill", "source": "here", "source-layer": "landuse",
   "filter": ["match", ["get", "kind"], ["hospital", "commercial", "industrial", "sports_centre", "university", "school", "college", "military"], true, false],
   "paint": {"fill-color": "#11212d"}},
  {"id": "water", "type": "fill", "source": "here", "source-layer": "water",
   "filter": ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false],
   "paint": {"fill-color": "#0a2b45"}},
  {"id": "waterway", "type": "line", "source": "here", "source-layer": "water",
   "filter": ["match", ["geometry-type"], ["LineString", "MultiLineString"], true, false],
   "paint": {"line-color": "#0a2b45", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1, 16, 4]}},
  {"id": "roads", "type": "line", "source": "here", "source-layer": "roads",
   "filter": ["match", ["get", "kind"], ["highway", "major_road", "minor_road", "path"], true, false],
   "layout": {"line-cap": "round", "line-join": "round"},
   "paint": {
    "line-color": ["match", ["get", "kind"], "highway", "#6f8fae", "major_road", "#40607c", "minor_road", "#2a4054", "#213341"],
    "line-width": ["interpolate", ["exponential", 1.6], ["zoom"],
      10, ["match", ["get", "kind"], "highway", 2.0, "major_road", 1.2, 0.4],
      14, ["match", ["get", "kind"], "highway", 6.0, "major_road", 4.0, "minor_road", 2.0, 0.8],
      18, ["match", ["get", "kind"], "highway", 30.0, "major_road", 22.0, "minor_road", 12.0, 4.0]]}},
  {"id": "traffic", "type": "raster", "source": "traffic", "layout": {"visibility": "none"}, "paint": {"raster-opacity": 0.9}},
  {"id": "bld-flat", "type": "fill", "source": "here", "source-layer": "buildings", "minzoom": 14,
   "filter": ["all", ["match", ["get", "kind"], ["building", "building_part"], true, false], ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false]],
   "paint": {"fill-color": "#1a2c3d", "fill-opacity": 0.9}},
  {"id": "bld-3d", "type": "fill-extrusion", "source": "here", "source-layer": "buildings", "minzoom": 14,
   "layout": {"visibility": "none"},
   "filter": ["all", ["match", ["get", "kind"], ["building", "building_part"], true, false], ["match", ["geometry-type"], ["Polygon", "MultiPolygon"], true, false]],
   "paint": {"fill-extrusion-color": "#2b3f55", "fill-extrusion-opacity": 0.85,
    "fill-extrusion-height": ["coalesce", ["get", "height"], 8]}},
  {"id": "road-labels", "type": "symbol", "source": "here", "source-layer": "road_labels", "minzoom": 13,
   "layout": {"symbol-placement": "line", "text-field": ["coalesce", ["get", "name:vi"], ["get", "name"]], "text-font": ["Inter-Medium"],
    "text-size": ["interpolate", ["linear"], ["zoom"], 13, 11, 17, 15], "text-max-angle": 30, "text-padding": 4},
   "paint": {"text-color": "#d6e4ef", "text-halo-color": "#0a1620", "text-halo-width": 1.5}},
  {"id": "places", "type": "symbol", "source": "here", "source-layer": "places", "minzoom": 10,
   "layout": {"text-field": ["coalesce", ["get", "name:vi"], ["get", "name"]], "text-font": ["Inter-Medium"],
    "text-size": ["match", ["get", "kind"], "locality", 15, 12], "text-max-width": 8},
   "paint": {"text-color": "#9fb6c8", "text-halo-color": "#0a1620", "text-halo-width": 1.5}}
 ]
}
"""
    }

    // Lớp bị che khi bật ảnh vệ tinh
    val BASE_LAYERS = listOf("park", "landuse", "water", "waterway", "roads", "bld-flat", "bld-3d")
}
