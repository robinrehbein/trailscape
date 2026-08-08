package de.trailscape.app.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.trailscape.app.ui.MapStyle
import de.trailscape.core.TrackPoint
import de.trailscape.core.haversineM
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Bruecke von Compose zur MapLibre-[MapView].
 *
 * ## Aufteilung
 *  * [MapViewHost] ist das Composable: es haelt die [MapView] am Compose-
 *    Lebenszyklus, laedt den Rasterstil und leitet Tippen nach oben weiter.
 *  * [MapController] ist der *imperative* Griff auf die Karte. Der Screen legt
 *    genau einen an (`remember { MapController() }`), gibt ihn hier herein und
 *    ruft darauf `setTrack(...)`, `fitToPoints(...)` usw.
 *
 * ## Warum ein Controller und keine Neuberechnung im Compose-Baum
 * Die Karte ist eine OpenGL-View mit eigenem Zustand; sie laesst sich nicht wie
 * ein Compose-Baum aus Daten neu erzeugen. Der Controller haelt deshalb den
 * *gewuenschten* Zustand (GeoJSON je Quelle, letzte Kamerafahrt) und spielt ihn
 * auf die Karte, sobald sie da ist. Genau das macht auch den Stilwechsel
 * unkompliziert: Beim Laden eines neuen Stils sind alle Quellen und Ebenen weg,
 * [MapController.onStyleLoaded] baut sie aus dem gemerkten Zustand neu auf.
 *
 * ## Linien statt Annotations
 * Der Live-Track waechst im Sekundentakt. Deshalb liegt jede Linie in einer
 * eigenen [GeoJsonSource], die bei einer Aenderung nur ihr GeoJSON neu gesetzt
 * bekommt — die [LineLayer] daruber bleibt stehen. Die alte
 * `Polyline`-Annotation-API (`map.addPolyline`) waere pro Punkt ein
 * Neuaufbau des Annotation-Managers.
 */
@Composable
internal fun MapViewHost(
    controller: MapController,
    style: MapStyle,
    locationEnabled: Boolean,
    onMapTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentTap by rememberUpdatedState(onMapTap)

    // Kameraposition ueber Konfigurationsaenderungen (Drehen) hinweg merken.
    // MapView.onSaveInstanceState/onCreate(Bundle) waere der View-Weg, in einem
    // Compose-Baum gibt es dafuer aber keinen Bundle-Anker: Die MapView wird
    // hier erzeugt, nicht aus einem Layout aufgeblasen. rememberSaveable ist
    // das Compose-Gegenstueck und rettet genau das, worauf es ankommt.
    var savedLat by rememberSaveable { mutableStateOf(GERMANY_LAT) }
    var savedLon by rememberSaveable { mutableStateOf(GERMANY_LON) }
    var savedZoom by rememberSaveable { mutableStateOf(GERMANY_ZOOM) }

    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false

        fun sync(target: Lifecycle.State) {
            if (mapView.isDestroyed) return
            if (target.isAtLeast(Lifecycle.State.STARTED) && !started) {
                mapView.onStart()
                started = true
            }
            if (target.isAtLeast(Lifecycle.State.RESUMED) && !resumed) {
                mapView.onResume()
                resumed = true
            }
            if (!target.isAtLeast(Lifecycle.State.RESUMED) && resumed) {
                mapView.onPause()
                resumed = false
            }
            if (!target.isAtLeast(Lifecycle.State.STARTED) && started) {
                mapView.onStop()
                started = false
            }
        }

        val observer = LifecycleEventObserver { source, _ -> sync(source.lifecycle.currentState) }
        sync(lifecycleOwner.lifecycle.currentState)
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.rememberCamera()?.let { camera ->
                savedLat = camera.lat
                savedLon = camera.lon
                savedZoom = camera.zoom
            }
            controller.detach()
            sync(Lifecycle.State.CREATED)
            if (!mapView.isDestroyed) {
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.getMapAsync { map ->
                map.uiSettings.apply {
                    // Attribution ist Pflicht (OSM/CARTO/Esri) — sie bleibt an,
                    // nur dezent unten links wie im Flutter-Original. Das
                    // MapLibre-Logo daneben ist rechtlich nicht noetig und
                    // wuerde die untere Leiste unnoetig fuellen.
                    isLogoEnabled = false
                    isAttributionEnabled = true
                    attributionGravity = Gravity.BOTTOM or Gravity.START
                    setAttributionMargins(12, 0, 0, 12)
                    isCompassEnabled = true
                    compassGravity = Gravity.TOP or Gravity.END
                    setCompassMargins(0, 220, 16, 0)
                    // Kein Property-Zugriff moeglich: Setzer und Getter heissen
                    // in MapLibre unterschiedlich (setCompassFadeFacingNorth /
                    // isCompassFadeWhenFacingNorth).
                    setCompassFadeFacingNorth(true)
                    // Neigen bringt auf einer Rasterkarte nichts und stoert
                    // beim Zwei-Finger-Zoom.
                    isTiltGesturesEnabled = false
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(savedLat, savedLon))
                    .zoom(savedZoom)
                    .build()
                map.addOnMapClickListener { latLng ->
                    currentTap(latLng.latitude, latLng.longitude)
                    true
                }
                controller.attach(map)
            }
            mapView
        },
    )

    // Stil laden und bei jeder Auswahl neu setzen. Der Callback baut Quellen
    // und Ebenen wieder auf — nach einem Stilwechsel sind sie sonst weg.
    LaunchedEffect(controller, style.id) {
        controller.applyStyle(context, style)
    }

    LaunchedEffect(controller, locationEnabled) {
        controller.setLocationEnabled(context, locationEnabled)
    }
}

