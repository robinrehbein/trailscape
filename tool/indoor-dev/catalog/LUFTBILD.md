# Luftbild-Register: Prüfung weiterer WMS für den Katalog (Vorbau bekannter Radstrecken)

Prüfdatum: **23.11.2026**. Alle Angaben aus echten Anfragen (`curl` aus der
Entwicklungsumgebung), Vorgehen wie in `../notes-luftbild.md`:

- `GetCapabilities` 1.3.0: Ebene, CRS-Liste (EPSG:4326 auch geerbt von der
  Wurzelebene), `MaxWidth`/`MaxHeight`, `Fees`/`AccessConstraints`.
- `GetMap` genau in der Form von `TileMath.wmsGetMapUrl`
  (`?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=…&STYLES=&CRS=EPSG:4326&BBOX=minLat,minLon,maxLat,maxLon&WIDTH=…&HEIGHT=…&FORMAT=image/jpeg`),
  600 m × 600 m um den Testpunkt, 1024² als JPEG, Bild angesehen.
  Bei vertauschten Achsen läge das Rechteck außerhalb jeder Abdeckung (Leerbild).
- JPEG-Art: SOF-Marker (`FF C0` Baseline / `FF C2` progressiv).
- Kontinuität: zweites 512²-Bild unmittelbar östlich, mittlerer Farbsprung an
  der Naht gegen den zwischen zwei Nachbarspalten.
- Außerhalb der Abdeckung: Bild weit außerhalb, Pixelstatistik.
- Größte Kantenlänge: 2048 und mehr abgerufen; über `MaxWidth` geprüft, was passiert.
- Lizenz: Capabilities, Metadatensatz und offizielle Lizenzseite.

Die Leerbild-Erkennung des Weltbaus (`isBlankImage`, `Aerial.kt`) ist
farbunabhängig (häufigste Farbe ≥ 98 % oder Streuung < 1,5, Transparenz zählt
mit) — schwarze oder transparente Leerbilder werden also ebenfalls verworfen.

## Ergebnis

