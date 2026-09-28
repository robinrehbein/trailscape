# Ideen-Liste

Festgehaltene Ideen, die bewusst noch nicht umgesetzt sind.

## Vor dem öffentlichen Release (Blocker)

- **OpenFreeMap: Offline-Download bestätigen lassen.** Die Nutzungsbedingungen
  (https://openfreemap.org/tos/, Stand 09.09.2026) untersagen „collect data …
  in automated ways without permission“. Ein vom Nutzer angestoßener,
  begrenzter Ausschnitt ist nach unserer Auslegung keine automatisierte
  Datensammlung, eine Erlaubnis ist das aber nicht. Für internes Testing
  vertretbar. Vor dem Release schriftlich bei info@openfreemap.org bestätigen
  lassen und Datum/Wortlaut im KDoc an `mapStyles`
  (`app/.../ui/MapStyles.kt`) vermerken. Ohne Bestätigung: beim Vektor-Stil
  `offlineAllowed = false` setzen und eine eigene Kachelquelle (z. B.
  selbst gehostete PMTiles) aushandeln bzw. aufbauen.

- **Bluetooth-Sensoren auf echter Hardware testen.** Die GATT-Anbindung
  (`app/.../sensors/`) ist nur ueber die reine Logik in `:core` getestet.
  Vor dem Release mit mindestens einem Pulsgurt (z. B. Polar H10), einem
  Leistungsmesser (z. B. Favero Assioma) und einem CSC-Sensor pruefen, je auf
  einem Geraet unter und ab Android 12: Koppeln, Verbinden bei
  Aufzeichnungsstart, Neuversuch nach Abbruch (Status 133), doppelte Werte ab
  API 33, Sensoren ohne Dienst-UUID in der Werbung. Dazu klaeren, ob Play fuer
  den RecordingService den Diensttyp `connectedDevice` verlangt, und die
  Angaben zu „Gesundheit und Fitness" im Datensicherheitsformular pruefen.

- **Strava-Upload freischalten (Code ist fertig, Gründer-Aufgaben offen).**
  Der optionale Upload (`app/.../strava/`, `core/.../StravaClient.kt`) ist nur
  in Builds mit Zugangsdaten sichtbar. Dafür fehlt noch:
  1. Strava-API-App unter https://www.strava.com/settings/api anlegen (Name,
     Website, Icon). „Authorization Callback Domain“ vermutlich
     `strava-callback` (Host von `trailscape://strava-callback`) — auf dem
     Gerät prüfen, sonst meldet Strava „invalid redirect_uri“.
  2. Client-ID und Client-Secret als GitHub-Secrets `STRAVA_CLIENT_ID` und
     `STRAVA_CLIENT_SECRET` hinterlegen, lokal als `strava.clientId`/
     `strava.clientSecret` in `~/.gradle/gradle.properties`. Nie ins Repo.
  3. Bewusst entscheiden, ob das in der APK auslesbare Secret tragbar ist.
     Wer es hat, kann sich als Trailscape ausgeben und das App-Ratenlimit
     verbrauchen, aber kein fremdes Konto übernehmen. Härtere Alternative
     (nicht gebaut): Token-Tausch und Refresh über einen kleinen eigenen
     Endpunkt (z. B. im Sync-Server), der das Secret hält; die App schickte
     dann nur Code bzw. Refresh-Token dorthin. Das brächte allerdings einen
     Trailscape-Server in den Datenfluss und änderte `PRIVACY.md`.
  4. Bei Strava den Review für mehr als einen Athleten beantragen (neue Apps:
     Kapazität 1 = nur der Entwickler) und ggf. höhere Ratenlimits
     (Standard 200 Anfragen/15 min, 2000/Tag für die ganze App).
  5. Markenrichtlinien von Strava: Der offizielle orange „Connect with
     Strava“-Knopf (`res/drawable/btn_strava_connect_with_orange.xml`) und
     ein fetter „View on Strava“-Link sind eingebaut. Offen ist nur noch die
     Frage, ob Strava beim Review die deutsche Beschriftung „Auf Strava
     ansehen“ akzeptiert. Die Richtlinie nennt wörtlich „View on Strava“.
  6. Google-Play-Datensicherheit: nutzerinitiierte Weitergabe von Standort-
     und Fitnessdaten an Strava angeben.
  7. Auf dem Gerät prüfen: Verbinden, Ablehnen, Häkchen „Aktivitäten
     hochladen“ abwählen, Upload von Hand, Auto-Upload im Flugmodus, Duplikat,
     Trennen, Sportart auf Strava = Radfahrt (sonst nach dem Upload
     `PUT /activities/{id}` mit `sport_type=Ride`).
  Später denkbar: verifizierte App Links statt `trailscape://` (braucht eine
  eigene Domain mit `/.well-known/assetlinks.json`), FIT statt GPX.

## Ideen

- **Englisch vor dem Release gegenlesen.** Alle Bereiche liegen jetzt als
  Ressourcen in `values/` und `values-en/` (Regeln in `docs/i18n.md`). Vor
  dem Release einmal die App komplett auf Englisch durchklicken
  (Sprachwahl unter *Mehr → Sprache*), vor allem die Sprachansagen mit einer
  englischen TTS-Stimme und die nachgezogenen Texte der Sensoren und von
  Strava (`strings_ble_sensors.xml`, `strings_strava.xml`).

- **Trainingspläne als teilbare Dateien („Plan-Rezepte")** — Trainingsplan und
  strukturierte Einheiten als lesbare JSON-Datei exportieren und importieren,
  damit Trainer, Vereine und Foren Pläne ohne Plattform und ohne Konto
  tauschen können. Baut auf dem vorhandenen formatstabilen JSON
  (`core/.../JsonSupport.kt`) und dem Datei-Ein-/Ausgang der Backup-Karte auf.
- **eFTP-Text bei Leistungsmesser** — `TrainingInsights` beschriftet die FTP
  weiter als „aus der GPS-Leistungsschätzung (±15–25 %)“, auch wenn das beste
  20-min-Mittel aus gemessener Leistung stammt. Herkunft je Tour mitfuehren
  und den Text dann unterscheiden.
- **Radtempo vom CSC-Sensor** — `radTempoKmh` (`core/.../BleSensorLogik.kt`)
  ist gerechnet und getestet, aber nicht verdrahtet. Mit einem Radumfang im
  Profil koennte es GPS im Tunnel oder auf der Rolle ersetzen.
- **RR-Intervalle speichern** — der Puls-Parser liest sie schon; als eigenes
  Feld in der Tour gaeben sie HRV waehrend der Fahrt her.
