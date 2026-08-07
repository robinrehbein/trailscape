# 🚵 Trailscape

Kostenlose native Android-App für GPS-Aufzeichnung und Routenplanung – die Alternative zu Strava und Komoot. Gebaut mit Flutter.

## Warum?

Strava- und Komoot-Abos sind teuer und binden Daten in die Cloud. Trailscape ist kostenlos und **local-first**: Deine Daten bleiben auf deinem Gerät, vollständig offline. Ohne Accounts, ohne Cloud, ohne versteckte Kosten – nur du und deine Touren.

## Features

- **GPS-Aufzeichnung mit Hintergrund-Tracking**: Touren aufzeichnen, die im Hintergrund weiterlaufen, auch bei gesperrtem Display (Foreground-Service)
- **Live-Anzeige**: Während der Fahrt Tempo, Distanz, Fahrzeit und Höhenmeter in großer Schrift anzeigen; Pause/Weiter-Funktion
- **GPX-Import/Export**: Touren importieren und exportieren für Austausch oder Backup
- **Statistiken**: Distanz, Dauer, Durchschnittsgeschwindigkeit, Höhenmeter für jede Tour
- **Routenplanung**: Zielsuche nach Ort, Stadt oder Straße (Nominatim), Möglichkeit, aktuelle Position als Start zu nutzen, Dropdowns für Fahrrad-Typ (Gravel, Rennrad) und bevorzugten Weg (Gemischt, Asphalt & Straße, Radwege & verkehrsarm, Kürzester Weg) mit automatischer Profil-Auswahl für BRouter-Routenberechnung
- **Routen-Navigation**: Navigation auf gespeicherten Touren mit GPS-Verfolgung, Anzeige der verbleibenden Kilometer und Vibrations-Warnung beim Verlassen der Route
- **Offline-Karten**: Regionen lokal herunterladen (max. 250 Kacheln pro Vorgang) und offline nutzen; automatischer Cache für weitere Regionen
- **Trainingspläne**: Automatische Fitnesslevel-Erkennung aus aufgezeichneten Touren und personalisierte Trainingspläne mit progressiver Steigerung, Erholungswochen und Fortschritts-Tracking
- **Lokale Speicherung**: Alle Daten lokal auf dem Gerät – keine Synchronisierung nötig
- **Selfhost-Sync (optional)**: Touren bidirektional mit eigenem Server synchronisieren (Details siehe unten)

## Tech-Stack

- **Flutter/Dart**: Native Android-Performance, kostenlos und quelloffen
- **flutter_map + OpenStreetMap**: Leichte Karten-Bibliothek mit kostenlosen, quelloffenen Kartendaten
- **geolocator**: Zuverlässiges GPS ohne externe Abhängigkeiten, kostenlos
- **BRouter**: Kostenloser Routing-Dienst für Wegberechnung, quelloffen
- **Nominatim (OpenStreetMap)**: Kostenlose Ortssuche für die Zielsuche in der Routenplanung

## Installation (Android)

Die APK wird bei jedem Push auf `main` automatisch von GitHub Actions gebaut und an das GitHub-Release `latest` angehängt.

1. Gehe zur [Releases-Seite](../../releases) dieses Repositories
2. Lade die neueste APK-Datei unter dem Release `latest` herunter
3. Öffne die APK auf deinem Android-Gerät
4. Bestätige die Installation (evtl. musst du unter Einstellungen > Sicherheit > "Unbekannte Quellen" die Installation erlauben)
5. Beim ersten Start erlaube die erforderlichen Berechtigungen:
   - **Standort**: Wähle "Immer erlauben" für Hintergrund-Tracking (notwendig für GPS-Aufzeichnung)
   - **Benachrichtigungen**: Erlaube Benachrichtigungen für Aufzeichnungsstatus

## Entwicklung

```bash
flutter pub get          # Dependencies installieren
flutter test             # Tests ausführen
flutter run              # Debug-Version auf Gerät/Emulator starten
flutter build apk --release  # Release-APK bauen
```

Voraussetzung: Flutter ≥ 3.x installiert und ein Android-SDK konfiguriert.

## Selfhost-Sync

Optional können Touren mit einem selbst gehosteten Server synchronisiert werden. Ein leichtgewichtiger Node.js-Server (eine Datei, keine externen Abhängigkeiten) speichert Touren JSON-basiert lokal. Authentifizierung erfolgt über Token, ein Docker-Image ist enthalten. Alle Details und Setup-Anweisungen findest du in [`server/README.md`](server/README.md).

## Lizenz

MIT
