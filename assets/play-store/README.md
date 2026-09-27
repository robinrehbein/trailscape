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
Schlüssel und sein Passwort gehören nicht ins Repository. Für spätere Uploads
muss dieser Schlüssel gesichert und die CI-Signierung entsprechend eingerichtet
werden.

Die Store-Screenshots sollen aus der echten Compose-Oberfläche kommen:

```bash
./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
```

Die PNGs liegen danach unter `app/build/outputs/roborazzi/`.
