# Indoor — Fahrt im Hintergrund

Stand 25.11.2026. Bisher pausierte eine Indoor-Fahrt, sobald man die App
verließ oder das Display sperrte (Gründer-Entscheidung 11.2 Nr. 4 im
Prototyp). Jetzt läuft sie weiter: Ein Vordergrunddienst hält Trainer,
Steuerung, Sitzung und Aufzeichnung am Leben, die 3D-Ansicht pausiert und
setzt beim Zurückkehren an der richtigen Stelle fort. Wer das alte Verhalten
will, schaltet es unter *Einstellungen → Indoor → Hintergrund →
„Im Hintergrund weiterfahren"* ab (Vorgabe: an; Schlüssel
`trailscape.indoor.background_ride`, `"false"` = aus). Der Schalter wird beim
Start jeder Fahrt frisch gelesen: Die Einstellungsseite hat ein eigenes
ViewModel, das des Tabs „Indoor" lebt über Tab-Wechsel hinweg.

## Aufbau

| Teil | Datei | Aufgabe |
|---|---|---|
| Laufzeit | `indoor/IndoorRideRuntime.kt` | wie bisher Trainer, Steuerbefehle (SIM/ERG), Sitzung, 10-Hz-Takt. Neu: austauschbare Darstellung (`replaceRenderer`), laufende Sicherung (`journal`, `requestJournal`) |
| Halter | `indoor/background/IndoorRideHost.kt` | eine Instanz je Prozess; hält die laufende Fahrt unabhängig vom Bildschirm, kennt die angehängte Oberfläche und schließt eine Fahrt ohne Oberfläche ab (`IndoorRideCloser`) |
| Dienst | `indoor/background/IndoorRideService.kt` | Vordergrunddienst `connectedDevice`: Benachrichtigung, Aktionen, Teil-Wecksperre; beendet sich mit der Fahrt |
| Sicherung | `indoor/background/IndoorRideJournal.kt` | Punkte und Stand der Fahrt auf dem Datenträger; Wiederherstellung als Tour |
| Wiederherstellung | `indoor/background/IndoorRideRecovery.kt` | beim Prozessstart aus `TrailscapeApplication` |
| Regeln | `indoor/background/IndoorBackgroundLogic.kt` | Startbedingung des Dienstes, Inhalt der Benachrichtigung |
| Oberfläche | `ui/indoor/IndoorViewModel.kt`, `IndoorSettingsScreen.kt`, `MainActivity.kt` | Anmelden, Parken, Übernehmen; Schalter; Rückkehr in die Fahrt |

Die Sitzung selbst bleibt ein reiner Automat in `:core`. Einzige Ergänzung des
Vertrags: `IndoorSession.recordedPointsFrom(fromIndex)` mit Vorgabe
`emptyList()` (rückwärtsverträglich, Attrappen müssen nichts tun) — die bisher
aufgezeichneten Punkte als Kopie, damit die Laufzeit sie sichern kann.

## Ablauf

1. **Start der Fahrt** (`startRide`, App im Vordergrund): Die Sicherung wird
   angelegt (immer, auch mit Schalter aus — auch eine pausierte Fahrt kann der
   Prozesstod treffen). Mit Schalter an meldet das ViewModel die Fahrt beim
   Halter an, hängt sich als Oberfläche an und startet den Dienst. Kann der
   Dienst nicht starten (ab Android 14 ohne `BLUETOOTH_CONNECT`, z. B. eine
   Demo-Fahrt ohne Bluetooth-Freigabe; oder das System verweigert den
   Start), gilt für diese Fahrt das alte Verhalten.
2. **App verlassen, Display sperren** (`ON_STOP`): Mit laufendem Dienst wird
   nur sofort gesichert, die Fahrt läuft weiter; sonst pausiert sie wie bisher
   und setzt beim Zurückkehren selbst fort. Die 3D-Ansicht hält ihre
   Zeichenschleife mit dem Lebenszyklus an (`WorldSurface`, unverändert); die
   Laufzeit legt ihr weiter den Zustand hin (`setState` speichert nur).
   Zurück im Vordergrund zeichnet sie den neuesten Zustand; liegt die
   gezeigte Stelle mehr als 30 m daneben, springt sie (`MotionSmoother.JUMP_M`,
   Kamera ebenso). Am Renderer war dafür nichts zu ändern.
