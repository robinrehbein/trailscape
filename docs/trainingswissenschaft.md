# Trainingswissenschaft — die fachliche Grundlage von Trailscape

Dieses Dokument beschreibt, **was die App rechnet, worauf sich das stützt und wie
belastbar es ist**. Es ersetzt die verstreuten Begründungen in den Code-Kommentaren
nicht, sondern ordnet sie: Jede Zahl ist aus dem Code gelesen (mit Fundstelle),
jede Literaturangabe ist gegen PubMed bzw. den Verlag geprüft (Stand 2026-11-24).

Die Kommentare im Code verweisen auf ein Dokument „Trailscape — Sportwissenschaftliche
Berechnungsbasis" mit §-Nummern (Stand 2026-08-08). Dieses Dokument liegt nicht im
Repository. Wo ein Kommentar eine §-Nummer nennt, steht sie hier in Klammern dabei,
damit sich beide Texte zuordnen lassen.

Inhalt: 0 Lesehilfe · 1 Herzfrequenz-Anker · 2 Lastmessung · 3 Fitness, Ermüdung,
Form · 4 Belastungsverhältnis akut/chronisch · 5 Erholungssignale (HRV, Ruhepuls,
Schlaf, Readiness) · 6 Intensitätsverteilung · 7 Intervallformen · 8 Belastungs-
steigerung · 9 Entlastungswochen und Blockstruktur · 10 Taper · 11 Längste
Vorbereitungsfahrt und Zieltypen · 12 FTP und 20-min-Test · 13 Kalorien und Wirkungsgrad ·
14 VO2max-Schätzung · 15 Was die App bewusst nicht behauptet · 16 Literatur

---

## 0 Lesehilfe

### 0.1 Einstufung der Evidenz

Jeder Abschnitt trägt eine Einstufung. Sie beschreibt, wie gut die **Regel, so wie
die App sie anwendet**, gestützt ist — nicht, wie gut das zugrunde liegende Konzept
ist.

| Stufe | Bedeutung |
|---|---|
| **belegt** | Durch kontrollierte Studien oder Meta-Analysen gestützt; die App übernimmt die Zahl im Wesentlichen. |
| **gängige Praxis** | In Trainingslehre, Software (TrainingPeaks, intervals.icu u. a.) oder Lehrbüchern verbreitet, aber nicht oder nur schwach experimentell geprüft — oder die App überträgt einen Befund auf eine andere Population. |
| **Faustregel** | Eigene Festlegung der App, nachvollziehbar begründet, aber ohne direkte Quelle. |

### 0.2 Grundsätze, die überall gelten

- **Geschätzt ist nicht gemessen.** Jede abgeleitete Größe trägt eine `Confidence`
  (`TrainingLoad.kt`, Kopfkommentar, §0/§8.5). Werte aus Formeln (HFmax nach Tanaka,
  eFTP, VO2max) werden als Schätzung beschriftet.
- **Persönliche Baseline statt Normtabelle.** Ruhepuls, HRV und Schlaf werden gegen
  die eigene Vergangenheit bewertet, nicht gegen Bevölkerungswerte (§5).
- **Beschreiben, nicht diagnostizieren.** Die App gibt Trainingshinweise. Sie stellt
  keine medizinischen Aussagen auf, sagt kein Verletzungs- oder Krankheitsrisiko voraus
  und ersetzt keine ärztliche Abklärung.

---

## 1 Herzfrequenz-Anker (§1)

### 1.1 HFmax nach Tanaka

**Was die App rechnet:** `HFmax = 208 − 0,7 × Alter`, geklemmt auf 120–230 bpm;
ein eingetragener Wert hat Vorrang (`TrainingLoad.kt`, `TrainingProfile.tanakaHrMax`,
`hrMax`).

**Grundlage:** Tanaka, Monahan & Seals (2001) leiteten die Gleichung meta-analytisch
aus 351 Studien (18 712 Personen) ab und bestätigten sie im Labor an 514 gesunden
Erwachsenen (dort 209 − 0,7 × Alter). Laut den Autoren hängt sie weder vom Geschlecht
noch vom Trainingszustand ab; die alte Formel 220 − Alter unterschätzt die HFmax
im Alter.

**Evidenz:** belegt (für die Gruppenvorhersage).

**Grenzen:** Für den Einzelnen ist die Streuung groß. Sekundärquellen nennen einen
Standardschätzfehler von rund 10 bpm; der Code-Kommentar „SEE ≈ ±10 bpm" ist damit
plausibel, im Originalvolltext aber nicht eingesehen. Ein 10-bpm-Fehler verschiebt
alle HF-Zonen und den TRIMP. Deshalb gewinnt ein gemessener Wert immer.

### 1.2 Ruhepuls und Schwellen-HF

- Ohne Messreihe gilt ein Ruhepuls von 60 bpm (`defaultRestingHrBpm`, §1.2) — **Faustregel**.
- Schwellen-HF (LTHR) ohne Test: `0,89 × HFmax`, immer im Fenster 0,80–0,95 × HFmax
  (`defaultLthrFactor`, `lthrMinFactor`, `lthrMaxFactor`, §1.3) — **Faustregel**.
  Der Faktor ist eine grobe Mittelung; die individuelle Schwelle schwankt stark.

### 1.3 Zonenmodelle

| Modell | Grenzen im Code (`TrainingLoad.kt`, `TrainingZones`) | Quelle | Einstufung |
|---|---|---|---|
| Friel (5 Zonen, Rad) | Z2 ab 81 %, Z3 ab 90 %, Z4 ab 94 %, Z5 ab 100 % LTHR | Friel, *The Cyclist's Training Bible*; Prozentwerte in Sekundärquellen bestätigt, Buch nicht eingesehen | gängige Praxis |
| Lucía (3 Domänen) | LIT < 0,85 × LTHR, MIT bis LTHR, HIT darüber | Lucía et al. (2003) verwenden die ventilatorischen Schwellen VT1/VT2; die LTHR-Abbildung ist eine **eigene Näherung** | Faustregel |
| Edwards (%HFmax) | 50–60–70–80–90–100 % HFmax | Edwards (1993), Buch, nicht eingesehen | gängige Praxis |
| Karvonen | `HFruhe + Anteil × (HFmax − HFruhe)` | Karvonen, Kentala & Mustala (1957) | belegt (als Rechenweg) |

Die benutzten Zonengrenzen werden pro Tour mitgespeichert (`zonesUsed`), damit eine
spätere Korrektur die alte Verteilung nicht verschiebt.

---

## 2 Lastmessung (§2, §3)

Alle Lastpfade münden in **eine Skala**: „1 Stunde an der Schwelle = 100 Punkte",
gedeckelt bei 500 (`maxLoad`). Welcher Pfad gilt, entscheidet eine Kaskade
(`computeRideLoad`, §3.1): **A** Herzfrequenz (≥ 80 % der Bewegungszeit mit Puls,
`minHrCoverage`) → **B** gemessene Leistung → **B** Physikmodell → **C** Anstrengungs-
empfinden (sRPE) → **D** Distanz/Höhenmeter-Heuristik.

### 2.1 Banister-TRIMP (Stufe A)

**Was die App rechnet** (`trimpSampleContribution`, §2.1): je Sample

```
x     = (HF − HFruhe) / (HFmax − HFruhe), geklemmt auf 0 … 1,05
TRIMP += Minuten × x × a × e^(b·x)
a, b  = 0,64 / 1,92 (Männer und „unbekannt"), 0,86 / 1,67 (Frauen)
```

Normiert wird auf eine Stunde exakt an der LTHR (`trimpReference`, `normalizeTrimp`,
§2.4): `Last = 100 × TRIMP / TRIMP_ref`. Nebenher werden Edwards-TRIMP (§2.2) und
Lucía-TRIMP (`1×LIT + 2×MIT + 3×HIT`, §2.3) nur informativ geführt.

**Grundlage:** Das Impuls-Antwort-Modell mit herzfrequenzgewichtetem Trainingsimpuls
geht auf Banister zurück (Morton, Fitz-Clarke & Banister 1990; Banister 1991, Buch-
kapitel). Die exponentielle Gewichtung bildet ab, dass die Laktatantwort mit der
Intensität überproportional steigt.

**Evidenz:** gängige Praxis. Das Modell ist etabliert; die konkreten Koeffizienten
werden in der Sekundärliteratur einheitlich so wiedergegeben, die Primärquelle
(Banister 1991) war online nicht einsehbar. Die Normierung auf „1 h an der Schwelle
= 100" ist eine **eigene Festlegung**, die HF- und Leistungsskala vergleichbar machen
soll (analog zum hrTSS gängiger Software).

**Grenzen:** Herzfrequenz reagiert träge (kurze harte Antritte werden unterschätzt),
driftet bei Hitze und Dehydrierung nach oben und hängt an den Ankern HFmax/HFruhe/LTHR.
Ohne Geschlechtsangabe senkt die App die Confidence.

### 2.2 eTSS mit IF²-Normierung (Stufe B)

**Was die App rechnet** (`computePhysicsEstimate`, §3.3):

```
NP  = (Mittel der 4. Potenz des 30-s-Gleitmittels)^(1/4)     (normalizedPowerW)
IF  = NP / FTP
eTSS = Stunden × IF² × 100
```

Mit Leistungsmesser (≥ 80 % gemessene Leistung, `MEASURED_POWER_MIN_COVERAGE`)
gilt die Messung. Ohne ihn schätzt ein Physikmodell die Leistung aus GPS, Höhe,
Masse (Fahrer + 12 kg Setup), CdA 0,38 m², Crr 0,008, Antriebswirkungsgrad 0,97 und
Luftdichte nach Höhe (`defaultSetupMassKg`, `defaultCda`, `defaultCrr`,
`defaultDriveEfficiency`, `airDensity`, §3.2). Die Physiklast wird mit einem Faktor
α = Median(HF-Last / Physiklast) an die HF-Skala angeglichen, gültig 0,4–2,0
(`alphaMin`, `alphaMax`).

