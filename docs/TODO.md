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

- **Play-Build: Update-Prüfung.** Der Build trägt seit #65
  `de.robinrehbein.trailscape` (`app/build.gradle.kts`). Offen ist noch, für
  Play-Installationen die GitHub-Update-Prüfung abzuschalten
  (`installingPackageName == "com.android.vending"` über
  `getInstallSourceInfo`, API 30+; darunter `getInstallerPackageName`), damit
  die App dort nicht auf eine APK außerhalb von Play verweist. Den Absatz zur
  Update-Prüfung in `PRIVACY.md` dann entsprechend anpassen.

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
  5. Markenrichtlinien von Strava prüfen: offizielles „Connect with
     Strava“-Knopfbild statt Textknopf, „View on Strava“-Gestaltung.
  6. Google-Play-Datensicherheit: nutzerinitiierte Weitergabe von Standort-
     und Fitnessdaten an Strava angeben.
  7. Auf dem Gerät prüfen: Verbinden, Ablehnen, Häkchen „Aktivitäten
     hochladen“ abwählen, Upload von Hand, Auto-Upload im Flugmodus, Duplikat,
     Trennen, Sportart auf Strava = Radfahrt (sonst nach dem Upload
     `PUT /activities/{id}` mit `sport_type=Ride`).
  8. Englische Texte (`values-en/strings_strava.xml`) erst zusammen mit der
     Übersetzung der ganzen App ergänzen — allein ergäben sie auf englischen
     Geräten eine Mischoberfläche. Englische Auslassungspunkte ohne
     Leerzeichen („Uploading to Strava…“).
  Später denkbar: verifizierte App Links statt `trailscape://` (braucht eine
  eigene Domain mit `/.well-known/assetlinks.json`), FIT statt GPX.

## Ideen

- **Trainingspläne als teilbare Dateien („Plan-Rezepte")** — Trainingsplan und
  strukturierte Einheiten als lesbare JSON-Datei exportieren und importieren,
  damit Trainer, Vereine und Foren Pläne ohne Plattform und ohne Konto
  tauschen können. Baut auf dem vorhandenen formatstabilen JSON
  (`core/.../JsonSupport.kt`) und dem Datei-Ein-/Ausgang der Backup-Karte auf.
- **Start und Ziel im Tour-Bild ausblenden** — die ersten und letzten ~300 m
  der Spur kappen (optional), damit ein geteiltes Bild die Haustuer nicht
  verraet. Heute sagt nur ein Hinweis im Teilen-Dialog, dass man Start und
  Ziel erkennen kann.