3. **Activity geht** (aus den Letzten Apps gewischt, vom System zerstört):
   `onCleared` gibt die Fahrt nicht frei, sondern **parkt** sie: Darstellung
   abgeben und zerstören, den Bildschirmzustand (Streckenname, Profil, Geist,
   Welt …) am Halter ablegen. Trainer, Sitzung und Takt laufen im Dienst
   weiter (`stopWithTask="false"`).
4. **Zurück in die App** — über die Benachrichtigung (Launcher-Intent mit
   `EXTRA_OPEN_INDOOR`) oder das App-Symbol (`MainActivity` sieht beim Start
   eine laufende Fahrt): Die Navigation öffnet den Indoor-Ablauf als
   gepushte Seite (`IndoorDestination.FLOW`). Das neue ViewModel übernimmt
   die geparkte Fahrt (`adoptBackgroundRide`, aufgerufen vom Tab „Indoor"
   und von der gepushten Seite, nie von „Einstellungen → Indoor"), baut eine neue Darstellung, lädt die Welt
   und hängt sie an die Laufzeit; der nächste Takt setzt sie an die aktuelle
   Stelle.
5. **Beenden**
   - in der App wie bisher (Halten auf „Beenden" → Auswertung → Speichern);
   - aus der Benachrichtigung mit angehängter Oberfläche: Die App beendet die
     Fahrt und zeigt die Auswertung; eine Meldung „Tippe hier, um die
     Auswertung zu sehen" führt hin;
   - aus der Benachrichtigung ohne Oberfläche: Der Halter beendet die Fahrt,
     merkt die Stelle und speichert sie direkt als Tour (dieselben Regeln wie
     die Auswertung, unter 60 s keine Tour); eine Meldung sagt, was geschah.
     Die Tourenliste lädt beim nächsten Öffnen nach (`IndoorRideHost.savedRides`
     → `AppViewModel.onIndoorRideSaved`).
6. **Pause/Weiter** aus der Benachrichtigung wirkt wie die Knöpfe im HUD.
   „Weiter" erscheint nur bei harter Pause; die Auto-Pause endet mit dem
   nächsten Tritt.

## Benachrichtigung

Kanal „Indoor-Fahrt" (`indoor_ride`, leise). Titel „Indoor · <Strecke>",
„Pausiert · …", „Bereit · …"; Text „185 W · 42:10 · 12,3 km" (Leistung als
3-s-Mittel, Fahrzeit wie im HUD inklusive Pausen, Distanz). Einmal je Sekunde
erneuert. Aktionen Pause/Weiter und Beenden; ein Tipp öffnet die Fahrt.
Ergebnis- und Wiederherstellungsmeldungen laufen über den Kanal
„Indoor-Fahrt beendet" (`indoor_ride_result`). Ohne `POST_NOTIFICATIONS`
läuft alles weiter, nur ohne sichtbare Meldung (der Dienst steht dann im
Task-Manager des Systems).

## Wecksperre

Die Fahrt ist eine Simulation mit 10 Takten je Sekunde, die dem Trainer
laufend Steigung oder Zielleistung schickt; schliefe die CPU bei gesperrtem
Display ein, stünde die virtuelle Fahrt (die Sitzung rechnet einen Abstand
über 2 s nicht als gefahren). Deshalb hält der Dienst eine
`PARTIAL_WAKE_LOCK` mit 10 Minuten Frist, die er jede Sekunde verlängert; sie
endet mit der Fahrt. Bildschirm-an während der Fahrt bleibt wie bisher
(`KeepScreenOn` im Vordergrund).

## Sicherung und Prozesstod

- **Was:** jeder aufgezeichnete Punkt (Zeit, Lage, Höhe, Puls, Leistung,
  Trittfrequenz) und der letzte Stand (Stelle in der Runde, Strecke, Fahrzeit,
  Anstieg), dazu ein Kopf mit Startzeit, Tourname, Modus und Strecke.
- **Wo:** `<filesDir>/indoor/ride-journal.txt` (vom Backup ausgenommen wie
  ganz `indoor/`).
- **Wie oft:** alle 5 s (`IndoorRideRuntime.JOURNAL_INTERVAL_MS`), bei jedem
  Phasenwechsel (Pause, Weiter, Auto-Pause), beim Gang in den Hintergrund
  und am Ende der Fahrt (der Rest samt angefangener Sekunde). Nur anhängen,
  je Schreiben ein `fsync`. Höchstens die letzten 5 s gehen verloren.
- **Wann gelöscht:** nach dem Speichern, beim Verwerfen, bei „Neue Fahrt"
  ohne Speichern und wenn die Fahrt unter einer Minute blieb.
- **Wiederaufnahme oder gespeicherter Teil?** Gespeicherter Teil. Eine
  laufende Sitzung samt Trainerverbindung lässt sich nach einem Prozesstod
  nicht verlustfrei fortsetzen (Zustand der Physik, ERG-Abschnitt, Geist,
  Gang). Beim nächsten Start der App wird die Sicherung zur Tour
  „Indoor · <Strecke> (wiederhergestellt)" (Fahrzeit, Strecke, Anstieg aus dem
  letzten Stand; unter 60 s keine Tour) und die Stelle wird gemerkt — die
  nächste Fahrt auf dieser Strecke bietet „Weiter bei km …" an. Eine Meldung
  „Indoor-Fahrt wiederhergestellt" sagt es.
