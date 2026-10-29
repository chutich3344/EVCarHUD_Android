package com.kevin.evcarhud

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

// Trang Bản đồ vẽ bằng MapLibre (bản đồ vector, có nhà 3D). Nền vector từ file OSM tự dựng (OsmTiles.URL_PM), không tốn lượt
// HERE; ảnh vệ tinh / giao thông HERE đi qua Worker, giữ 30 ngày / 5 phút trong bộ nhớ đệm của MapLibre (200 MB). Phông chữ tên đường nằm sẵn trong app (assets/glyphs), không tải mạng.
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

    // Kiểu bản đồ đêm (hợp với HUD). Nền vector là file PMTiles OSM tự dựng (lược đồ OpenMapTiles), đọc từng đoạn
    // trên mạng, không tốn lượt HERE; lớp vệ tinh / giao thông vẫn là HERE (bật bằng nút)
    fun style(): String {
        val p = HereApi.PROXY
        return """
{
 "version": 8,
 "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
 "sources": {
  "osm": {"type": "vector", "url": "pmtiles://${OsmTiles.URL_PM}",
   "attribution": "© OpenStreetMap contributors © OpenMapTiles"},
  "sat": {"type": "raster", "tiles": ["$p/maps/v3/base/mc/{z}/{x}/{y}/jpeg?style=satellite.day&size=512"], "tileSize": 512, "maxzoom": 19},
  "traffic": {"type": "raster", "tiles": ["$p/traffic.maps/v3/flow/mc/{z}/{x}/{y}/png?size=512"], "tileSize": 512, "maxzoom": 18}
 },
 "layers": [
  {"id": "bg", "type": "background", "paint": {"background-color": "#0a1620"}},
  {"id": "sat", "type": "raster", "source": "sat", "layout": {"visibility": "none"}},
  {"id": "park", "type": "fill", "source": "osm", "source-layer": "park", "paint": {"fill-color": "#0f3324", "fill-opacity": 0.85}},
  {"id": "landcover", "type": "fill", "source": "osm", "source-layer": "landcover",
   "filter": ["match", ["get", "class"], ["grass", "wood", "farmland"], true, false],
   "paint": {"fill-color": "#0f3324", "fill-opacity": 0.6}},
  {"id": "landuse", "type": "fill", "source": "osm", "source-layer": "landuse",
   "filter": ["match", ["get", "class"], ["hospital", "commercial", "industrial", "school", "university", "college", "stadium", "military"], true, false],
   "paint": {"fill-color": "#11212d"}},
  {"id": "water", "type": "fill", "source": "osm", "source-layer": "water", "paint": {"fill-color": "#0a2b45"}},
  {"id": "waterway", "type": "line", "source": "osm", "source-layer": "waterway",
   "paint": {"line-color": "#0a2b45", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1, 16, 4]}},
  {"id": "roads", "type": "line", "source": "osm", "source-layer": "transportation",
   "filter": ["match", ["get", "class"], ["motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service"], true, false],
   "layout": {"line-cap": "round", "line-join": "round"},
   "paint": {
    "line-color": ["match", ["get", "class"], "motorway", "#6f8fae", ["trunk", "primary"], "#4a6a86", ["secondary", "tertiary"], "#36506a", "#2a4054"],
    "line-width": ["interpolate", ["exponential", 1.6], ["zoom"],
      10, ["match", ["get", "class"], "motorway", 2.0, ["trunk", "primary"], 1.2, 0.4],
      14, ["match", ["get", "class"], "motorway", 6.0, ["trunk", "primary"], 4.0, ["secondary", "tertiary"], 3.0, 1.5],
      18, ["match", ["get", "class"], "motorway", 30.0, ["trunk", "primary"], 22.0, ["secondary", "tertiary"], 16.0, 10.0]]}},
  {"id": "traffic", "type": "raster", "source": "traffic", "layout": {"visibility": "none"}, "paint": {"raster-opacity": 0.9}},
  {"id": "bld-flat", "type": "fill", "source": "osm", "source-layer": "building", "minzoom": 14,
   "paint": {"fill-color": "#1a2c3d", "fill-opacity": 0.9}},
  {"id": "bld-3d", "type": "fill-extrusion", "source": "osm", "source-layer": "building", "minzoom": 14,
   "layout": {"visibility": "none"},
   "paint": {"fill-extrusion-color": "#2b3f55", "fill-extrusion-opacity": 0.85,
    "fill-extrusion-height": ["coalesce", ["get", "render_height"], 8]}},
  {"id": "road-labels", "type": "symbol", "source": "osm", "source-layer": "transportation_name", "minzoom": 13,
   "layout": {"symbol-placement": "line", "text-field": ["coalesce", ["get", "name:vi"], ["get", "name:latin"]], "text-font": ["Inter-Medium"],
    "text-size": ["interpolate", ["linear"], ["zoom"], 13, 11, 17, 15], "text-max-angle": 30, "text-padding": 4},
   "paint": {"text-color": "#d6e4ef", "text-halo-color": "#0a1620", "text-halo-width": 1.5}},
  {"id": "places", "type": "symbol", "source": "osm", "source-layer": "place", "minzoom": 10,
   "filter": ["match", ["get", "class"], ["city", "town", "suburb", "quarter", "neighbourhood"], true, false],
   "layout": {"text-field": ["coalesce", ["get", "name:vi"], ["get", "name:latin"]], "text-font": ["Inter-Medium"],
    "text-size": ["match", ["get", "class"], "city", 15, "town", 14, 12], "text-max-width": 8},
   "paint": {"text-color": "#9fb6c8", "text-halo-color": "#0a1620", "text-halo-width": 1.5}}
 ]
}
"""
    }

    // Lớp bị che khi bật ảnh vệ tinh
    val BASE_LAYERS = listOf("park", "landcover", "landuse", "water", "waterway", "roads", "bld-flat", "bld-3d")
}
