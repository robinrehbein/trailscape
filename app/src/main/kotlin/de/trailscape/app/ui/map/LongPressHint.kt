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

/**
 * Text der einmaligen Snackbar — sagt, was die Geste beim Erkunden wirklich
 * tut: einen Punkt setzen, dessen Ortskarte „Route hierher" und „Runde ab
 * hier" anbietet. Bewusst kurz; die Ortskarte erklaert den Rest selbst.
 */
internal const val LONG_PRESS_HINT_TEXT =
    "Tipp: Lange auf die Karte drücken setzt einen Punkt – als Ziel oder Start einer Runde."

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
 * So lange muss der Tipp mindestens gestanden haben, damit er als gesehen
 * gilt, wenn ihn etwas vorzeitig abraeumt (Aufgabe, Meldung, App im
 * Hintergrund). Zwei Sekunden reichen, um den kurzen Satz zu lesen; was
 * kuerzer stand, war nur ein Aufblitzen.
 */
internal const val LONG_PRESS_HINT_SEEN_MS = 2_000L

/**
 * Schluessel des Merkers (im gemeinsamen [KeyValueStore], wie der
 * Onboarding-Merker). Gesetzt wird er, sobald der Tipp einmal gesehen wurde
 * ([langDrueckHinweisGesehen]) **oder** die Nutzerin die Geste von selbst
 * gefunden hat — wer lange drueckt,
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
    /** Ruhige Karte, nur das eingeklappte „Wohin?"-Blatt — der einzige Zustand fuer den Tipp. */
    ERKUNDEN,

    /**
     * Ort, Tour, Suche, Rundenwahl, Generator, Verlauf, hochgewischtes
     * „Wohin?"-Blatt, Kartenstil-Blatt oder ein Dialog offen: Die Nutzerin ist
     * beschaeftigt, oder ein Scrim laege ueber dem Tipp.
     */
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
 *  * die App steht im Vordergrund (sonst verstriche der Tipp ungesehen),
 *  * der Merker ist noch nicht gesetzt,
 *  * die Nutzerin bewegt die Karte nicht gerade selbst und hat es auch in
 *    den letzten [LONG_PRESS_HINT_CALM_MS] nicht getan,
 *  * und keine andere Snackbar steht — der Tipp soll keine echte Meldung
 *    verdraengen und sich auch nicht hinter ihr anstellen (die
 *    `SnackbarHostState`-Warteschlange wuerde ihn sonst spaeter in einem
 *    Zustand zeigen, fuer den er nicht gedacht war).
 */
internal fun sollLangDrueckHinweisZeigen(
    lage: LangDrueckLage,
    imVordergrund: Boolean,
    hinweisErledigt: Boolean,
    geradeGeschwenkt: Boolean,
    andereSnackbarSichtbar: Boolean,
): Boolean =
    lage == LangDrueckLage.ERKUNDEN &&
        imVordergrund &&
        !hinweisErledigt &&
        !geradeGeschwenkt &&
        !andereSnackbarSichtbar

/**
 * Gilt der Tipp nach seinem Ende als gesehen?
 *
 * Ja, wenn er regulaer endete (weggetippt oder ausgelaufen) oder vor dem
 * Abraeumen mindestens [LONG_PRESS_HINT_SEEN_MS] stand. Sonst bleibt der
 * Merker leer und der Tipp kommt beim naechsten ruhigen Erkunden wieder —
 * sein Versprechen ist „einmal gesehen", nicht „einmal aufgeblitzt".
 */
internal fun langDrueckHinweisGesehen(regulaerBeendet: Boolean, sichtbarMs: Long): Boolean =
    regulaerBeendet || sichtbarMs >= LONG_PRESS_HINT_SEEN_MS
