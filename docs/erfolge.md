# Erfolge — Abzeichen, Punkte, Stufen

Stand: 25.11.2026. Etappe 1 aus `docs/konzept-spiel-community.md` (Abschnitte 2.2–2.7,
Fahrplan Kapitel 7). Diese Datei hält fest, **was gebaut ist**, nach welchen Regeln, und wo das
Konzept Lücken hatte und wie sie entschieden wurden.

## 1 Grundsätze

- **Abgeleitet statt gespeichert.** Punkte, Stufe, Abzeichen und Fortschritt rechnet `:core` aus
  den Touren, dem aktuellen Plan und dem Jahresziel (`core/…/Achievements.kt`), wie die übrige
  Motivation. „Heute" kommt als Parameter. Gespeichert wird nur, was sich nicht ableiten lässt
  (Abschnitt 5).
- **Belohnt wird Regelmäßigkeit, nicht Menge.** Punkte aus Fahrzeit mit Tagesdeckel; keine
  Punkte für Tempo, Bestwerte, Höhenmeter, Last oder Intensität; **keine Tagesserien**. Die
  Wochenserie kennt Pausenwochen. Es gibt ein Abzeichen für eine eingehaltene Erholungswoche.
- **Keine Abzüge**, keine Telemetrie, kein Konto, kein Server.
- **Abschaltbar**: *Einstellungen → Motivation → Erfolge anzeigen*. Aus heißt: keine Karte im
  Training-Tab, keine Abzeichen-Zeile nach der Fahrt (Tourblatt, Uhr); es wird dann auch nichts
  gerechnet oder festgehalten. Der gespeicherte Stand bleibt erhalten.

## 2 Die 30 Abzeichen

Reihenfolge und Bedingungen wie im Konzept (2.4). Der Schlüssel (`AchievementId.key`) steht im
gespeicherten Stand und im Backup und ändert sich nie; Namen und Sätze liegen in
`res/values[-en]/strings_spiel1.xml` (`erfolge_badge_<key>_title`/`_body`).

| # | Schlüssel | Name | Bedingung | Stand in dieser Version |
|---|---|---|---|---|
| 1 | `first_ride` | Losgefahren | erste gespeicherte Tour | aktiv |
| 2 | `ride_50km` | Halbe Sache | eine Tour ≥ 50 km | aktiv |
| 3 | `ride_100km` | Hunderter | eine Tour ≥ 100 km | aktiv |
| 4 | `ride_1000m` | Tausend Meter | eine Tour ≥ 1000 Hm | aktiv |
| 5 | `ride_4h` | Lange Leitung | eine Tour mit Fahrzeit ≥ 4 h | aktiv |
| 6 | `rides_10` | Zehn Touren | 10 Touren | aktiv |
| 7 | `rides_100` | Hundert Touren | 100 Touren | aktiv |
| 8 | `total_1000km` | Tausend Kilometer | 1000 km gesamt | aktiv |
| 9 | `total_earth` | Einmal um die Erde | 40 075 km gesamt | aktiv |
| 10 | `total_everest` | Everest | 8849 Hm gesamt | aktiv |
| 11 | `streak_4` | Vier Wochen dabei | Wochenserie 4 (mit Pausenwochen) | aktiv |
| 12 | `streak_12` | Ein Vierteljahr | Wochenserie 12 | aktiv |
| 13 | `streak_52` | Ein ganzes Jahr | Wochenserie 52 | aktiv |
| 14 | `month_complete` | Monat geschafft | alle Monats-Challenges eines Monats | aktiv |
| 15 | `challenge_bonus` | Zugabe | erste Bonusstufe einer Challenge | aktiv |
| 16 | `year_goal` | Jahresziel | Jahresziel erreicht | aktiv |
| 17 | `plan_first` | Nach Plan | erste Planeinheit `ERLEDIGT` | aktiv |
| 18 | `plan_full_week` | Volle Woche | alle Einheiten einer Planwoche `ERLEDIGT`/`TEILWEISE`, ≥ 1 `ERLEDIGT` | aktiv |
| 19 | `recovery_week` | Erholung ist Training | Erholungswoche mit Tour, km ≤ 110 % ihres Ziels | aktiv |
| 20 | `plan_finished` | Durchgezogen | Plan bis zum Ende, ≥ 80 % der Einheiten `ERLEDIGT` | aktiv |
| 21 | `indoor_first` | Drinnen | erste Fahrt mit `Ride.indoor` | aktiv |
| 22 | `indoor_10h` | Wintertraining | 10 h Fahrzeit drinnen | aktiv |
| 23 | `ghost_beaten` | Geist geschlagen | vor dem Geist einer früheren eigenen Fahrt im Ziel | **kommt bald** |
| 24 | `known_route` | Bekannte Strecke | eine Katalogstrecke vollständig gefahren | **kommt bald** |
| 25 | `climb_repeat` | Bergkenner | erster Anstieg mit zwei Befahrungen | **kommt bald** |
| 26 | `climb_faster` | Schneller oben | eigene Bestzeit an einem Anstieg verbessert | **kommt bald** |
| 27 | `round_5` | Rundenkenner | dieselbe Runde fünfmal | aktiv, nur auf dem Bildschirm gerechnet |
| 28 | `tiles_100` | Kartograf | 100 Entdeckt-Kacheln | aktiv, nur auf dem Bildschirm gerechnet |
| 29 | `tiles_square_5` | Quadrat | größtes Quadrat ≥ 5 × 5 Kacheln | aktiv, nur auf dem Bildschirm gerechnet |
| 30 | `new_ways_100km` | Neue Wege | 100 km auf vorher nicht gefahrenen Wegen | aktiv, nur auf dem Bildschirm gerechnet |

