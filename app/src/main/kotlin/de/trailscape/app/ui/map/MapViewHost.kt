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
import de.trailscape.core.klemmeOffRouteZoom
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
import org.maplibre.android.style.layers.FillLayer
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
    gesturesEnabled: Boolean = true,
    renderingActive: Boolean = true,
    /**
     * Die Nutzerin hat die Karte **selbst** verschoben oder gezoomt.
     *
     * Nur diese Meldung unterscheidet eine Handbewegung von den Kamerafahrten,
     * die der Screen selbst ausloest (`moveTo`, `fitToPoints`) — MapLibre nennt
     * den Ausloeser jeder Kamerabewegung, und alles ausser
     * `REASON_API_GESTURE` kommt aus dem Programm. Der Screen schaltet daran
     * „Karte folgt mir" ab, statt die Ansicht beim naechsten GPS-Punkt wieder
     * wegzuziehen.
     */
    onUserPan: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentTap by rememberUpdatedState(onMapTap)
    val currentPan by rememberUpdatedState(onUserPan)

    // Kameraposition ueber Konfigurationsaenderungen (Drehen) hinweg merken.
    // MapView.onSaveInstanceState/onCreate(Bundle) waere der View-Weg, in einem
    // Compose-Baum gibt es dafuer aber keinen Bundle-Anker: Die MapView wird
    // hier erzeugt, nicht aus einem Layout aufgeblasen. rememberSaveable ist
    // das Compose-Gegenstueck und rettet genau das, worauf es ankommt.
    var savedLat by rememberSaveable { mutableStateOf(GERMANY_LAT) }
    var savedLon by rememberSaveable { mutableStateOf(GERMANY_LON) }
    var savedZoom by rememberSaveable { mutableStateOf(GERMANY_ZOOM) }
    // Der Kurs (bearing) gehoert seit der Navi-Kamera mit zum geretteten
    // Zustand: Eine Drehung waehrend der course-up-Navigation darf die Karte
    // nicht zurueck auf Nord kippen. Ausserhalb der Navigation ist er 0 —
    // das Bestandsverhalten (Nord oben) bleibt damit unveraendert.
    var savedBearing by rememberSaveable { mutableStateOf(0.0) }

    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    // Der Zaehlstand der bereits abgesetzten Lebenszyklus-Rufe liegt bewusst
    // ausserhalb des DisposableEffect: Sowohl der Lebenszyklus der Activity als
    // auch [renderingActive] steuern dieselbe Zustandsmaschine. Haenge man
    // stattdessen `renderingActive` an die Schluessel des Effekts, liefe bei
    // jedem Umschalten `onDispose` — und das zerstoert die MapView.
    val mapLifecycle = remember(mapView) { MapViewLifecycle() }
    val currentRendering by rememberUpdatedState(renderingActive)

    fun sync(target: Lifecycle.State) {
        if (mapView.isDestroyed) return
        if (target.isAtLeast(Lifecycle.State.STARTED) && !mapLifecycle.started) {
            mapView.onStart()
            mapLifecycle.started = true
        }
        if (target.isAtLeast(Lifecycle.State.RESUMED) && !mapLifecycle.resumed) {
            mapView.onResume()
            mapLifecycle.resumed = true
        }
        if (!target.isAtLeast(Lifecycle.State.RESUMED) && mapLifecycle.resumed) {
            mapView.onPause()
            mapLifecycle.resumed = false
        }
        if (!target.isAtLeast(Lifecycle.State.STARTED) && mapLifecycle.started) {
            mapView.onStop()
            mapLifecycle.started = false
        }
    }

    /**
     * Deckelt den Lebenszyklus auf STARTED, solange nichts von der Karte zu
     * sehen ist. Aus Sicht der MapView ist das genau der Zustand „sichtbar,
     * aber nicht im Vordergrund" — sie stellt das Zeichnen ein, behaelt aber
     * Stil, Quellen und Kameraposition.
     */
    fun effective(state: Lifecycle.State): Lifecycle.State =
        if (currentRendering) state else minOf(state, Lifecycle.State.STARTED)

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { source, _ ->
            sync(effective(source.lifecycle.currentState))
        }
        sync(effective(lifecycleOwner.lifecycle.currentState))
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.rememberCamera()?.let { camera ->
                savedLat = camera.lat
                savedLon = camera.lon
                savedZoom = camera.zoom
                savedBearing = camera.bearing
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
                    // Attribution ist Pflicht (OSM/CARTO/Esri) — sie bleibt an.
                    // Das MapLibre-Logo daneben ist rechtlich nicht noetig und
                    // wuerde die Ecke unnoetig fuellen.
                    isLogoEnabled = false
                    isAttributionEnabled = true
                    // Oben links statt (wie urspruenglich, dem Flutter-Original
                    // folgend) unten links: Dort verschwand das Attributions-
                    // Icon regelmaessig unter der Live-Leiste bzw. der
                    // Tour-Statistikkarte (`LiveRecordingCard`/`RideCard` in
                    // `MapPanels.kt`) — beide sitzen `fillMaxWidth()` am
                    // unteren Rand von `MapScreen.kt` und decken damit
                    // zuverlaessig auch die linke Ecke ab, sobald aufgezeichnet
                    // oder eine Tour ausgewaehlt wird. Unten rechts liegen
                    // zusaetzlich die runden Aufnahme-/Standort-Knoepfe, oben
                    // rechts sitzen Kompass und die drei Kartenknoepfe
                    // (Suche/Stil/Download) samt der Panels, die darunter
                    // aufklappen (Suche, Rundkurs-Generator, Planung, Download-
                    // Fortschritt — alle ebenfalls `fillMaxWidth()`). Oben links
                    // bleibt dagegen in jedem erreichbaren Bildschirmzustand
                    // frei: Die oberste Zeile in `MapScreen.kt` ordnet ihre
                    // Knoepfe mit `horizontalArrangement = Arrangement.End` an
                    // (die linke Haelfte bleibt dort leer), und jedes Panel
                    // darunter haengt strikt UNTER dieser Zeile im Compose-
                    // `Column` — nie auf ihrer Hoehe. Diese feste Ecke ist damit
                    // einfacher und robuster als ein dynamisches Ausweichen
                    // (Panelhoehen laufend in Pixel-Margins umrechnen), ohne
                    // dass der Info-Knopf je verdeckt waere.
                    attributionGravity = Gravity.TOP or Gravity.START
                    setAttributionMargins(12, 12, 0, 0)
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
                setzeGesten(map, gesturesEnabled)
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(savedLat, savedLon))
                    .zoom(savedZoom)
                    .bearing(savedBearing)
                    .build()
                map.addOnMapClickListener { latLng ->
                    currentTap(latLng.latitude, latLng.longitude)
                    true
                }
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        currentPan()
                    }
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

    // Auch nachtraeglich anwenden: Die `factory` laeuft nur einmal und haelt
    // damit den Wert vom ersten Aufbau fest.
    LaunchedEffect(mapView, gesturesEnabled) {
        mapView.getMapAsync { setzeGesten(it, gesturesEnabled) }
    }

    LaunchedEffect(renderingActive) {
        sync(effective(lifecycleOwner.lifecycle.currentState))
    }
}

