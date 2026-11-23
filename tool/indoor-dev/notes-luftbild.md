# Luftbild-Register: Prüfung der Länder-WMS (Entwurf)

Stand: **23.11.2026**. Alle Dienste mit echten Anfragen geprüft (`curl` aus der
Entwicklungsumgebung): `GetCapabilities` 1.3.0 und `GetMap` 1.3.0 mit
`CRS=EPSG:4326`, Achsenreihenfolge `BBOX=minLat,minLon,maxLat,maxLon`, je
600 m × 600 m um ein bekanntes Bauwerk in 1024² und 2048² als `image/jpeg`.

Prüfschritte je Dienst:

1. **Achsenreihenfolge:** Bild um ein markantes Bauwerk muss es mittig zeigen
   (bei vertauschten Achsen läge das Rechteck in Afrika/Asien → Leerbild).
2. **Kontinuität:** zweites Bild unmittelbar östlich daneben; mittlerer
   Farbsprung zwischen letzter Spalte links und erster Spalte rechts muss in
   der Größenordnung des Sprungs zwischen zwei benachbarten Spalten liegen.
3. **JPEG-Art:** Marker vor `FF DA` — `FF C0` = Baseline, `FF C2` = progressiv.
4. **Außerhalb der Abdeckung:** Antwort ist ein einfarbiges (weißes) Bild.
5. **Fehler:** falscher Ebenenname → `ServiceExceptionReport` als `text/xml`
   mit **Status 200** (NRW, Sachsen geprüft).
6. **Lizenz:** aus der Lizenzseite des Landesamts bzw. dem
   `Fees`/`AccessConstraints`-Text der GetCapabilities (amtliche Selbstauskunft
   des Dienstes).

## Ergebnis

| Land | Dienst | Ebene | max. Bild | 4326 + Achsen | JPEG | Kontinuität (Naht / Nachbarspalte) | Lizenz | Empfehlung |
|---|---|---|---|---|---|---|---|---|
| NRW | `https://www.wms.nrw.de/geobasis/wms_nw_dop` | `nw_dop_rgb` | 5000 | ja (Altenberger Dom mittig, Chor im Osten) | Baseline | – | dl-de/zero-2-0 | eingetragen (bestätigt) |
| Sachsen | `https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest` | `sn_dop_020` | 4096 | ja (Frauenkirche Dresden mittig, Lutherdenkmal SW) | Baseline | 2,0 / 10,3 | dl-de/by-2-0, „Quelle: GeoSN, dl-de/by-2-0" | **aufnehmen** |
| Thüringen | `https://www.geoproxy.geoportal-th.de/geoproxy/services/DOP` | `th_dop` | 5000 | ja (Erfurter Altstadt/Domplatz) | Baseline | 12,3 / 13,5 | CC BY 4.0, „© GDI-Th" | **aufnehmen** |
| Brandenburg + Berlin | `https://isk.geobasis-bb.de/mapproxy/dop20c/service/wms` | `bebb_dop20c` | 8000 | ja (Schloss Sanssouci mittig) | Baseline | 14,6 / 15,3 | dl-de/by-2-0, „© GeoBasis-DE/LGB, dl-de/by-2-0; © Geoportal Berlin, dl-de/by-2-0" | **aufnehmen** |
| Bayern | `https://geoservices.bayern.de/od/wms/dop/v1/dop40` | `by_dop40c` | 6000 | ja (Frauenkirche München mittig) | Baseline | 8,8 / 6,0 | CC BY 4.0, „Bayerische Vermessungsverwaltung – www.geodaten.bayern.de" | **aufnehmen** (DOP 40 cm) |
| Niedersachsen | `https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms` | `ni_dop20` | 15000 | ja (Neues Rathaus Hannover, Maschteich) | Baseline | 12,2 / 9,9 | CC BY 4.0, „LGLN (Jahr des Datenbezugs)" | technisch ok, **Quellenangabe verlangt Jahreszahl** → nur mit Jahr zur Bauzeit |
| Baden-Württemberg | `https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C` | `IMAGES_DOP_20_RGB` | 15000 | ja (Neues Schloss Stuttgart) | Baseline | 10,2 / 9,9 | dl-de/by-2-0, „LGL-BW (Jahr) Datenlizenz Deutschland - Namensnennung - Version 2.0, www.lgl-bw.de" | wie NI: Jahreszahl |
| Rheinland-Pfalz | `https://geo4.service24.rlp.de/wms/rp_dop20.fcgi` | `rp_dop20` | 10000 | ja (Mainzer Dom) | Baseline | 13,8 / 14,7 | dl-de/by-2-0, „©GeoBasis-DE / LVermGeoRP (Jahr), dl-de/by-2-0, http://www.lvermgeo.rlp.de [Daten bearbeitet]" | wie NI: Jahreszahl |
| Sachsen-Anhalt | `https://www.geodatenportal.sachsen-anhalt.de/wss/service/ST_LVermGeo_DOP_WMS_OpenData/guest` | `lsa_lvermgeo_dop20_2` | 4096 | ja (Magdeburger Dom an der Elbe) | Baseline | 4,7 / 4,6 | **in den Capabilities keine Angabe** | nicht aufnehmen, bis die Lizenz belegt ist |
| Berlin allein | `https://gdi.berlin.de/services/wms/truedop_2024` | `truedop_2024` | – | ja (Brandenburger Tor) | **progressiv** | 14,0 / 13,5 | dl-de/zero-2-0 | nicht nötig (in `bebb_dop20c` enthalten); Testfall für Progressiv-Erkennung |
| Hamburg | `https://geodienste.hamburg.de/HH_WMS_DOP` | – | – | – | – | – | – | 404, Adresse veraltet; nicht aufgenommen |
| Hessen | `https://www.gds.hessen.de/wms/dop` | – | – | – | – | – | – | 404; nicht aufgenommen |