Zu jedem erreichten Abzeichen nennt die Rechnung, soweit bekannt, die **freischaltende Tour**
und den **Tag**; offene haben einen Fortschritt (`AchievementState.current`/`target`).

### 2.1 „Kommt bald" — Schnittstellen für die parallelen Zweige

Die Abzeichen 23–26 hängen an Funktionen, die auf anderen Zweigen entstehen. Hier steht nur
eine schmale Schnittstelle in `AchievementSources` (`Achievements.kt`); ist das Feld `null`,
steht das Abzeichen auf `AchievementStatus.COMING_SOON` und erscheint gesammelt unter
„Kommt bald" (nur Name und Bedingung, zählt nicht in „x von y").

| Abzeichen | Feld | Liefert, sobald gemergt |
|---|---|---|
| 23 Geist geschlagen | `ghostWins: List<AchievementUnlock>?` | „Tour drinnen nachfahren" (`claude/idee-01-nachfahren`): je Sieg gegen den Geist einer eigenen Fahrt Tour-ID und Tag |
| 24 Bekannte Strecke | `knownRouteCompletions: List<AchievementUnlock>?` | Indoor-Kennzeichnung bzw. Nachfahren: je vollständig gefahrener Katalogstrecke Tour-ID und Tag (heute trägt `Ride` keinen Katalogbezug) |
| 25, 26 Anstiege | `climbs: ClimbAchievementFacts?` | Bergwertung (`claude/idee-11-streckenleben`): erste wiederholte Befahrung und erste verbesserte Bestzeit |

Einbinden heißt: das Feld in `ui/erfolge/ErfolgeState.kt` füllen — in `loadErfolgeSources`
(nur der Bildschirm), oder, wenn die Quelle billig ist, schon in `rememberErfolge`, damit das
Abzeichen auch im Tourblatt erscheint. Eine leere Liste heißt „gibt es, noch nicht erreicht".
Code der anderen Zweige ist hier bewusst nicht kopiert. Die heutige Segment-Registry
(`RideSegments.kt`) könnte 25/26 sofort liefern; laut Konzept soll aber die Bergwertung die eine
Rechnung sein.

### 2.2 Teure Quellen

