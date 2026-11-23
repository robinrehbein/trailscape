# Testdaten Geodaten (indoor-world)

Alle Dateien am **23.11.2026** per `curl` abgerufen, unverändert gespeichert
(Ausnahme: `tilejson.json`, gekürzt). Raum: Altenberg/Odenthal (NRW, um
51,055° N, 7,133° O), dort liegt die Beispielstrecke.

| Datei | Quelle | Lizenz / Quellenangabe |
|---|---|---|
| `terrarium_13_4258_2740.png` | `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/13/4258/2740.png` (Terrarium, 256², 108 kB) | Tilezen Joerd, Quellenmix (u. a. SRTM, EU-DEM, ETOPO1); Angabe nach https://github.com/tilezen/joerd/blob/master/docs/attribution.md, für Deutschland u. a. „Europe terrain data produced using Copernicus data and information funded by the European Union - EU-DEM layers" und „… SRTM terrain data courtesy of the U.S. Geological Survey" |
| `vector_14_8516_5481.pbf` | `https://tiles.openfreemap.org/planet/20261115_080022_pt/14/8516/5481.pbf` (unkomprimiert, 43 kB) | „OpenFreeMap © OpenMapTiles Data from OpenStreetMap" — Daten ODbL 1.0 (© OpenStreetMap-Mitwirkende), Schema OpenMapTiles (CC BY 4.0) |
| `vector_14_8516_5480.pbf` | dieselbe Quelle, Kachel `14/8516/5480` (nördlich, 66 kB) | wie oben |
| `tilejson.json` | `https://tiles.openfreemap.org/planet` — gekürzt auf die gebrauchten Ebenen, ohne `name:*`-Felder | wie oben |
| `aerial_nw_256.jpg` | `https://www.wms.nrw.de/geobasis/wms_nw_dop` GetMap 1.3.0, `LAYERS=nw_dop_rgb`, `CRS=EPSG:4326`, `BBOX=51.05355,7.13067,51.05585,7.13433`, 256², JPEG Baseline (Altenberger Dom) | dl-de/zero-2-0 (https://www.govdata.de/dl-de/zero-2-0), freiwillig: „© Geobasis NRW" |
| `aerial_nw_blank_256.jpg` | derselbe Dienst, `BBOX=50.0,10.0,50.01,10.01` (außerhalb NRW): rein weißes Leerbild | dl-de/zero-2-0 |
| `wms_exception.xml` | derselbe Dienst mit `LAYERS=gibtsnicht`: `ServiceExceptionReport`, **Status 200**, `text/xml` | dl-de/zero-2-0 |
| `aerial_be_progressive_256.jpg` | `https://gdi.berlin.de/services/wms/truedop_2024` GetMap 1.3.0, `LAYERS=truedop_2024`, `CRS=EPSG:4326`, `BBOX=52.51515,13.37583,52.51745,13.37957`, 256² — **progressives** JPEG (Marker `FF C2`) | dl-de/zero-2-0 (laut GetCapabilities des Geoportals Berlin) |
