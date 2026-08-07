# 🚵 Trailscape

Kostenlose, local-first Web-App für GPS-Aufzeichnung und Routenplanung von Gravel-Biking-Touren – die Alternative zu Strava und Komoot.

## Warum?

Strava- und Komoot-Abos kosten Geld und erfordern Accounts. Trailscape ist kostenlos, weil es vollständig lokal im Browser läuft: Keine Server, keine Konten, keine Cloud. Deine Daten bleiben auf deinem Gerät – offline und privat.

## Features (MVP)

- **GPS-Aufzeichnung**: Touren direkt im Browser aufzeichnen und speichern (erfordert HTTPS oder localhost)
- **Live-Aufzeichnung**: Während der Fahrt Tempo, Distanz, Fahrzeit und Höhenmeter in großen Ziffern anzeigen; Pause/Weiter-Funktion; Display bleibt per Wake Lock an
- **Routen-Navigation**: Navigation auf gespeicherten Touren starten mit GPS-Verfolgung, Anzeige der verbleibenden Kilometer und Warnung (mit Vibration) beim Verlassen der Route
- **Mobile-first UI**: Bottom-Tab-Navigation (Karte, Touren, Training, Mehr), großer runder Aufnahme-Button auf der Karte, große Touch-Ziele – optimiert für einhändige Bedienung auf dem Rad
- **GPX Import/Export**: Touren importieren und exportieren für Austausch oder Backup
- **Statistiken**: Distanz, Dauer, Durchschnittsgeschwindigkeit, Höhenmeter
- **Kartenansicht**: OpenStreetMap und OpenTopoMap (Kartenverfügbarkeit lokal gepuffert)
- **Offline-Karten**: Sichtbare Kartenausschnitte lokal speichern und offline nutzen (Service Worker liefert Kacheln aus dem Cache)
- **Interaktives Höhenprofil**: Mit Karten-Verknüpfung (Hover im Profil zeigt die Position auf der Karte)
- **Routenplanung**: Interaktiv auf der Karte planen (Klick für Wegpunkte, Drag zum Verschieben, Rechtsklick zum Löschen). Mehrere Routing-Profile (Gravel/Trekking, Rennrad, Kürzeste) über BRouter. Live-Anzeige von Distanz und Höhenmetern mit Profil. Benötigt Internetverbindung, der Rest der App funktioniert offline.
- **Lokale Speicherung**: Alle Daten in IndexedDB – kein Server, keine Synchronisierung nötig
- **Selfhost-Sync (optional)**: Touren in beide Richtungen mit eigenem Node.js-Server synchronisieren (Token-basiert, keine externen Abhängigkeiten)
- **PWA-Installation**: Als App auf deinem Gerät installierbar, auch ohne App-Store
- **Trainingsplan**: Automatische Fitnesslevel-Erkennung aus aufgezeichneten Touren und personalisierte Trainingspläne bis zu Events mit progressiver Steigerung, Erholungswochen sowie automatischem Fortschritts-Tracking

## Tech-Stack

- **Vite**: Build-Tool und Dev-Server (schnell, kostenlos)
- **TypeScript**: Typsicherheit ohne externe Dependencies (kostenlos)
- **Leaflet**: Leichte Karten-Bibliothek (kostenlos, quelloffen)
- **OpenStreetMap-Tiles**: Kostenlose Kartendaten (quelloffen)
- **BRouter**: Kostenloser Routing-Dienst für Wegberechnung (quelloffen)
- **IndexedDB**: Browser-API für lokale Speicherung (kostenlos, keine Datenbank-Server)
- **PWA**: Web-Standards (kostenlos, keine App-Store-Gebühren)

## Entwicklung

```bash
npm install       # Dependencies installieren
npm run dev       # Dev-Server starten (localhost:5173)
npm run build     # Für Production bauen
npm run preview   # Production-Build lokal testen
```

**Hinweis**: GPS-Funktionalität erfordert HTTPS oder localhost. Im Browser müssen Berechtigungen für Geolokalisierung gewährt werden.

## Aufs Smartphone bringen (GitHub Pages)

Trailscape lässt sich kostenlos über GitHub Pages bereitstellen und dann als PWA auf dem Smartphone installieren. Ein GitHub-Actions-Workflow baut und deployt die App automatisch bei jedem Push auf `main`.

1. In den Repo-Settings unter **Pages** als Source **"GitHub Actions"** auswählen.
2. Nach jedem Push auf `main` läuft der Workflow automatisch und veröffentlicht die aktuelle Version.
3. Die App ist danach unter `https://<user>.github.io/trailscape/` erreichbar.
4. Auf dem Handy im Browser öffnen und über **"Zum Startbildschirm hinzufügen"** als App installieren.
5. Beim ersten Aufzeichnen die GPS-Berechtigung im Browser erlauben.
6. Bei privaten Repos erfordert GitHub Pages einen Bezahlplan – alternativ das Repo auf public stellen.

## Selfhost-Sync

Optional lassen sich Touren mit einem eigenen Server synchronisieren. Ein leichtgewichtiger Node.js-Server im Ordner `server/` (ohne externe Abhängigkeiten) speichert Touren JSON-basiert lokal. Authentifizierung erfolgt über Token, ein Docker-Image ist enthalten. Alle Details und Setup-Anweisungen findest du in `server/README.md`. In der App werden Touren bidirektional synchronisiert – ideal, wenn du Trailscape auf mehreren Geräten nutzen möchtest.

## Roadmap

- **v0.2**: Automatische Pausenerkennung, Foto-Anhänge zu Touren
- **v0.3**: Eigenes Gravel-Routing-Profil (OSM-Surface-Daten stärker gewichten)
- **v0.4**: Touren teilen (öffentliche Links)

## Lizenz

MIT