Runden (27), Kacheln (28, 29) und Wege (30) brauchen die Punkte aller Touren. Sie werden über
dieselben Wege und Caches wie Routenwahl und Rundenvergleich geholt
(`AppViewModel.achievementRounds`, `exploredTilesForPlanning`, `riddenWaysForPlanning`), und
zwar **nur beim Öffnen des Bildschirms**. Tourblatt, Training-Karte und Uhr rechnen ohne sie
(„Wird berechnet …"). Was einmal erreicht ist, hält `earned` fest (Abschnitt 5), mit dem Tag der
Berechnung, weil der Cache keinen Tag kennt.

## 3 Punkte und Stufen

Je Kalendertag (Konzept 2.3):

```
Punkte = min(Bewegungszeit in ganzen Minuten, 180)            // Tagesdeckel 3 h
       + 40 je Planeinheit ERLEDIGT, 20 je TEILWEISE
       + 60 je Woche mit erreichtem Wochenziel
       + 100 je erreichter Monats-Challenge, + 50 je Bonusstufe
```

Stufe L gilt ab `P(L) = 500 × (L − 1)^1,6`, auf 10 gerundet, L = 1 … 50:

| Stufe | 2 | 3 | 5 | 10 | 12 | 20 | 30 | 50 |
|---|---|---|---|---|---|---|---|---|
| ab Punkten | 500 | 1520 | 4590 | 16 820 | 23 180 | 55 590 | 109 350 | 253 100 |

Die gezeigte Stufe sinkt nie: Die höchste je gezeigte steht in `keptLevel`.

## 4 Pausenwochen

- Eine **markierte Pausenwoche ohne Tour** hält die Wochenserie, verlängert sie aber nicht.
- Höchstens **2 Pausen in je 12 Wochen**: der Reihe nach gezählt, eine Pause gilt, wenn in den
  elf Wochen davor weniger als zwei gelten (`acceptedPauseWeeks`). Eine markierte Woche, in der
  doch gefahren wurde, zählt als normale Woche und verbraucht nichts.
- Markieren lassen sich im Bildschirm die laufende und die letzte Woche, jeweils nur ohne Tour
  und nur, wenn die Grenze es zulässt (`canMarkPauseWeek`, verdrängt keine spätere Pause).
- Eine pausierte laufende Woche gilt nicht als „Serie in Gefahr".

## 5 Was gespeichert wird

`AchievementProgressData` (`core/…/AchievementProgress.kt`), eine Zeile JSON im
Schlüssel-Wert-Speicher unter `trailscape.erfolge.progress.v1` (`data/ErfolgeStore.kt`), unter
1 kB:

```json
{"version":1,"keptLevel":3,"pointsSince":"2026-01-05","pauseWeeks":["2026-08-03"],
 "earned":{"first_ride":"2026-01-06"},"seen":["first_ride"],"hidden":true}
```

| Feld | Bedeutung |
|---|---|
| `keptLevel` | höchste je gezeigte Stufe (gegen Herabstufen) |
| `pointsSince` | Punktebeginn, einmalig der Tag der Installation; nur geschrieben mit Wert |
| `pauseWeeks` | markierte Pausenwochen (Montage) |
| `earned` | Abzeichen → Tag des Erreichens; hält fest, was sich später nicht mehr ableiten lässt |
| `seen` | schon als „Neu" gezeigt |
| `hidden` | „Erfolge anzeigen" aus; nur geschrieben, wenn `true` |

**Lesen ist tolerant**: unbekannte Schlüssel werden ignoriert, kaputte Einträge fallen einzeln
weg, eine höhere `version` wird gelesen, soweit die Felder bekannt sind, unbekannte
Abzeichen-Schlüssel in `earned`/`seen` bleiben erhalten. Neue Felder kommen nur hinzu.

**Backup**: zusätzlicher Schlüssel `progress` im Kopf (nach `yearGoal`, vor `rides`), derselbe
Inhalt. Ältere App-Versionen lesen nur bekannte Schlüssel (`parseBackupJson`) und übergehen
`progress`; ein kaputtes `progress` macht den Import nie ungültig. Beim Import wird
**zusammengeführt** statt überschrieben: höchste Stufe, frühester Punktebeginn, alle Pausen, je
Abzeichen das früheste Datum, alles Gesehene. Getestet in `AchievementProgressTest`.

**Sync**: geht nicht über den Selfhost-Sync (der kennt nur Touren). Weil fast alles aus den
Touren folgt, stimmen Abzeichen und Punkte auf zwei Geräten trotzdem überein; nur `earned`,
Pausen und Stufe sind je Gerät. Ein Endpunkt `PUT /api/state/progress` bleibt Konzept 2.6.

## 6 Oberfläche

- **Training → Fortschritt**: Karte „Erfolge" (Stufe, „x von y Abzeichen", Weg zur nächsten
  Stufe, „2 neu") unter den Monats-Challenges. Antippen öffnet das Blatt.
- **Blatt „Erfolge"** (`ui/erfolge/ErfolgeScreen.kt`): Stufe mit Punkten und Herkunft, kurze
  Erklärung der Punkte, Hinweis auf den Punktebeginn (nur, wenn es ältere Touren gibt);
  Wochenserie mit Pausenschaltern; Abzeichen nach Gruppen (Touren, Zusammen, Dranbleiben,
  Trainingsplan, Drinnen, Entdecken), erreicht mit Datum, offen mit Bedingung, Fortschritt und
  Balken; „Kommt bald". Was beim Öffnen neu war, trägt „Neu" und gilt danach als gesehen.
- **Nach der Fahrt** (Tourblatt auf der Karte): neue Abzeichen der Tour als **eine** Zeile
  („Neues Abzeichen: Hunderter" bzw. „… und 2 weitere"), direkt nach einer neuen Bestweite,
  sonst ganz oben (`withAchievementHighlight`).
- **Uhr**: dieselbe Zeile als eine der zwei Zeilen von `PFAD_ABSCHLUSS`
  (`wear/WearAbschluss.kt`). Das Protokoll (`AbschlussInfo`, je Zeile höchstens 60 Zeichen)
  bleibt unverändert; ein Test prüft, dass jede Zeile in 60 Zeichen passt.
- **Symbole**: selbst gezeichnete, einfarbige Liniensymbole (`ErfolgeIcons.kt`), je Gruppe eins,
  dazu Medaille, Sanduhr, Pause.

## 7 Entscheidungen, wo das Konzept Lücken hatte

1. **Pausenwoche (a) „Erholungswoche des Plans mit Tour".** Eine Woche mit Tour zählt ohnehin
   und verlängert die Serie; sie als bloß „haltend" zu werten, bestrafte Erholungswochen. Also
   zählt sie wie jede Woche mit Tour. Eine Erholungswoche **ohne** Tour hält die Serie nicht
   von selbst — dafür gibt es die Markierung (b).
2. **Kontingent der Pausen** chronologisch (siehe 4); eine Pause in einer Woche mit Tour
   verbraucht nichts. Rückwirkend markierbar ist nur die letzte Woche — weiter zurück bräuchte
   eine Kalenderauswahl, die der erste Schritt nicht rechtfertigt.
3. **„Unter dem Deckel der Erholungswoche"** heißt ≤ 110 % ihres Wochenziels (gerundete Ziele,
   nicht kilometergenau planbare Runden), und die Woche muss vorbei sein. Ohne Plan zählt die
   Entlastungswoche des mitwachsenden Wochenziels (`WeeklyGoalKind.ENTLASTUNG`) — sonst gäbe es
   das wichtigste Ausgleichs-Abzeichen nur mit Plan.