| Land | wmsUrl | Ebene | max. px | 4326 + Achsen | JPEG | Naht / Nachbarspalte | außerhalb | Lizenz | Wortlaut | Empfehlung |
|---|---|---|---|---|---|---|---|---|---|---|
| Frankreich (IGN Géoplateforme) | `https://data.geopf.fr/wms-r/wms` | `ORTHOIMAGERY.ORTHOPHOTOS` | 5010 (4096 geprüft) | ja (Alpe d'Huez: Ortskern; Arenberg: Trouée als gerade NW–SO-Schneise im Wald) | Baseline | 9,9 / 8,5 | weiß (JPEG und PNG); CH, IT, ES, BE, DE weiß; Korsika und Übersee haben Bild | Licence Ouverte (Etalab) | Quelle (mind. Name des Lizenzgebers) **und Datum der letzten Aktualisierung** | **aufnehmen** (Datum einsetzen, wie NI) |
| Flandern (Digitaal Vlaanderen) | `https://geo.api.vlaanderen.be/OMWRGBMRVL/wms` | `Ortho` | 2048 (2049 → `ServiceExceptionReport`, Status 200) | ja (Koppenberg: Felder/Hof der Vlaamse Ardennen; Gegenprobe Gent-Zentrum mittig; vertauscht → weiß) | Baseline | 4,2 / 3,7 | weiß (JPEG und PNG) | Modellicentie gratis hergebruik v1.0 | „Bron: Luchtopnamen Digitaal Vlaanderen“ | **aufnehmen** |
| Spanien (IGN, PNOA máxima actualidad) | `https://www.ign.es/wms-inspire/pnoa-ma` | `OI.OrthoimageCoverage` | 4096 (4097 → `ServiceExceptionReport`, Status 200) | ja (Sa Calobra: Bucht und Torrent de Pareis mittig; Teide-Krater mittig) | Baseline | 16,4 / 16,6 | weiß (Atlantik, Frankreich, Portugal) | CC BY 4.0 | Dienstformel „CC BY 4.0 scne.es“; Produktformel mit `<fecha>` | **aufnehmen** (Jahr einsetzen, s. u.) |
| Italien/Toskana (Geoscopio) | `https://www502.regione.toscana.it/wmsraster/com.rt.wms.RTmap/wms/wmsofc` (ohne Query!) | `rt_ofc.10k13` (AGEA 2013) | 5000 | ja (Asciano: Ortskern mittig) | Baseline | – | `rt_ofc.2k15/16/17` am Testpunkt weiß (Teilabdeckung) | **nicht belegt**: Ebene „propr. AGEA“, Metadaten „non disponibile (dataset non RT)“ | – | **nicht** |
| USA (USGS, NAIP-ImageServer) | `https://imagery.nationalmap.gov/arcgis/services/USGSNAIPImagery/ImageServer/WMSServer` | `USGSNAIPImagery:NaturalColor` | 4000 (4096 → Status 200, 0 Byte) | ja (Cottonwood Falls: Ortsgitter, Sportplatz mittig) | Baseline | 14,4 / 9,6 | **schwarz** (JPEG), transparent (PNG) | Public Domain | erbeten: „Credit: U.S. Geological Survey“; Dienst: „USGS, USDA, The National Map: Orthoimagery“ | **aufnehmen** (langsam: 15–30 s je Bild) |
| USA (USGS, Kachel-Basiskarte) | `https://basemap.nationalmap.gov/arcgis/services/USGSImageryOnly/MapServer/WMSServer` | `0` | 4096 (4097 → `ServiceExceptionReport`) | ja (gleiches Bild, unscharf) | Baseline | 4,2 / 3,4 | weiß in Mexiko/Kanada/Golf; im offenen Atlantik dunkelblau, in Europa grobes Weltbild | Public Domain | wie oben | nicht nötig (nur bis 1:9 028, ≈ 2 m/px, hochskaliert) |
| Hessen (HLBG) | `https://www.gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-images.ows` | `he_dop20_rgb` | 3000 (3001 → `ServiceExceptionReport`) | ja (Großer Feldberg: Sendeanlagen mittig) | Baseline | 18,4 / 17,3 | weiß (JPEG und PNG) | dl-de/zero-2-0 (HVGG § 18) | keine Pflicht; wird ein Quellenvermerk gegeben, ist auf Veränderungen hinzuweisen | **aufnehmen** |
| Baden-Württemberg (LGL) | `https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C` | `IMAGES_DOP_20_RGB` | 15000 (4096 geprüft) | ja (Schauinsland: Kuppe, Straßenkehren) | Baseline | 17,5 / 13,1 | weiß, aber **Graustufen-JPEG** / Palette-PNG | dl-de/by-2-0 | „LGL-BW (Jahr) Datenlizenz Deutschland - Namensnennung - Version 2.0, www.lgl-bw.de“ | **aufnehmen** (Jahr einsetzen) |
| Sachsen-Anhalt (LVermGeo) | `https://www.geodatenportal.sachsen-anhalt.de/wss/service/ST_LVermGeo_DOP_WMS_OpenData/guest` | `lsa_lvermgeo_dop20_2` | 4096 (2048 geprüft) | ja (Brocken: Gipfelbauten, Brockenbahn mittig; Winterbild mit Schnee) | Baseline | 18,4 / 18,7 | weiß | dl-de/by-2-0 (jetzt belegt) | „© GeoBasis-DE / LVermGeo ST“ | **aufnehmen** |

Bildgrößen 1024² JPEG: FR 160–240 kB, VL 100 kB, ES 270 kB, NAIP 290 kB, HE
260 kB, BW 270 kB, ST 1,07 MB (wie schon notiert: geringe Kompression; 2048²
3,7 MB). Antwortzeiten 1–3 s, NAIP 15–30 s.

## Hinweise für den Weltbau

- **Jahr/Datum in der Quellenangabe** (FR, ES, BW — wie NI/RP in
  `notes-luftbild.md`): Die Schnipsel unten tragen den Platzhalter `{jahr}`.
  Den gibt es im Weltbau noch nicht; ohne Ersetzung (z. B. Jahr des Abrufs
  beim Bau) dürfen diese drei nicht eingetragen werden.
- **NAIP**: schwarzes Leerbild (wird erkannt), sehr langsame Antworten —
  Zeitlimit des Abrufs prüfen. Über 4000 px kommt eine leere 200-Antwort
  (`application/octet-stream`, 0 Byte) → `maxSizePx` höchstens 4000.
- **Baden-Württemberg**: Leerbild als Graustufen-JPEG — der `RasterDecoder`
  muss einkanalige JPEGs lesen (sonst „unlesbar“, Ergebnis ebenfalls `null`).
- **Toskana**: Der Pfad `…/RTmap/wms/wmsofc` liefert dieselben Capabilities
  wie `…/RTmap/wms?map=wmsofc` (gleiche Größe, 246 209 Byte) — technisch wäre
  die Positivliste also erfüllbar; es scheitert an der Lizenz.
- **Italien national** (Geoportale Nazionale, `wms.pcn.minambiente.it` bzw.
  `gn.mase.gov.it`): HTTPS 301 bzw. 403, Adresse braucht ohnehin `map=` im
  Query → nicht nutzbar.
- Neue Hosts für `PRIVACY.md` bei Aufnahme: `data.geopf.fr`,
  `geo.api.vlaanderen.be`, `www.ign.es`, `imagery.nationalmap.gov`,
  `www.gds-srv.hessen.de`, `owsproxy.lgl-bw.de`,
  `www.geodatenportal.sachsen-anhalt.de`. (Flandern protokolliert laut
  Privacyverklaring IP-Adressen bis 90 Tage.)

## Kotlin-Schnipsel

Rechtecke grob; außerhalb der Abdeckung liefern alle Dienste ein Leerbild,
Überlappungen sind daher unkritisch.

```kotlin
// Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Alpe d'Huez, Arenberg mittig), nahtlos,
// Baseline, bis 5010 px, ausserhalb weiss. Lizenz Licence Ouverte (Etalab) laut Metadatensatz
// IGNF_BD-ORTHO; verlangt Quelle und Datum der letzten Aktualisierung ({jahr} beim Bau einsetzen).
// Rechteck: Festland + Korsika.
AerialSource(
    id = "fr",
    label = "Frankreich",
    wmsUrl = "https://data.geopf.fr/wms-r/wms",
    layer = "ORTHOIMAGERY.ORTHOPHOTOS",
    minLat = 41.30,
    minLon = -5.20,
    maxLat = 51.15,
    maxLon = 9.60,
    attribution = "Luftbild: © IGN – BD ORTHO®, Licence Ouverte Etalab ({jahr})",
    license = "Licence Ouverte 2.0",
    licenseUrl = "https://www.data.gouv.fr/pages/legal/licences/etalab-2.0",
    maxSizePx = 4096,
),
// Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Gent-Zentrum mittig), nahtlos, Baseline,
// hoechstens 2048 px, ausserhalb weiss. Modellicentie gratis hergebruik v1.0, Bronvermelding laut Metadaten.
AerialSource(
    id = "vl",
    label = "Flandern",
    wmsUrl = "https://geo.api.vlaanderen.be/OMWRGBMRVL/wms",
    layer = "Ortho",
    minLat = 50.68,
    minLon = 2.54,
    maxLat = 51.51,
    maxLon = 5.92,
    attribution = "Luftbild: Bron: Luchtopnamen Digitaal Vlaanderen",
    license = "Modellicentie gratis hergebruik v1.0",
    licenseUrl = "https://www.vlaanderen.be/digitaal-vlaanderen/onze-diensten-en-platformen/open-data/voorwaarden-voor-het-hergebruik-van-overheidsinformatie/modellicentie-gratis-hergebruik",
    maxSizePx = 2048,
),
// Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Sa Calobra, Teide mittig), nahtlos, Baseline,
// bis 4096 px, ausserhalb weiss. CC BY 4.0; Kurzform nach IGN-Lizenz Nr. 3 („Cuarto“): „CC-BY 4.0 scne.es <fecha>“.
// Zwei Rechtecke: Halbinsel + Balearen (ohne Ceuta/Melilla), Kanaren.
AerialSource(
    id = "es",
    label = "Spanien",
    wmsUrl = "https://www.ign.es/wms-inspire/pnoa-ma",
    layer = "OI.OrthoimageCoverage",
    minLat = 35.95,
    minLon = -9.40,
    maxLat = 43.80,
    maxLon = 4.35,
    attribution = "Luftbild: PNOA CC-BY 4.0 scne.es {jahr}",
    license = "CC BY 4.0",
    licenseUrl = "https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf",
    maxSizePx = 4096,
),
AerialSource(
    id = "es-cn",
    label = "Kanarische Inseln",
    wmsUrl = "https://www.ign.es/wms-inspire/pnoa-ma",
    layer = "OI.OrthoimageCoverage",
    minLat = 27.60,
    minLon = -18.20,
    maxLat = 29.45,
    maxLon = -13.35,
    attribution = "Luftbild: PNOA CC-BY 4.0 scne.es {jahr}",
    license = "CC BY 4.0",
    licenseUrl = "https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf",
    maxSizePx = 4096,
),
// Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Cottonwood Falls mittig), Baseline, bis 4000 px,
// ausserhalb SCHWARZ (PNG transparent), 15–30 s je Bild. Public Domain (USGS/USDA NAIP). Nur CONUS
// (Alaska-Bilder im Basisdienst sind SPOT mit Lizenzbeschraenkung).
AerialSource(
    id = "us",
    label = "USA (CONUS)",
    wmsUrl = "https://imagery.nationalmap.gov/arcgis/services/USGSNAIPImagery/ImageServer/WMSServer",
    layer = "USGSNAIPImagery:NaturalColor",
    minLat = 24.40,
    minLon = -124.80,
    maxLat = 49.40,
    maxLon = -66.90,
    attribution = "Luftbild: USGS, USDA, The National Map: Orthoimagery",
    license = "Public Domain",
    licenseUrl = "https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits",
    maxSizePx = 2048,
),
// Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Grosser Feldberg mittig), nahtlos, Baseline,
// bis 3000 px, ausserhalb weiss. Nutzung ohne Bedingung (HVGG § 18, dl-de/zero-2-0 laut opendata.hessen.de);
// gibt man einen Quellenvermerk, ist auf Veraenderungen hinzuweisen („bearbeitet“).
AerialSource(
    id = "he",
    label = "Hessen",
    wmsUrl = "https://www.gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-images.ows",
    layer = "he_dop20_rgb",
    minLat = 49.36,
    minLon = 7.71,
    maxLat = 51.70,
    maxLon = 10.30,
    attribution = "Luftbild: Geobasisdaten © Hessische Verwaltung für Bodenmanagement und Geoinformation, bearbeitet",
    license = "dl-de/zero-2-0",
    licenseUrl = "https://www.govdata.de/dl-de/zero-2-0",
),
// Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Schauinsland), nahtlos, Baseline, bis 15000 px,
// ausserhalb weisses GRAUSTUFEN-JPEG. dl-de/by-2-0, Jahr des Datenbezugs in Klammern ({jahr}).
AerialSource(
    id = "bw",
    label = "Baden-Württemberg",
    wmsUrl = "https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C",
    layer = "IMAGES_DOP_20_RGB",
    minLat = 47.53,
    minLon = 7.50,
    maxLat = 49.80,
    maxLon = 10.50,
    attribution = "Luftbild: LGL-BW ({jahr}) Datenlizenz Deutschland - Namensnennung - Version 2.0, www.lgl-bw.de",
    license = "dl-de/by-2-0",
    licenseUrl = "https://www.govdata.de/dl-de/by-2-0",
),
// Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Brocken mittig), nahtlos, Baseline, bis 4096 px,
// ausserhalb weiss. Offene Daten des LVermGeo: dl-de/by-2-0, Bereitsteller „© GeoBasis-DE / LVermGeo ST“.
AerialSource(
    id = "st",
    label = "Sachsen-Anhalt",
    wmsUrl = "https://www.geodatenportal.sachsen-anhalt.de/wss/service/ST_LVermGeo_DOP_WMS_OpenData/guest",
    layer = "lsa_lvermgeo_dop20_2",
    minLat = 50.93,
    minLon = 10.55,
    maxLat = 53.05,
    maxLon = 13.20,
    attribution = "Luftbild: © GeoBasis-DE / LVermGeo ST, dl-de/by-2-0",
    license = "dl-de/by-2-0",
    licenseUrl = "https://www.govdata.de/dl-de/by-2-0",
),
```

## Belege Lizenz

- **Frankreich:** GetCapabilities `AccessConstraints`: „Conditions Générales
  d'Utilisation disponibles ici : https://cartes.gouv.fr/cgu“, `Fees`: „none“.
  CGU (Version du 15 octobre 2024): „Les données contenues dans les API sont
  soumises à la licence choisie et renseignée par le Fournisseur de données,
  accessible depuis les métadonnées de données de l'API.“; Annexe 2: „Sur la
  Géoplateforme, l'utilisation des géodonnées et géoservices est gratuite (sauf
  licence spécifique).“; Annexe 1: WMS-Raster „40 requêtes/s“. Metadatensatz
  der Ebene (`https://data.geopf.fr/csw?…&ID=IGNF_BD-ORTHO`): „Licence Ouverte
  / Open License (compatible ODC-BY, CC-BY 2.0)“ mit Link auf
  https://www.etalab.gouv.fr/wp-content/uploads/2018/11/open-licence.pdf (eine
  Versionsnummer nennt der Datensatz nicht). Licence Ouverte 2.0
  (https://www.data.gouv.fr/pages/legal/licences/etalab-2.0): „… à des fins
  commerciales ou non, dans le monde entier et pour une durée illimitée …“,
  „… ou en l'incluant dans votre propre produit ou application. Sous réserve
  de : mentionner la paternité de l'«Information» : sa source (a minima le nom
  du « Concédant ») et la date de la dernière mise à jour de l'« Information »
  réutilisée.“