/**
 * Welche Lebenszyklus-Rufe die MapView schon bekommen hat. MapLibre verlangt
 * sie paarweise und in der richtigen Reihenfolge; ein doppeltes `onPause` oder
 * ein `onResume` ohne vorheriges `onStart` quittiert es mit Abstuerzen.
 */
private class MapViewLifecycle {
    var started = false
    var resumed = false
}

/**
 * Schaltet die Kartengesten an der Karte selbst ab, statt sie mit einem
 * unsichtbaren Beruehrungsfaenger zu ueberdecken.
 *
 * Gebraucht wird das in der Tourendetailansicht: Dort sitzt eine Karte fester
 * Hoehe in einer scrollbaren Seite und wuerde jeden senkrechten Wisch als
 * Kartenverschiebung schlucken. Ein daruebergelegter Faenger wuerde
 * funktionieren, verliesse sich aber darauf, wie Compose unverbrauchte
 * Beruehrungen an eine eingebettete Android-View weiterreicht — eine feine
 * Regel, die sich mit einer Bibliotheksversion aendern kann. Der Schalter in
 * MapLibre ist dagegen eindeutig.
 *
 * Neigen bleibt unabhaengig davon immer aus (siehe `isTiltGesturesEnabled`).
 */
private fun setzeGesten(map: MapLibreMap, erlaubt: Boolean) {
    map.uiSettings.apply {
        isScrollGesturesEnabled = erlaubt
        isZoomGesturesEnabled = erlaubt
        isRotateGesturesEnabled = erlaubt
        isDoubleTapGesturesEnabled = erlaubt
        isQuickZoomGesturesEnabled = erlaubt
    }
}

