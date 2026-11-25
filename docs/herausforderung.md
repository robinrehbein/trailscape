# Herausforderung teilen

Stand: 25.11.2026. Etappe 3 aus `docs/konzept-spiel-community.md` (Abschnitte 1.2,
4.2, 5.1): die erste Form von „gemeinsam fahren“, **ohne Server, ohne Konto, ohne
Drittdienst**. Eine Indoor-Fahrt wird als kleine Datei weitergegeben; wer sie
bekommt, fährt auf derselben Strecke gegen die Zeit des Absenders.

## 1 Ablauf

1. **Fahren.** Auf einer bekannten Strecke oder der Teststrecke, ab dem Start.
2. **Teilen.** Im Tourdetail der gespeicherten Tour: Teilen-Symbol →
   „Herausforderung teilen“ (die Auswertung führt mit „Tour ansehen“ dorthin und
   sagt das in einem Satz). Name wählen, Datum an oder aus, die **Vorschau**
   zeigt den Inhalt der Datei. „Teilen“ öffnet das Share-Sheet des Systems
   (Messenger, Mail, Dateien …). Damit das auch später noch geht, legt der
   Indoor-Ablauf beim Speichern einer teilbaren Tour einen Entwurf ab
   (`app/…/challenge/ChallengeDraftStore.kt`: Strecke, Version, Kurve, Tag –
   ohne Namen); die Zeile erscheint nur bei Touren mit Entwurf.
3. **Empfangen.** „Öffnen mit“ auf die Datei (Dateiendung `.trailscape-challenge`)
   oder in der App Indoor → Herausforderungen → „Datei wählen“.
   Es erscheint die Vorschau, erst „Speichern“ legt die Herausforderung ab;
   „Trailscape öffnen“ führt danach direkt auf Indoor → Herausforderungen.
4. **Fahren gegen.** Unter Indoor → Herausforderungen wählt „Gegen <Name>
   fahren“ Strecke und Mitfahrer und wechselt auf „Strecken“, wo „Fahren“
   startet. Fehlt die Strecke, steht dort „Strecke herunterladen“ und führt in
   den vorhandenen Katalog-Ablauf unter „Strecken“ (Voranschlag, Herunterladen,
   Fortsetzen).
5. **Ergebnis.** Die Auswertung nennt „x s vor/hinter <Name>“. Die eigene Tour
   als **Antwort** teilen (dieselbe Datei, andere Richtung) geht nach dem
   Speichern im Tourdetail: Teilen → „Als Antwort teilen“.
6. **Verwalten.** Die Liste unter Indoor → Herausforderungen zeigt alle
   gespeicherten Herausforderungen; das Papierkorb-Symbol löscht eine (mit
   Rückfrage).

Wo das in der Bedienstruktur hängt, steht in `docs/ux/umbau.md`.

Fragment-Links und eine eigene Domain gibt es **nicht**; siehe Abschnitt 8.

## 2 Datenschutz

Entscheidungen, bewusst und im Code an einer Stelle festgehalten:

| Frage | Entscheidung |
|---|---|
| Eigene Außenstrecken teilen? | **Nein.** `OWN_ROUTES_SHAREABLE = false`. Die Linie einer Außenfahrt verrät Wohnort und Gewohnheiten. Teilbar sind nur bekannte Strecken (Katalog) und die Teststrecke. Planungen und erzeugte Strecken sind ebenfalls nicht teilbar. |
| Was verlässt das Gerät? | Nur, was die Vorschau zeigt, und nur dorthin, wohin der Nutzer die Datei schickt. Trailscape lädt nichts hoch und sieht die Datei nicht. |
| Puls, Leistung, Trittfrequenz? | **Nie** in der Datei. Die Kurve enthält nur Zeit und Laufmeter. |
| Orte? | Nie. Auf bekannten Strecken gibt die Kennung den Ort ohnehin öffentlich vor; die Kurve enthält keine Koordinaten. |
| Zeiten? | Keine Uhrzeiten, keine Zeitstempel. Höchstens das **Datum** der Fahrt (`JJJJ-MM-TT`), abschaltbar. |
| Geräte, Konto, Nutzerkennung? | Nie. Der Dateiname enthält weder Namen noch Datum: `herausforderung-<Kennung>.trailscape-challenge`. |
| Name? | Frei wählbar, kurz, bereinigt. Er steht nur in der Datei. |
| Aussehen? | Optional das Avatar-Objekt der Garage, unverändert und undurchsichtig. Solange es keine Garage gibt, bleibt das Feld leer. |