**Grundlage:** Normalized Power, Intensity Factor und Training Stress Score stammen
von Coggan (Allen, Coggan & McGregor 2019). TSS ist so definiert, dass eine Stunde
an der FTP 100 ergibt — das ist die Skala, auf die Trailscape alle Pfade abbildet.

**Evidenz:** gängige Praxis (weit verbreitet, aber proprietär entstanden und wenig
unabhängig validiert). Das Physikmodell selbst: **Faustregel**-Parameter, im Code
als „medium" eingestuft.

**Grenzen:** Wegen `eTSS ∝ 1/FTP²` schlägt ein FTP-Fehler quadratisch durch (25 % zu
niedrige FTP → Faktor 1,8 auf jede Last; im Code ausdrücklich vermerkt). Das
Physikmodell kennt weder Wind noch Untergrund noch Windschatten; der Fehler liegt
laut Code-Kommentar bei ±15–25 %.

### 2.3 sRPE nach Foster (Stufe C)

**Was die App rechnet:** `Last = (1/6) × Bewegungsminuten × RPE` mit RPE auf der
Skala 0–10 (`defaultRpeFactor`, `computeRideLoad`, §3.4). Eine Stunde mit RPE 10
ergibt damit 100 Punkte.

**Grundlage:** Foster et al. (2001) zeigten, dass „Session-RPE × Dauer" mit einem
HF-basierten Lastmaß konsistent zusammenhängt, auch bei Intervallen. Eine Übersicht
(Haddad et al. 2017) fand 36 Studien, die Validität und Reliabilität der Methode
mit der modifizierten CR-10-Skala bestätigen. Foster (1998) nutzte dieselbe Größe
für Monotonie und Strain.

**Evidenz:** belegt (für die Methode). Der Umrechnungsfaktor 1/6 auf die 100er-Skala
ist eine **Faustregel**. Eine individuelle Kalibrierung gibt es nicht: `computeRideLoad`
nimmt den Faktor als Parameter, aber kein Aufrufer überschreibt ihn — es gilt immer
der Default (der frühere Kommentar „sRPE-Kalibrierung" war irreführend und ist
korrigiert).

**Grenzen:** Subjektiv, abhängig von Tagesform und Erfahrung; Foster empfiehlt die
Abfrage rund 30 Minuten nach der Einheit.

### 2.4 Heuristik (Stufe D)

`Last = Stunden × 55 × Faktor`, Faktor = (km + Hm/10) / (Stunden × 22), geklemmt
0,7–1,5 (`heuristicLoad`, §3.5). **Faustregel**, letzte Instanz, immer Confidence LOW.

---

## 3 Fitness, Ermüdung, Form (§4.2–§4.3)

### 3.1 Impuls-Antwort-Modell, 42/7 Tage

**Was die App rechnet** (`PerformanceManagement.kt`, `computeFitnessSeries`;
Konstanten `lambdaCtl`, `lambdaAtl` in `TrainingLoad.kt`):

```
λ_ctl = 1 − e^(−1/42)      λ_atl = 1 − e^(−1/7)
CTL_t = CTL_{t−1} + λ_ctl × (Last_t − CTL_{t−1})     „Fitness"
ATL_t = ATL_{t−1} + λ_atl × (Last_t − ATL_{t−1})     „Ermüdung"
TSB_t = CTL_{t−1} − ATL_{t−1}                          „Form"
```

Startwert: Mittel der ersten 42 Tage (ab 14 Tagen Historie das Mittel der
vorhandenen), angezeigt wird die Kurve erst ab 28 Tagen (`displayReady`).

Formbänder (`classifyTsb`): > 25 sehr frisch · 5…25 Formspitze · −10…5 neutral ·
−30…−10 produktiv · < −30 Überlastung.

**Grundlage:** Banisters Modell (Morton et al. 1990): Ein Trainingsimpuls erzeugt
zwei Antworten — Fitness und Ermüdung —, die exponentiell abklingen; Leistung ist
ihre Differenz. Die Zeitkonstanten 42 und 7 Tage und die Bezeichnungen CTL/ATL/TSB
sind die Voreinstellung des „Performance Manager" von Coggan/TrainingPeaks.

**Evidenz:** Modell **belegt** als Beschreibungsrahmen; die Zeitkonstanten 42/7 und
die Formbänder sind **gängige Praxis**.

**Grenzen:** Hellard et al. (2006) zeigten bei Eliteschwimmern, dass die individuell
geschätzten Zeitkonstanten sehr breite Konfidenzintervalle haben (Fitness z. B.
38 Tage, 95 %-KI 17–59; Ermüdung 19 Tage, 6–32) und Parameter stark korrelieren.
Busso (2003) zeigte, dass ein nichtlineares Modell mit lastabhängiger Ermüdung die
Leistung besser abbildet als das lineare Original. Feste 42/7 Tage sind also eine
brauchbare Konvention, keine individuelle Physiologie. Die Kurve ist zudem nur so gut
wie die Lasten, aus denen sie gebaut ist (siehe FTP-Abhängigkeit in 2.2).

### 3.2 Rampenrate

**Was die App rechnet:** `Rampe = CTL_t − CTL_{t−7}` (CTL-Punkte pro Woche),
Bänder (`classifyRampRate`, §4.3): < 0 Formverlust · 0–3 Erhaltung · 3–6 Aufbau ·
6–8 aggressiv · > 8 zu schnell. Standard-Zielrampe für Wochenziel und Plan:
4 CTL/Woche (`defaultTargetRampPerWeek`, §6.3).

**Evidenz:** gängige Praxis (Bänder dieser Größenordnung sind in Trainingssoftware
verbreitet; eine experimentelle Herleitung ist nicht bekannt, eine Primärquelle für
die konkreten Grenzen wurde nicht gefunden).

### 3.3 Wochenziel aus der Zielrampe

`Wochenlast = 7 × (CTL + Rampe / (1 − (1 − λ_ctl)^7))`, gedeckelt auf 130 % des
Vierwochenmittels und auf `Wochenstunden × 58` (`weeklyLoadTarget`,
`ctlWeeklyResponse`, `weeklyLoadPerHour`, §6.3). Die 58 Last/h sind aus der eigenen
IF²-Normierung und einer pyramidalen Mischung hergeleitet (Kommentar an
`weeklyLoadPerHour`) — **Faustregel**, sauber dokumentiert. Zum 130-%-Deckel siehe 8.

Diese Rampenformel gilt für das **empfohlene Wochenziel** (und die Zielprognose), nicht
mehr für den Trainingsplan: Dort entsteht die Last jeder Einheit aus ihrer Struktur
(siehe 7 und 8), und die Rampe wirkt nur als Deckel.

**CTL-Vorschau zum Renntag** (`projectedEventCtl`, `GoalPrognosis.kt`): Für Pläne des
heutigen Generators wird die CTL über die geplanten Wochenlasten fortgeschrieben
(`CTL ← CTL + (Wochenlast/7 − CTL) × ctlWeeklyResponse`). **Gespeicherte Altpläne**
(vor dem Planumbau; erkannt an Intervallen alten Formats bzw. einem rein lockeren
Taper ohne gespeichertes Tempo und ohne Präferenzen, `isLegacyGeneratorPlan`) tragen
zwar auch Last-Ziele, aber aus einem hochskalierten Wochenbudget; sie behalten bewusst
die alte Rechnung „+4 CTL je verbleibender Aufbauwoche" — sonst änderte sich ihre
Vorschau still (Beispiel CTL 40, Einsteiger: 68 → 24).

---

## 4 Belastungsverhältnis akut/chronisch (§4.4)

**Was die App rechnet** (`computeFitnessSeries`):

```
akut     = EWMA mit λ = 2/(7+1)
chronisch = EWMA mit λ = 2/(28+1), Wert von vor 7 Tagen („entkoppelt")
Verhältnis = akut / chronisch, unterdrückt bei chronischer Wochenlast < 20
```

Bänder (`classifyLoadRatio`, `loadRatioBandLow/High`): < 0,8 niedrig · 0,8–1,5 im
Band · > 1,5 Belastungssprung. Gabbett (2016) nennt 0,8–1,3 als günstigen Bereich und
ab 1,5 als kritisch; das Band der App (obere Grenze 1,5) ist eine **eigene Konvention**. Ein Sprung erzeugt nur einen **weichen Hinweis**
(`assessDeload` → `warnings`), keinen Deload-Auslöser.

**Grundlage:** Das Verhältnis wurde vor allem durch Gabbett (2016) bekannt: Er
beschreibt 0,8–1,3 als „sweet spot" und ≥ 1,5 als „danger zone" für Verletzungen im
Mannschaftssport und mahnt selbst zur Vorsicht bei Einzelsportarten. Williams et al.
(2017) schlugen exponentiell gewichtete Mittel (EWMA) statt rollierender Summen vor;
Lolli et al. (2019) zeigten, dass die „gekoppelte" Variante (akute Woche steckt im
chronischen Nenner) eine Scheinkorrelation erzeugt.

**Kritik:** Impellizzeri et al. (2020) halten fest, dass kein Kausalzusammenhang
zwischen ACWR und Verletzungen nachgewiesen ist, das Verhältnis statistisch
problematisch ist (es normiert den Zähler auch entkoppelt nicht sauber) und es
**keine Evidenz für den Einsatz in Trainingsempfehlungen zur Verletzungsprävention**
gibt. Impellizzeri et al. (2021) folgern, die chronische Last spiele nicht die
behauptete Rolle, und empfehlen, das ACWR samt zugrunde liegender Theorie
aufzugeben.

**Was daraus für Trailscape folgt:** Die App nennt den Wert bewusst **nicht
„Verletzungsrisiko"** (Kommentar an `LoadRatioBand`) und benutzt ihn nur als
beschreibenden Hinweis „deine Belastung ist deutlich über dem Gewohnten". Das ist mit
der Kritik vereinbar.

**Evidenz:** Als Beschreibung eines Lastsprungs **gängige Praxis**; als
Risikoprädiktor **nicht belegt** (und so auch nicht verwendet). Das Band 0,8–1,5 ist
eine **eigene Konvention** — die frühere Herkunftsangabe „Garmin-Konvention" im Code ließ
sich nicht verifizieren und wurde aus dem Kommentar entfernt; dort stehen jetzt Gabbett
(2016) und die Kritik von Impellizzeri et al. (2020).