/** Ein runder Marker auf der Karte (Wegpunkt, Start/Ziel, Ort). */
internal data class MapMarker(
    val lat: Double,
    val lon: Double,
    /** ARGB-Farbe, wie sie [androidx.compose.ui.graphics.Color.toArgb] liefert. */
    val color: Int,
    val radius: Float = 7f,
    /**
     * `true`: gefuellter Punkt mit weissem Rand (Wegpunkte, Start/Ziel, der im
     * Hoehenprofil abgelesene Punkt) — die bisherige Form. `false`: ein Ring
     * mit Loch in der Markerfarbe selbst (ein ausgewaehlter Ort aus der Suche,
     * siehe `PlaceCard.kt`) — die „Nadel"-Ersatzform.
     *
     * ## Warum ein Ring und kein echtes Nadel-Symbol
     * Ein Symbol-Icon braucht in MapLibre eine eigene [org.maplibre.android.style.layers.SymbolLayer]
     * mit im Stil registriertem Bild (`Style.addImage`), eigener Ankerlogik
     * und eigener Skalierung je Displaydichte — Infrastruktur, die es fuer
     * eine einzelne Ortsmarkierung noch nicht gibt und die sich mit der
     * bestehenden [CircleLayer]-Pipeline (eine Quelle, ein Layer, Farbe und
     * Groesse je Punktmerkmal) nicht teilen laesst. Die Ringform bleibt
     * dagegen vollstaendig in dieser Pipeline: nur zwei weitere
     * GeoJSON-Eigenschaften ([PROP_OPACITY], [PROP_STROKE_WIDTH]) auf
     * demselben Layer, deutlich unterscheidbar von jedem gefuellten Punkt und
     * ohne eine zweite Ebene oder ein weiteres Bild-Asset.
     */
    val filled: Boolean = true,
)

/**
 * Kameraposition in der einfachsten Form, die der Screen braucht.
 *
 * [bearing] kam mit der Navi-Kamera dazu (0 = Nord oben, der Default und das
 * Bestandsverhalten ausserhalb der Navigation).
 */