/** Ein runder Marker auf der Karte (Wegpunkt, Start/Ziel, Suchtreffer). */
internal data class MapMarker(
    val lat: Double,
    val lon: Double,
    /** ARGB-Farbe, wie sie [androidx.compose.ui.graphics.Color.toArgb] liefert. */
    val color: Int,
    val radius: Float = 7f,
)

/** Kameraposition in der einfachsten Form, die der Screen braucht. */
internal data class CameraSnapshot(val lat: Double, val lon: Double, val zoom: Double)

/**
 * Imperativer Griff auf die Karte. Alle Methoden sind aus dem Main-Thread
 * aufzurufen und vertragen es, dass die Karte noch gar nicht da ist: Der
 * gewuenschte Zustand wird gemerkt und beim Laden des Stils nachgezogen.
 */
internal class MapController {

    private var map: MapLibreMap? = null
    private var style: Style? = null

    /** Zuletzt gewuenschter Stil — gemerkt, falls die Karte noch nicht da war. */
    private var wantedStyle: MapStyle? = null
    private var styleContext: Context? = null

    /** GeoJSON je Quelle — die Wahrheit, aus der der Stil wieder aufgebaut wird. */
    private val geoJson: MutableMap<String, String> = linkedMapOf(
        SOURCE_TRACK to EMPTY_FEATURES,
        SOURCE_PLANNED to EMPTY_FEATURES,
        SOURCE_LIVE to EMPTY_FEATURES,
        SOURCE_MARKERS to EMPTY_FEATURES,
    )

    /** Kamerafahrt, die vor dem Fertigwerden der Karte angefordert wurde. */
    private var pendingCamera: ((MapLibreMap) -> Unit)? = null

    private var locationWanted = false

    /** Ob Karte **und** Stil stehen — erst dann greifen Ebenen und Kamera. */
    var isReady by mutableStateOf(false)
        private set

    // ------------------------------------------------------------- Anbindung

    internal fun attach(map: MapLibreMap) {
        this.map = map
        // Der Stil wird in aller Regel gesetzt, bevor `getMapAsync` gefeuert
        // hat — dann steht er hier schon bereit und wird jetzt nachgeholt.
        val pendingStyle = wantedStyle
        val context = styleContext
        if (pendingStyle != null && context != null) {
            applyStyle(context, pendingStyle)
        }
    }

    internal fun detach() {
        map = null
        style = null
        isReady = false
    }

