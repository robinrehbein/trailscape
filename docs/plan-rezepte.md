# Plan-Rezepte — Trainingspläne als teilbare Dateien

Ein **Plan-Rezept** ist ein Trainingsplan (oder eine einzelne Einheit) als
lesbare JSON-Datei. Trainer, Vereine und Foren können Pläne so ohne Plattform
und ohne Konto tauschen: Datei verschicken, beim Empfänger öffnen, fertig.

Das Rezept enthält **keine persönlichen Daten**. Die Intensitäten stehen
relativ (Zone oder Prozent der FTP); die Umrechnung auf Watt macht erst die
App des Empfängers mit dessen FTP.

Code: `core/…/PlanRecipe.kt` (Format, Lesen, Schreiben),
`core/…/PlanRecipeConvert.kt` (Rezept ⇄ Plan, Vorschau), Tests in
`core/…/PlanRecipeTest.kt` (die Beispiele unten werden dort gelesen).

## Datei, Endung, Typ

| | |
|---|---|
| Endung | `.tsplan.json` (z. B. `Grundlage-12-Wochen.tsplan.json`) |
| MIME-Typ beim Teilen | `application/vnd.trailscape.plan+json` |
| Zeichensatz | UTF-8 (ein BOM vorn ist erlaubt) |
| Größe | höchstens 256 KiB |