**Grenzen:** Die Verschiebung des chronischen Nenners um 7 Tage nähert die
„entkoppelte" Variante an, ist aber nicht identisch mit ihr.

---

## 5 Erholungssignale (§5)

### 5.1 HRV: ln rMSSD, Wochenmittel, Normalband

**Was die App rechnet** (`Readiness.kt`, `assessHrv`; Konstanten in `TrainingLoad.kt`):

- Plausible Tageswerte 5–300 ms, ausgewertet wird **ln(rMSSD)**.
- **Rollmittel** der letzten 7 Tage (`hrvRollingDays`), mindestens 3 Werte.
- **Baseline**: Mittel und Standardabweichung der Tage 7–59 davor (`hrvBaselineDays = 60`,
  Rollfenster ausgeschlossen), mindestens 14 Werte, σ mindestens 0,05.
- **Normalband**: Baseline ± 0,75 σ (`hrvBandFactor`). Unter dem Band → GELB.
- **Eskalation** zusätzlich auf der Standardfehler-Skala σ/√n: ORANGE ab z ≤ −1,0 und
  z_mean ≤ −3,0; ROT ab z ≤ −1,5 und z_mean ≤ −5,0.
- **Über dem Band bei gleichzeitig auffälligem Ruhepuls** → Status `SAETTIGUNG`,
  ORANGE; für den Nutzer ein **uneinheitliches Signal** (HRV hoch, Ruhepuls hoch),
  beschreibend und keine Diagnose. Der Enum-Name ist historisch und bleibt (er wird in
  Tests und UI verwendet), meint aber **nicht** die Sättigung nach Plews et al. (s. u.).
- **Über dem Band bei unauffälligem Ruhepuls** → `UEBER_BAND`, GRÜN, ohne Bonus und ohne
  Abzug im Readiness-Score; der Text bewertet es als „weder gut noch schlecht".

**Grundlage:** Plews et al. (2013, IJSPP) zeigten, dass das **Wochenmittel** von
ln rMSSD (und Ruhepuls) Trainingsanpassungen deutlich besser abbildet als ein
Einzeltag (z. B. r = 0,72 statt −0,06 gegenüber der aeroben Maximalgeschwindigkeit).
Plews et al. (2013, Sports Med) empfehlen geeignete Mittelungsverfahren und
individuelle Langzeitbeobachtung; Plews et al. (2012) nutzten die Variabilität des
Wochenmittels. Buchheit (2014) betont, Veränderungen gegen Messfehler und
kleinste bedeutsame Veränderung (SWC) des Einzelnen zu werten und den
Trainingskontext einzubeziehen.

**Evidenz:** ln rMSSD und Wochenmittel: **belegt**. Die konkrete Bandbreite 0,75 σ,
das 60-Tage-Fenster und die SEM-Eskalation: **Faustregel** — eine **eigene,
bewusst konservativere Festlegung**. Die HRV-gesteuerten Studien (5.2) vergleichen ein
7-Tage-Mittel mit einer 28-Tage-Baseline und ± 0,5 σ. Die App nimmt eine längere
Baseline, die das Rollfenster ausschließt (ein anhaltender Einbruch zieht so seine
eigene Referenz nicht mit), und ein breiteres Band. Frühere Kommentare, Plews & Laursen
oder HRV4Training nutzten ein 60-Tage-Fenster, ließen sich nicht verifizieren und sind
entfernt.