Der Empfänger speichert die Datei lokal (`<filesDir>/challenges`). Sie geht nicht
in den Sync und nicht ins Backup. „Löschen“ entfernt sie vom Gerät; die Datei,
die man bekommen hat, liegt unberührt in der Nachrichten-App.

`PRIVACY.md` bleibt im Kern richtig („kein Trailscape-Server“). Sollte der Text
dort ergänzt werden, genügt ein Satz: Eine Herausforderung ist eine Datei, die du
selbst weitergibst; sie enthält Strecke, Zeitverlauf, einen frei gewählten Namen
und optional das Datum, nichts davon geht an Trailscape.

## 3 Dateiformat `*.trailscape-challenge`

JSON, UTF-8, ohne Byte-Order-Mark (gelesen wird sie trotzdem), kompakt
geschrieben, Schlüsselreihenfolge fest (gleiche Eingabe, gleiche Bytes).
MIME-Typ beim Teilen: `application/vnd.trailscape.challenge`. Höchstens
256 KB (`CHALLENGE_MAX_BYTES`); eine Stunde Fahrt sind rund 5 KB.

```json
{
  "format": "trailscape-challenge",
  "version": 1,
  "route": { "kind": "catalog", "id": "alpe-dhuez", "version": 2 },
  "name": "Anna",
  "date": "2026-11-20",
  "curve": { "stepS": 5, "totalS": 12.5, "dm": [0, 400, 800, 1000] },
  "avatar": { "v": 1 }
}
```

| Feld | Typ | Pflicht | Bedeutung und Grenzen |
|---|---|---|---|
| `format` | Text | ja | Muss `trailscape-challenge` sein. |
| `version` | ganze Zahl | ja | Format-Version, heute `1`. Höher als die der App → Ablehnung „neuere Version“ (`TOO_NEW`) mit der Zahl; kleiner als 1 → ungültig. |
| `route.kind` | Text | ja | `catalog` (bekannte Strecke), `testworld` (Teststrecke). `own` ist **reserviert** (eigene Strecke mit Geometrie, Version 2 oder später) und wird abgelehnt (`ROUTE_OWN_UNSUPPORTED`). Andere Werte: ungültig. |
| `route.id` | Text | bei `catalog` | Katalogkennung, wie `isSafeCatalogId`: `[a-z0-9][a-z0-9._-]{0,79}`, ohne `..`. |
| `route.version` | ganze Zahl | bei `catalog` | Welt-Version aus dem Katalog, 1…9999. |
| `name` | Text | nein | Anzeigename; beim Lesen **erneut bereinigt**: keine Steuer-, Format-, Richtungsumschalter- oder Privatzeichen, Leerraum zusammengezogen, höchstens 24 Codepunkte. Leer erlaubt (die App zeigt „Ohne Namen“). |
| `date` | Text | nein | Tag der Fahrt `JJJJ-MM-TT`, Jahr 2000…2200. Unlesbar → verworfen, die Fahrt bleibt gültig. |
| `curve.stepS` | ganze Zahl | ja | Abtastabstand in Sekunden, 1…60 (Schreiber: 5). |
| `curve.totalS` | Zahl | ja | Fahrzeit in Sekunden, 10…86 400. Der **letzte** Abtastwert gehört zu dieser Zeit; es gilt `(n−2)·stepS < totalS ≤ (n−1)·stepS`. |
| `curve.dm` | Liste ganzer Zahlen | ja | Laufmeter in **Dezimetern** zu den Zeiten `0, stepS, 2·stepS, … , totalS`; 2…20 000 Werte, erster Wert 0, **nicht fallend**, Gesamtstrecke mindestens 50 m. Stillstand (gleiche Werte) ist erlaubt. Pausen sind schon herausgekürzt: es zählt die Fahrzeit. |
| `avatar` | Objekt | nein | Undurchsichtig; höchstens 2048 Zeichen. Kein Objekt oder zu groß → verworfen, die Fahrt bleibt gültig. |
| weitere | beliebig | nein | **Werden ignoriert** (Vorwärtskompatibilität), auch in `route` und `curve`. |

