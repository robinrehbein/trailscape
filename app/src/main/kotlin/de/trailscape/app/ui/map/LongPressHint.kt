package de.trailscape.app.ui.map

import de.trailscape.core.KeyValueStore

/**
 * Der einmalige Tipp zum **langen Druecken** auf die Karte (Massnahme U6).
 *
 * Langes Druecken ist seit der Fuehrung „Klartext" die einzige Geste, die auf
 * der Karte etwas anlegt (siehe `onMapLongPress` in `MapScreen.kt`) — und
 * zugleich eine, die man nicht sieht: Kein Knopf verraet sie, und ein
 * gewoehnlicher Tipp tut bewusst nichts. Ein Zahnrad oder ein eigener Knopf
 * auf der Karte kam nicht in Frage (Entscheidung Gruender: die Karte bleibt
 * frei). Stattdessen gibt es zwei leise Wege, die Geste zu entdecken:
 *
 *  * einmal eine Snackbar beim Erkunden (hier entschieden, gezeigt in
 *    `MapScreen.kt`),
 *  * dauerhaft eine kleine Hinweiszeile im hochgewischten „Wohin?"-Blatt
 *    (`ExploreSheet.kt`).
 *
 * Die Entscheidung ist eine **reine Funktion** ([sollLangDrueckHinweisZeigen]),
 * damit sich die ganze Matrix aus Kartenzustand, Merker, Schwenk und fremder
 * Snackbar ohne Compose und ohne Uhr pruefen laesst
 * (`LongPressHintTest`).
 */

/** Text der einmaligen Snackbar — sagt, was die Geste beim Erkunden wirklich tut. */
internal const val LONG_PRESS_HINT_TEXT =
    "Tipp: Lange auf die Karte drücken, um einen Punkt zu setzen – für eine Route dorthin oder eine Runde ab dort."

/** Die dauerhafte Zeile im hochgewischten „Wohin?"-Blatt. */
internal const val LONG_PRESS_HINT_LINE = "Lange auf die Karte drücken: Punkt setzen"

/**
 * So lange muss die Karte nach dem Betreten des Erkundens bzw. nach dem
 * letzten eigenen Schwenk ruhig stehen, bevor der Tipp kommt. Wer gerade
 * wischt, schaut auf die Karte und nicht auf den unteren Rand — ein Tipp in
 * diesem Moment ginge unter oder stoerte.
 */
internal const val LONG_PRESS_HINT_CALM_MS = 3_000L

/**
 * Schluessel des Merkers (im gemeinsamen [KeyValueStore], wie der
 * Onboarding-Merker). Gesetzt wird er, sobald der Tipp einmal erschienen ist
 * **oder** die Nutzerin die Geste von selbst gefunden hat — wer lange drueckt,
 * braucht den Tipp nicht mehr.
 */
internal const val LONG_PRESS_HINT_STORAGE_KEY = "trailscape.hint.longPress"

/** Ob der Tipp erledigt ist (gezeigt oder die Geste schon benutzt). */
internal fun langDrueckHinweisErledigt(store: KeyValueStore): Boolean =
    runCatching { store.getString(LONG_PRESS_HINT_STORAGE_KEY) != null }.getOrDefault(false)

/** Merkt den Tipp als erledigt — danach erscheint er nie wieder. */
internal fun merkeLangDrueckHinweisErledigt(store: KeyValueStore) {
    runCatching { store.setString(LONG_PRESS_HINT_STORAGE_KEY, "1") }
}

/**
 * In welcher Lage die Karte fuer den Tipp gerade ist — groeber als [MapMode],
 * weil fuer den Tipp auch Aufzeichnung und offene Aufgaben zaehlen, die
 * [MapMode] nicht kennt (Aufzeichnung laeuft in jedem Modus).
 */
internal enum class LangDrueckLage {
    /** Ruhige Karte, kein Blatt ausser „Wohin?" — der einzige Zustand fuer den Tipp. */
    ERKUNDEN,

    /** Ort, Tour, Suche, Rundenwahl, Generator oder Verlauf offen: Die Nutzerin ist beschaeftigt. */
    AUFGABE,

    /** Routenplanung: Die Planung erklaert das Setzen selbst (Platzhalterzeile im Blatt). */
    PLANUNG,

    /** Aufzeichnung laeuft: Langes Druecken tut dann nichts, ein Tipp waere falsch. */
    AUFZEICHNUNG,

    /** Navigation laeuft: dasselbe — und am Lenker lenkt jede Einblendung ab. */
    NAVIGATION,
}

/**
 * Leitet die [LangDrueckLage] aus den Zustaenden von `MapScreen.kt` ab.
 *
 * Die Rangfolge folgt `onMapLongPress`: Aufzeichnung und Navigation schlucken
 * die Geste ganz, deshalb gehen sie allem anderen vor; danach die Planung;
 * erst dann die offenen Aufgaben des Erkundens.
 */
internal fun langDrueckLage(
    mode: MapMode,
    aufzeichnung: Boolean,
    navigation: Boolean,
    aufgabeOffen: Boolean,
): LangDrueckLage = when {
    aufzeichnung -> LangDrueckLage.AUFZEICHNUNG
    navigation || mode == MapMode.NAVIGIEREN -> LangDrueckLage.NAVIGATION
    mode == MapMode.PLANEN -> LangDrueckLage.PLANUNG
    aufgabeOffen -> LangDrueckLage.AUFGABE
    else -> LangDrueckLage.ERKUNDEN
}

/**
 * Soll der einmalige Tipp zum langen Druecken **jetzt** erscheinen?
 *
 * Nur, wenn alles zusammenkommt:
 *  * die Karte ist im ruhigen Erkunden ([LangDrueckLage.ERKUNDEN]),
 *  * der Merker ist noch nicht gesetzt,
 *  * die Nutzerin hat die Karte nicht gerade selbst verschoben,
 *  * und keine andere Snackbar steht — der Tipp soll keine echte Meldung
 *    verdraengen und sich auch nicht hinter ihr anstellen (die
 *    `SnackbarHostState`-Warteschlange wuerde ihn sonst spaeter in einem
 *    Zustand zeigen, fuer den er nicht gedacht war).
 */
internal fun sollLangDrueckHinweisZeigen(
    lage: LangDrueckLage,
    hinweisErledigt: Boolean,
    geradeGeschwenkt: Boolean,
    andereSnackbarSichtbar: Boolean,
): Boolean =
    lage == LangDrueckLage.ERKUNDEN &&
        !hinweisErledigt &&
        !geradeGeschwenkt &&
        !andereSnackbarSichtbar