4. **Nur der aktuelle Plan** ist gespeichert. Plan-Abzeichen und -Punkte früherer Pläne würden
   beim Ersetzen verschwinden; die Abzeichen hält `earned` fest, die Stufe `keptLevel`. Die
   Punktezahl selbst kann dann sinken, die Stufe nicht.
5. **Gespeicherter statt angepasster Plan** (`adaptPlan` des Plan-Tabs): Die Anpassung ändert
   sich mit jeder Fahrt; ein Abzeichen soll nicht davon abhängen, wann man hinsieht. Die
   Zuordnung der Touren ist dieselbe (`weekSessionProgress`, mit den Tourlasten der Auswertung).
6. **„Durchgezogen"**: alle Planwochen samt Toleranztag vorbei, ≥ 80 % **aller** Einheiten
   `ERLEDIGT` (das Zielevent zählt als Einheit).
7. **Punktebeginn (2.7)** ist der Tag der Erstinstallation (`PackageInfo.firstInstallTime`),
   einmal festgelegt und im Backup. Wochen- und Monatsboni zählen, wenn die Woche bzw. der Monat
   an oder nach diesem Tag **endet** — die Installationswoche bekommt ihren Bonus.
8. **Planpunkte** zählen am Tag der zugeordneten Tour (sonst am Tag der Einheit).
9. **Wochenziel-Bonus**: Planwoche mit Ziel > 0, sonst das mitwachsende Wochenziel
   (`suggestWeeklyGoal`) — dieselbe Vorrangregel wie „Heute".