**Prüfung beim Lesen** (`parseChallenge`, wirft nie): Größe, Schachtelungstiefe
(höchstens 16, gegen Stapelüberlauf), gültiges JSON mit Objekt an der Wurzel,
Kennung und Version, Strecke, Wertebereiche, Monotonie, Zeitkonsistenz von
`totalS` und Plausibilität: höchstens 40 m/s zwischen zwei Abtastwerten, mindestens
10 s und 50 m. Jede Verletzung ergibt ein `ChallengeReject` und eine verständliche
Meldung, keine Ausnahme und keinen Absturz.

**Versionen.** Eine App liest Dateien bis zu ihrer eigenen Format-Version. Neue,
nur ergänzende Felder erhöhen die Version nicht (sie werden von älteren Apps
ignoriert); Änderungen, ohne die eine Datei falsch gelesen würde, erhöhen sie.
Beispiele für Version 2: eigene Strecken mit Geometrie (nur mit ausdrücklicher
Zustimmung), mehrere Fahrten in einer Datei.

**Kennung.** `Challenge.id` = die ersten 16 Hexstellen von SHA-256 über die
Dateibytes, wie sie `toJson()` schreibt. Dieselbe Datei zweimal zu speichern
ergibt keinen zweiten Eintrag; sie ist zugleich der Dateiname im Speicher.

## 4 Zuordnung zur Strecke

Eine Kurve gilt für **genau eine Version** einer Strecke (Länge und Verlauf
können sich beim Neubau ändern). Auf dem Gerät gilt (`challengeRouteInfo`):

| Zustand | Bedeutung | In der App |
|---|---|---|
| `READY` | Teststrecke, oder die installierte Version ist gleich `route.version` | „Gegen <Name> fahren“ |
| `DOWNLOAD` | Strecke fehlt oder installiert ist eine **ältere** Version | „Strecke laden“ → Katalog-Ablauf (Laden bzw. Aktualisieren) |
| `MISMATCH` | installiert ist eine **neuere** Version | Hinweis, nicht fahrbar |
| `UNKNOWN` | Katalog und App kennen die Strecke nicht | Hinweis „App aktualisieren“ |

Nur `READY` wird Mitfahrer; `setGhost` und die Auswahl prüfen das erneut.
Gewählt werden kann die Herausforderung nur auf der Strecke, zu der sie gehört;
ein Streckenwechsel setzt den Mitfahrer zurück.

## 5 Im Code

| Teil | Datei |
|---|---|
| Format, Prüfung, Namen, Kennung | `core/…/challenge/Challenge.kt` |
| Geist aus der Kurve | `GhostSpec.Shared` (`IndoorSession.kt`), `ghostRunner`/`challengeCurveOf` (`IndoorGhost.kt`); die Sitzung fährt sie wie `PastRideGhost` |
| Speicher | `app/…/challenge/ChallengeStore.kt` |
| Zuordnung zur Strecke | `app/…/challenge/ChallengeRouteInfo.kt` |
| Teilen (Share-Sheet) | `app/…/challenge/ChallengeShare.kt`, `res/xml/file_paths.xml` (vorhandener Pfad) |
| „Öffnen mit“ | `app/…/challenge/ChallengeImportActivity.kt`, Manifest |
| Zustand und Ablauf | `ui/indoor/IndoorChallengeController.kt`, `IndoorViewModel.kt` (`IndoorGhostOption.Challenge`) |
| Oberfläche | `ui/indoor/IndoorChallengeSection.kt`, `IndoorChallengeUi.kt` |
| Texte | `strings_spiel3.xml` (de, en) |

Das HUD der Fahrt ist unverändert: Die Herausforderung läuft über denselben
Mitfahrer-Weg wie eine frühere eigene Fahrt (`GhostState`, Abstand, Name als
Beschriftung).

**Start mittendrin.** Eine Fahrt, die nicht am Anfang der Strecke begann, lässt
sich nicht teilen (die Kurve wird auf 0 m verankert). Das Teilen wird dann gar
nicht angeboten.

## 6 Tests

