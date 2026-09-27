# Play-Store-Material für Trailscape

- Paketkennung: `de.robinrehbein.trailscape`
- Standardsprache: Deutsch (`de-DE`)
- App-Icon: `icon-512.png` (512 × 512)
- Feature-Grafik: `feature-graphic.png` (1024 × 500)
- Smartphone-Screenshots: `screenshots/01-heute.png` und
  `screenshots/02-verlauf.png` (je 1080 × 1920)
- Texte und Versionshinweise: `listing-de-DE.md`

Das Launcher-Icon stammt aus `assets/icon/trailscape-master.png`. Mit
`tool/prepare_play_icon.py` werden die Android-Dichten und das Play-Icon
erstellt; `tool/prepare_play_graphic.py` erzeugt die Feature-Grafik.

Für das Play-Bundle liegt der Upload-Schlüssel lokal unter
`~/.android/trailscape-play-upload.jks`. Sein Passwort ist im macOS-Schlüsselbund
unter dem Dienst `trailscape-play-upload-key-password` gespeichert. Der
Schlüssel und sein Passwort gehören nicht ins Repository. Der Schlüssel muss
für spätere Uploads gesichert bleiben.

## Automatische interne Releases

Jeder erfolgreiche Push auf `main` erstellt weiterhin das GitHub-Release und
veröffentlicht danach ein separat signiertes Telefon-Bundle im internen
Play-Test. Der Workflow liegt in `.github/workflows/build.yml`; der API-Upload
in `tool/publish_play_internal.py`. Pull Requests, andere Branches und manuelle
Workflow-Läufe veröffentlichen nichts. Bereits veröffentlichte oder neuere
Versionscodes werden übersprungen.

Für den Play-Job werden diese verschlüsselten GitHub-Repository-Secrets benötigt:

| Secret | Inhalt |
| --- | --- |
| `PLAY_UPLOAD_KEYSTORE_BASE64` | Base64-Inhalt von `~/.android/trailscape-play-upload.jks` |
| `PLAY_UPLOAD_KEYSTORE_PASSWORD` | Passwort des Play-Upload-Schlüssels |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON-Schlüssel eines Google-Dienstkontos für die Android Publisher API |

Das Dienstkonto benötigt in der Play Console nur Zugriff auf
`de.robinrehbein.trailscape` und das Recht, Releases in Test-Tracks zu
veröffentlichen. In seinem Google-Cloud-Projekt muss die Android Publisher API
aktiv sein. Die Pipeline bricht mit einer klaren Fehlermeldung ab, falls eines
der Secrets fehlt. Der bestehende GitHub-APK-Schlüssel bleibt separat, damit
direkt installierte APKs weiterhin aktualisierbar sind.

Der Versionscode ist `2000 + GITHUB_RUN_NUMBER`. Die Pipeline veröffentlicht
gezielt nur den internen Play-Test; weitere Play-Tracks werden nicht automatisch
hochgestuft. Status und Fehlermeldungen sind im GitHub-Actions-Lauf sichtbar.

Die Store-Screenshots sollen aus der echten Compose-Oberfläche kommen:

```bash
./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
```

Die PNGs liegen danach unter `app/build/outputs/roborazzi/`.