- Endet eine Fahrt ungeplant ohne Prozesstod (Schalter aus oder Dienst nicht
  gestartet, und die Activity geht), bleibt die Sicherung ebenso liegen und
  wird beim nächsten Prozessstart zur Tour. Beginnt vorher eine neue Fahrt,
  legt sie die alte Sicherung beiseite (`ride-journal-<Zeit>.txt`) statt sie
  zu überschreiben; die Wiederherstellung nimmt beiseitegelegte immer mit.
- Der Dienst kehrt mit `START_NOT_STICKY` nicht von selbst zurück: Nach einem
  Prozesstod gibt es keine Fahrt mehr, die er fortsetzen könnte.

## Android 14+ und Google Play

- **Manifest:** `<service android:foregroundServiceType="connectedDevice">`
  und `FOREGROUND_SERVICE_CONNECTED_DEVICE` (normale Berechtigung, kein
  Dialog). `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` und `WAKE_LOCK` waren
  schon deklariert.
- **Voraussetzung zur Laufzeit (ab API 34):** Für `connectedDevice` muss eine
  der vom System genannten Berechtigungen erteilt sein; Trailscape nutzt
  `BLUETOOTH_CONNECT`, die jeder hat, der einen Trainer gekoppelt hat
  (`IndoorBackgroundLogic.canStartService`). Ohne sie startet der Dienst
  nicht, und die Fahrt pausiert beim Verlassen der App wie früher.
- **Start nur aus dem Vordergrund:** Der Dienst startet beim Start der Fahrt
  (Nutzeraktion in der sichtbaren App) — Android 12+ verbietet den Start aus
  dem Hintergrund. `connectedDevice` hat keine Laufzeitbegrenzung (anders als
  `dataSync` ab Android 15).
- **Play Console:** Für jeden Vordergrunddienst-Typ verlangt Play die
  Erklärung unter *App-Inhalte → Berechtigungen für Dienste im Vordergrund*:
  Typ „Verbundenes Gerät", Beschreibung des Nutzens (Verbindung zum
  Bluetooth-Rollentrainer halten und ihn während einer Indoor-Fahrt steuern,
  auch bei gesperrtem Display), sichtbare Auswirkung für den Nutzer
  (laufende Benachrichtigung mit Leistung, Zeit, Distanz, Pause und Beenden)
  und ein **Video**, das die Funktion zeigt (Fahrt starten, App verlassen,
  Benachrichtigung, zurückkehren). Ohne vollständige Erklärung lehnt Play
  Updates ab. Der vorhandene `location`-Dienst der Aufzeichnung und der
  `dataSync`-Typ des Kachel-Downloads sind davon unabhängig.

## Grenzen und Annahmen

