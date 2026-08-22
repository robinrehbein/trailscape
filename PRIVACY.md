# Datenschutzerklärung — Trailscape

**Stand: 19. August 2026** · gilt für die Android-App Trailscape
(`io.github.robinrehbein.trailscape`), verteilt als APK über die
[GitHub-Releases](https://github.com/robinrehbein/trailscape/releases) dieses
Projekts.

Diese Erklärung beschreibt die App so, wie sie tatsächlich gebaut ist. Jede
Aussage darin lässt sich am Quellcode nachprüfen — die Datei-, Klassen- und
Servernamen sind bewusst mit angegeben.

---

## Kurzfassung

- **Kein Konto, keine Registrierung, keine Anmeldung.** Trailscape kennt keine
  Nutzerkennung.
- **Kein Analytics, kein Tracking, keine Werbung, keine Werbe-ID, keine
  Crash-Telemetrie.** Es gibt kein SDK von Google Analytics, Firebase,
  Crashlytics, Sentry, Meta oder ähnlichem im Projekt.
- **Alle deine Daten liegen auf dem Gerät** — Touren als JSON-Dateien im
  privaten App-Verzeichnis, Einstellungen in den SharedPreferences. Es gibt
  keinen Trailscape-Server, auf dem etwas von dir liegt. Der Entwickler hat
  keinen Zugriff auf deine Daten.
- **Gesundheitsdaten werden nur gelesen**, aus Health Connect, und
  ausschließlich auf dem Gerät verarbeitet. Sie werden nirgendwohin übertragen
  und nicht nach Health Connect zurückgeschrieben.
- **Anfragen ins Netz gehen an die Dienste, die eine konkrete Aktion braucht**
  (Kartenkacheln, Routing, Ortssuche, Kachel-Downloads, auf Tipp abgerufene
  Wettervorhersage, optional dein eigener Sync-Server) — plus **eine**
  Ausnahme: eine stille Update-Prüfung bei GitHub, höchstens einmal am Tag,
  abschaltbar unter *Mehr → Über*. Diese Dienste sehen dabei deine
  IP-Adresse — siehe unten, Abschnitt „Was das Gerät nach außen sendet".

---

## 1. Wer ist verantwortlich?

Trailscape ist ein privates Open-Source-Projekt von Robin Rehbein. Es gibt
keinen kommerziellen Betrieb, keine Auftragsverarbeiter und keinen
Server-Dienst hinter der App.

Kontakt für Datenschutzfragen und Fehlermeldungen:
<https://github.com/robinrehbein/trailscape/issues>

---

## 2. Was auf dem Gerät gespeichert wird

Alles hier Genannte liegt im **privaten Speicherbereich der App**. Andere Apps
können es nicht lesen; beim Deinstallieren verschwindet es vollständig.

| Was | Wo | Inhalt |
|---|---|---|
| Touren | `<filesDir>/rides/<id>.json` | Zeitpunkt, Name, GPS-Punkte (Position, Höhe, Zeit, ggf. Puls), berechnete Statistik |
| Laufende Aufzeichnung | `<filesDir>/recording/active.jsonl` | GPS-Punkte der gerade laufenden Tour, damit ein Absturz sie nicht verliert. Wird nach dem Speichern der Tour gelöscht |
| Trainingsprofil und -plan | SharedPreferences (`trailscape.*`) | Alter, Geschlecht, Gewicht, FTP/Schwellenwerte, Zielsetzung, Wochenplan |
| Vitalhistorie | SharedPreferences (`trailscape.vitals.v1`) | Tageswerte der letzten 400 Tage: Ruhepuls, HRV, Schlafstunden, VO₂max — lokal gehalten, weil Health Connect Daten nach 30 Tagen löscht und die Baselines längere Fenster brauchen (`core/…/VitalsHistory.kt`) |
| Kartenstil-Auswahl | SharedPreferences (`trailscape.mapstyle`) | ID des gewählten Kachelstils |
| Sync-Einstellungen (optional) | SharedPreferences (`trailscape.sync`) | Adresse **deines** Sync-Servers und dein Zugangstoken — im Klartext im privaten App-Speicher |
| Health-Sync-Stand | SharedPreferences (`trailscape.healthsync`) | Zeitstempel des letzten Imports, damit nichts doppelt importiert wird |
| Offline-Karten | `<filesDir>/mbgl-offline.db` | heruntergeladene Kartenkacheln der von dir gewählten Regionen |
| Absturzberichte | `<filesDir>/crash/last-crash.txt` | siehe Abschnitt 6 |
| Zum Teilen erzeugte GPX-Dateien | `<cacheDir>/geteilte-touren/` | nur der gerade geteilte Export; älter als eine Stunde wird automatisch gelöscht |

Gesundheitsdaten aus Health Connect (Puls, Ruhepuls, HRV, Schlaf, VO₂max)
werden für die Auswertung verwendet und, soweit sie zu einer Tour gehören,
mit dieser Tour gespeichert. Auch sie verlassen das Gerät nicht — es sei denn,
du hast den optionalen Sync mit deinem eigenen Server eingerichtet.

---

## 3. Berechtigungen und wofür sie gebraucht werden

| Berechtigung | Wofür |
|---|---|
| Standort (genau/ungefähr) | Aufzeichnung der Tour und Anzeige der eigenen Position auf der Karte. Ohne laufende Aufzeichnung fragt die App keine Positionen ab. „Während der Nutzung erlauben" genügt: Die Aufzeichnung läuft als Vordergrunddienst weiter, auch bei gesperrtem Display — eine Hintergrund-Standortberechtigung (`ACCESS_BACKGROUND_LOCATION`) fragt die App nicht an und deklariert sie auch nicht |
| Vordergrunddienst (Standort) | damit die Aufzeichnung bei gesperrtem Display und nach dem Wegwischen der App weiterläuft |
| Benachrichtigungen | die Anzeige der laufenden Aufzeichnung |
| Internet | Kartenkacheln, Routing, Ortssuche, optionaler Sync |
| Health Connect: Training, Trainingsrouten, Herzfrequenz, Ruhepuls, HRV, Schlaf, Distanz, Kalorien, VO₂max | **nur lesend**, für den Import von Trainings und die Erholungs-/Formberechnung |

Trailscape fragt **keine** Berechtigung für Kontakte, Kamera, Mikrofon,
Telefonstatus, Aktivitätserkennung oder Werbe-ID an.

---

## 4. Was das Gerät nach außen sendet

Anfragen entstehen als Folge einer Aktion — Karte anzeigen, Route berechnen,
Ort suchen, Kacheln herunterladen, synchronisieren — mit **einer** Ausnahme:
der täglichen Update-Prüfung beim App-Start (letzte Tabellenzeile), die sich
abschalten lässt. Bei jeder dieser Anfragen sieht der jeweilige Betreiber
technisch bedingt deine **IP-Adresse** und den Zeitpunkt; welche Daten darüber
hinaus mitgehen, steht in der Tabelle. Für die Verarbeitung dort gelten die
Datenschutzbestimmungen des jeweiligen Betreibers, nicht diese Erklärung.

| Empfänger | Wann | Was mitgeht |
|---|---|---|
| Der gewählte **Kachel-Server** — je nach Kartenstil `basemaps.cartocdn.com` (CARTO), `tile-cyclosm.openstreetmap.fr`, `tile.openstreetmap.org`, `tile.opentopomap.org` oder `server.arcgisonline.com` (Esri) | sobald die Karte einen Ausschnitt zeichnet oder eine Offline-Region geladen wird | Kachelkoordinaten (`z/x/y`). Daraus ergibt sich, **welchen Kartenausschnitt du dir ansiehst** — zusammen mit der IP-Adresse also ein Hinweis darauf, wo du dich aufhältst oder hin willst |
| **brouter.de** | wenn du eine Route berechnen lässt | die Koordinaten deiner Wegpunkte und das gewählte Routing-Profil; beim ersten Mal zusätzlich das Profil selbst |
| **brouter.de** | wenn du unter *Mehr → Offline-Routing* Routing-Kacheln herunterlädst oder aktualisierst (`https://brouter.de/brouter/segments4/…`, siehe `app/…/routing/SegmentDownloader.kt` und `core/…/RoutingSegments.kt`) | der Name der gewählten **5°×5°-Kachel** (z. B. `E10_N45.rd5`) — daraus ergibt sich die grobe Region, für die du Routing willst, typischerweise also deine Wohn- oder Urlaubsgegend. Bei einer Delta-Aktualisierung steht zusätzlich die **MD5-Prüfsumme deines lokalen Kachelstands** in der URL; sie verrät dem Server, welchen Tagesstand du zuletzt geladen hattest, aber nichts über deine Touren |
| **nominatim.openstreetmap.org** | wenn du in der Routenplanung nach einem Ort suchst | dein **Suchtext** und ein App-Kennzeichen im User-Agent (`Trailscape/1.0 (github.com/robinrehbein/trailscape)`, von den Nominatim-Nutzungsrichtlinien verlangt) |
| **api.open-meteo.com** (Wettervorhersage) | wenn du auf der Startseite „Fenster suchen" oder „Aktualisieren" tippst | die **Koordinaten des Starts deiner letzten Tour** (Breite/Länge) für die stündliche 2-Tages-Vorhersage — daraus ergibt sich deine ungefähre Wohn- oder Fahrgegend (`core/…/Weather.kt`). Ohne gespeicherte Tour gibt es keine Anfrage, und nichts geht automatisch raus: Das Fenster wird nur auf deinen Tipp hin geladen |
| **Dein eigener Sync-Server** (nur wenn du in *Mehr → Sync* eine Adresse hinterlegt hast) | beim Synchronisieren | deine Touren inklusive GPS-Punkten und dein Zugangstoken (`Authorization: Bearer …`), an genau die Adresse, die du eingetragen hast — an niemanden sonst |
| **github.com** | nur wenn du auf „Auf GitHub melden" tippst | der Bericht, den du vorher im Dialog gesehen hast. Abgeschickt wird das Formular erst von dir, im Browser |
| **api.github.com** (Update-Prüfung) | beim App-Start, höchstens einmal in 24 Stunden — **abschaltbar** unter *Mehr → Über → „Täglich still nach Updates suchen"* | eine GET-Anfrage auf die Release-Liste dieses Projekts (`/repos/robinrehbein/trailscape/releases`). Mitgesendet werden nur die technisch nötigen Header, darunter der User-Agent `Trailscape-Android` — GitHub erfährt also IP-Adresse, Zeitpunkt und dass irgendein Gerät Trailscape benutzt, aber keine Version, keine Geräte- oder Nutzerkennung und keine sonstigen Daten (`app/…/update/UpdateChecker.kt`, `UpdateLogic.kt`) |

Zu den Kartenkacheln: Wer nur ungern seinen Kartenausschnitt an einen Anbieter
gibt, lädt die Region einmal als **Offline-Karte** herunter (*Mehr →
Offline-Karten*) — danach kommen die Kacheln aus dem Gerät.

Zur Update-Prüfung: Die App wird als APK per Sideload verteilt, kein Store
aktualisiert sie — ohne diese Prüfung erführe niemand von einer neuen Version
(und damit auch nicht von Fehlerkorrekturen). Sie ist die einzige Anfrage, die
nicht unmittelbar aus einer Nutzeraktion folgt. Wer sie abschaltet, kann
jederzeit von Hand prüfen (*Mehr → Über → „Nach Updates suchen"*); die App
lädt und installiert dabei in keinem Fall selbst etwas, der Download läuft
über die Release-Seite im Browser.

Es gibt keine weiteren Netzwerkverbindungen. Insbesondere kein
„Nach-Hause-Telefonieren", keine Absturz- oder Nutzungsstatistik.

---

## 5. Health Connect

- Trailscape fragt **ausschließlich Leserechte** an. In Health Connect gibt es
  keine Schreibrechte für diese App, und der Code enthält keinen Schreibpfad.
- Gelesen werden Trainingseinheiten samt Route, Herzfrequenz, Ruhepuls,
  Herzfrequenzvariabilität (rMSSD), Schlaf, Distanz, verbrannte Kalorien und
  VO₂max — nur für Zeiträume, die für einen Import in Frage kommen.
- Die Daten werden auf dem Gerät ausgewertet (Trainingslast, Fitness,
  Erholung) und dort gespeichert. Sie werden **nicht** übertragen, nicht
  weitergegeben und nicht ausgewertet, um dir etwas zu verkaufen.
- Du kannst die Freigabe in Health Connect jederzeit widerrufen. Die App
  funktioniert dann weiter, nur ohne die importierten Werte.
- Die Verbindung ist optional. Ohne sie zeichnet Trailscape ganz normal per
  GPS auf.

---

## 6. Absturzberichte

Stürzt die App ab, schreibt sie einen Bericht in das private App-Verzeichnis
(`<filesDir>/crash/last-crash.txt`) und **sonst nichts**. Der Bericht enthält
ausschließlich Technik: Zeitpunkt, App-Version, Android-Version, Gerätemodell,
Speicherstand und den Stacktrace. Keine Standortpunkte, keine Touren, keine
Gesundheitsdaten, keine Zugangsdaten.

Beim nächsten Start fragt die App einmal nach. Du kannst den Bericht
**ansehen** (vollständig, markierbar), auf GitHub melden (öffnet ein
vorbefülltes Formular im Browser — abgeschickt wird es von dir), per
Teilen-Menü weitergeben oder verwerfen (löscht die Datei). **Automatisch
gesendet wird nichts.** Wer nichts tut, behält den Bericht auf dem Gerät.

Dasselbe gilt für *Mehr → Über → Problem melden*: Der Text (App-Version,
Gerät, Android-Version und, nur wenn du es ankreuzt, die Diagnose des letzten
Health-Syncs) wird dir vorher gezeigt, und du entscheidest, ob und wohin er
geht.

---

## 7. Android-Backup

Die App erlaubt Androids **Auto Backup** (`android:allowBackup="true"`) —
aber nur für die **Touren**. Das bedeutet: Android kann die Tourdateien unter
`rides/` im Rahmen des Systembackups in dein Google-Konto sichern und auf
einem neuen Gerät wiederherstellen. Das ist absichtlich so — es gibt keine
Trailscape-Cloud, aus der sich bei einem Gerätewechsel etwas nachladen ließe.

Was du dazu wissen solltest:

- Das Backup läuft über **Google**, nicht über den Entwickler. Ob es
  Ende-zu-Ende-verschlüsselt ist, hängt von deinem Gerät und deiner
  Android-Version ab (bei den meisten aktuellen Geräten mit Bildschirmsperre:
  ja, mit einem aus der Sperre abgeleiteten Schlüssel).
- **Ausgeschlossen sind die gesamten SharedPreferences**
  (`trailscape_prefs.xml`) — und damit das Sync-Zugangstoken (das dort im
  Klartext liegt) und die Vitalhistorie mit 400 Tagen
  Ruhepuls/HRV/Schlaf/VO₂max. Beides bleibt auf dem Gerät und geht weder ins
  Google-Backup noch in den direkten Gerätewechsel-Transfer. Die Kehrseite,
  bewusst in Kauf genommen: Einstellungen und Trainingsprofil wandern beim
  Gerätewechsel **nicht** automatisch mit — dafür ist der manuelle
  Backup-Export da (*Mehr → Daten & Backup*).
- Ebenfalls ausgenommen sind die laufende Aufzeichnung, die heruntergeladenen
  Offline-Karten und -Routing-Kacheln und die Absturzberichte (siehe
  `res/xml/backup_rules.xml` und `res/xml/data_extraction_rules.xml`).
  Absturzberichte stehen ausdrücklich auf dieser Ausschlussliste, damit sie
  das Gerät wirklich nur dann verlassen, wenn du sie selbst verschickst.
- Du kannst das Backup vollständig abschalten: in den Android-Einstellungen
  unter *System → Sicherung* (Bezeichnung je nach Hersteller). Dann bleibt
  alles ausschließlich lokal — dann aber bitte an den manuellen
  Backup-Export denken (*Mehr → Daten & Backup*).
- Der **manuelle Backup-Export** schreibt eine unverschlüsselte
  Klartext-JSON-Datei mit allen Touren (jeder GPS-Punkt, mit Zeitstempeln und
  ggf. Puls) und dem Trainingsprofil (Alter, Geschlecht, Gewicht, Ruhepuls,
  LTHR, FTP). Wo sie liegt und wem du sie gibst, entscheidest du — die App
  weist an der Export-Stelle darauf hin. Bei Weitergabe über Mail oder Cloud
  entsprechend vertraulich behandeln.

---

## 8. Deine Rechte und wie du sie ausübst

Da keine Daten bei einem Anbieter liegen, brauchst du für nichts davon eine
Anfrage an jemanden:

- **Auskunft und Datenübertragbarkeit** — *Mehr → Daten & Backup → Backup
  exportieren* schreibt alle Touren und das Trainingsprofil in eine lesbare
  JSON-Datei. Einzelne Touren lassen sich zusätzlich als GPX teilen.
- **Löschung** — einzelne Touren in der Tourenliste löschen; alles auf einmal
  über die Android-Einstellungen (*Apps → Trailscape → Speicher → Daten
  löschen*) oder durch Deinstallation der App. Damit ist auch der letzte
  Absturzbericht weg. Ist der optionale Selfhost-Sync eingerichtet, wird das
  Löschen einer Tour beim nächsten Abgleich an **deinen** Server weitergegeben
  (als Löschvermerk, sog. Tombstone), damit die Tour nicht beim übernächsten
  Sync von dort zurückkehrt — der Server ist deiner, gelöscht wird also
  ausschließlich bei dir.
- **Widerruf** — die Health-Connect-Freigabe in Health Connect, die
  Standortfreigabe in den Android-Einstellungen, den Sync durch Leeren der
  Serveradresse in *Mehr → Sync*.
- **Berichtigung** — Touren lassen sich umbenennen, das Trainingsprofil
  jederzeit ändern.

Rechte gegenüber den in Abschnitt 4 genannten Dritten (CARTO, OpenStreetMap
Foundation, OpenTopoMap, Esri, brouter.de, GitHub, Google) machst du direkt
dort geltend; wir geben ihnen nichts über dich weiter, was über die dort
genannten Anfragen hinausgeht.

---

## 9. Kinder

Die App richtet sich nicht an Kinder und erhebt bewusst keine Daten, die eine
Person identifizieren.

---

## 10. Änderungen

Änderungen an dieser Erklärung erscheinen in der Versionsgeschichte dieser
Datei im Repository. Wesentliche Änderungen werden in den Release-Notizen
erwähnt.
