# Klartext — App-Screenshots

Aus der echten App gerendert (Robolectric + Roborazzi, Galaxy S25: 411 × 891 dp,
xxhdpi), mit Beispieldaten aus `app/src/test/kotlin/de/trailscape/app/ui/ScreenshotTest.kt`.
Die Karte selbst ist in diesen Bildern eine neutrale Fläche (MapLibre läuft auf der JVM
nicht); alles darüber ist echt.

`21-…` und `22-…` zeigen dieselben Screens bei 130 % Systemschrift.

Neu erzeugen:

```bash
./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
# PNGs liegen danach in app/build/outputs/roborazzi/
```