**Grenzen:**
- Bellenger et al. (2016, Meta-Analyse): Ruhe-HRV steigt bei positiver Anpassung
  leicht, ist aber bei Überbeanspruchung („overreaching") **weitgehend unverändert**.
  Eine unauffällige HRV ist also kein Beweis für Erholung.
- Le Meur et al. (2013): Bei funktionell überbeanspruchten Ausdauersportlern
  **stieg** die parasympathische Aktivität (Wochenmittel ln rMSSD) bei **sinkendem**
  Puls. Ein Wert über dem Band ist daher nicht automatisch „gut".
- „Sättigung" heißt bei Plews et al. (2013, Sports Med) etwas anderes: ln rMSSD
  **sinkt trotz sinkendem Ruhepuls** (bei sehr fitten Athleten mit niedrigem Puls).
  Das Muster der App (HRV hoch, Ruhepuls hoch) ist damit nicht gemeint. Die App
  schreibt es deshalb nicht dieser Quelle zu, sondern nennt es ein uneinheitliches
  Signal und rät nur zur Vorsicht („beobachte die nächsten Tage"); eine eigene
  Festlegung ohne direkte Quelle.
- Messbedingungen (Uhr am Handgelenk, Schlafphase, Alkohol, Infekt) beeinflussen den
  Wert; die App misst nicht selbst, sondern übernimmt Plattformwerte.

### 5.2 HRV-gesteuertes Training

**Was die App tut:** Liegt die HRV unter dem Normalband, wird ein harter Reiz vertagt
(`recommendToday`: `HARTE_EINHEIT` nur ohne HRV-Flag ≥ GELB); bei ORANGE gibt es nur
lockere Grundlage, bei ROT einen Ruhetag.

**Grundlage:**

| Studie | Population | Regel | Ergebnis |
|---|---|---|---|
| Kiviniemi et al. 2007 | 26 mäßig trainierte Männer, Laufen, 4 Wochen | Einzeltag HF-Power; unter (10-Tage-Mittel − 1 SD) oder 2 Tage fallend → locker/Ruhe | VO2peak nur in der HRV-Gruppe signifikant gestiegen; Max-Geschwindigkeit stärker gestiegen |
| Vesterinen et al. 2016 | 40 Freizeitläufer, 8 Wochen | 7-Tage-Mittel rMSSD gegen 4-Wochen-Baseline, SWC = Mittel ± 0,5 SD; außerhalb → locker | Gleiche bzw. bessere 3000-m-Leistung mit **weniger** harten Einheiten (13,2 vs. 17,7) |
| Javaloyes et al. 2019 | 17 gut trainierte Radfahrer, 8 Wochen | 7-Tage-Mittel ln rMSSD, 4 Wochen Baseline, SWC = Mittel ± 0,5 SD | 40-min-Zeitfahren +7,3 % nur in der HRV-Gruppe; **kein signifikanter Gruppenunterschied** |

Die Regeln der beiden jüngeren Studien sind in der Meta-Analyse von
Manresa-Rocamora et al. (2021) tabelliert. Diese Meta-Analyse fand einen Vorteil
HRV-gesteuerten Trainings für vagale HRV-Indizes, für VO2max, Schwellenleistung und
Ausdauerleistung aber nur **kleine, nicht signifikante** Effekte (SMD ≈ 0,2).

**Evidenz:** gängige Praxis mit unterstützender, aber schwacher Studienlage
(kleine Stichproben, Gruppenunterschiede meist nicht signifikant). Ehrlich formuliert:
HRV-Steuerung erreicht mindestens ähnliche Anpassungen mit tendenziell weniger harten
Einheiten — eine deutliche Überlegenheit ist nicht gezeigt.

### 5.3 Ruhepuls

**Was die App rechnet** (`assessRestingHeartRate`, §5.1): Baseline = Median der Tage
8–60, Streuung = 1,4826 × MAD (mind. 1,5 bpm), mind. 21 Werte; bewertet wird der
Median der letzten 3 Tage.
GELB: +3 bpm und z ≥ 1 an ≥ 2 Messungen in Folge · ORANGE: +5 bpm und z ≥ 1,5 ·
ROT: +8 bpm oder ≥ 3 Messungen in Folge ≥ +5 bpm.

**Grundlage:** Plews et al. (2013, IJSPP): Auch beim Ruhepuls ist das Wochenmittel
aussagekräftiger als der Einzeltag. Buchheit (2014) zur Interpretation gegen die
individuelle Streuung.

**Evidenz:** Robuste Baseline: gängige Praxis. Die bpm-Schwellen: **Faustregel**.

**Grenzen:** Der Ruhepuls reagiert auf vieles außer Training (Hitze, Koffein,
Alkohol, Infekt, Stress). Die App gibt nur Trainingshinweise.

### 5.4 Schlaf

**Was die App rechnet** (`assessSleep`, §5.2): Baseline = Median der letzten
28 Nächte (geklemmt 4,5–9,5 h), mind. 14 Nächte; Abweichung der letzten Nacht und
Defizit der letzten 7 Nächte. GELB ab −1 h oder z ≤ −1 · ORANGE ab −1,5 h oder 7-Tage-
Defizit ≥ 4 h · ROT ab −2,5 h bei gleichzeitig auffälligem Ruhepuls. Liegt die
eigene Baseline unter 6,5 h, erscheint höchstens einmal im Monat ein separater
Hinweis, der die Tagesempfehlung **nicht** drosselt.

**Grundlage:** AASM/SRS-Konsens (Watson et al. 2015): Erwachsene sollten
regelmäßig **7 Stunden oder mehr** schlafen. Fullagar et al. (2015, Übersicht):
Schlafverlust kann Leistung und kognitive Funktion beeinträchtigen, die Befunde zur
Ausdauerleistung sind aber uneinheitlich.

**Evidenz:** Empfehlung ≥ 7 h: belegt (Konsens). Die Abweichungsschwellen:
**Faustregel**.

**Grenzen:** Schlafdauer aus Wearables ist eine Schätzung; Schlafqualität fließt nicht
ein.

### 5.5 Readiness-Score

**Was die App rechnet** (`computeReadiness`, §5.3/§5.4): gewichtetes Mittel normierter
Strafterme — HRV 0,40 · Ruhepuls 0,25 · Schlaf 0,20 · Form (TSB) 0,15
(`readinessWeight…`); fehlende Signale werden herausgerechnet und die übrigen
Gewichte renormiert. Bänder: ≥ 80 hart möglich · 60–79 normal · 40–59 locker ·
< 40 Ruhe (`classifyReadiness`).

**Evidenz:** **Faustregel**. Kein veröffentlichter, validierter Score; die Gewichtung
folgt der Plausibilität (HRV misst den Zustand direkt, Schlaf ist Einflussfaktor,
TSB ist ein Modellwert). Der Score sollte in der App als Orientierung, nicht als
Messwert beschrieben werden.

---

## 6 Intensitätsverteilung (§6.3)

**Was die App rechnet:** Zielverteilung LIT : MIT : HIT = 80 : 5 : 15 (polarisiert)
bzw. 75 : 15 : 10 (pyramidal) (`intensityDistributionTarget`,
`PerformanceManagement.kt`; nur in der Herleitung von `weeklyLoadPerHour` und in
Kommentaren benutzt). Höchstens 2 harte Tage pro 7 Tage
(`MAX_HARD_DAYS_PER_7D`, `app/…/TrainingInsights.kt`; im Plan `PLAN_MAX_HARD_DAYS_PER_7D`,
`PlanWeeks.kt`), nie zwei harte Tage nebeneinander, nie ein harter Tag direkt vor oder
direkt nach der langen Tour (`chooseQualityDays`, `enforceHardDayRules`). Trainingstage
und Qualitätstage werden gemeinsam gewählt (`chooseTrainingDays`); lässt sich der Tag
nach der langen Tour nicht vermeiden (z. B. nur Sa/So verfügbar), entfällt die
Qualität, statt hart am Folgetag zu liegen. Im Plan gibt es je Woche höchstens eine Qualitätseinheit
bei bis zu vier Einheiten, ab fünf Einheiten zwei (`maxQualityFor`, grob 80/20 nach
Einheiten, Seiler 2010). Einsteiger fahren die ersten 3 Aufbauwochen und die
Grundlagenphase reine Grundlage (`BEGINNER_BASE_WEEKS`), danach einen sanften
Tempo-Reiz.

**Grundlage:**

- Seiler & Kjerland (2006): Junioren-Skilangläufer trainierten ~75 % in Zone 1,
  ~8 % in Zone 2, ~17 % in Zone 3 (HF-basiert; ähnlich nach Session-RPE).
- Seiler (2010, Übersicht): Bei Spitzensportlern sind rund **80 % der Einheiten**
  niedrig intensiv, rund 20 % enthalten hochintensive Abschnitte; für schon gut
  trainierte Athleten gibt es keinen überzeugenden Beleg, dass mehr Intensität
  langfristig mehr bringt.
- Stöggl & Sperlich (2014): Bei 48 gut trainierten Ausdauersportlern brachte
  polarisiertes Training (tatsächlich 68 : 6 : 26) über 9 Wochen die größten
  Zuwächse (VO2peak +11,7 %); Schwellen- und reines Umfangstraining brachten wenig.
- Neal et al. (2013): Bei trainierten Radfahrern (Crossover, je 6 Wochen) war
  polarisiert (80 : 0 : 20) dem Schwellenmodell bei Spitzenleistung und Laktatschwelle
  überlegen.
- Muñoz et al. (2014): Bei Freizeitläufern kein signifikanter Unterschied im
  10-km-Ergebnis (5,0 % vs. 3,6 %), nur in einer Teilauswertung.
- Rosenblat et al. (2019, Meta-Analyse, 3 Studien): moderater Vorteil polarisiert
  gegenüber Schwelle im Zeitfahren.
- Silva Oliveira et al. (2024, Meta-Analyse, 17 Studien): polarisiert leicht besser
  für VO2peak (SMD 0,24), v. a. bei kurzen Interventionen und hoch trainierten
  Athleten; **kein Unterschied** bei Zeitfahren und Schwellenleistung.
- Filipas et al. (2022): Bei gut trainierten Läufern verbesserten alle Verteilungen;
  am meisten der Wechsel pyramidal → polarisiert.

**Evidenz:** „Überwiegend locker, wenig hart" ist **belegt**. Dass polarisiert einer
pyramidalen Verteilung klar überlegen sei, ist **nicht** belegt — die Unterschiede
sind klein. Für Freizeitfahrer mit wenig Zeit ist eine pyramidale Verteilung
vertretbar (**gängige Praxis**).

**Grenzen:** Prozentangaben hängen stark davon ab, ob man **Einheiten** zählt
(Seiler 2010: 80/20) oder **Zeit in Zonen** (dann ist der lockere Anteil meist
deutlich höher). Die App sollte angeben, was sie zählt.

---

## 7 Intervallformen

**Was die App baut** (`PlanWorkouts.kt`, `WorkoutKind`, `progressionSteps`;
Auswahl je Stufe und Phase in `qualityKindsFor`, `PlanWeeks.kt`). Jede Qualitätseinheit
hat 15 min Einfahren und 10 min Ausfahren; die Pause zwischen den Belastungen steht
je Art fest:

| Art | Zone (Anteil FTP) | Pause | Progressionsstufen (Wdh. × min) |
|---|---|---|---|
| Tempo | 76–87 % | 4 min | 3×5 · 4×5 · 3×8 · 4×8 · 3×12 |
| Sweet Spot | 88–94 % (keine formale Zone) | 5 min | 2×10 · 3×10 · 3×12 · 3×15 · 4×15 |
| Schwelle | 95–105 % | 3 min | 3×8 · 4×8 · 5×8 · 4×12 |
| VO2max | 106–120 %, Puls 90–95 % HFmax | 3 min | 3×4 · 4×4 · 5×4 · 6×4 |

Ambitionierte steigen eine Stufe höher ein (`levelStepOffset`). Die Belastungszeit
steigt streng monoton; die Schritte sind **Faustregeln** der Trainingspraxis. Die
Leistungszonen folgen Allen & Coggan (2019), die Pulsgrenzen den Friel-Zonen der App
(1.3).

### 7.1 4 × 8 min (Seiler et al. 2013)

35 trainierte Freizeitradfahrer (VO2peak 52 ml/kg/min), 7 Wochen, **2 Intervall-
einheiten pro Woche** plus 2–3 lockere Einheiten. Verglichen: 4 × 4, 4 × 8,
4 × 16 min, jeweils mit der **höchsten für die ganze Einheit durchhaltbaren
Intensität**. Ergebnis: 4 × 8 min lief bei **~90 % HFpeak** (Laktat ~9,6 mmol/l)
und brachte die größten Zuwächse (VO2peak, Leistung bei VO2peak und bei 4 mmol/l,
im Mittel +11,4 % gegenüber 4,2–5,6 %). Die Autoren: 32 min bei ~90 % HFmax bringen
mehr als 16 min bei ~95 % HFmax — bei geringerem Anstrengungsempfinden.

**Pause:** 2 Minuten. Das steht im Abstract nicht; belegt ist es durch das
Folgeprotokoll derselben Arbeitsgruppe (Sylta et al. 2016: „four interval bouts of
4, 8, or 16 min separated by a 2-min rest", 20–30 min Einfahren bei 55–70 % HFpeak)
und übereinstimmende Sekundärquellen. Der Originalvolltext von 2013 war nicht
einsehbar.

**Einordnung:** 4 × 8 ist eine **hochintensive** Einheit (über der Schwelle,
VO2max-nah), kein Schwellentraining.

### 7.2 4 × 4 min (Helgerud et al. 2007)

40 mäßig trainierte Männer, **Laufen**, 8 Wochen, **3 Einheiten pro Woche**:
4 × 4 min bei **90–95 % HFmax** mit **3 min aktiver Pause bei ~70 % HFmax**.
VO2max +7,2 % (4 × 4) bzw. +5,5 % (15/15), signifikant mehr als langsames
Dauertraining (70 % HFmax) oder Schwellentraining (85 % HFmax) bei gleicher Gesamtarbeit.
Stöggl & Sperlich (2014) übernahmen das Protokoll (20 min Einfahren bei 75 % HFpeak,
4 × 4 bei 90–95 %, 3 min aktive Pause) auch für Radfahrer.

**Evidenz:** belegt (beide Protokolle). Übertragung von Läufern auf Radfahrer bei
Helgerud: plausibel, durch Seiler 2013 (Rad, 4 × 4 mit 2 min Pause bei 94 % HFpeak)
gestützt.

### 7.3 Wie weit die Umsetzung den Protokollen folgt

- **VO2max** (3–6 × 4 min, 3 min Pause, 90–95 % HFmax) folgt Helgerud 2007 in Form,
  Intensität und Pause; die Stufen 5 × 4 und 6 × 4 sind Faustregel, die 3 Wiederholungen
  der Einstiegsstufe liegen unter dem Protokoll.
- **Schwelle** (3–5 × 8 min, 3 min Pause, 95–105 % FTP) übernimmt von Seiler 2013 nur
  die **Form** 4 × 8. Die Studie fuhr ~90 % HFpeak bei Laktat deutlich über der
  Schwelle mit 2 min Pause; die App fährt an der Schwelle mit 3 min Pause. Die Studie
  ist daher **Formvorbild, kein Beleg für Intervalle an der Schwelle**; Schwellen-
  intensität und Pause sind Faustregeln der Praxis (so steht es im Code-Kommentar an
  `WorkoutKind.THRESHOLD`).
- **Tempo** und **Sweet Spot** sind verbreitete Praxisformen ohne direkte Studienvorlage.

---

## 8 Belastungssteigerung

**Was die App rechnet:**

- Plan: Wochenumfang einer Aufbauwoche höchstens **+15 %** gegenüber der **letzten
  Aufbauwoche** (nicht der Vorwoche), mindestens aber +5 km, damit kleine Umfänge nicht
  stehen bleiben (`MAX_WEEKLY_INCREASE`, `MIN_WEEKLY_STEP_KM`, `rampCapKm`,
  `PlanWeeks.kt`). Nach einer deutlich verfehlten Woche bleibt die nächste Aufbauwoche
  auf dem Erreichten (`holdNextBuild`).
  Der Deckel gilt für die **tatsächliche Wochensumme** der Einheiten (Qualität und
  Mindestdauern eingerechnet), nicht nur für den Wunschumfang.
- Plan, Last: Mit bekanntem CTL ist die Last einer Aufbauwoche auf das **1,30-Fache** der
  Referenz gedeckelt (`LOAD_RAMP_CAP`; Referenz = das Größere aus 7 × CTL und der
  Last der letzten Aufbauwoche). Die Last jeder Einheit kommt aus ihrer Struktur:
  `Σ Dauer_h × IF² × 100` (`PlanWorkouts.kt`). Die frühere Rechnung mit einem
  Wochen-Lastbudget aus der Zielrampe gibt es im Plan nicht mehr.
- Plan, Zeitbudget: gilt in **jeder** Woche, auch in Ziel- und Testwoche (ohne das Event
  und ohne eine vom Ziel festgelegte Vergleichsrunde). Die Zahl der Einheiten ist
  höchstens Budget / 30 min.
- Greift ein Deckel, wird **gekürzt und neu gebaut** (`fitWeek`), nie skaliert. Im
  Aufbau eines Eventplans in dieser Reihenfolge: Wiederholungen der Qualität (× 0,75,
  bis 2), Grundlagen-/Regenerationsfahrten (unter 30 min gestrichen), eine
  Qualitätseinheit, zuletzt die lange Tour — sie trägt das Ziel und bekommt am Ende,
  was vom Wunschumfang unverplant blieb (`refillLong`, höchstens bis zu ihrer
  Untergrenze aus 11). In Fitnessblock, Erholung und Taper zuerst der Umfang, die
  Intensität zuletzt. Im Aufbau eines Eventplans ist die lange Tour immer die längste
  Einheit (notfalls werden dafür Wiederholungen gekürzt oder die Qualität gestrichen).
- **Untergrenze einer Woche:** Ist nichts mehr zu kürzen, bleibt genau **eine**
  Grundlagenfahrt mit der kürzesten Strecke, die 30 min dauert, stehen — auch wenn sie
  über dem Lastdeckel liegt (bei CTL 1 liegt der Deckel bei 9 Punkten, 30 min Grundlage
  kosten ~25). Eine Woche „ganz ohne" wäre kein Plan (**Faustregel**, bewusste Ausnahme).

**Dauer der Einheiten** (`PlanWeeks.kt`, alles **Faustregeln** der Trainingspraxis):

| Größe | Wert | Konstante |
|---|---|---|
| Mindestdauer jeder Einheit | 30 min (darunter lohnt das Umziehen nicht; Ein-/Ausfahren einer Qualitätseinheit allein sind 25 min) | `MIN_SESSION_MIN` |
| „Lange Tour" heißt sie erst ab | 60 min (sonst Titel der Grundlagenfahrt) | `LONG_RIDE_MIN_MIN` |
| Regeneration/Kompensation am Tag nach der langen Tour | ≤ 75 min | `RECOVERY_RIDE_MAX_MIN` |
| lockere Fahrt (Erholung, Taper) | ≤ 90 min; „Kurze lockere Ausfahrt" nur bis 60 min | `EASY_RIDE_MAX_MIN`, `TAPER_SHORT_MAX_MIN` |
| Grundlagenfahrt (GA1) neben der langen Tour | ≤ 4 h und nie länger als die lange Tour | `ENDURANCE_RIDE_MAX_MIN` |
| lange Tour eines Eventplans | ≤ 80 % der Zieldistanz, nie länger als das Event | `LONG_RIDE_MAX_GOAL_SHARE` |
| lange Tour eines Fitnessblocks | ≤ 4 h | `FITNESS_LONG_RIDE_MAX_MIN` |
| lange Tour in Taper- und Erholungswoche | ≤ 4 h (zusätzlich zum Anteil an der Aufbau-Tour) | `TAPER_LONG_RIDE_MAX_MIN` |

Was keine Fahrt im Rahmen dieser Grenzen trägt, plant der Plan **nicht** ein, statt es
auf eine Einheit zu kippen; die Machbarkeitsprüfung (11) sagt dann, was fehlt.
- Wochenziel ohne Plan: **+8 %**, höchstens +25 km (`aufbauFactor`, `aufbauMaxJumpKm`,
  `WeeklyGoal.kt`).
- Warnung, wenn eine Woche **> 130 %** des Vierwochenmittels liegt (`assessDeload`),
  und gleicher Deckel für das empfohlene Wochenziel (`weeklyLoadTarget`) und für die
  Plan-Last (`LOAD_RAMP_CAP`) — der Plan schreibt nie vor, was die Auswertung danach
  anmahnt.

**Grundlage und Evidenz — ehrlich:**

- Die **„10-%-Regel"** ist eine **Faustregel**. In der einzigen dazu bekannten
  randomisierten Studie (Buist et al. 2008, Laufanfänger) senkte ein nach der
  10-%-Regel gestufter 13-Wochen-Plan die Verletzungsrate **nicht** (20,8 % vs. 20,3 %).
- Nielsen et al. (2014, Kohorte, Laufanfänger, explorativ): Wer die Wochenstrecke
  innerhalb von 2 Wochen um **mehr als 30 %** steigerte, hatte tendenziell mehr
  „distanzbedingte" Verletzungen (HR 1,59; 95 %-KI 0,96–2,66 — knapp nicht signifikant).
  Die Autoren raten zu < 30 % pro Woche und fordern RCTs.
- Gabbett (2016) beschreibt ähnliche Größenordnungen für Mannschaftssport (siehe 4,
  samt Kritik).

**Einstufung:** +15 % und +8 % sind **Faustregeln** (der Kommentar im Code sagt das
selbst); der 130-%-Deckel liegt in der
Größenordnung von Nielsen et al. 2014, ist aber eine Übertragung von Laufanfängern
auf Radfahrer (**gängige Praxis**). Radfahren ist als nicht stoßbelastende Sportart
vermutlich toleranter; Daten dazu fehlen.

---

## 9 Entlastungswochen und Blockstruktur

**Was die App rechnet:**

- Plan: jede **4. Woche Erholung** (3:1), außer sie wäre die letzte Aufbauwoche vor
  dem Taper (`planWeekKinds`, `PlanWeeks.kt`). Umfang **55 %** der letzten Aufbauwoche
  (−45 %, `RECOVERY_FACTOR`; Mitte des −40-bis-50-%-Korridors von `assessDeload`) —
  als Deckel auf die tatsächliche Wochensumme. Die Zahl der Einheiten sinkt um genau
  eine (mindestens zwei); die letzte Qualitätseinheit bleibt mit halber Wiederholungszahl
  erhalten („Intensität bleibt"), die lange Tour ist auf 70 % der letzten Aufbau-Tour und
  höchstens 4 h verkürzt (`RECOVERY_LONG_SHARE`). Vor dem Test eines Fitnessblocks ist
  eine Erholungswoche erwünscht.
- Deload-Empfehlung (`assessDeload`, `Readiness.kt`, §6.2/§8.2): ausgelöst durch
  TSB < −30 an 3 Tagen in Folge, Rampe > 8 an drei Wochenpunkten oder Readiness < 40
  an ≥ 3 von 7 Tagen. Empfehlung: **Volumen −40 bis −50 %, Intensität halten**
  (`volumeReductionLow/High`, `deloadDetail`).
- Wochenziel ohne Plan: Entlastung bei **80 %** des Schnitts (`entlastungFactor`,
  `WeeklyGoal.kt`).

**Grundlage und Evidenz — ehrlich:**

- Für den Ausdauersport gibt es **keine** kontrollierten Studien, die einen 3:1-Rhythmus
  gegen andere Rhythmen testen. Der Rhythmus ist **gängige Praxis**.
- Der einzige Konsens zum Begriff „Deload" stammt aus dem Kraftsport (Bell et al.
  2023, Delphi-Verfahren): „Deloading is a period of reduced training stress designed
  to mitigate physiological and psychological fatigue, promote recovery, and enhance
  preparedness for subsequent training."
- Blockperiodisierung: Issurin (2010, Übersicht); Rønnestad et al. (2014) fanden bei
  trainierten Radfahrern Vorteile eines HIT-Blocks (1 Woche mit 5 HIT-Einheiten, dann
  3 Wochen mit je 1) gegenüber verteilten Einheiten. Die Meta-Analyse von Mølmen et al.
  (2019) zeigt kleine Vorteile für VO2max und Wmax, betont aber die geringe
  methodische Qualität (PEDro 3,7/10). Sylta et al. (2016) fanden über 12 Wochen
  dagegen **keinen** Unterschied zwischen verschiedenen HIT-Anordnungen bei gleicher
  Gesamtlast.
- Volumen senken, Intensität halten: im Taper belegt (siehe 10); für die
  Entlastungswoche eine **Übertragung** (gängige Praxis).

**Einstufung:** gängige Praxis; die Trigger-Schwellen sind **Faustregeln**.

---

## 10 Taper

**Was die App rechnet** (`PlanWeeks.kt`, `TWO_WEEK_TAPER_FACTORS`,
`SINGLE_TAPER_FACTOR`, `TWO_WEEK_TAPER_MIN_WEEKS`, `buildTaperWeek`,
`buildEventWeek`):

- Ab **8 Wochen** Laufzeit zwei Taperwochen mit **65 %** und **45 %** des tatsächlich
  erreichten Spitzenumfangs (−35 % / −55 %), kürzere Pläne eine Taperwoche mit 55 %
  (Faustregel, keine Studienempfehlung). „Erreicht" heißt: der größte Umfang einer
  tatsächlich gebauten Aufbauwoche — drücken Zeitbudget oder Lastdeckel den Aufbau unter
  den Startumfang, gilt dieser niedrigere Wert (ohne Aufbauwoche der Startumfang; in
  `adaptPlan` nur die neu gebauten Aufbauwochen).
- **Intensität gehalten:** Die letzte Qualitätseinheit bleibt mit gekürzter
  Wiederholungszahl (60 % bzw. 50 %, mindestens 2) erhalten.
- **Häufigkeit gehalten:** Die Zahl der Einheiten bleibt wie in der letzten
  Aufbauwoche; gekürzt wird jede Einheit, nicht ihre Zahl (Ausnahmen: Zeitbudget und
  die Tage direkt vor einem Montags-/Dienstagsevent). Die lange Tour ist auf 60 %
  bzw. 45 % (eine Taperwoche: 50 %) der längsten Aufbau-Tour, auf 80 % der
  Zieldistanz und auf 4 h begrenzt; lockere Fahrten auf 90 min.
- **Zielwoche:** eine kurze Qualitätseinheit mit höchstens 2 Wiederholungen 3–5 Tage
  vorher (nie am Montag nach einer langen oder harten Sonntagsfahrt der Vorwoche), bei
  großem Raster eine lockere Ausfahrt (50 min), 1–2 Tage vorher eine Aktivierung
  (40 min), dann das Event. Das Zeitbudget gilt auch hier: zuerst fällt die lockere
  Ausfahrt, dann die Vorbelastung. Vor einem **Montagsevent** liegt die Aktivierung am
  Sonntag der letzten Taperwoche; die beiden Tage davor tragen weder lange Tour noch
  Qualität (vor einem Dienstagsevent der Sonntag).

**Verifizierte Kernzahlen:**

| | Bosquet et al. 2007 (Meta-Analyse, 27 Studien) | Mujika & Padilla 2003 (Übersicht) |
|---|---|---|
| Dauer | **2 Wochen** optimal (ES 0,59) | 4 bis > 28 Tage |
| Volumen | **−41 bis −60 %**, exponentiell abnehmend (ES 0,72) | **−60 bis −90 %** |
| Intensität | **beibehalten** | **beibehalten** |
| Häufigkeit | **nicht verändern** | höchstens **−20 %** |
| Form | progressiv/exponentiell | progressiv nichtlinear besser als Stufen-Taper |
| Effekt | — | Leistung typischerweise **+~3 %** (0,5–6 %) |

**Evidenz:** belegt (wettkampforientierte Athleten; Übertragung auf Freizeitfahrer
plausibel, aber nicht eigens geprüft).

**Bewertung:** Dauer (2 Wochen), Intensität und Häufigkeit folgen Bosquet et al. 2007
und Mujika & Padilla 2003; das Volumen (−35 % und −55 %, im Mittel rund −45 %) liegt
am unteren Rand der −41 bis −60 % bei Bosquet und unter den −60 bis −90 % bei Mujika &
Padilla. Der Abfall ist zweistufig statt exponentiell. Kurze Pläne (< 8 Wochen) haben
nur eine Taperwoche und weichen damit von den zwei Wochen ab.

---

## 11 Längste Vorbereitungsfahrt und Zieltypen

**Was die App rechnet:** Ein Plan „trägt", wenn die längste geplante Trainingsfahrt
(ohne das Event) mindestens **60 %** der Zieldistanz erreicht (`minLongestRideShare`,
`assessPlanFeasibility`, `Training.kt`). Der angestrebte Spitzenumfang ist
**1,3 × Zieldistanz** (`PEAK_DISTANCE_FACTOR`, `peakKmFor`, `PlanWeeks.kt`). Die lange
Tour wird in jeder Aufbauwoche auf mindestens `(0,6 + 0,05) × Zieldistanz` gezogen,
aber höchstens auf die Hälfte des Wochenumfangs und 70 % des nach den Qualitäts-
einheiten verteilbaren Umfangs (`LONG_RIDE_FLOOR_MARGIN`, `LONG_RIDE_MAX_WEEK_SHARE`,
`LONG_RIDE_MAX_SHARE`); was die Untergrenze braucht, wird anteilig von den übrigen
Grundlagenfahrten genommen, und eine Fahrt, die dadurch unter 30 min fiele, wird
gestrichen. Nach oben ist die lange Tour auf **80 %** der Zieldistanz begrenzt
(`LONG_RIDE_MAX_GOAL_SHARE`, Faustregel: Oberkante des Praxis-Korridors plus Luft) und
damit nie länger als das Event. Reicht der Umfang nicht, sagt `assessPlanFeasibility` das ehrlich
und schlägt eine machbare Distanz (auf 5 km abgerundet) bzw. Wochenzahl vor; ein
Zeitbudget als Engpass wird getrennt benannt.

**Grundlage:** Der Code-Kommentar beruft sich auf Trainingspraxis („rund zwei Drittel
bis drei Viertel der Zieldistanz").

**Evidenz:** **Praxisregel / Faustregel.** Eine wissenschaftliche Quelle für einen
Mindestanteil wurde nicht gefunden. Der Wert ist als Erfahrungswert gekennzeichnet.

### 11.1 Zieltypen

- **Event** (`GoalType.EVENT`, „Distanz X am Datum Y"): 3–52 Wochen
  (`MIN_WEEKS`, `MAX_WEEKS`); Aufbau, Erholung im 3:1-Rhythmus, Taper (Abschnitt 10),
  Zielwoche mit dem Event. Ab 1000 m Anstieg gilt der Plan als hügelig (Intervalle am
  Berg, IF-Aufschlag +0,05 für Grundlagenfahrten, `CLIMB_HINT_THRESHOLD_M`,
  `HILLY_IF_BONUS`). Mit Zielzeit oder bei einem Ziel unter dem heutigen Umfang kommen
  Renntempo-Blöcke (2–3 Blöcke, 10–25 min bei IF 0,80) in die lange Tour, im
  spezifischen Abschnitt immer (`racePaceSteps`, `RACE_PACE_IF`; Faustregel).
- **Fitnessblock** (`GoalType.FITNESS`, „schneller werden"): 4–16 Wochen
  (`FITNESS_MIN_WEEKS`, `FITNESS_MAX_WEEKS`), Umfang wächst höchstens auf das 1,2-Fache
  (`FITNESS_PEAK_FACTOR`, Faustregel), 3:1 bis zur **Testwoche**. Der Block endet am
  Zieltag mit der **Vergleichsrunde** (immer an diesem Tag, wie ein Event); der
  **20-min-Test** (FTP ≈ 95 % des 20-min-Mittels, Allen & Coggan 2019, siehe 12) liegt an
  einem Trainingstag mindestens zwei, bevorzugt drei Tage davor. Nichts liegt nach dem
  Zieltag. Gibt es dafür keinen Trainingstag (z. B. nur Sa/So erlaubt) oder passt der
  Test nicht ins Zeitbudget, entfällt er — `fitnessTestWeekContents` sagt, was die
  Testwoche enthält. Beide sind ohne Event-Markierung. Für den Test selbst gibt der Plan
  **keinen** Zielbereich vor (ein Bereich wäre bei einem Maximaltest ein Deckel; nur das
  Ein-/Ausfahren hat Z2). Der Plan hat keine Zieldistanz und trägt immer.

---

## 12 FTP und 20-min-Test

**Was die App rechnet:** `eFTP = 0,95 × bestes 20-min-Mittel` der letzten 90 Tage,
geklemmt 100–400 W, nur als **Untergrenze** (steigt durch Belege, fällt nicht durch
Ruhetage); ohne Daten 2,4 W/kg (`estimateEftpW`, `resolveEftp`, `eftpWindowDays`,
`defaultEftpWPerKg`, `TrainingLoad.kt`, §3.3).

**Grundlage:** Allen, Coggan & McGregor (2019): FTP ≈ höchste über ~60 min haltbare
Leistung, praktisch geschätzt als 95 % eines 20-min-Tests.

**Validierung:** Borszcz et al. (2018, 23 trainierte Radfahrer): FTP20 (0,95 × 20 min),
FTP60 und individuelle anaerobe Schwelle unterscheiden sich im Mittel nicht
(Bias −1 bis −5 W), die **Übereinstimmungsgrenzen sind aber groß** (bis ±60 W);
an FTP20 hielten die Fahrer im Mittel 50,9 ± 15,7 min durch. MacInnis et al. (2019):
20-min-Zeitfahren sehr reliabel (ICC 0,99), stark mit der 60-min-Leistung
korreliert (R² 0,92).

**Evidenz:** Als Gruppenschätzung **belegt**, individuell **ungenau**. Die 90-Tage-
Fenster und das „nur nach oben"-Verhalten sind **gängige Praxis** (intervals.icu u. a.).

**Grenzen:** Die App bestimmt das 20-min-Mittel meist aus **geschätzter** Leistung
(Physikmodell) und aus Fahrten, die kein Test waren. Der Wert bleibt deshalb höchstens
MEDIUM. Der 2,4-W/kg-Default ist bewusst niedrig (**Faustregel**).

---

## 13 Kalorien und Wirkungsgrad

**Was die App rechnet:** `kcal = Ø-Leistung × Zeit / 1000 / 0,24 / 4,184`
(`estimateKcal`, `grossEfficiency = 0.24`, `kJPerKcal`, `TrainingLoad.kt`, §3.2).
200 W über 1 h → ~717 kcal.

**Grundlage:** Bruttowirkungsgrad = mechanische Leistung / Energieumsatz. Ettema &
Lorås (2009, Übersicht): Der Bruttowirkungsgrad hängt stark von der Leistung ab
(bei niedriger Leistung fällt der Grundumsatz stärker ins Gewicht). Moseley et al.
(2004, 69 Radfahrer): Bruttowirkungsgrad bei 165 W **17,9–18,8 %**, unabhängig vom
Leistungsniveau. In der Literatur werden häufig 18–23 % genannt.

**Evidenz:** Rechenweg **belegt**; der Wert 0,24 liegt **über** dem typischen
Bereich gemessener Werte (~18–23 %). Er entspricht der Faustregel „1 kJ Tretarbeit ≈
1 kcal" und ist damit eher eine **untere Schätzung** des Umsatzes. Der Wert ist
bewusst **nicht** geändert (das würde alle bisherigen kcal-Werte verschieben und ist
eine Entscheidung des Nutzers); der Code-Kommentar sagt das jetzt offen.

**Grenzen:** Ohne Leistungsmesser ist schon die Ø-Leistung geschätzt (±15–25 %).
Kalorienangaben sind Orientierung, keine Ernährungsberatung.

---

## 14 VO2max-Schätzung (§7.3)

**Was die App rechnet** (`RideAnalysis.kt`), Rangfolge Plattformwert > Regression > Uth:

- **Uth-Methode** (`estimateVo2MaxFromHrRatio`): `VO2max = Faktor × HFmax / HFruhe`
  mit Faktor **15,3** (Männer und unbekanntes Geschlecht) bzw. **14,5** bei
  `Sex.WEIBLICH` (Uth 2005), Band ±15 % (`vo2MaxBandRatio`), Confidence LOW.
- **Regression** (`estimateVo2MaxFromSegments`): stabile Segmente (≥ 5 min,
  60–90 % HFmax, 50–200 W), Sauerstoffbedarf nach ACSM-Gleichung
  `VO2 = 10,8 × W / kg + 7`, lineare Regression gegen die HF und Extrapolation auf
  HFmax; Gates ≥ 6 Segmente, r² ≥ 0,80, HF-Spanne ≥ 25 bpm; Band ±10 %.
- Anzeige immer als Band, geglättet über einen 28-Tage-Median.

**Grundlage:**

- Uth et al. (2004): 46 **gut trainierte Männer** (21–51 J.). Proportionalitätsfaktor
  15,3; Standardschätzfehler ~4,5 % mit gemessener HFmax, **~7,8 % mit altersgeschätzter
  HFmax**. Die Anwendbarkeit auf andere Gruppen sei „direkt zu validieren".
- Uth (2005): Bei 27 trainierten **Frauen** beträgt der Faktor **14,5**
  (SEE ~6,8 %).
- ACSM-Gleichung für Beinergometrie: `VO2 = 1,8 × Arbeitsrate (kg·m/min) / kg + 7`,
  in Watt `10,8 × W / kg + 7`, gültig etwa 50–200 W (ACSM's Guidelines for Exercise
  Testing and Prescription; Formel und Gültigkeitsbereich in mehreren Sekundärquellen
  bestätigt, Leitlinientext nicht eingesehen). Der Filter 50–200 W im Code passt dazu.

**Evidenz:** Uth: **belegt** für trainierte Männer und (Faktor 14,5) trainierte Frauen,
sonst gängige Praxis; bei unbekanntem Geschlecht gilt 15,3 und die Schätzung kann bei
Frauen um rund 5 % zu hoch liegen. Regression
über Submaximalwerte: **gängige Praxis** (Prinzip der submaximalen Extrapolation),
die Gates sind Faustregeln.

**Grenzen:** Mit Tanaka-HFmax und Plattform-Ruhepuls addieren sich zwei Schätzfehler;
±15 % ist eine angemessene, eher knappe Bandbreite. Die Regression beruht meist auf
**geschätzter** Leistung und einer Gleichung für Ergometer im Labor.

---

## 15 Was die App bewusst nicht behauptet

- **Kein Verletzungsrisiko.** Das Belastungsverhältnis ist ein beschreibender Hinweis
  (Abschnitt 4).
- **Keine Diagnose aus HRV, Ruhepuls oder Schlaf.** Auffällige Werte führen zu einem
  vorsichtigeren Trainingsvorschlag, nicht zu einer gesundheitlichen Aussage.
- **Keine Leistungsgarantie.** Zielzeit-Prognosen (`GoalPrognosis.kt`) sind offen
  gelegte Näherungsrechnungen (u. a. 9 m Flachstrecke je Höhenmeter, höchstens 8 %
  Tempogewinn) — **Faustregeln**.
- **Fitness-Trend in Klartext** (`FitnessTrend.kt`) vergleicht 42 Tage mit den
  42 Tagen davor (Meter je Herzschlag, Tempo, längste Tour) — beschreibend, ohne
  wissenschaftlichen Anspruch, mit dokumentierten Störgrößen (Wind, Hitze,
  Windschatten).

---

## 16 Literatur

Alle Einträge mit PMID wurden am 2026-11-24 über PubMed (NCBI E-utilities) mit
Autoren, Jahr, Titel, Zeitschrift und DOI abgeglichen; die zitierte Kernaussage
wurde am Abstract (bzw., wo angegeben, am Volltext) geprüft.

### 16.1 Zeitschriftenartikel (verifiziert)

1. Bell L, Strafford BW, Coleman M, Androulakis Korakakis P, Nolan D (2023). Integrating Deloading into Strength and Physique Sports Training Programmes: An International Delphi Consensus Approach. *Sports Med Open* 9:87. doi:10.1186/s40798-023-00633-0 · PMID 37730925
2. Bellenger CR, Fuller JT, Thomson RL, Davison K, Robertson EY, Buckley JD (2016). Monitoring Athletic Training Status Through Autonomic Heart Rate Regulation: A Systematic Review and Meta-Analysis. *Sports Med* 46(10):1461–1486. doi:10.1007/s40279-016-0484-2 · PMID 26888648
3. Borszcz FK, Tramontin AF, Bossi AH, Carminatti LJ, Costa VP (2018). Functional Threshold Power in Cyclists: Validity of the Concept and Physiological Responses. *Int J Sports Med* 39(10):737–742. doi:10.1055/s-0044-101546 · PMID 29801189 (Korrektur: PMID 29906813)
4. Bosquet L, Montpetit J, Arvisais D, Mujika I (2007). Effects of tapering on performance: a meta-analysis. *Med Sci Sports Exerc* 39(8):1358–1365. doi:10.1249/mss.0b013e31806010e0 · PMID 17762369
5. Buchheit M (2014). Monitoring training status with HR measures: do all roads lead to Rome? *Front Physiol* 5:73. doi:10.3389/fphys.2014.00073 · PMID 24578692
6. Buist I, Bredeweg SW, van Mechelen W, Lemmink KA, Pepping GJ, Diercks RL (2008). No effect of a graded training program on the number of running-related injuries in novice runners: a randomized controlled trial. *Am J Sports Med* 36(1):33–39. doi:10.1177/0363546507307505 · PMID 17940147
7. Busso T (2003). Variable dose-response relationship between exercise training and performance. *Med Sci Sports Exerc* 35(7):1188–1195. doi:10.1249/01.MSS.0000074465.13621.37 · PMID 12840641
8. Ettema G, Lorås HW (2009). Efficiency in cycling: a review. *Eur J Appl Physiol* 106(1):1–14. doi:10.1007/s00421-009-1008-7 · PMID 19229554
9. Filipas L, Bonato M, Gallo G, Codella R (2022). Effects of 16 weeks of pyramidal and polarized training intensity distributions in well-trained endurance runners. *Scand J Med Sci Sports* 32(3):498–511. doi:10.1111/sms.14101 · PMID 34792817
10. Foster C (1998). Monitoring training in athletes with reference to overtraining syndrome. *Med Sci Sports Exerc* 30(7):1164–1168. doi:10.1097/00005768-199807000-00023 · PMID 9662690
11. Foster C, Florhaug JA, Franklin J, Gottschall L, Hrovatin LA, Parker S, Doleshal P, Dodge C (2001). A new approach to monitoring exercise training. *J Strength Cond Res* 15(1):109–115. PMID 11708692
12. Fullagar HH, Skorski S, Duffield R, Hammes D, Coutts AJ, Meyer T (2015). Sleep and athletic performance: the effects of sleep loss on exercise performance, and physiological and cognitive responses to exercise. *Sports Med* 45(2):161–186. doi:10.1007/s40279-014-0260-0 · PMID 25315456
13. Gabbett TJ (2016). The training-injury prevention paradox: should athletes be training smarter and harder? *Br J Sports Med* 50(5):273–280. doi:10.1136/bjsports-2015-095788 · PMID 26758673 (Bänder 0,8–1,3 / ≥ 1,5 am Volltext PMC4789704 geprüft)
14. Haddad M, Stylianides G, Djaoui L, Dellal A, Chamari K (2017). Session-RPE Method for Training Load Monitoring: Validity, Ecological Usefulness, and Influencing Factors. *Front Neurosci* 11:612. doi:10.3389/fnins.2017.00612 · PMID 29163016
15. Helgerud J, Høydal K, Wang E, Karlsen T, Berg P, Bjerkaas M, et al. (2007). Aerobic high-intensity intervals improve VO2max more than moderate training. *Med Sci Sports Exerc* 39(4):665–671. doi:10.1249/mss.0b013e3180304570 · PMID 17414804
16. Hellard P, Avalos M, Lacoste L, Barale F, Chatard JC, Millet GP (2006). Assessing the limitations of the Banister model in monitoring training. *J Sports Sci* 24(5):509–520. doi:10.1080/02640410500244697 · PMID 16608765
17. Impellizzeri FM, Tenan MS, Kempton T, Novak A, Coutts AJ (2020). Acute:Chronic Workload Ratio: Conceptual Issues and Fundamental Pitfalls. *Int J Sports Physiol Perform* 15(6):907–913. doi:10.1123/ijspp.2019-0864 · PMID 32502973
18. Impellizzeri FM, Woodcock S, Coutts AJ, Fanchini M, McCall A, Vigotsky AD (2021). What Role Do Chronic Workloads Play in the Acute to Chronic Workload Ratio? Time to Dismiss ACWR and Its Underlying Theory. *Sports Med* 51(3):581–592. doi:10.1007/s40279-020-01378-6 · PMID 33332011
19. Issurin VB (2010). New horizons for the methodology and physiology of training periodization. *Sports Med* 40(3):189–206. doi:10.2165/11319770-000000000-00000 · PMID 20199119
20. Javaloyes A, Sarabia JM, Lamberts RP, Moya-Ramon M (2019). Training Prescription Guided by Heart-Rate Variability in Cycling. *Int J Sports Physiol Perform* 14(1):23–32. doi:10.1123/ijspp.2018-0122 · PMID 29809080
21. Karvonen MJ, Kentala E, Mustala O (1957). The effects of training on heart rate; a longitudinal study. *Ann Med Exp Biol Fenn* 35(3):307–315. PMID 13470504
22. Kiviniemi AM, Hautala AJ, Kinnunen H, Tulppo MP (2007). Endurance training guided individually by daily heart rate variability measurements. *Eur J Appl Physiol* 101(6):743–751. doi:10.1007/s00421-007-0552-2 · PMID 17849143
23. Le Meur Y, Pichon A, Schaal K, Schmitt L, Louis J, Gueneron J, Vidal PP, Hausswirth C (2013). Evidence of parasympathetic hyperactivity in functionally overreached athletes. *Med Sci Sports Exerc* 45(11):2061–2071. doi:10.1249/MSS.0b013e3182980125 · PMID 24136138
24. Lolli L, Batterham AM, Hawkins R, Kelly DM, Strudwick AJ, Thorpe R, et al. (2019). Mathematical coupling causes spurious correlation within the conventional acute-to-chronic workload ratio calculations. *Br J Sports Med* 53(15):921–922. doi:10.1136/bjsports-2017-098110 · PMID 29101104
25. Lucía A, Hoyos J, Santalla A, Earnest C, Chicharro JL (2003). Tour de France versus Vuelta a España: which is harder? *Med Sci Sports Exerc* 35(5):872–878. doi:10.1249/01.MSS.0000064999.82036.B4 · PMID 12750600
26. MacInnis MJ, Thomas ACQ, Phillips SM (2019). The Reliability of 4-Minute and 20-Minute Time Trials and Their Relationships to Functional Threshold Power in Trained Cyclists. *Int J Sports Physiol Perform* 14(1):38–45. doi:10.1123/ijspp.2018-0100 · PMID 29809063
27. Manresa-Rocamora A, Sarabia JM, Javaloyes A, Flatt AA, Moya-Ramón M (2021). Heart Rate Variability-Guided Training for Enhancing Cardiac-Vagal Modulation, Aerobic Fitness, and Endurance Performance: A Methodological Systematic Review with Meta-Analysis. *Int J Environ Res Public Health* 18(19):10299. doi:10.3390/ijerph181910299 · PMID 34639599 (Studienregeln am Volltext PMC8507742 geprüft)
28. Mølmen KS, Øfsteng SJ, Rønnestad BR (2019). Block periodization of endurance training – a systematic review and meta-analysis. *Open Access J Sports Med* 10:145–160. doi:10.2147/OAJSM.S180408 · PMID 31802956
29. Morton RH, Fitz-Clarke JR, Banister EW (1990). Modeling human performance in running. *J Appl Physiol* 69(3):1171–1177. doi:10.1152/jappl.1990.69.3.1171 · PMID 2246166
30. Moseley L, Achten J, Martin JC, Jeukendrup AE (2004). No differences in cycling efficiency between world-class and recreational cyclists. *Int J Sports Med* 25(5):374–379. doi:10.1055/s-2004-815848 · PMID 15241718
31. Mujika I, Padilla S (2003). Scientific bases for precompetition tapering strategies. *Med Sci Sports Exerc* 35(7):1182–1187. doi:10.1249/01.MSS.0000074448.73931.11 · PMID 12840640
32. Muñoz I, Seiler S, Bautista J, España J, Larumbe E, Esteve-Lanao J (2014). Does polarized training improve performance in recreational runners? *Int J Sports Physiol Perform* 9(2):265–272. doi:10.1123/ijspp.2012-0350 · PMID 23752040
33. Neal CM, Hunter AM, Brennan L, O'Sullivan A, Hamilton DL, De Vito G, Galloway SD (2013). Six weeks of a polarized training-intensity distribution leads to greater physiological and performance adaptations than a threshold model in trained cyclists. *J Appl Physiol* 114(4):461–471. doi:10.1152/japplphysiol.00652.2012 · PMID 23264537
34. Nielsen RØ, Parner ET, Nohr EA, Sørensen H, Lind M, Rasmussen S (2014). Excessive progression in weekly running distance and risk of running-related injuries: an association which varies according to type of injury. *J Orthop Sports Phys Ther* 44(10):739–747. doi:10.2519/jospt.2014.5164 · PMID 25155475
35. Plews DJ, Laursen PB, Kilding AE, Buchheit M (2012). Heart rate variability in elite triathletes, is variation in variability the key to effective training? A case comparison. *Eur J Appl Physiol* 112(11):3729–3741. doi:10.1007/s00421-012-2354-4 · PMID 22367011
36. Plews DJ, Laursen PB, Kilding AE, Buchheit M (2013). Evaluating training adaptation with heart-rate measures: a methodological comparison. *Int J Sports Physiol Perform* 8(6):688–691. doi:10.1123/ijspp.8.6.688 · PMID 23479420
37. Plews DJ, Laursen PB, Stanley J, Kilding AE, Buchheit M (2013). Training adaptation and heart rate variability in elite endurance athletes: opening the door to effective monitoring. *Sports Med* 43(9):773–781. doi:10.1007/s40279-013-0071-8 · PMID 23852425
38. Rønnestad BR, Hansen J, Ellefsen S (2014). Block periodization of high-intensity aerobic intervals provides superior training effects in trained cyclists. *Scand J Med Sci Sports* 24(1):34–42. doi:10.1111/j.1600-0838.2012.01485.x · PMID 22646668
39. Rosenblat MA, Perrotta AS, Vicenzino B (2019). Polarized vs. Threshold Training Intensity Distribution on Endurance Sport Performance: A Systematic Review and Meta-Analysis of Randomized Controlled Trials. *J Strength Cond Res* 33(12):3491–3500. doi:10.1519/JSC.0000000000002618 · PMID 29863593
40. Seiler KS, Kjerland GØ (2006). Quantifying training intensity distribution in elite endurance athletes: is there evidence for an "optimal" distribution? *Scand J Med Sci Sports* 16(1):49–56. doi:10.1111/j.1600-0838.2004.00418.x · PMID 16430681
41. Seiler S (2010). What is best practice for training intensity and duration distribution in endurance athletes? *Int J Sports Physiol Perform* 5(3):276–291. doi:10.1123/ijspp.5.3.276 · PMID 20861519
42. Seiler S, Jøranson K, Olesen BV, Hetlelid KJ (2013). Adaptations to aerobic interval training: interactive effects of exercise intensity and total work duration. *Scand J Med Sci Sports* 23(1):74–83. doi:10.1111/j.1600-0838.2011.01351.x · PMID 21812820 (Pausendauer nur über Nr. 44 und Sekundärquellen belegt)
43. Silva Oliveira P, Boppre G, Fonseca H (2024). Comparison of Polarized Versus Other Types of Endurance Training Intensity Distribution on Athletes' Endurance Performance: A Systematic Review with Meta-analysis. *Sports Med* 54(8):2071–2095. doi:10.1007/s40279-024-02034-z · PMID 38717713
44. Sylta Ø, Tønnessen E, Hammarström D, Danielsen J, Skovereng K, Ravn T, Rønnestad BR, Sandbakk Ø, Seiler S (2016). The Effect of Different High-Intensity Periodization Models on Endurance Adaptations. *Med Sci Sports Exerc* 48(11):2165–2174. doi:10.1249/MSS.0000000000001007 · PMID 27300278 (Methodenteil im Volltext geprüft)
45. Stöggl T, Sperlich B (2014). Polarized training has greater impact on key endurance variables than threshold, high intensity, or high volume training. *Front Physiol* 5:33. doi:10.3389/fphys.2014.00033 · PMID 24550842 (Verteilung 68/6/26 und 4×4-Protokoll am Volltext PMC3912323 geprüft)
46. Tanaka H, Monahan KD, Seals DR (2001). Age-predicted maximal heart rate revisited. *J Am Coll Cardiol* 37(1):153–156. doi:10.1016/s0735-1097(00)01054-8 · PMID 11153730
47. Uth N, Sørensen H, Overgaard K, Pedersen PK (2004). Estimation of VO2max from the ratio between HRmax and HRrest — the Heart Rate Ratio Method. *Eur J Appl Physiol* 91(1):111–115. doi:10.1007/s00421-003-0988-y · PMID 14624296
48. Uth N (2005). Gender difference in the proportionality factor between the mass specific VO2max and the ratio between HRmax and HRrest. *Int J Sports Med* 26(9):763–767. doi:10.1055/s-2005-837443 · PMID 16237622
49. Vesterinen V, Nummela A, Heikura I, Laine T, Hynynen E, Botella J, Häkkinen K (2016). Individual Endurance Training Prescription with Heart Rate Variability. *Med Sci Sports Exerc* 48(7):1347–1354. doi:10.1249/MSS.0000000000000910 · PMID 26909534
50. Watson NF, Badr MS, Belenky G, Bliwise DL, Buxton OM, Buysse D, et al. (2015). Recommended Amount of Sleep for a Healthy Adult: A Joint Consensus Statement of the American Academy of Sleep Medicine and Sleep Research Society. *Sleep* 38(6):843–844. doi:10.5665/sleep.4716 · PMID 26039963
51. Williams S, West S, Cross MJ, Stokes KA (2017). Better way to determine the acute:chronic workload ratio? *Br J Sports Med* 51(3):209–210. doi:10.1136/bjsports-2016-096589 · PMID 27650255

### 16.2 Bücher (bibliografisch verifiziert, Inhalt nur über Sekundärquellen)

52. Allen H, Coggan AR, McGregor S (2019). *Training and Racing with a Power Meter*, 3. Aufl. Boulder: VeloPress. ISBN 978-1-937715-93-9. — FTP, 20-min-Test, NP/IF/TSS, Performance Manager.
53. Banister EW (1991). Modeling elite athletic performance. In: MacDougall JD, Wenger HA, Green HJ (Hrsg.), *Physiological Testing of the High-Performance Athlete*, 2. Aufl., Champaign: Human Kinetics, S. 403–424/425 (Seitenende in den Quellen uneinheitlich). — Herkunft der TRIMP-Koeffizienten laut Sekundärliteratur; Primärtext nicht eingesehen.

### 16.3 Nicht verifiziert (nur als Herkunftsangabe der Praxis genannt)

- Edwards S (1993). *The Heart Rate Monitor Book* — Edwards-TRIMP; nicht eingesehen.
- Friel J. *The Cyclist's Training Bible* — LTHR-Zonen; Prozentwerte nur über Sekundärquellen bestätigt, Ausgabe nicht geprüft.
- ACSM's Guidelines for Exercise Testing and Prescription — Beinergometrie-Gleichung; Formel über Sekundärquellen bestätigt, Ausgabe nicht geprüft.
- HRV4Training (60-Tage-Baseline), „Garmin-Konvention" (Band 0,8–1,5), TrainingPeaks-Bänder für Rampe und TSB — Herstellerangaben, nicht geprüft.