10. **Stufenschwellen** auf 10 gerundet (Stufe 5 ab 4590, das Konzept schreibt gerundet
    „4 600").
11. **„Neue Wege"** ist die Länge aller **verschiedenen** gefahrenen Wege: Zellen des Wegeindex
    durch 65 Zellen je km (Erfahrungswert aus `RiddenWays.kt`). Jede Zelle zählt beim ersten
    Befahren — die Summe ist „km auf damals neuen Wegen". Die ersten Touren zählen mit; wer immer
    dieselbe Strecke pendelt, kommt nicht hin.
12. **„Rundenkenner"** zählt je Tour die früheren Touren, die `sameRoute` zu ihr sind (wie das
    Tourblatt, nicht transitiv), jede Fahrt auch ohne Bewegungszeit.
13. **Drinnen (21, 22)** nutzt das vorhandene `Ride.indoor`. Die parallel entstehende
    Indoor-Kennzeichnung erweitert nur, welche Touren als drinnen gelten; hier ändert sich nichts.
14. **Einmal erreicht bleibt erreicht** (`earned`), auch nach Löschen der Tour; die Rechnung
    gewinnt, wenn sie Tour und Tag kennt.
15. **Wochenserie mit Pausen** gilt im Bildschirm „Erfolge" und für die Abzeichen 11–13.
    „Heute", Widget, Erinnerungen und Wochenrückblick zeigen weiter `weekStreak` ohne Pausen —
    siehe offene Punkte.

## 8 Tests

- `core/…/AchievementsTest.kt`: Stufenkurve, Punkte (Deckel, Plan, Wochenziel, Challenges,
  Punktebeginn, nichts für Tempo/Höhenmeter), Pausen und Serie, alle Abzeichenarten samt
  freischaltender Tour, „kommt bald"/„wird berechnet", Festgehaltenes, Rundenkenner, Platz der
  Highlight-Zeile.
- `core/…/AchievementProgressTest.kt`: Format hin und zurück, alte und neue Stände, kaputte und
  unbekannte Einträge, Zusammenführen, Backup alt/neu, byteidentisches Streaming mit `progress`.
- `app/…/ui/erfolge/ErfolgeTextsTest.kt` (Robolectric): Sätze in beiden Sprachen, Rundung,
  Uhrzeile ≤ 60 Zeichen, `ErfolgeStore`.
- `app/…/ui/erfolge/ErfolgeScreenshotTest.kt` (nur `-Pscreenshots`): Bildschirm hell, dunkel,
  englisch, ohne Plan, erster Tag, Pausenschalter, Karte und Highlight-Zeile (DE/EN).

## 9 Offen

- **Punkteformel und Stufenkurve** sind nicht an echten Daten nachgerechnet (Konzept 8).
- **Pausen überall**: `weekStreakWithPauses` ist der Ersatz für `weekStreak`; an „Heute"
  (`TodayScreen`), Widget (`WidgetState`), Erinnerungen (`Reminders.kt`, dort gehört auch
  „in einer Erholungswoche nicht anmahnen" hin) und Wochenrückblick ist es noch nicht
  angeschlossen, um gemeinsam genutzte Dateien klein zu halten.
- **Uhr-Sprache**: wie alle Abschluss-Zeilen in der Sprache des Telefons.
- **Sync** des kleinen Stands (Abschnitt 5).
- **„Kommt bald"** (2.1) nach dem Merge der drei Zweige anschließen.
- **Freischaltungen** (Garage, Etappe 2) können an `AchievementState.reached` hängen.
