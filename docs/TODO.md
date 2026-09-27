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

- **Play-Build: Paketname und Update-Prüfung.** `PRIVACY.md` nennt für Google
  Play `de.robinrehbein.trailscape`, der Build setzt aber nur
  `io.github.robinrehbein.trailscape` (`app/build.gradle.kts`). Vor dem
  Play-Release den Play-Build bewusst auf die neue Kennung stellen (etwa als
  eigener Flavor — ein Wechsel der bestehenden Kennung würde Sideload-Installationen
  vom Update abschneiden) und für Play-Installationen die GitHub-Update-Prüfung
  abschalten (`installingPackageName == "com.android.vending"` über
  `getInstallSourceInfo`, API 30+; darunter `getInstallerPackageName`), damit
  die App dort nicht auf eine APK außerhalb von Play verweist. Den Absatz zur
  Update-Prüfung in `PRIVACY.md` dann entsprechend anpassen.

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
