package de.trailscape.app.ui.indoor

import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.app.challenge.ChallengeRouteInfo
import de.trailscape.app.challenge.ChallengeStore
import de.trailscape.core.challenge.Challenge
import de.trailscape.core.challenge.ChallengeReject
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.indoor.catalog.CatalogText

/**
 * Zustände der „Herausforderung teilen" (docs/herausforderung.md): Liste auf der
 * Startseite, Vorschau beim Import, Vorschau beim Teilen. Reine Daten, damit
 * Bildschirme und Tests sie ohne Ablauf bauen können.
 */

/**
 * Was eine Datei enthält — genau das, was die Vorschau zeigt.
 *
 * @property name bereinigter Anzeigename, leer = ohne Namen.
 * @property route die Strecke der Datei.
 * @property info Passung zur Strecke auf diesem Gerät.
 * @property distanceKm gefahrene Strecke der Kurve.
 * @property durationS Fahrzeit der Kurve.
 * @property date Tag der Fahrt oder `null` (nicht enthalten).
 * @property hasAvatar die Datei trägt ein Avatar-Objekt.
 */
data class ChallengePreviewUi(
    val name: String,
    val route: ChallengeRoute,
    val info: ChallengeRouteInfo,
    val distanceKm: Double,
    val durationS: Int,
    val date: String?,
    val hasAvatar: Boolean,
)

fun challengePreviewOf(challenge: Challenge, info: ChallengeRouteInfo): ChallengePreviewUi = ChallengePreviewUi(
    name = challenge.name,
    route = challenge.route,
    info = info,
    distanceKm = challenge.curve.totalDistM / 1000.0,
    durationS = Math.round(challenge.curve.totalS).toInt(),
    date = challenge.date,
    hasAvatar = challenge.avatarJson != null,
)

/** Der Import einer Datei (Dateiauswahl oder „Öffnen mit"). */
sealed interface ChallengeImportUi {
    /** Gelesen und geprüft, noch nicht gespeichert. */
    data class Preview(val preview: ChallengePreviewUi, val challenge: Challenge) : ChallengeImportUi

    /** Das Ergebnis von „Speichern". */
    data class Saved(val preview: ChallengePreviewUi, val result: ChallengeStore.SaveResult) : ChallengeImportUi

    /** Abgelehnt; [fileVersion] bei [ChallengeReject.TOO_NEW]. */
    data class Failed(val reason: ChallengeReject, val fileVersion: Int?) : ChallengeImportUi
}

/** Ein Eintrag der Liste gespeicherter Herausforderungen. */
data class IndoorChallengeUi(
    val id: String,
    val name: String,
    val date: String?,
    val routeKey: String,
    val catalogId: String?,
    val routeName: CatalogText?,
    val availability: ChallengeAvailability,
    val distanceKm: Double,
    val durationS: Int,
)

/**
 * Der Abschnitt „Herausforderungen" der Startseite. `null` an der Startseite =
 * Abschnitt nicht anzeigen (Vorgabe, damit bestehende Bilder gleich bleiben).
 *
 * @property selectedId die gerade als Mitfahrer gewählte Herausforderung.
 */
data class IndoorChallengesUi(
    val items: List<IndoorChallengeUi>,
    val selectedId: String? = null,
)

/**
 * Das Teilen der eigenen Fahrt (Auswertung): Name und Datum sind wählbar, die
 * Vorschau zeigt jeweils den aktuellen Inhalt der Datei.
 *
 * @property json die fertige Datei; `null`, solange sie berechnet wird oder wenn [failed].
 * @property fileId Kennung für den Dateinamen.
 * @property failed die Fahrt lässt sich nicht als Herausforderung teilen.
 */
data class ChallengeShareUi(
    val name: String,
    val includeDate: Boolean,
    val preview: ChallengePreviewUi?,
    val json: String?,
    val fileId: String?,
    val failed: Boolean = false,
    val reply: Boolean = false,
)