Die Endung endet bewusst auf `.json`: Jeder Texteditor öffnet die Datei, und
Rezepte lassen sich von Hand schreiben. Der eigene MIME-Typ und die eigene
Endung sorgen dafür, dass Trailscape sie nicht mit dem **Backup**
(`trailscape-backup-*.json`) oder mit **GPX/FIT-Dateien** verwechselt: Der
Backup-Import prüft den Inhalt und lehnt ein Rezept ab (und umgekehrt das
Rezept-Lesen ein Backup, siehe „Fehlermeldungen"); der GPX-Import nimmt den
Typ gar nicht an.

## Aufbau

Ein Plan-Rezept (alle Schlüssel klein, Reihenfolge egal):

```json
{
  "format": "trailscape-plan-recipe",
  "version": 1,
  "title": "Grundlage 2 Wochen",
  "author": "RV Beispiel",
  "notes": "Zum Ausprobieren.",
  "weeks": [
    {
      "kind": "build",
      "sessions": [
        {
          "day": "tue",
          "title": "Sweet Spot",
          "segments": [
            { "minutes": 15, "zone": "endurance" },
            { "minutes": 12, "zone": "sweetSpot", "repeat": 3, "recoveryMinutes": 5 },
            { "minutes": 10, "zone": "recovery" }
          ]
        },
        {
          "day": "sat",
          "title": "Lange Fahrt",
          "notes": "Ruhig bleiben.",
          "segments": [
            { "label": "Einrollen", "minutes": 60, "ftpPercent": [60, 70] }
          ]
        }
      ]
    },
    { "kind": "recovery", "sessions": [] }
  ]
}
```

Eine einzelne Einheit trägt statt `weeks` ein Feld `workout` (ohne `day`):

```json
{
  "format": "trailscape-plan-recipe",
  "version": 1,
  "title": "4 × 4 VO2max",
  "workout": {
    "title": "4 × 4 VO2max",
    "segments": [
      { "minutes": 15, "zone": "endurance" },
      { "minutes": 4, "zone": "vo2max", "repeat": 4, "recoveryMinutes": 3 },
      { "minutes": 10, "zone": "recovery" }
    ]
  }
}
```

### Felder

**Kopf**

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `format` | ja | Immer `"trailscape-plan-recipe"`. |
| `version` | ja | Ganze Zahl, heute `1`. Eine neuere Version als die der App wird abgelehnt (mit Hinweis auf ein App-Update), nicht halb gelesen. |
| `title` | ja | Frei gewählter Titel, bis 80 Zeichen. |
| `author` | nein | Frei gewählter Name, bis 80 Zeichen (Verein, Trainer, Forum …). |
| `notes` | nein | Freitext, bis 2000 Zeichen. |
| `weeks` | ja¹ | Liste von Wochen, 1–52. |
| `workout` | ja¹ | Eine einzelne Einheit. |

¹ Genau eines von beiden.

**Woche** (`weeks[]`)

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `kind` | nein | `build` (Aufbau, Standard), `recovery` (Entlastung), `taper`, `peak` (Höhepunkt/Zielwoche). Unbekannte Werte lesen als `build`. |
| `sessions` | nein | Einheiten der Woche, höchstens 14; leer = Ruhewoche. Mindestens eine Woche braucht eine Einheit. |

**Einheit** (`sessions[]` bzw. `workout`)

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `day` | in `weeks` ja | `mon` `tue` `wed` `thu` `fri` `sat` `sun` (Groß-/Kleinschreibung egal). Bei `workout` nicht vorgesehen. |
| `title` | ja | Bis 80 Zeichen. |
| `notes` | nein | Hinweis zur Einheit, bis 2000 Zeichen. |
| `segments` | ja | 1–40 Abschnitte der Reihe nach. Die Einheit dauert höchstens 12 Stunden. |

**Abschnitt** (`segments[]`)

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `minutes` | ja | Dauer einer Belastung, ganze Minuten 1–600. |
| `zone` | ja² | Name einer Zone (Tabelle unten). |
| `ftpPercent` | ja² | `[von, bis]` in Prozent der FTP, ganze Zahlen 20–250, `von ≤ bis`. Hat Vorrang vor `zone`. |
| `repeat` | nein | Wiederholungen, 1–60 (Standard 1). |
| `recoveryMinutes` | nein | Erholung **zwischen** den Wiederholungen, 0–60 (Standard 0). Sie wird in der Zone `recovery` gefahren und steht nicht nach der letzten Wiederholung. |
| `label` | nein | Kurzer Name des Abschnitts, bis 60 Zeichen. |

² `zone` oder `ftpPercent`.

Die Dauer eines Abschnitts ist `repeat × minutes + (repeat − 1) × recoveryMinutes`.
Im Beispiel dauert die erste Einheit 15 + 3 × 12 + 2 × 5 + 10 = 71 Minuten.

### Zonen

Die Zonen sind die der App (Leistungszonen nach Coggan; Herleitung und Quellen
in [trainingswissenschaft.md](trainingswissenschaft.md)). Beim Import werden
sie wie ein `ftpPercent`-Bereich behandelt.

| `zone` | Bereich der FTP |
|---|---|
| `recovery` | 40–55 % |
| `endurance` | 56–75 % |
| `tempo` | 76–87 % |
| `sweetSpot` | 88–94 % |
| `threshold` | 95–105 % |
| `vo2max` | 106–120 % |

Wer andere Grenzen will, schreibt `ftpPercent` statt `zone`. Ein Abschnitt in
einer Zone wird beim Export wieder mit `zone` geschrieben, ein selbst
definierter Bereich mit `ftpPercent`.

## Unbekanntes und Neueres

* **Unbekannte Schlüssel** werden überlesen (in jeder Ebene). Neue Felder
  kommen in späteren Versionen nur hinzu, ändern aber keine bestehenden.
* **Unbekannte `kind`-Werte** lesen als `build`.
* **Eine höhere `version`** wird abgelehnt: „Dieses Rezept stammt aus einer
  neueren Version von Trailscape (Format N). Bitte die App aktualisieren."
* Ein Format-Bruch erhöht `version`; solange sie `1` ist, lesen alle
  Trailscape-Versionen mit Rezept-Import jede Datei, die eine frühere schrieb.

## Was nie in der Datei steht

Beim Export aus der App landen **nur** Titel, Autor (beides frei eingetippt,
beides leer lassbar), Wochenart, Wochentag und die Struktur der Einheiten
(Dauer, Zone/Prozent, Wiederholungen, Erholung) in der Datei. **Nicht**:

* FTP, Gewicht, Alter, Puls-Werte, Profil, Ziel-Name, Zieldatum, Zielzeit,
  Zieldistanz, Höhenmeter, Orte, Touren, Routen, Geräte-/Konto-Kennungen;
* der Planbeginn (die Wochen sind „Woche 1, 2 …", nicht Kalenderwochen);
* Kilometer, Tempo und Trainingslast (sie hängen vom Tempo der Person ab und
  werden beim Empfänger neu gerechnet);
* das Zielevent und die Beschreibungstexte der App (sie enthalten Zahlen aus
  dem persönlichen Tempo oder dem Ziel).

Wer ein Rezept von Hand schreibt oder bearbeitet, entscheidet selbst über
`title`, `author` und `notes` — diese Felder sind die einzige Stelle, an der
Freitext steht.

## Import in der App

1. **Öffnen:** Training → Plan → „Rezept öffnen" (Dateiauswahl) oder die Datei
   in einer anderen App („Öffnen mit"/„Teilen") an Trailscape geben.
2. **Vorschau:** Titel, Autor, Dauer in Wochen, Einheiten je Woche, Gesamtzeit
   und die Einheiten der ersten Woche — mit Watt auf Basis der **eigenen** FTP
   (ohne FTP im Profil nur in Prozent, mit Hinweis).
3. **Bestätigen:** Es wird nichts übernommen, bevor „Übernehmen" getippt wird.
   Gibt es schon einen Plan, steht ausdrücklich da, dass er **ersetzt** wird.
4. **Ergebnis:** Der Plan beginnt am kommenden Montag (heute, wenn heute Montag
   ist). Er ist ein Fitness-Plan ohne Event: keine Zielprognose, und die App
   rechnet ihn nicht stillschweigend um (anders als selbst erzeugte Pläne).

Eine **einzelne Einheit** wird in die laufende Woche des bestehenden Plans
eingetragen (Wahl des Wochentags in der Vorschau). Gibt es noch keinen Plan,
entsteht ein Plan mit einer Woche.

## Export in der App

Training → Plan → „Plan teilen" / „Als Datei speichern" (ganzer Plan) und
„Einheit teilen" (eine Einheit der laufenden Woche). Vorher fragt die App
Titel und — freiwillig — Autor ab; vorbelegt ist nichts aus dem Profil.

## Fehlermeldungen beim Lesen

Fremde Dateien werden streng geprüft und führen nie zu einem Absturz:

| Fall | Meldung (sinngemäß) |
|---|---|
| leer, über 256 KiB | Datei zu groß oder leer |
| kein UTF-8 / kein JSON | keine lesbare Textdatei / kein JSON |
| mehr als 8 Ebenen tief | zu tief verschachtelt |
| JSON ohne `format` (z. B. ein Backup) | „Das ist kein Plan-Rezept" |
| neuere `version` | App aktualisieren |
| Wert außerhalb des Bereichs | Pfad des Felds, z. B. `weeks[2].sessions[0].segments[1].minutes` |
| zu viele Wochen/Einheiten/Abschnitte | zu groß |
| ohne Einheit | Rezept ist leer |

## Hinweis

Rezepte sind Trainingsvorschläge, keine medizinische Beratung. Wer Pläne
aus dem Netz importiert, sollte sie vorher lesen — die Datei ist dafür
gemacht.