internal data class CameraSnapshot(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
)

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
        // Die drei Kachel-Quellen stehen bewusst mit in dieser Map und nicht
        // irgendwo daneben: Ein Stilwechsel raeumt saemtliche Quellen ab, und
        // nur was hier steht, baut [onStyleLoaded] wieder auf. Sonst waere der
        // Nebel nach jedem Wechsel des Kartenstils weg — genau wie es der
        // Tour-Linie ohne SOURCE_TRACK ergehen wuerde.
        SOURCE_FOG to EMPTY_FEATURES,
        SOURCE_FOG_OUTLINE to EMPTY_FEATURES,
        SOURCE_MAX_SQUARE to EMPTY_FEATURES,
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
        // Die drei Ebenen der Entdeckt-Kacheln liegen UNTER der Tour-Linie:
        // Nebel, Kachelraster und Groesstes-Quadrat sind Untergrund, die Spur
        // einer Tour, die geplante Route und die Marker bleiben darueber
        // lesbar. [addLayerIfAbsent] haengt oben an und taugt dafuer nicht —
        // deshalb [addLayerBelowIfAbsent] mit [LAYER_TRACK] als Anker. Jede
        // weitere Ebene, die hier direkt unter [LAYER_TRACK] eingehaengt wird,
        // landet ueber der zuvor eingehaengten; die Reihenfolge der drei
        // Aufrufe ist also zugleich ihre Stapelreihenfolge von unten nach
        // oben.
        loaded.addLayerBelowIfAbsent(
            FillLayer(LAYER_FOG, SOURCE_FOG).withProperties(
                PropertyFactory.fillColor(FOG_COLOR),
                // Ohne das zeichnet MapLibre die Kanten aneinandergrenzender
                // Nebel-Rechtecke einzeln weich aus — an jeder gemeinsamen
                // Kante liegen dann zwei halbdurchsichtige Raender
                // uebereinander und es entsteht ein sichtbares Gitternetz aus
                // Haarlinien quer ueber die ganze unbefahrene Flaeche.
                PropertyFactory.fillAntialias(false),
            ),
            below = LAYER_TRACK,
        )
        loaded.addLayerBelowIfAbsent(
            LineLayer(LAYER_FOG_OUTLINE, SOURCE_FOG_OUTLINE).withProperties(
                PropertyFactory.lineColor(EXPLORED_OUTLINE_COLOR),
                PropertyFactory.lineWidth(EXPLORED_OUTLINE_WIDTH),
            ),
            below = LAYER_TRACK,
        )
        loaded.addLayerBelowIfAbsent(
            LineLayer(LAYER_MAX_SQUARE, SOURCE_MAX_SQUARE).withProperties(
                PropertyFactory.lineColor(MAX_SQUARE_COLOR),
                PropertyFactory.lineWidth(MAX_SQUARE_WIDTH),
            ),
            below = LAYER_TRACK,
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
                // Farbe, Groesse, Fuellung und Randbreite stehen an jedem
                // Punkt selbst: ein Layer reicht fuer Wegpunkte, Start/Ziel
                // und einen ausgewaehlten Ort (siehe MapMarker.filled).
                PropertyFactory.circleColor(Expression.get(PROP_COLOR)),
                PropertyFactory.circleRadius(Expression.get(PROP_RADIUS)),
                PropertyFactory.circleOpacity(Expression.get(PROP_OPACITY)),
                PropertyFactory.circleStrokeWidth(Expression.get(PROP_STROKE_WIDTH)),
                PropertyFactory.circleStrokeColor(Expression.get(PROP_STROKE_COLOR)),
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

    /**
     * Die drei Ebenen der Entdeckt-Kacheln in einem Zug: der Nebel ueber allem
     * Unbefahrenen, das gruene Raster um das Befahrene und der Rahmen des
     * groessten zusammenhaengenden Quadrats. Das GeoJSON rechnet der Screen
     * (siehe `ExplorerTileLayer.kt`) — hier kommt es nur an.
     *
     * `null` je Ebene heisst „nichts anzeigen" und setzt [EMPTY_FEATURES]:
     * Eine leere Merkmalsammlung zeichnet nichts, die Ebene bleibt aber
     * stehen. Das ist billiger und vor allem zustandsaermer als ein
     * Sichtbarkeits-Schalter ([PropertyFactory.visibility]), der nach jedem
     * Stilwechsel eigens wiederhergestellt werden muesste — das GeoJSON
     * dagegen liegt ohnehin in [geoJson] und kommt von selbst zurueck.
     */
    fun setExplorerTiles(fogJson: String?, outlineJson: String?, maxSquareJson: String?) {
        setSource(SOURCE_FOG, fogJson ?: EMPTY_FEATURES)
        setSource(SOURCE_FOG_OUTLINE, outlineJson ?: EMPTY_FEATURES)
        setSource(SOURCE_MAX_SQUARE, maxSquareJson ?: EMPTY_FEATURES)
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

    /**
     * Fuehrt die **Navi-Kamera** nach: Position, Zoom und Kurs in einem Zug,
     * die Position auf Wunsch ins untere Drittel des Bildes verschoben.
     *
     * ## Wie der Versatz ins untere Drittel funktioniert
     * Ueber das Kamera-Padding von MapLibre 11 ([CameraPosition.Builder.padding]):
     * Ein oberes Padding von [NAV_CAMERA_VERSATZ_ANTEIL] der Kartenhoehe
     * verschiebt die Mitte des nutzbaren Ausschnitts nach unten — bei 0,4
     * liegt der Zielpunkt auf 70 % der Hoehe, also im unteren Drittel, und
     * die Fahrtrichtung bekommt den grossen Rest des Bildes. Das Padding
     * haengt an der Kameraposition selbst, nicht am View, und wird von
     * [resetNavCamera] wieder auf null gesetzt — ausserhalb der Navigation
     * bleibt alles zentriert wie bisher.
     *
     * Gefahren wird mit `easeCamera` in [NAV_CAMERA_EASE_MS]: Die GPS-Punkte
     * kommen etwa sekuendlich, und eine gleichlange, lineare Fahrt dorthin
     * ergibt das fluessige Mitschwimmen der bekannten Navi-Apps — `moveCamera`
     * wuerde springen, `animateCamera` mit seiner Beschleunigungskurve pumpen.
     * Eine `ease`-Fahrt meldet sich ausserdem nicht als Geste, loest also
     * nicht das „Nutzerin hat selbst verschoben"-Signal aus.
     */
    fun moveToNavCamera(
        lat: Double,
        lon: Double,
        zoom: Double,
        bearingGrad: Double,
        versatz: Boolean,
    ) {
        run(afterReady = false) { map ->
            val topPad = if (versatz) map.height * NAV_CAMERA_VERSATZ_ANTEIL else 0.0
            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(lat, lon))
                        .zoom(zoom)
                        .bearing(bearingGrad)
                        .padding(0.0, topPad, 0.0, 0.0)
                        .build(),
                ),
                NAV_CAMERA_EASE_MS,
            )
        }
    }

    /**
     * Nimmt die Navi-Kamera zurueck: Kurs wieder Nord, Padding null, Position
     * und Zoom bleiben, wo sie sind. Gerufen beim Ende der Navigation und
     * beim Umschalten auf „Nord oben" — danach verhaelt sich die Kamera exakt
     * wie vor der Navigation.
     */
    fun resetNavCamera() {
        run(afterReady = false) { map ->
            val position = map.cameraPosition
            val target = position.target ?: return@run
            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target)
                        .zoom(position.zoom)
                        .bearing(0.0)
                        .padding(0.0, 0.0, 0.0, 0.0)
                        .build(),
                ),
                CAMERA_ANIMATION_MS,
            )
        }
    }

    /**
     * Abseits der Route: zoomt so weit heraus, dass die eigene Position und
     * der naechste Routenpunkt **gemeinsam** im Bild stehen — Nord oben und
     * zentriert (kein Kurs-Drehen und kein Drittel-Versatz: Wer die Route
     * sucht, braucht die Uebersicht, nicht den Fahr-Blick). Die Zoomgrenzen
     * kommen aus `:core` (`klemmeOffRouteZoom`, 12..16).
     */
    fun frameOffRoute(lat: Double, lon: Double, routeLat: Double, routeLon: Double) {
        run(afterReady = true) { map ->
            val bounds = runCatching {
                LatLngBounds.Builder()
                    .include(LatLng(lat, lon))
                    .include(LatLng(routeLat, routeLon))
                    .build()
            }.getOrNull() ?: return@run
            val pad = OFF_ROUTE_FIT_PADDING_PX
            val target = runCatching {
                // Ausdruecklich fuer Nord oben (bearing 0, tilt 0) rechnen —
                // die Kamera kann in diesem Moment noch course-up gedreht sein.
                map.getCameraForLatLngBounds(bounds, intArrayOf(pad, pad, pad, pad), 0.0, 0.0)
            }.getOrNull() ?: return@run
            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target.target)
                        .zoom(klemmeOffRouteZoom(target.zoom))
                        .bearing(0.0)
                        .padding(0.0, 0.0, 0.0, 0.0)
                        .build(),
                ),
                NAV_CAMERA_EASE_MS,
            )
        }
    }

    /** Aktuelle Kameraposition, oder `null` solange die Karte nicht steht. */
    fun rememberCamera(): CameraSnapshot? {
        val position = map?.cameraPosition ?: return null
        val target = position.target ?: return null
        return CameraSnapshot(target.latitude, target.longitude, position.zoom, position.bearing)
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
     *
     * ## Woher der Standortpunkt seine Positionen bezieht
     * Ueber `useDefaultLocationEngine(true)` weiter unten, und diese
     * Voreinstellung ist nachgeprueft: In MapLibre 11.13.5 gibt
     * `LocationEngineDefault.getDefaultLocationEngine(context)`
     * bedingungslos eine `MapLibreFusedLocationEngineImpl` zurueck — es gibt
     * dort keine Abfrage, ob Google Play services vorhanden sind, und im
     * ganzen Artefakt keine einzige `com.google.android.gms`-Referenz. Jene
     * Engine sitzt ihrerseits direkt auf [android.location.LocationManager]
     * auf. Das ist der Unterschied zu Mapbox, von dem diese API abstammt:
     * Dort waehlte `LocationEngineProvider.getBestLocationEngine()` zur
     * Laufzeit den gebuendelten Google-Dienst, sobald er im Klassenpfad lag —
     * MapLibre hat genau diese Weiche beim Fork entfernt.
     *
     * Fuer den Ausbau von `play-services-location` heisst das: Hier war
     * nichts zu tun, und es geht dadurch auch nichts verloren. Anders als der
     * Aufzeichnungsdienst mischt diese Engine allerdings `network` mit
     * hinzu — fuer einen Standortpunkt auf der Karte ist das richtig (er soll
     * auch in Gebaeuden ungefaehr stimmen), fuer eine Tour waere es das nicht.
     * Der Punkt auf der Karte und die aufgezeichnete Spur duerfen deshalb
     * sichtbar auseinanderliegen; die Spur ist die genauere von beiden.
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

/**
 * Wie [addLayerIfAbsent], haengt die Ebene aber direkt **unter** [below] ein
 * statt oben auf den Stapel.
 *
 * Gebraucht fuer alles, was Untergrund ist und nicht Aufschrift: Die
 * Entdeckt-Kacheln liegen so unter Tourlinie, geplanter Route und Markern,
 * ohne dass diese in einer bestimmten Reihenfolge hinzugefuegt werden
 * muessten. [below] muss zu diesem Zeitpunkt im Stil stehen — sonst wirft
 * MapLibre.
 */
private fun Style.addLayerBelowIfAbsent(layer: Layer, below: String) {
    if (getLayer(layer.id) == null) {
        addLayerBelow(layer, below)
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
    val builder = StringBuilder(markers.size * 128)
    builder.append("{\"type\":\"FeatureCollection\",\"features\":[")
    markers.forEachIndexed { index, marker ->
        if (index > 0) builder.append(',')
        // Gefuellter Punkt: deckende Fuellung, duenner weisser Rand — die
        // bisherige Form. Ring mit Loch (MapMarker.filled == false): keine
        // Fuellung (opacity 0 legt die Kartenkachel darunter frei), dafuer ein
        // breiterer Rand in der Markerfarbe selbst, sonst waere der Ring auf
        // einer hellen Kachel unsichtbar (siehe MapMarker.filled-KDoc).
        val opacity = if (marker.filled) 1f else 0f
        val strokeWidth = if (marker.filled) FILLED_STROKE_WIDTH else RING_STROKE_WIDTH
        val strokeColor = if (marker.filled) hexColor(MARKER_STROKE_COLOR) else hexColor(marker.color)
        builder.append("{\"type\":\"Feature\",\"properties\":{")
            .append('"').append(PROP_COLOR).append("\":\"").append(hexColor(marker.color))
            .append("\",\"").append(PROP_RADIUS).append("\":").append(number(marker.radius))
            .append(",\"").append(PROP_OPACITY).append("\":").append(number(opacity))
            .append(",\"").append(PROP_STROKE_WIDTH).append("\":").append(number(strokeWidth))
            .append(",\"").append(PROP_STROKE_COLOR).append("\":\"").append(strokeColor).append('"')
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

/** Nebel ueber allem Unbefahrenen (siehe [MapController.setExplorerTiles]). */
private const val SOURCE_FOG = "ts-fog-source"

/** Das gruene Raster um die befahrenen Kacheln. */
private const val SOURCE_FOG_OUTLINE = "ts-fog-outline-source"

/** Der Rahmen des groessten zusammenhaengenden Quadrats. */
private const val SOURCE_MAX_SQUARE = "ts-maxsquare-source"

private const val LAYER_TRACK = "ts-track-layer"
private const val LAYER_PLANNED = "ts-planned-layer"
private const val LAYER_LIVE = "ts-live-layer"
private const val LAYER_MARKERS = "ts-marker-layer"

private const val LAYER_FOG = "ts-fog-layer"
private const val LAYER_FOG_OUTLINE = "ts-fog-outline-layer"
private const val LAYER_MAX_SQUARE = "ts-maxsquare-layer"

private const val PROP_COLOR = "ts-color"
private const val PROP_RADIUS = "ts-radius"

/** Fuellungsdeckkraft — siehe [MapMarker.filled]: 1 gefuellt, 0 hohler Ring. */
private const val PROP_OPACITY = "ts-opacity"
private const val PROP_STROKE_WIDTH = "ts-strokeWidth"
private const val PROP_STROKE_COLOR = "ts-strokeColor"

private const val LINE_WIDTH = 5f

/** Randbreite eines gefuellten Punkts — unveraendert die bisherige Zahl. */
private const val FILLED_STROKE_WIDTH = 3f

/**
 * Randbreite eines Ort-Rings. Breiter als [FILLED_STROKE_WIDTH]: Der Rand
 * traegt hier die ganze Zeichnung (die Fuellung ist transparent), er muss
 * also allein schon als Form erkennbar sein.
 */
private const val RING_STROKE_WIDTH = 5f

private val TRACK_COLOR = GravelGreen.toArgb()
private val PLANNED_COLOR = RouteBlue.toArgb()
private val LIVE_COLOR = RecordRed.toArgb()
private val MARKER_STROKE_COLOR = Color.White.toArgb()

/**
 * Der Schleier ueber dem Unbefahrenen: ein dunkles, sehr kuehles Grau bei
 * rund 33 % Deckkraft (84/255).
 *
 * Bewusst eine feste Zahl und keine Theme-Farbe — aus demselben Grund wie die
 * drei Tourfarben in `MapColors.kt`: Sie liegt auf den Kartenkacheln, nicht
 * auf einer Theme-Flaeche. Der Nebel muss im hellen wie im dunklen Modus
 * gleich dicht wirken; eine Theme-Farbe waere im Dunkelmodus hell und wuerde
 * das Verhaeltnis umdrehen. Und er muss duenn bleiben: Er soll zeigen, wo man
 * noch nicht war, nicht die Karte darunter unbenutzbar machen — man plant ja
 * gerade dort die naechste Tour.
 *
 * Voll qualifiziert, weil [androidx.compose.ui.graphics.Color] in dieser
 * Datei bereits als `Color` importiert ist.
 */
private val FOG_COLOR = android.graphics.Color.argb(84, 22, 24, 28)

/**
 * Das Raster um die befahrenen Kacheln: [GravelGreen], aber auf rund 45 %
 * heruntergenommen (115/255). Voll deckend waere aus der Uebersicht ein
 * gruenes Gitter, das jede Tourlinie ueberstimmt — es soll die entdeckte
 * Flaeche nur umreissen.
 */
private val EXPLORED_OUTLINE_COLOR = android.graphics.Color.argb(115, 0x2D, 0x5A, 0x3D)

/** Der Rahmen des groessten Quadrats — [GravelGreen] voll, er ist die Trophaee. */
private val MAX_SQUARE_COLOR = GravelGreen.toArgb()

private const val EXPLORED_OUTLINE_WIDTH = 1.0f
private const val MAX_SQUARE_WIDTH = 2.5f

/** Kartenmitte beim ersten Start: Deutschland, wie im Flutter-Original. */
internal const val GERMANY_LAT = 51.0
internal const val GERMANY_LON = 10.0
internal const val GERMANY_ZOOM = 6.0

/** Zoomstufe, auf die beim ersten aufgezeichneten Punkt mindestens gezoomt wird. */
internal const val MIN_RECORDING_ZOOM = 15.0

private const val MAX_FIT_ZOOM = 16.0
private const val MAX_CAMERA_ZOOM = 20.0
private const val CAMERA_ANIMATION_MS = 600

/**
 * Anteil der Kartenhoehe, der als oberes Kamera-Padding die Position ins
 * untere Drittel schiebt (siehe [MapController.moveToNavCamera]): Mitte des
 * Rests = (0,4 + 1,0) / 2 = 70 % der Hoehe.
 */
private const val NAV_CAMERA_VERSATZ_ANTEIL = 0.4

/**
 * Dauer der Kamerafahrt je Navi-Update — knapp unter dem GPS-Sekundentakt,
 * damit die naechste Fahrt die laufende abloest statt auf sie zu warten.
 */
private const val NAV_CAMERA_EASE_MS = 900

/** Rand der Abseits-Ansicht um Position und Routenpunkt (Pixel). */
private const val OFF_ROUTE_FIT_PADDING_PX = 96

private val DEFAULT_FIT_PADDING = MapPadding(left = 48, top = 120, right = 48, bottom = 220)
