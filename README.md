# 🚵 Trailscape

Kostenlose, local-first Web-App für GPS-Aufzeichnung und Routenplanung von Gravel-Biking-Touren – die Alternative zu Strava und Komoot.

## Warum?

Strava- und Komoot-Abos kosten Geld und erfordern Accounts. Trailscape ist kostenlos, weil es vollständig lokal im Browser läuft: Keine Server, keine Konten, keine Cloud. Deine Daten bleiben auf deinem Gerät – offline und privat.

## Features (MVP)

- **GPS-Aufzeichnung**: Touren direkt im Browser aufzeichnen (erfordert HTTPS oder localhost)
- **GPX Import/Export**: Touren importieren und exportieren für Austausch oder Backup
- **Statistiken**: Distanz, Dauer, Durchschnittsgeschwindigkeit, Höhenmeter
- **Kartenansicht**: OpenStreetMap und OpenTopoMap (Kartenverfügbarkeit lokal gepuffert)
- **Interaktives Höhenprofil**: Mit Karten-Verknüpfung (Hover im Profil zeigt die Position auf der Karte)
- **Routenplanung**: Interaktiv auf der Karte planen (Klick für Wegpunkte, Drag zum Verschieben, Rechtsklick zum Löschen). Mehrere Routing-Profile (Gravel/Trekking, Rennrad, Kürzeste) über BRouter. Live-Anzeige von Distanz und Höhenmetern mit Profil. Benötigt Internetverbindung, der Rest der App funktioniert offline.
- **Lokale Speicherung**: Alle Daten in IndexedDB – kein Server, keine Synchronisierung nötig
- **PWA-Installation**: Als App auf deinem Gerät installierbar, auch ohne App-Store

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

## Roadmap

- **v0.2**: Automatische Pausenerkennung, Foto-Anhänge zu Touren
- **v0.3**: Eigenes Gravel-Routing-Profil (OSM-Surface-Daten stärker gewichten)
- **v0.4**: Optionale Self-Hosted-Synchronisierung, Touren teilen (Link/QR)
- **v1.0**: Offline-Kartenpakete für Regionen ohne Internetverbindung

## Lizenz

MIT
