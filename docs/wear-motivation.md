# Motivation auf der Uhr: Machbarkeit

Frage: (a) ein bis zwei Highlight-Zeilen nach der Fahrt, (b) Wochenserie plus Wochenziel auf einen Blick.
Ursprünglich nur gelesen; (a) und (b) sind inzwischen gebaut (siehe dort).

## Was die Uhr-App heute ist

- Modul `:wear` (`wear/build.gradle.kts`), gleiche `applicationId` und Signatur wie `:app`, ~2100 Zeilen,
  **keine Tests** (`wear/src/test` gibt es nicht). Aus dem Spike (`docs/wear-spike.md`) gewachsen, aber
  nicht mehr nur Spike: Die CI baut APK und `.aab` (`.github/workflows`), das APK liegt bewusst nicht
  bei den Releases; Verteilung für andere nur über Play Internal Testing (siehe Spike-Doku f).
- Sie ist eine Begleiterin (README „Galaxy Watch“): Health Services liefert Puls und GPS
  (`exercise/ExerciseRecorder.kt`), `comm/SensorSender.kt` schickt sie gebündelt ans Handy; die
  Tour entsteht dort. Auf der Uhr bleibt nur ein Journal (`record/RecordingJournal.kt`).
- Die Aufzeichnungsansicht (`ui/RecordingScreen.kt`); dazu seit (b) Kachel und Komplikation mit dem
  Wochenstand des Telefons (`glance/`, `tile/`, `complication/`).
- Datenfluss, alles `MessageClient`/`CapabilityClient` aus `play-services-wearable` (Verträge in
  `core/.../WearProtocol.kt`):
  - Uhr → Handy: `PFAD_SENSOR` (SensorBatch), `PFAD_BEFEHL_AN_TELEFON` (start/pause/weiter/stopp).
  - Handy → Uhr: `PFAD_BEFEHL_AN_UHR`, `PFAD_ZUSTAND` (`AufzeichnungsZustand`: läuft, pausiert, Dauer,
    Distanz, HF), gesendet aus `app/.../record/RecordingService.kt` (`sendeZustandAnUhr`) über
    `app/.../wear/WearBridge.kt`, Empfang `app/.../wear/WearListenerService.kt`.
  - Neu seit (a): Handy → Uhr `PFAD_ABSCHLUSS` (bis zu zwei Highlight-Zeilen nach der gespeicherten Tour).
  - Nachrichten sind flüchtig: ohne erreichbare Uhr gehen sie verloren.

## (a) Highlights nach der Fahrt — gebaut (Minimalform)