Bildgrößen (JPEG, 600 m × 600 m, Stadtgebiet): 1024² 190–420 kB, 2048²
650 kB–1,3 MB; Sachsen-Anhalt fällt mit 830 kB / 2,6 MB aus der Reihe (geringe
Kompression). NRW, ländlich (Altenberg, 256 m): 256² 16 kB, 1024² 188 kB,
2048² 697 kB. Antwortzeiten 1–4 s je Bild.

## Belege Lizenz

- **NRW:** GetCapabilities `Fees`: „… Datenlizenz Deutschland – Zero
  (https://www.govdata.de/dl-de/zero-2-0). Jede Nutzung ist ohne
  Einschränkungen oder Bedingungen zulässig."
- **Sachsen:** GetCapabilities `AccessConstraints` verweist auf
  https://geoportal.sachsen.de/nutzungsbedingungen.html → „Allgemeine
  Nutzungsbedingungen des GeoSN"
  https://www.landesvermessung.sachsen.de/allgemeine-nutzungsbedingungen-8954.html:
  „Die Erlaubnis entspricht dem Lizenzmuster der Datenlizenz Deutschland –
  Namensnennung – Version 2.0 unter Angabe des Quellenvermerks
  ‚Landesamt für Geobasisinformation Sachsen (GeoSN)' oder kurz ‚GeoSN'." —
  Beispiel elektronische Medien: „Quelle: GeoSN, dl-de/by-2-0".
- **Thüringen:** GetCapabilities `Fees`: „Lizenz: Creative Commons BY 4.0
  (https://creativecommons.org/licenses/by/4.0/deed.de) Namensnennung:
  ‚© GDI-Th'".
- **Brandenburg/Berlin:** GetCapabilities `AccessConstraints`: „… ‚Datenlizenz
  Deutschland - Namensnennung - Version 2.0' (https://www.govdata.de/dl-de/by-2-0)
  … Namensnennung: ‚GeoBasis-DE/LGB' … werden auch Daten des Bundeslandes
  Berlin genutzt, ist der Copyright-Vermerk um den Hinweis ‚© Geoportal Berlin,
  dl-de/by-2-0' zu ergänzen."
- **Bayern:** GetCapabilities `AccessConstraints`: „CC BY 4.0 vgl.
  https://creativecommons.org/licenses/by/4.0/deed.de"; Datensatzseite
  https://geodaten.bayern.de/opengeodata/OpenDataDetail.html?pn=dop40 (Lizenz
  CC BY 4.0). Wortlaut der Namensnennung „Bayerische Vermessungsverwaltung –
  www.geodaten.bayern.de" laut LDBV-Vortrag
  https://www.ldbv.bayern.de/mam/ldbv/dateien/infoverm_2026_eckrich.pdf (eine
  eigene Nutzungsbedingungs-Seite mit Wortlaut habe ich nicht gefunden).
- **Niedersachsen / Baden-Württemberg / Rheinland-Pfalz:** jeweils
  GetCapabilities (Wortlaut in der Tabelle); alle verlangen das **Jahr des
  Datenbezugs** in der Quellenangabe. Das Register hat einen festen Text —
  wer sie aufnimmt, muss das Jahr beim Bau einsetzen (z. B. Platzhalter im
  `attribution`-Text, den der Weltbau mit dem Baujahr füllt).

## Hinweise für den Weltbau

- Alle geprüften Dienste liefern außerhalb ihrer Abdeckung rein weiße Bilder
  (NRW, Sachsen geprüft: 100 % `#FFFFFF`) → Leerbild-Erkennung genügt.
- Die Umgebungsrechtecke überlappen (Länder sind keine Rechtecke).
  `fetchAerial` fragt alle Einträge, deren Rechteck die Kachelmitte enthält,
  in Registerreihenfolge und nimmt das erste Bild, das kein Leerbild ist.
  An einer Landesgrenze kann ein Bild halb weiß sein (unter 98 % gleich) —
  das bleibt als Schwäche (der Weltbau könnte weiße Flächen mit der erzeugten
  Bodentextur auffüllen).
- Der WMS liefert bei `FORMAT=image/jpeg` überall Baseline, außer Berlin
  (progressiv) → PNG-Rückfall ist umgesetzt (`fetchAerial`).
- Neue Hosts für `PRIVACY.md`: `geodienste.sachsen.de`,
  `www.geoproxy.geoportal-th.de`, `isk.geobasis-bb.de`,
  `geoservices.bayern.de`.
