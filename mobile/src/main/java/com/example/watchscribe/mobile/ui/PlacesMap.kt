package com.example.watchscribe.mobile.ui

import android.graphics.PointF
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.watchscribe.mobile.data.Recording
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import androidx.compose.ui.graphics.Color

/** A place on the map: average position of its notes. */
data class PlacePin(val name: String, val lat: Double, val lon: Double, val count: Int, val color: Int)

fun pinsFor(places: List<Pair<String, List<Recording>>>): List<PlacePin> =
    places.mapIndexedNotNull { i, (name, list) ->
        val geo = list.filter { it.lat != null && it.lon != null }
        if (geo.isEmpty()) null
        else PlacePin(
            name, geo.map { it.lat!! }.average(), geo.map { it.lon!! }.average(), list.size,
            Palette.PlaceTints[i % Palette.PlaceTints.size].toArgb(),
        )
    }

// OpenFreeMap: free OpenStreetMap vector tiles, no key or sign-up
private const val STYLE = "https://tiles.openfreemap.org/styles/liberty" // colourful, with streets, shops, transit
private const val SRC = "pins"

private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

private fun features(pins: List<PlacePin>) = FeatureCollection.fromFeatures(pins.map { p ->
    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
        addStringProperty("name", p.name)
        addStringProperty("label", p.count.toString())
        addStringProperty("color", hex(p.color))
    }
})

private fun fit(map: MapLibreMap, pins: List<PlacePin>, padPx: Int) {
    if (pins.isEmpty()) return
    if (pins.size == 1) {
        map.cameraPosition = CameraPosition.Builder().target(LatLng(pins[0].lat, pins[0].lon)).zoom(13.0).build()
    } else {
        val b = LatLngBounds.Builder().apply { pins.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, padPx))
        if (map.cameraPosition.zoom > 14.0) map.moveCamera(CameraUpdateFactory.zoomTo(14.0))
    }
}

/** Map with one pin per place (count inside). Tap a pin to open that place's notes. */
@Composable
fun PlacesMap(pins: List<PlacePin>, onPin: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val currentOnPin = rememberUpdatedState(onPin)
    Box(
        modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Surface),
        contentAlignment = Alignment.Center,
    ) {
        if (pins.isEmpty()) {
            Text(
                "The map fills in once notes have a location.\nAllow location on the Notes screen.",
                color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp),
            )
            return@Box
        }
        val holder = remember {
            MapLibre.getInstance(ctx)
            val opts = MapLibreMapOptions.createFromAttributes(ctx).textureMode(true).attributionEnabled(false).logoEnabled(false)
            object {
                val view = MapView(ctx, opts).apply {
                    onCreate(null)
                    // Let the map pan inside the scrolling screen
                    setOnTouchListener { v, e ->
                        v.parent?.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP && e.action != MotionEvent.ACTION_CANCEL)
                        false
                    }
                }
                var map: MapLibreMap? = null
                var style: Style? = null
            }
        }
        DisposableEffect(holder) {
            val v = holder.view
            v.onStart(); v.onResume()
            v.getMapAsync { map ->
                holder.map = map
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                map.setStyle(Style.Builder().fromUri(STYLE)) { style ->
                    holder.style = style
                    style.addSource(GeoJsonSource(SRC, features(pins)))
                    style.addLayer(
                        CircleLayer("pin-dots", SRC).withProperties(
                            PropertyFactory.circleRadius(15f),
                            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(3f),
                        )
                    )
                    style.addLayer(
                        SymbolLayer("pin-labels", SRC).withProperties(
                            PropertyFactory.textField(Expression.get("label")),
                            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                            PropertyFactory.textSize(13f),
                            PropertyFactory.textColor(hex(Palette.Ground.toArgb())),
                            PropertyFactory.textAllowOverlap(true),
                            PropertyFactory.textIgnorePlacement(true),
                        )
                    )
                    style.addLayer(
                        SymbolLayer("pin-names", SRC).withProperties(
                            PropertyFactory.textField(Expression.get("name")),
                            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                            PropertyFactory.textSize(13f),
                            PropertyFactory.textColor("#1B1F24"),
                            PropertyFactory.textHaloColor("#FFFFFF"),
                            PropertyFactory.textHaloWidth(2f),
                            PropertyFactory.textOffset(arrayOf(0f, 1.9f)),
                            PropertyFactory.textAnchor("top"),
                            PropertyFactory.textAllowOverlap(true),
                        )
                    )
                    fit(map, pins, (48 * ctx.resources.displayMetrics.density).toInt())
                }
                map.addOnMapClickListener { ll ->
                    val pt: PointF = map.projection.toScreenLocation(ll)
                    val hit = map.queryRenderedFeatures(pt, "pin-dots").firstOrNull()
                    hit?.getStringProperty("name")?.let { currentOnPin.value(it); true } ?: false
                }
            }
            onDispose { v.onPause(); v.onStop(); v.onDestroy() }
        }
        AndroidView(
            factory = { holder.view },
            modifier = Modifier.fillMaxSize(),
            update = {
                (holder.style?.getSource(SRC) as? GeoJsonSource)?.setGeoJson(features(pins))
            },
        )
        Text(
            "© OpenStreetMap · OpenFreeMap", fontSize = 9.sp, color = Color(0xFF333333),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                .background(Color(0xB3FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