Stand: umgesetzt. Was es tut und wo:
- **Protokoll** (`core/.../WearProtocol.kt`): `PFAD_ABSCHLUSS = "/trailscape/abschluss"`,
  `AbschlussInfo(rideId, zeilen)`, Hand-JSON wie die anderen. Höchstens 2 Zeilen à 60 Zeichen
  (sonst „…"), gekürzt beim Kodieren **und** Dekodieren; `dekodiereAbschlussInfo` ist tolerant
  (kaputt, leer, fehlende Felder → `null`, wirft nie). Tests in `WearProtocolTest`.
- **Handy**: `app/.../wear/WearAbschluss.kt`, ausgelöst in `TrailscapeApplication`
  (`installWatchRideSummary`) über `RecordingRepository.finishedRides` — derselbe Fluss wie Widget
  und Strava, meldet erst nach dem Speichern, nichts im `RecordingService`. Je Tour eine eigene
  Coroutine auf IO, alles in `runCatching`, keine Wiederholung. Gesendet wird nur, wenn die Uhr seit
  dem Start der Tour etwas geschickt hat (`WearBridge.letzteUhrNachrichtMs`, gesetzt in
  `WearListenerService`), gerade erreichbar ist und es Highlights gibt. Gerechnet:
  `rideHighlights(..., routeComparison = null, max = 2)` aus den Zusammenfassungen; Wochenziel über
  `weekTargetKmOutsideUi` (`ui/TodayRouteTarget.kt`: das zuletzt auf „Heute" gezeigte Ziel dieser
  Woche, sonst `weekTarget` mit der rohen Planwoche bzw. `suggestWeeklyGoal`). Die Sätze kommen aus
  `rideHighlightText` (`ui/map/RideHighlights.kt`), das auch das Tourblatt rendert.
- **Uhr**: `CommandListenerService` nimmt den Pfad an (eigener Intent-Filter im Uhr-Manifest —
  ohne ihn startet Play Services den Dienst für diesen Pfad nicht), `RecordingStatus.abschlussZeilen`
  hält die Zeilen nur im Speicher, `EndeScreen` (`ui/RecordingScreen.kt`) zeigt sie klein unter dem
  Endstand, je max. 2 Zeilen, ohne Überschrift. Weg mit „Zurück zum Start" oder dem nächsten Start.
- **Alt/neu**: Eine alte Uhr hat keinen Intent-Filter für den Pfad (und ihr `when` kein `else`) —
  die Nachricht verpufft. Ein altes Handy sendet ihn nie; die neue Uhr zeigt dann wie bisher nur den
  Endstand.

Bekannte Grenzen:
- **Sprache**: Die Zeilen kommen in der Sprache der Telefon-App, nicht der Uhr.
- **Ende am Handy**: Seit „Uhr endet mit dem Handy" (unten) erreicht die Uhr ihr Abschlussbild auch,
  wenn die Tour am Handy beendet wird — mit einem neuen Handy und einer neuen Uhr. Mit einer alten
  Seite bleibt es beim alten Verhalten: Die Uhr hält die Zeilen, bis sie selbst gestoppt wird.
- Ungeprüft ohne Gerätepaar: Zustellzeit, Layout auf runder Anzeige. `:wear` hat keine Tests, die
  Uhrseite ist nur kompiliert.

### Uhr endet mit dem Handy

Wird die Tour am Handy regulär beendet (Knopf in der App oder in der Benachrichtigung), beendet auch
die Uhr ihre Aufzeichnung und zeigt ihr Abschlussbild.
- **Mechanismus**: ein expliziter Befehl, nicht das Auswerten von `AufzeichnungsZustand.laeuft`. Der
  Zustand geht im 5-s-Takt und auch bei Pause, Startfehler und Absturz-Rettung mit `laeuft = false`
  raus; „Ende" ließe sich daraus nur raten, und eine alte oder verspätete Nachricht trüge keinen
  Bezug. Stattdessen `Befehl(cmd = "telefon-beendet", zeitMs = <Ende am Handy>)` auf
  `PFAD_BEFEHL_AN_UHR` (`WearBridge.sendTelefonBeendet`, einmal, ohne Wiederholung), gesendet in
  `RecordingService.finishAndStop`, nur wenn dort wirklich eine Tour lief.
- **Bewusst nicht `stopp`**: Eine heutige Uhr setzt `stopp` ungeprüft um und meldet ihr Ende mit
  `stopp` zurück — auf alten Uhren gäbe das eine Schleife und keinen Schutz vor alten Nachrichten.
  Den neuen Wert verwirft eine alte Uhr im `else`.
- **Entscheidung der Uhr** (`telefonEndeBeendetUhr` in `core/.../WearProtocol.kt`, Tests in
  `WearProtocolTest`): nur wenn die Uhr gerade aufzeichnet oder pausiert, ihre Aufzeichnung **vor**
  dem Ende am Handy begann, die Nachricht höchstens 60 s alt ist (`TELEFON_ENDE_MAX_ALTER_MS`) und
  höchstens 30 s „aus der Zukunft" kommt (`TELEFON_ENDE_UHRZEIT_TOLERANZ_MS`, Uhrzeitversatz).
  Dann derselbe Weg wie der eigene Stopp-Knopf (`RecordingService.beendeVersuch`: Sensoren aus,
  Übung beenden, Journal, `BEENDET`, Dienst beenden), nur ohne `stopp` zurück ans Handy
  (`ACTION_STOP_VOM_TELEFON`). Die Uhr speichert nichts, also auch nichts doppelt.
- **Pause** am Handy schickt keinen Befehl; die Uhr läuft weiter wie bisher.
- **Nicht** bei Abbruch mit Fehler (`failAndStop`) und **nicht** bei der Absturz-Rettung: Dort hat
  niemand „Ende" gesagt; im Zweifel läuft die Uhr weiter und wird von Hand gestoppt. Ein „Verwerfen"
  gibt es am Handy nicht.
- **Echo**: Stoppt die Uhr selbst, stoppt sie das Handy (`stopp`), und das Handy meldet
  `telefon-beendet` zurück. Die Uhr ist dann schon `BEENDET` und ignoriert es.
- **Alt/neu**: Der Pfad hat seit jeher einen Intent-Filter im Uhr-Manifest, es braucht keinen neuen.
  Alte Uhr + neues Handy: Wert unbekannt, nichts passiert (wie bisher). Neue Uhr + altes Handy: Es
  kommt nichts, die Uhr läuft wie bisher weiter. Das Zusatzfeld `zeitMs` lesen alte Seiten nicht.
- Ungeprüft ohne Gerätepaar: Zustellung bei geschlossener Uhr-App, Reihenfolge gegenüber
  `PFAD_ABSCHLUSS` (die Zeilen bleiben ohnehin stehen, egal was zuerst ankommt).

## (b) Serie und Wochenziel auf einen Blick — gebaut

Stand: umgesetzt als Kachel und Komplikation. Was es tut und wo:
- **Protokoll** (`core/.../WearGlance.kt`): `PFAD_WOCHE = "/trailscape/woche"` als **DataItem** (nicht
  Nachricht: der Stand muss auch ankommen, wenn die Uhr gerade nicht verbunden ist). `WochenGlance`
  (Version `v`, Serie in Wochen, Woche erledigt, km, Ziel-km optional, Montag der Woche als Epochentag,
  Stand-Zeitstempel). Grenzen: Serie 0…5200, km 0…10 000 (auf 0,1 gerundet), Ziel 1…10 000, Montag muss
  ein Montag sein. `dekodiereWochenGlance` wirft nie: kaputt, fremde Version oder außerhalb der Grenzen
  → `null`. Tests in `WearGlanceTest`.
- **Veraltete Daten** (`glanceFuerHeute`, rein, getestet): Gleiche Kalenderwoche (Montag-Start) →
  unverändert. Eine Woche älter und dort erledigt → Serie lebt, diese Woche nicht erledigt, 0 km
  („in Gefahr", wie `weekStreak` am Montag). Sonst → Serie 0. Die Uhr behauptet nie „diese Woche
  geschafft" aus einer früheren Woche.
- **Handy** (`app/.../wear/WearGlanceSender.kt`): rechnet mit `loadWidgetState` (dieselbe Rechnung und
  dasselbe Wochenziel wie das Widget; aus `TrailscapeWidget` herausgehoben nach
  `widget/WidgetStateLoader.kt`) und wird aus `WidgetUpdater.request` angestoßen, also an genau den
  Stellen der Widget-Aktualisierung (Tour gespeichert, App im Hintergrund, Datums-/Zeitzonen-/
  Sprachwechsel). Gesendet wird nur bei geändertem Inhalt (der zuletzt erfolgreich geschriebene Stand
  liegt im Schlüssel-Wert-Speicher; der Zeitstempel zählt nicht). Alles auf IO in `runCatching`,
  Fehler nur als `WEAR_SEND_FAILED` im Diagnose-Log.
- **Uhr** (`wear/.../glance/`): `WochenListenerService` (`DATA_CHANGED`, eigener Dienst, die
  Aufzeichnung bleibt unberührt) speichert den letzten dekodierbaren Stand in SharedPreferences
  (`GlanceStore`) und stößt Kachel und Komplikation an (`GlanceUpdater`). Fehlt jeder Stand, fragt die
  Anzeige den Data Layer einmal aktiv ab (`nachziehen`, 2 s Timeout). `TagWechselReceiver` (Datums-,
  Zeitzonen-, Sprachwechsel) zeichnet am Montag ohne Telefon neu.
- **Kachel** (`tile/WochenTileService.kt`, `Material3TileService` mit klassischen ProtoLayout-Bausteinen):
  Status klein, Serie groß („5 Wochen"), „42 von 60 km", Bogen am Rand in der Akzentfarbe; Tipp öffnet
  die Uhr-App; Leerzustand „Noch keine Daten – Handy-App öffnen". Freshness bis kurz nach Mitternacht.
- **Komplikation** (`complication/WochenComplicationService.kt`): SHORT_TEXT („5 W", Titel „Serie"),
  RANGED_VALUE (gefahrene km von Ziel-km) und LONG_TEXT („5 Wochen" / „42 von 60 km"), Vorschaudaten,
  Tipp öffnet die App. Kein Abfragetakt (`UPDATE_PERIOD_SECONDS = 0`).
- **Abhängigkeiten** (`wear/build.gradle.kts`): `androidx.wear.tiles:tiles:1.6.2`,
  `androidx.wear.protolayout:protolayout`, `-expression`, `-material3` je `1.4.2`,
  `androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0`. Keine neue Berechtigung.
- **Release**: `:wear` ist weiter nicht minimiert (`isMinifyEnabled = false`), die Dienste können
  also nicht von R8 entfernt werden; `:wear:assembleRelease` baut.

Bekannte Grenzen:
- **Montag ohne Telefon**: Die Uhr rechnet nur fort, was sie weiß: Serie lebt (falls die Vorwoche
  erledigt war), 0 km, das alte Ziel. Das neue Wochenziel (Planwoche!) und echte Kilometer kennt erst
  das nächste Senden des Telefons; das geschieht, sobald die Handy-App einmal läuft oder das Widget
  am Datumswechsel neu zeichnet.
- **Nur die Handy-Seite rechnet**: Die Uhr zeigt, was das Telefon zuletzt gesendet hat. Eine Tour,
  die erst später importiert wird (Health Connect), erscheint erst nach dem nächsten Senden.
- **Ein Stand, kein Verlauf**; eine neuere Telefon-App mit höherer Protokollversion lässt die Uhr beim
  letzten bekannten Stand, bis die Uhr-App aktualisiert ist.
- **Sprache**: Die Texte der Uhr folgen der Uhrsprache; Zahlen kommen vom Telefon.
- Ungeprüft ohne Gerätepaar: Zustellzeit des DataItems, Rendering der Kachel auf runder Anzeige,
  Komplikationsfelder verschiedener Zifferblätter, Verhalten über den Datumswechsel. `:wear` hat keine
  Tests, die Uhrseite ist nur kompiliert (Prüfpunkte: `docs/geraete-pruefliste.md`).

## Testbarkeit

- JVM (`:core`): Kodierung/Dekodierung, Zeilenwahl, Berechnung von `WochenStand` aus `RideFact`s
  (Muster: `core/src/test/.../WearProtocolTest.kt`, `WeeklyGoalTest.kt`), etwa Montag ohne Fahrt.
- Nur auf der Uhr: `DataClient`-Zustellung, Tile-Rendering und -Aktualisierung, Complication-Größen, Akku.
  `:wear` hat keine Testinfrastruktur; Geräteprüfung wie im Spike nötig (Watch Ultra, Wear OS 5).

## Risiken

- Akku: (a) praktisch null (eine Nachricht je Fahrt). (b) Tile nur bei Datenänderung neu anfordern
  (`requestUpdate`), keine Freshness-Intervalle; das Handy sendet höchstens ein paar Mal am Tag.
- Zustellung: Funk Handy ↔ Uhr reißt öfter ab (siehe `PhoneLink`-KDoc). `putDataItem` gleicht nach;
  Nachrichten (a) tun es nicht, die Anzeige ist dort nur „nice to have“, Verlust ist hinnehmbar.
- Play: Tiles und Complications brauchen keine Sonderfreigabe, aber die Uhr-App muss überhaupt über Play
  (Internal Testing) kommen, mit identischer Signatur (PEPK-Upload), sonst bricht der Data Layer
  (Spike-Doku f). Vorher `isMinifyEnabled = false` (`wear/build.gradle.kts`) als offene Schuld klären.
  Keine neue Berechtigung nötig.

## Empfehlung

(a) in der Minimalform und (b) als Kachel plus Komplikation sind gebaut. Offen bleibt die Geräteprüfung
(`docs/geraete-pruefliste.md`) und die Frage, ob die Uhr-App über Play verteilt wird; sonst erreicht (b)
nur das Entwicklungsgerät.
