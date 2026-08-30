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
 * setzt, ob die Zurueck-Geste die Planung verlaesst, welche Stufe das
 * Kartenblatt zeigt. `searchOpen` gehoert bewusst nicht zu diesem Modus — die
 * Ortssuche ist in jedem der drei Modi erreichbar (siehe Klassen-KDoc von
 * `MapScreen.kt`, „Suche jederzeit") und damit orthogonal, kein vierter Wert.
 *
 * ## Warum ein Aufzaehlungswert und keine Flag-Kombination
 * Vorher stand „wird gerade geplant?" als eigenes `Boolean`, „wird gerade
 * navigiert?" als `navTarget != null` — zwei Fragen an zwei verschiedene
 * Stellen im Code, deren Antworten sich nie gegenseitig ausschliessen mussten,
 * es aber in der Praxis (bis auf eine Ausnahme, siehe unten) taten. Jedes neue
 * Panel mit eigenen Sichtbarkeitsregeln (Rundkurs-Generator, Download,
 * unteres Blatt) fragte seither erneut dieselbe Kombination ab, und mit jeder
 * neuen Kombination stieg das Risiko, eine Stelle zu vergessen. Ein einziger
 * `mode`-Wert macht die Frage „was gilt gerade?" an jeder Stelle zu einem
 * einzigen `when`, statt zu einer stillschweigenden Annahme ueber die
 * Reihenfolge, in der Flags gesetzt werden.
 *
 * ## Die eine bewusste Ausnahme: Navigation der eigenen geplanten Route
 * [NAVIGIEREN] wird ausschliesslich beim Navigieren einer **gespeicherten
 * Tour** angenommen (`runNavigateRide` in `MapScreen.kt`) — dort ist Browsen
 * und Navigieren exklusiv: Waehrend [PLANEN] laesst sich in `MapScreen.kt`
 * gar keine Tour auswaehlen (das Erkunden-Gesicht des Blatts weicht ja bereits
 * waehrend [PLANEN], siehe dessen Vorrang-Regeln), also kann [NAVIGIEREN] in
 * diesem Fall nie aus [PLANEN] kommen, immer nur aus [ERKUNDEN].
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

/**
 * Die drei Stufen des **einen** Blatts am unteren Kartenrand — das Verhalten,
 * das der Nutzer am Karte-Screen von `docs/design/prototyp-eine-leiste.html`
 * freigegeben hat.
 *
 * Ueber der schwebenden Navigationskapsel liegt genau ein aufziehbares Blatt.
 * Eingeklappt zeigt es Griff und Suchzeile und laesst die Karte fast
 * vollstaendig frei; eine Stufe hoeher gibt es die Aktionszeile „Route planen ·
 * Kartenstil · Offline" frei; in der obersten Stufe steht an deren Stelle der
 * Planungsinhalt. Gewechselt wird per Tipp auf den Griff und per vertikalem
 * Ziehen (beides bringt [SwipeableSheet] mit), und die Stufe ueberlebt einen
 * Tabwechsel (`rememberSaveable` in `MapScreen.kt`).
 *
 * ## Warum eine Ableitung und kein eigener gespeicherter Zustand
 * Die Stufe wird in `MapScreen.kt` aus [MapMode] und der Erkunden-Stufe
 * **abgeleitet**, nicht daneben gehalten. Ein zweiter gespeicherter Zustand
 * haette dieselbe Frage („wird gerade geplant?") ein zweites Mal beantworten
 * koennen — und die beiden Antworten waeren frueher oder spaeter
 * auseinandergelaufen. Der Aufzaehlungswert ist damit die *Lesart* des
 * Zustands, nicht seine Quelle; geschrieben wird ueber `goToSheetStage` in
 * `MapScreen.kt`, das die Aenderung an die jeweils zustaendige Quelle
 * weiterreicht.
 *
 * ## Warum die Stufe nicht dasselbe ist wie [MapMode]
 * [MapMode] sagt, **welche Bedienflaeche gilt** (setzt ein Kartentipp einen
 * Wegpunkt? verlaesst die Zurueck-Geste die Planung?). Die Stufe sagt nur, wie
 * viel vom Blatt zu sehen ist. Deshalb hat [MapMode.PLANEN] zwei Stufen:
 * [PLANEN] mit vollem Planungsinhalt und [EINGEKLAPPT], sobald die Nutzerin
 * mit der Karte arbeitet und Wegpunkte hintippt (siehe `onMapTap` in
 * `MapScreen.kt`) — die Planung laeuft dann unveraendert weiter, das Blatt
 * gibt nur die Karte frei. Und [MapMode.NAVIGIEREN] hat gar keine: Waehrend
 * Navigation, Aufzeichnung, gewaehlter Tour, gewaehltem Ort oder offener
 * Rundenwahl ist dieses Blatt ueberhaupt nicht komponiert (siehe die
 * Rangfolge im Klassen-KDoc von `MapScreen.kt`).
 */
internal enum class MapSheetStage {
    /** Nur Griff und die eine Peek-Zeile — der Ruhezustand, maximale Kartensicht. */
    EINGEKLAPPT,

    /** Zusaetzlich die Aktionszeile „Route planen · Kartenstil · Offline". */
    AUFGEZOGEN,

    /** Statt der Aktionszeile der Planungsinhalt (`PlanningSheet` in `PlanningPanel.kt`). */
    PLANEN,
}