    /** Setzt den Rasterstil und baut danach Quellen, Ebenen und Standort neu auf. */
    internal fun applyStyle(context: Context, mapStyle: MapStyle) {
        wantedStyle = mapStyle
        styleContext = context.applicationContext
        val map = map ?: return
        isReady = false
        style = null
        map.setStyle(Style.Builder().fromJson(mapStyle.toRasterStyleJson())) { loaded ->
            onStyleLoaded(context.applicationContext, loaded, mapStyle)
        }
    }

    private fun onStyleLoaded(context: Context, loaded: Style, mapStyle: MapStyle) {
        style = loaded
        // Rasterkacheln enden bei der hoechsten Stufe des Anbieters; darueber
        // hinaus darf die Kamera trotzdem (MapLibre skaliert die letzte Stufe).
        map?.setMaxZoomPreference(min(MAX_CAMERA_ZOOM, mapStyle.maxZoom + 2.0))

        // Idempotent: Sollte derselbe Stil (etwa durch zwei schnell
        // aufeinanderfolgende setStyle-Aufrufe) zweimal gemeldet werden, wirft
        // ein zweites addSource/addLayer — deshalb vorher nachsehen.
        geoJson.forEach { (sourceId, json) ->
            val existing = loaded.getSourceAs<GeoJsonSource>(sourceId)
            if (existing != null) {
                existing.setGeoJson(json)
            } else {
                loaded.addSource(GeoJsonSource(sourceId).also { it.setGeoJson(json) })
            }
        }

        loaded.addLayerIfAbsent(
            LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                PropertyFactory.lineColor(TRACK_COLOR),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        loaded.addLayerIfAbsent(
            LineLayer(LAYER_PLANNED, SOURCE_PLANNED).withProperties(
                PropertyFactory.lineColor(PLANNED_COLOR),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                // Gestrichelt wie die geplante Route im Flutter-Original.
                PropertyFactory.lineDasharray(arrayOf(2.4f, 1.6f)),
            ),
        )
        loaded.addLayerIfAbsent(
            LineLayer(LAYER_LIVE, SOURCE_LIVE).withProperties(
                PropertyFactory.lineColor(LIVE_COLOR),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        loaded.addLayerIfAbsent(
            CircleLayer(LAYER_MARKERS, SOURCE_MARKERS).withProperties(
                // Farbe und Groesse stehen an jedem Punkt selbst: ein Layer
                // reicht fuer Wegpunkte, Start/Ziel und Suchtreffer.
                PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                PropertyFactory.circleRadius(Expression.get(PROP_RADIUS)),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(MARKER_STROKE_COLOR),
            ),
        )

        isReady = true

        if (locationWanted) {
            activateLocation(context, loaded)
        }

        val camera = pendingCamera
        pendingCamera = null
        camera?.invoke(map ?: return)
    }

    // ---------------------------------------------------------------- Linien

    /** Die ausgewaehlte Tour (gruen). */
    fun setTrack(points: List<TrackPoint>) = setLine(SOURCE_TRACK, points)

    /** Die geplante Route (blau, gestrichelt). */
    fun setPlannedRoute(points: List<TrackPoint>) = setLine(SOURCE_PLANNED, points)

    /** Der laufend wachsende Aufzeichnungs-Track (rot). */
    fun setLiveTrack(points: List<TrackPoint>) = setLine(SOURCE_LIVE, points)

    /** Alle runden Marker auf einmal. */
    fun setMarkers(markers: List<MapMarker>) {
        setSource(SOURCE_MARKERS, markerFeatureCollection(markers))
    }

    private fun setLine(sourceId: String, points: List<TrackPoint>) {
        setSource(sourceId, lineFeatureCollection(points))
    }

    private fun setSource(sourceId: String, json: String) {
        if (geoJson[sourceId] == json) return
        geoJson[sourceId] = json
        val source = style?.getSourceAs<GeoJsonSource>(sourceId) ?: return
        source.setGeoJson(json)
    }

    // ---------------------------------------------------------------- Kamera

    /**
     * Faehrt die Kamera so, dass alle [points] sichtbar sind — mit demselben
     * Rand und derselben Zoomgrenze wie `_fitToPoints` im Flutter-Original.
     */
    fun fitToPoints(points: List<TrackPoint>, padding: MapPadding = DEFAULT_FIT_PADDING) {
        if (points.isEmpty()) return
        run(afterReady = true) { map ->
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(LatLng(it.lat, it.lon)) }
            val bounds = runCatching { builder.build() }.getOrNull() ?: return@run

            val target = runCatching {
                map.getCameraForLatLngBounds(
                    bounds,
                    intArrayOf(padding.left, padding.top, padding.right, padding.bottom),
                )
            }.getOrNull() ?: return@run

            // getCameraForLatLngBounds kennt keine Obergrenze; bei sehr kurzen
            // Touren zoomt es sonst bis an den Anschlag.
            val zoom = min(target.zoom, MAX_FIT_ZOOM)
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(target.target).zoom(zoom).build(),
                ),
                CAMERA_ANIMATION_MS,
            )
        }
    }