- Doze: Ein Vordergrunddienst mit Teil-Wecksperre sollte auch bei liegendem
  Gerät und gesperrtem Display weiterlaufen; manche Hersteller-Energiesparer
  beenden Dienste trotzdem. Dann greift die Sicherung. Eine
  Ausnahme von der Akkuoptimierung fragt der Indoor-Teil nicht eigens an.
- Die Oberfläche hält während der Fahrt genau ein ViewModel; ein zweites
  übernimmt erst, wenn das erste geparkt hat.
- Schaltet man „Im Hintergrund weiterfahren" während einer Fahrt um, gilt das
  ab der nächsten.

## Tests

- `IndoorSessionRecordedPointsTest` (`:core`): stückweise Abfrage ergibt
  genau die Aufzeichnung.
- `IndoorRideJournalTest`: Hin und zurück, abgerissene Zeile, Wiederherstellung
  (Tour, Name, Kennzahlen, gemerkte Stelle), zu kurz, Speicherfehler,
  Beiseitelegen.
- `IndoorRideRuntimeBackgroundTest`: Sicherung alle 5 s, bei Pause, auf Wunsch
  und am Ende ohne Dopplung; Darstellung abgeben und neu anhängen.
- `IndoorRideHostTest`: Beenden aus der Benachrichtigung mit und ohne
  Oberfläche.
- `IndoorBackgroundFlowTest` (Robolectric): Vorgabe und Merken des Schalters;
  Hintergrund pausiert nicht; die Fahrt überlebt das Aufräumen des
  ViewModels, ein neues übernimmt sie samt neuer Darstellung an der richtigen
  Stelle; Beenden ohne Oberfläche speichert; Schalter aus und gescheiterter
  Dienst verhalten sich wie früher.
- `IndoorBackgroundLogicTest`: Startbedingung ab Android 14, Inhalt der
  Benachrichtigung je Phase, deutsche und englische Texte.
- `IndoorBackgroundScreenshotTest` (`-Pscreenshots`): Einstellungen mit
  Schalter an/aus und englisch (`idee05-0*.png`).

## Auf dem Gerät zu prüfen

- [ ] Fahrt mit KICKR starten, App verlassen (Home): Benachrichtigung zeigt
      Leistung, Zeit, Distanz; der Trainer bekommt weiter Steigung (spürbar am
      Widerstand an einem Anstieg); zurück über die Benachrichtigung — die
      3D-Ansicht steht an der richtigen Stelle und läuft flüssig weiter.
- [ ] Display sperren, 10 Minuten fahren, entsperren: Strecke und Zeit sind
      weitergelaufen (keine Sprünge in der Aufzeichnung, Distanz plausibel).
- [ ] 30 Minuten und länger bei gesperrtem Display, Gerät liegt still (Doze):
      läuft die Fahrt durch? Auf Samsung/Xiaomi/OnePlus mit
      Hersteller-Energiesparer gesondert.
- [ ] Pause/Weiter und Beenden aus der Benachrichtigung, mit offener App und
      nach dem Wegwischen aus den Letzten Apps; „Gespeichert"-Meldung, Tour in
      der Liste.
- [ ] Wegwischen aus den Letzten Apps, dann App-Symbol: führt direkt in die
      laufende Fahrt.
- [ ] Prozesstod während der Fahrt (Entwickleroptionen „Hintergrundprozesse:
      keine" bzw. `adb shell am kill` / „Beenden erzwingen"): nach dem
      nächsten Start „Indoor-Fahrt wiederhergestellt", Tour mit Zusatz
      „(wiederhergestellt)", nächste Fahrt auf derselben Strecke bietet
      „Weiter bei km …".
- [ ] Schalter aus: Verlassen der App pausiert, Zurückkehren setzt fort, keine
      Benachrichtigung.
- [ ] Android 14+: Demo-Fahrt ohne Bluetooth-Freigabe — kein Absturz, die
      Fahrt pausiert beim Verlassen wie früher.
- [ ] Benachrichtigungen abgelehnt (Android 13+): Fahrt läuft im Hintergrund
      trotzdem weiter.
- [ ] Zwift Click und Pulsgurt bleiben im Hintergrund verbunden.
- [ ] Akkuverbrauch einer Stunde Fahrt mit gesperrtem Display gegenüber einer
      Stunde mit Display an.
- [ ] Englisch: Benachrichtigung und Einstellung.
