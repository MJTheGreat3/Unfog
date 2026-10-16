package com.matt.unfog.ui

import android.graphics.Color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

/** Sources and layers Unfog draws on top of the base map. */
object MapLayers {
    const val HEX_FINE = "hex-fine"
    const val HEX_COARSE = "hex-coarse"
    const val ROUTES = "routes"
    const val PHOTOS = "photos"
    const val REPLAY = "replay"
    const val REPLAY_HEAD = "replay-head"
    const val TRIP_PHOTOS = "trip-photos"

    /** Zoom level where the frequency map switches from coarse to fine hexes. */
    const val HEX_SWITCH_ZOOM = 11.5f

    const val LAYER_PHOTO_CLUSTERS = "photos-clusters"
    const val LAYER_PHOTO_POINTS = "photos-points"
    const val LAYER_TRIP_PHOTOS = "trip-photos-pins"

    private val layersBySource = mapOf(
        HEX_FINE to listOf("hex-fine-fill"),
        HEX_COARSE to listOf("hex-coarse-fill"),
        ROUTES to listOf("routes-casing", "routes-line"),
        PHOTOS to listOf(LAYER_PHOTO_CLUSTERS, "photos-count", LAYER_PHOTO_POINTS),
        REPLAY to listOf("replay-line"),
        REPLAY_HEAD to listOf("replay-head"),
        TRIP_PHOTOS to listOf(LAYER_TRIP_PHOTOS, "trip-photos-label"),
    )

    fun install(style: Style) {
        val empty = FeatureCollection.fromFeatures(emptyList())
        listOf(HEX_FINE, HEX_COARSE, ROUTES, REPLAY, REPLAY_HEAD, TRIP_PHOTOS).forEach {
            style.addSource(GeoJsonSource(it, empty))
        }
        style.addSource(
            GeoJsonSource(
                PHOTOS, empty,
                GeoJsonOptions().withCluster(true).withClusterRadius(40).withClusterMaxZoom(16),
            )
        )

        val heat = interpolate(
            linear(), toNumber(get("days")),
            stop(1, rgb(255, 214, 102)),
            stop(3, rgb(255, 138, 61)),
            stop(10, rgb(240, 86, 122)),
            stop(30, rgb(176, 124, 255)),
        )
        style.addLayer(
            FillLayer("hex-coarse-fill", HEX_COARSE).withProperties(
                fillColor(heat), fillOpacity(0.55f), fillOutlineColor(Color.argb(60, 0, 0, 0)),
            ).apply { maxZoom = HEX_SWITCH_ZOOM }
        )
        style.addLayer(
            FillLayer("hex-fine-fill", HEX_FINE).withProperties(
                fillColor(heat), fillOpacity(0.6f), fillOutlineColor(Color.argb(60, 0, 0, 0)),
            ).apply { minZoom = HEX_SWITCH_ZOOM }
        )

        val width = interpolate(linear(), zoom(), stop(4, 1.5f), stop(12, 3.5f), stop(17, 7f))
        style.addLayer(
            LineLayer("routes-casing", ROUTES).withProperties(
                lineColor(Color.argb(140, 0, 0, 0)),
                lineWidth(interpolate(linear(), zoom(), stop(4, 3f), stop(12, 6f), stop(17, 10f))),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addLayer(
            LineLayer("routes-line", ROUTES).withProperties(
                lineColor(get("color")), lineWidth(width),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            )
        )

        style.addLayer(
            LineLayer("replay-line", REPLAY).withProperties(
                lineColor(Color.rgb(255, 138, 61)), lineWidth(width), lineOpacity(0.95f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addLayer(
            CircleLayer("replay-head", REPLAY_HEAD).withProperties(
                circleRadius(7f), circleColor(Color.WHITE),
                circleStrokeColor(Color.rgb(255, 138, 61)), circleStrokeWidth(3f),
            )
        )

        style.addLayer(
            CircleLayer(LAYER_PHOTO_CLUSTERS, PHOTOS).withProperties(
                circleColor(Color.rgb(127, 200, 248)),
                circleRadius(interpolate(linear(), get("point_count"), stop(2, 13f), stop(50, 22f))),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
            ).withFilter(has("point_count"))
        )
        style.addLayer(
            SymbolLayer("photos-count", PHOTOS).withProperties(
                textField(get("point_count_abbreviated")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(12f), textColor(Color.rgb(18, 21, 29)),
            ).withFilter(has("point_count"))
        )
        style.addLayer(
            CircleLayer(LAYER_PHOTO_POINTS, PHOTOS).withProperties(
                circleColor(Color.rgb(127, 200, 248)), circleRadius(7f),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
            ).withFilter(not(has("point_count")))
        )

        // Numbered pins for one trip: label is the photo's place in time order ("4" or "4–7").
        style.addLayer(
            CircleLayer(LAYER_TRIP_PHOTOS, TRIP_PHOTOS).withProperties(
                circleColor(Color.rgb(127, 200, 248)), circleRadius(12f),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
            )
        )
        style.addLayer(
            SymbolLayer("trip-photos-label", TRIP_PHOTOS).withProperties(
                textField(get("label")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(11f), textColor(Color.rgb(18, 21, 29)),
                textAllowOverlap(true), textIgnorePlacement(true),
            )
        )

        layersBySource.keys.forEach { show(style, it, false) }
    }

    fun set(style: Style, source: String, data: FeatureCollection) {
        style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(data)
    }

    fun show(style: Style, source: String, visible: Boolean) {
        val v = if (visible) Property.VISIBLE else Property.NONE
        layersBySource[source]?.forEach { style.getLayer(it)?.setProperties(visibility(v)) }
    }

}