    /**
     * Zentriert auf eine Position. [minZoom] hebt die Zoomstufe nur an, wenn
     * sie noch darunter liegt (Original: `math.max(camera.zoom, 15)`).
     */
    fun moveTo(lat: Double, lon: Double, minZoom: Double? = null, animate: Boolean = true) {
        run(afterReady = false) { map ->
            val zoom = if (minZoom == null) map.cameraPosition.zoom else max(map.cameraPosition.zoom, minZoom)
            val update = CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom)
            if (animate) map.animateCamera(update, CAMERA_ANIMATION_MS) else map.moveCamera(update)
        }
    }

    /** Aktuelle Kameraposition, oder `null` solange die Karte nicht steht. */
    fun rememberCamera(): CameraSnapshot? {
        val position = map?.cameraPosition ?: return null
        val target = position.target ?: return null
        return CameraSnapshot(target.latitude, target.longitude, position.zoom)
    }

    /** Der sichtbare Ausschnitt — Grundlage des Offline-Downloads. */
    fun visibleBounds(): LatLngBounds? =
        map?.projection?.visibleRegion?.latLngBounds

    /** Aktuelle Zoomstufe, oder `null`. */
    fun currentZoom(): Double? = map?.cameraPosition?.zoom

    /**
     * Ob zwei Koordinaten auf dem Bildschirm hoechstens [tolerancePx] Pixel
     * auseinanderliegen. So erkennt der Screen ein Tippen *auf* einen
     * Wegpunkt, ohne die Trefferpruefung an MapLibre abzugeben.
     */
    fun isWithinScreenDistance(
        a: TrackPoint,
        bLat: Double,
        bLon: Double,
        tolerancePx: Float,
    ): Boolean {
        val projection = map?.projection ?: return false
        val metersPerPixel = projection.getMetersPerPixelAtLatitude(bLat)
        if (metersPerPixel <= 0 || metersPerPixel.isNaN()) return false
        val distanceM = haversineM(a, TrackPoint(lat = bLat, lon = bLon))
        return distanceM <= metersPerPixel * tolerancePx
    }

    private fun run(afterReady: Boolean, action: (MapLibreMap) -> Unit) {
        val map = map
        if (map == null || (afterReady && !isReady)) {
            pendingCamera = action
            return
        }
        action(map)
    }

    // --------------------------------------------------------------- Standort

    /**
     * Schaltet den Standortpunkt an oder aus. Ohne Berechtigung passiert
     * nichts — die Karte bleibt benutzbar, nur ohne eigenen Punkt.
     */
    fun setLocationEnabled(context: Context, enabled: Boolean) {
        locationWanted = enabled
        val loaded = style ?: return
        if (enabled) {
            activateLocation(context, loaded)
        } else {
            val component = map?.locationComponent ?: return
            if (component.isLocationComponentActivated) {
                component.isLocationComponentEnabled = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun activateLocation(context: Context, loaded: Style) {
        if (!hasLocationPermission(context)) return
        val component = map?.locationComponent ?: return
        runCatching {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, loaded)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
            component.isLocationComponentEnabled = true
            // Die Kamera folgt bewusst NICHT von selbst: Im Flutter-Original
            // bewegte nur der Positions-Knopf (und die Aufzeichnung) die Karte.
            component.cameraMode = CameraMode.NONE
            component.renderMode =
                if (hasCompass(context)) RenderMode.COMPASS else RenderMode.NORMAL
        }
    }

    /** Zuletzt vom Standortpunkt gemeldete Position (falls vorhanden). */
    fun lastKnownLocation(): Pair<Double, Double>? {
        val component = map?.locationComponent ?: return null
        if (!component.isLocationComponentActivated) return null
        val location = component.lastKnownLocation ?: return null
        return location.latitude to location.longitude
    }
}

/** Fuegt eine Ebene nur hinzu, wenn es sie im Stil noch nicht gibt. */
private fun Style.addLayerIfAbsent(layer: Layer) {
    if (getLayer(layer.id) == null) {
        addLayer(layer)
    }
}

/** Rand einer Kamerafahrt in Pixeln. */
internal data class MapPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

// ------------------------------------------------------------------ GeoJSON

internal const val EMPTY_FEATURES: String = """{"type":"FeatureCollection","features":[]}"""

/** LineString-Feature aus Trackpunkten; unter zwei Punkten leer. */
private fun lineFeatureCollection(points: List<TrackPoint>): String {
    if (points.size < 2) return EMPTY_FEATURES
    val builder = StringBuilder(points.size * 24)
    builder.append("{\"type\":\"FeatureCollection\",\"features\":[")
    builder.append("{\"type\":\"Feature\",\"properties\":{},")
    builder.append("\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
    points.forEachIndexed { index, point ->
        if (index > 0) builder.append(',')
        builder.append('[').append(coordinate(point.lon)).append(',')
            .append(coordinate(point.lat)).append(']')
    }
    builder.append("]}}]}")
    return builder.toString()
}

private fun markerFeatureCollection(markers: List<MapMarker>): String {
    if (markers.isEmpty()) return EMPTY_FEATURES
    val builder = StringBuilder(markers.size * 96)
    builder.append("{\"type\":\"FeatureCollection\",\"features\":[")
    markers.forEachIndexed { index, marker ->
        if (index > 0) builder.append(',')
        builder.append("{\"type\":\"Feature\",\"properties\":{")
            .append('"').append(PROP_COLOR).append("\":\"").append(hexColor(marker.color))
            .append("\",\"").append(PROP_RADIUS).append("\":").append(number(marker.radius))
            .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(coordinate(marker.lon)).append(',').append(coordinate(marker.lat))
            .append("]}}")
    }
    builder.append("]}")
    return builder.toString()
}

/**
 * Zahl fuer GeoJSON. Immer [Locale.ROOT] — mit deutschem Gebietsschema waere
 * das Dezimaltrennzeichen ein Komma und das JSON kaputt.
 */
private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

private fun number(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

private fun hexColor(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

// ------------------------------------------------------------------ Konstanten

private const val SOURCE_TRACK = "ts-track-source"
private const val SOURCE_PLANNED = "ts-planned-source"
private const val SOURCE_LIVE = "ts-live-source"
private const val SOURCE_MARKERS = "ts-marker-source"

private const val LAYER_TRACK = "ts-track-layer"
private const val LAYER_PLANNED = "ts-planned-layer"
private const val LAYER_LIVE = "ts-live-layer"
private const val LAYER_MARKERS = "ts-marker-layer"

private const val PROP_COLOR = "ts-color"
private const val PROP_RADIUS = "ts-radius"

private const val LINE_WIDTH = 5f

private val TRACK_COLOR = GravelGreen.toArgb()
private val PLANNED_COLOR = RouteBlue.toArgb()
private val LIVE_COLOR = RecordRed.toArgb()
private val MARKER_STROKE_COLOR = Color.White.toArgb()

/** Kartenmitte beim ersten Start: Deutschland, wie im Flutter-Original. */
internal const val GERMANY_LAT = 51.0
internal const val GERMANY_LON = 10.0
internal const val GERMANY_ZOOM = 6.0

/** Zoomstufe, auf die beim ersten aufgezeichneten Punkt mindestens gezoomt wird. */
internal const val MIN_RECORDING_ZOOM = 15.0

private const val MAX_FIT_ZOOM = 16.0
private const val MAX_CAMERA_ZOOM = 20.0
private const val CAMERA_ANIMATION_MS = 600

private val DEFAULT_FIT_PADDING = MapPadding(left = 48, top = 120, right = 48, bottom = 220)
