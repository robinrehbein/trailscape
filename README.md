# 🚵 Trailscape

Kostenlose, local-first Web-App für GPS-Aufzeichnung und Routenplanung von Gravel-Biking-Touren – die Alternative zu Strava und Komoot.

## Warum?

Strava- und Komoot-Abos kosten Geld und erfordern Accounts. Trailscape ist kostenlos, weil es vollständig lokal im Browser läuft: Keine Server, keine Konten, keine Cloud. Deine Daten bleiben auf deinem Gerät – offline und privat.

## Features (MVP)

- **GPS-Aufzeichnung**: Touren direkt im Browser aufzeichnen (erfordert HTTPS oder localhost)
- **GPX Import/Export**: Touren importieren und exportieren für Austausch oder Backup
- **Statistiken**: Distanz, Dauer, Durchschnittsgeschwindigkeit, Höhenmeter
- **Kartenansicht**: OpenStreetMap und OpenTopoMap (Kartenverfügbarkeit lokal gepuffert)
- **Lokale Speicherung**: Alle Daten in IndexedDB – kein Server, keine Synchronisierung nötig
- **PWA-Installation**: Als App auf deinem Gerät installierbar, auch ohne App-Store

## Tech-Stack

- **Vite**: Build-Tool und Dev-Server (schnell, kostenlos)
- **TypeScript**: Typsicherheit ohne externe Dependencies (kostenlos)
- **Leaflet**: Leichte Karten-Bibliothek (kostenlos, quelloffen)
- **OpenStreetMap-Tiles**: Kostenlose Kartendaten (quelloffen)
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

- **v0.2**: Höhenprofil-Diagramm, automatische Pausenerkennung, Foto-Anhänge zu Touren
- **v0.3**: Routenplanung mit Gravel-Fokus (BRouter/OSRM-Integration, OSM-Surface-Daten)
- **v0.4**: Optionale Self-Hosted-Synchronisierung, Touren teilen (Link/QR)
- **v1.0**: Offline-Kartenpakete für Regionen ohne Internetverbindung

## Lizenz

MIT