- **Flandern:** GetCapabilities `Fees`: „Het gebruik van de service is
  kosteloos.“; `AccessConstraints` → Gebruiksrecht-Seite: „Gelieve de toegangs-
  en gebruiksbepalingen te raadplegen in de metadata van alle datasets …“.
  Metadatensatz `f5304d6d-0dd4-43fd-a726-427af31e8d61` (metadata.vlaanderen.be):
  „Modellicentie voor gratis hergebruik“
  (https://data.vlaanderen.be/id/licentie/modellicentie-gratis-hergebruik/v1.0)
  und „Bronvermeldingsvoorschrift:Bron: Luchtopnamen Digitaal Vlaanderen“.
  Lizenzregister: „Elk commercieel of niet-commercieel hergebruik voor
  onbepaalde duur is toegelaten, zonder dat daar kosten aan verbonden zijn. Als
  enige gebruiksvoorwaarde geldt een bronvermeldingsplicht.“ Lizenztext Art. 3:
  „… het niet-exclusieve en wereldwijde recht om het product te hergebruiken
  voor elk rechtmatig doeleinde, met inbegrip van het reproduceren, doorgeven,
  publiceren, aanpassen en commercieel exploiteren van het product.“; Art. 4:
  „… bij elk hergebruik van het product, een bronvermelding op te nemen zoals
  wordt gespecificeerd door de licentiegever.“
- **Spanien:** GetCapabilities `AccessConstraints`: „CC BY 4.0 scne.es“,
  `Fees`: „No se aplican condiciones“. Lizenz
  https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf:
  „… el uso de los productos y servicios de información geográfica del IGN …
  conlleva la aceptación por el usuario de una licencia CC-BY 4.0“; Produkte:
  „«<identificador del producto> <fecha> CC-BY 4.0 <atribución de productores>»“;
  Dienste (Nr. 2): „… la fórmula de reconocimiento establecida para los datos
  acoplados … y, si no hay datos acoplados, la fórmula que esté incluida en la
  autodescripción del servicio original, en la etiqueta <AccessConstraints>.“;
  Kurzform (Nr. 3 „Cuarto“): „«CC-BY 4.0 <atribución productores> <fecha>»“;
  abgeleitete Werke (Nr. 4): „Obra derivada de PNOA 2010-2013 CC-BY scne.es“.
  Die Nennung muss „visible junto con los datos … a pie de mapa, imagen,
  presentación o ventana de visualización“ stehen. Der im Auftrag vermutete
  Wortlaut „PNOA cedido por © Instituto Geográfico Nacional“ steht in dieser
  Lizenz nicht. Ob die 3D-Textur als „obra derivada“ zählt, ist nicht geklärt;
  `{jahr}` deckt beide Lesarten ab. Melilla-Bild „© Airbus DS“ liegt außerhalb
  der Rechtecke.
- **Toskana:** Capabilities `Fees`/`AccessConstraints`: „none“, aber Abstract:
  „Per obblighi di licenza gli strati delle ortofoto sono visualizzabili solo in
  abbinamento ai disclaimer dei rispettivi proprietari del dato.“ Ebene
  `rt_ofc.10k13`: „OFC 2013 10K propr. AGEA …“, MetadataURL →
  „SCHEDA DI METAINFORMAZIONE NON DISPONIBILE (DATASET NON RT)“. Die
  RT-eigenen Ebenen (`rt_ofc.2k*`, „proprieta' RT“, „Copertura … parziale“)
  decken den Testpunkt nicht.
- **USA:** USGS Copyrights and Credits
  (https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits):
  „USGS-authored or produced data and information are considered to be in the
  U.S. Public Domain.“ … „we ask that proper credit be given … Credit: U.S.
  Geological Survey“. ImageServer `copyrightText`: „USGS, USDA, The National
  Map: Orthoimagery. January 09, 2025.“; Basiskarte: „… allows free downloads of
  public domain, 1-meter resolution orthoimagery … for the conterminous United
  States. However, the 10-meter Alaska orthoimagery data will not be available
  … due to license restrictions.“
- **Hessen:** opendata.hessen.de, Datensatz `atkis-dop-20`: Lizenz
  „Datenlizenz Deutschland – Zero – Version 2.0“, Ressource „WMS Schnittstelle“
  = obige Adresse. GetCapabilities `AccessConstraints`: „Jede Nutzung der
  Geobasisdaten und zugehörigen Metadaten ist ohne Einschränkung oder Bedingung
  erlaubt. Die bereitgestellten Geobasisdaten und Metadaten dürfen für die
  kommerzielle und nicht kommerzielle Nutzung insbesondere … an Dritte
  übermittelt werden, … in interne und externe Geschäftsprozesse, Produkte und
  Anwendungen … eingebunden werden. Wird bei der Nutzung der Geobasisdaten oder
  deren Metadaten ein Quellenvermerk beigegeben, ist in diesem auf
  Veränderungen, Bearbeitungen, neue Gestaltungen oder sonstige Abwandlungen der
  Daten oder Metadaten hinzuweisen. (§ 18 HVGG)“. Wortlaut des Vermerks aus dem
  Dienst-Abstract: „Geobasisdaten © Hessische Verwaltung für Bodenmanagement und
  Geoinformation: Digitale Orthophotos“.
- **Baden-Württemberg:** GetCapabilities `AccessConstraints`: „Es gelten die
  Lizenzbedingungen ‚Datenlizenz Deutschland - Namensnennung - Version 2.0' …
  Als Rechteinhaber und Bereitsteller ist die Datenhaltende Stelle, sowie das
  Jahr des Datenbezugs in Klammern anzugeben. Beispiel für Quellenvermerk:
  LGL-BW (2024) Datenlizenz Deutschland - Namensnennung - Version 2.0,
  www.lgl-bw.de“; `Fees`: „Unentgeltliche Nutzung nach Open Data Lizenz“.
- **Sachsen-Anhalt:** Capabilities weiter ohne Lizenzangabe; MetadataURL
  (metaver.de) antwortete mit 429. Belegt über die Open-Data-Seite
  https://www.lvermgeo.sachsen-anhalt.de/de/gdp-open-data.html, die unter
  „Karten und Luftbilder“ die DOP führt: „Für die nachfolgend kostenfrei
  bereitgestellten Daten (außer Satellitenbilddaten, basemap.de, basemap.de
  P10/TK10) gelten die Nutzungsbedingungen des LVermGeo, wonach diese unter der
  Open-Data-Lizenz ‚Datenlizenz Deutschland – Namensnennung – Version 2.0'
  genutzt werden dürfen.“ Nutzungsbedingungen V 5.0 (Stand 01.03.2024), Nr. 2:
  „Offene Daten des LVermGeo dürfen frei genutzt werden unter der Lizenz
  ‚Datenlizenz Deutschland - Namensnennung - Version 2.0' … Der Bereitsteller
  nach Absatz 2 Nr. 1 dieser Lizenz ist mit ‚© GeoBasis-DE / LVermGeo ST' zu
  bezeichnen.“ Dass genau dieser WMS (`…_OpenData/guest`) gemeint ist, schließe
  ich aus dem Dienstnamen; die Seite selbst nennt als WMS nur den
  INSPIRE-Dienst `INSPIRE_LVermGeo_ATKIS_OI`.

## NRW-Katalogstrecken mit Laserdaten (24.11.2026)

`kahler-asten`, `balkantrasse` (neu) und `altenberg` (neu) liegen im
Register-Rechteck von NRW und nehmen das Luftbild `nw` (`www.wms.nrw.de`,
dl-de/zero-2-0, „Luftbild: © Geobasis NRW“) — kein neuer Luftbild-Dienst.
Mit `"laser": true` kommt im Manifest eine zweite Quellenangabe dazu
(Art `ELEVATION`): „Gelände, Bewuchs und Gebäude: © Geobasis NRW (DGM1,
DOM1, 3D-Gebäudemodell LoD2)“, dl-de/zero-2-0. Neue Hosts beim Bau (nicht in
der App): `www.wcs.nrw.de`, `www.opengeodata.nrw.de`. Hinweis: Die
Kandidatenliste von `catalog-build` nennt bei NRW-Strecken auch
„Frankreich“, weil dessen grobes Rechteck bis 51,15° N und 9,6° O reicht;
gebaut und genannt wird nur, was Bild liefert (Leerbild-Erkennung).