- `core/…/challenge/ChallengeTest.kt`: Schreiben und Lesen, Datei von Hand aus
  Version 1, feste Ausgabe, unbekannte Felder, neuere Version, fremdes Format,
  Müll und Tiefenangriff, Größe, eigene Strecke, kaputte Strecken und Kurven,
  Unplausibles, Namen, Datum, Avatar, Kennung, Zuordnung der Schlüssel.
- `core/…/indoor/ChallengeGhostTest.kt`: Fahrt → Kurve → Datei → Geist.
- `app/…/challenge/ChallengeStoreTest.kt`: Speicher (doppelt, beschädigt,
  fremder Name, Obergrenze, Löschen), Lesegrenze, Zuordnung `READY`/`DOWNLOAD`/
  `MISMATCH`/`UNKNOWN`.
- `app/…/ui/indoor/IndoorChallengeFlowTest.kt`: Import mit Vorschau, Ablehnung,
  Löschen, Mitfahrer nur auf der passenden Strecke, Fahrt, Ergebnis, Antwort
  teilen mit Datum an/aus.
- `app/…/ui/indoor/ChallengeScreenshotTest.kt` (`-Pscreenshots`): Segment
  „Herausforderungen“ (mit Liste, leer), Sicht „Strecken“ mit gewählter
  Herausforderung, Vorschau, Ergebnisse, Auswertung, Zeile im Blatt „Teilen“.

## 7 Offene Punkte

- „Öffnen mit“ hängt an Dateinamen und MIME-Typ, die Messenger unterschiedlich
  weitergeben (oft `application/octet-stream`; dann greift nur der
  Dateiname-Filter, und der ist bei `content://` anbieterabhängig). Der Weg
  „Datei wählen“ in der App geht immer. Mit Geräten und Messengern **nicht**
  getestet.
- Die Avatar-Quelle (`avatarJson`) ist als Parameter des `IndoorViewModel`
  angelegt und bleibt leer, bis die Garage (`claude/spiel-2-garage`) ihr JSON
  liefert; dann ist nur der Parameter zu verdrahten.
- Eine Herausforderung zeigt im 3D-Teil denselben Geist wie eine eigene frühere
  Fahrt; ein Avatar des Gegners wird noch nicht gezeichnet (Etappe 2 / Garage).
- Der Name des Absenders ist unbestätigt (jeder kann jeden Namen eintragen). Das
  ist bei Dateien zwischen Bekannten unkritisch und der Grund, ihn auf kurze,
  bereinigte Zeichen zu beschränken.

## 8 Was für Fragment-Links nötig wäre (nicht gebaut)

Ein Link `https://<domain>/c#<daten>` würde die Herausforderung anklickbar machen;
das Fragment schickt der Browser nicht an den Server.

1. **Domain und statische Seite** (etwa GitHub Pages): eine Seite, die das
   Fragment liest und die App öffnet bzw. zum Store leitet.
2. **Verifizierte App Links:** `/.well-known/assetlinks.json` auf der Domain mit
   dem Zertifikats-Fingerabdruck der Release-Signatur, `android:autoVerify="true"`
   im Manifest für `https://<domain>/c`. Heute fehlt die Domain (`docs/TODO.md`,
   Strava-Abschnitt); ein eigenes Schema `trailscape://` wird in vielen Messengern
   nicht angeklickt.
3. **Kodierung:** dieselbe JSON-Datei, komprimiert (Deflate) und Base64URL; für
   Katalogstrecken mit gröberer Kurve (alle 10–30 s), damit der Link kurz bleibt.
   Eine Stunde Fahrt: Datei rund 5 KB, Link nach Kompression und Base64 grob
   3–4 KB. Wie lange Links in Messengern abgeschnitten oder umgebrochen
   werden, ist ungetestet.
4. **Lesen in der App:** `MainActivity` oder die `ChallengeImportActivity` nimmt
   den `https`-Aufruf, holt das Fragment aus der URI und gibt es an
   `parseChallenge` — dieselbe Prüfung wie bei der Datei.
5. **Datenschutz:** Der Link verrät dem Messenger-Anbieter den Inhalt (er steht
   im Text der Nachricht); die Seite selbst sieht ihn nicht. In den Dialog
   gehört derselbe Hinweis wie beim Datei-Teilen.
6. **Pflege:** Die Domain wird zum Betriebsaufwand (Verlängerung, Rechtstexte,
   Impressum für die Seite).
