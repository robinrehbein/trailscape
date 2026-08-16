package de.trailscape.app.ui.map

/**
 * Die drei Modi der Karte — SSOT fuer alles, was frueher aus der Kombination
 * mehrerer Flags (`planning: Boolean`, `navTarget != null`, `searchOpen`, …)
 * abgelesen werden musste (siehe „Warum ein Aufzaehlungswert" unten).
 *
 * ## Was ein Modus NICHT ist
 * [MapMode] ersetzt weder [de.trailscape.core.NavState] noch `navTarget`
 * selbst — die Punktliste, das Navigationsziel und der Live-Fortschritt der
 * Navigation bleiben eigene Zustaende in `MapScreen.kt`. Der Modus sagt nur,
 * **welche Bedienflaeche gerade gilt**: ob ein Kartentipp einen Wegpunkt
 * setzt, ob die Zurueck-Geste die Planung verlaesst, ob das Tourenblatt
 * weichen muss. `searchOpen` gehoert bewusst nicht zu diesem Modus — die
 * Ortssuche ist in jedem der drei Modi erreichbar (siehe Klassen-KDoc von
 * `MapScreen.kt`, „Suche jederzeit") und damit orthogonal, kein vierter Wert.
 *
 * ## Warum ein Aufzaehlungswert und keine Flag-Kombination
 * Vorher stand „wird gerade geplant?" als eigenes `Boolean`, „wird gerade
 * navigiert?" als `navTarget != null` — zwei Fragen an zwei verschiedene
 * Stellen im Code, deren Antworten sich nie gegenseitig ausschliessen mussten,
 * es aber in der Praxis (bis auf eine Ausnahme, siehe unten) taten. Jedes neue
 * Panel mit eigenen Sichtbarkeitsregeln (Rundkurs-Generator, Download,
 * Tourenblatt) fragte seither erneut dieselbe Kombination ab, und mit jeder
 * neuen Kombination stieg das Risiko, eine Stelle zu vergessen. Ein einziger
 * `mode`-Wert macht die Frage „was gilt gerade?" an jeder Stelle zu einem
 * einzigen `when`, statt zu einer stillschweigenden Annahme ueber die
 * Reihenfolge, in der Flags gesetzt werden.
 *
 * ## Die eine bewusste Ausnahme: Navigation der eigenen geplanten Route
 * [NAVIGIEREN] wird ausschliesslich beim Navigieren einer **gespeicherten
 * Tour** angenommen (`runNavigateRide` in `MapScreen.kt`) — dort ist Browsen
 * und Navigieren exklusiv: Waehrend [PLANEN] laesst sich in `MapScreen.kt`
 * gar keine Tour auswaehlen (das Tourenblatt weicht ja bereits waehrend
 * [PLANEN], siehe dessen Vorrang-Regeln), also kann [NAVIGIEREN] in diesem
 * Fall nie aus [PLANEN] kommen, immer nur aus [ERKUNDEN].
 *
 * Tippt die Nutzerin dagegen in der Planung selbst auf „Navigieren"
 * (`runNavigatePlannedRoute`), bleibt der Modus [PLANEN] — bewusst, nicht aus
 * Versehen: `runRecording()` liest an dieser Stelle noch, ob [PLANEN] gilt,
 * um zu entscheiden, ob die geplante Route die anschliessende Aufzeichnung
 * ueberlebt (siehe deren KDoc, „planen → Navigieren → Aufnahme starten" ist
 * die vorgesehene Reihenfolge). Ein Wechsel nach [NAVIGIEREN] an dieser Stelle
 * wuerde genau dieses Signal loeschen und die Route beim Start der Aufnahme
 * wieder verschwinden lassen — der Fehler, den dieselbe Reihenfolge frueher
 * schon einmal hatte. Der laufende Navigationsfortschritt braucht dafuer kein
 * eigenes Modus-Flag: Er haengt bereits vollstaendig an `navTarget != null`,
 * das unabhaengig vom Modus weiterlaeuft (die Navigationsleiste zeigt sich
 * darum in [ERKUNDEN], [PLANEN] **und** [NAVIGIEREN] gleichermassen).
 */
enum class MapMode {
    /** Ruhezustand: Karte ansehen, Tour auswaehlen, Kartenstil oder -ausschnitt waehlen. */
    ERKUNDEN,

    /** Wegpunkte setzen/entfernen, Rundkurs generieren, Route speichern oder teilen. */
    PLANEN,

    /** Eine gespeicherte Tour wird abgefahren — siehe Klassen-KDoc fuer die Abgrenzung. */
    NAVIGIEREN,
}
