# Prüfliste fürs Gerät

Diese Funktionen wurden nur auf der JVM getestet: mit Unit-Tests, gerenderten
Screenshots und dem Release-Build. Diese Liste beschreibt, was auf einem echten
Telefon (und einer Uhr) geprüft werden muss, bevor die Funktionen als fertig
gelten.

## Startseite „Heute"

- [ ] Wochenziel ohne Trainingsplan: Steht eine Zahl da („x von y km“)? Wirkt
      sie für die eigene übliche Woche plausibel? Wächst der Balken nach einer
      Fahrt?
- [ ] Wochenserie: Stimmt die Zahl der Wochen? Steht ab Wochenmitte da, wie
      viele Tage bleiben?
- [ ] „Nächste Bestweite“: Baut der Knopf eine Runde in der genannten Länge?
- [ ] Jahresziel setzen, ändern, entfernen. Die Startseite bleibt danach
      flüssig bedienbar, auch mit mehr als 3000 km im Jahr.
- [ ] Monats-Challenge-Zeile: Verschwindet sie, wenn alles geschafft ist, und
      erscheint dann die Bonus-Stufe?
- [ ] Montags bis mittwochs: Erscheint der Rückblick auf die letzte Woche mit
      richtigen Zahlen?

## Wochen-Fahrplan

- [ ] Ohne Trainingsplan, Wochenziel offen: Erscheint unter „Diese Woche“ die
      Karte „Wochen-Fahrplan“ mit ein bis drei Tagen? Passen die Tage zu den
      eigenen üblichen Fahrtagen, die Längen zur eigenen typischen Runde?
- [ ] Mit Trainingsplan (auch einem, der erst nächste Woche beginnt) und nach
      geschafftem Wochenziel: Die Karte fehlt.
- [ ] Nach 18 Uhr steht heute nicht mehr im Plan; nach einer Fahrt heute
      ebenfalls nicht. Sonntagabend: „kein Fahrtag mehr“ statt Vorschlägen,
      der Wetter-Schalter lässt sich trotzdem umlegen, und es geht keine
      Anfrage raus.
- [ ] Großer Rest am Wochenende: Erscheint „Knapp“ mit einer plausiblen
      Obergrenze statt einer Mammutrunde?
- [ ] „Runde planen“ öffnet die Karte und baut eine Runde in genau der
      genannten Länge.
- [ ] Schalter „Wetter für den Wochenplan“ ist ab Werk aus. Eingeschaltet:
      Stehen nach kurzer Zeit „trocken, 14 °C“ o. ä. und „Wetter: Open-Meteo“
      da? Passen Temperatur und Regen grob zum Wetterbericht?
- [ ] Mit Netzwerk-Mitschnitt (z. B. PCAPdroid): genau eine Anfrage an
      `api.open-meteo.com` mit zwei Nachkommastellen; erneutes Öffnen von
      *Heute* innerhalb von 3 Stunden fragt nicht erneut.
- [ ] Ohne Standortfreigabe, aber mit Touren: Wetter kommt für den Start der
      letzten Tour (nur, wenn sie höchstens 14 Tage alt ist), es erscheint
      kein Berechtigungsdialog. Ganz ohne Ort:
      „Kein Standort bekannt“. Im Flugmodus: „Vorhersage gerade nicht
      erreichbar“, der Plan steht trotzdem.
- [ ] Ausschalten: Die Wetterzeilen verschwinden sofort, auch wenn gerade
      noch eine Anfrage lief.
- [ ] Englisch: Wochentage („Thu“) und Texte passen, nichts abgeschnitten.

## Nach der Fahrt

- [ ] Aufzeichnung beenden, einmal in der App und einmal über die
      Benachrichtigung: Zeigt das Tourblatt auf der Karte bis zu drei Erfolge?
      Bleibt genug Karte sichtbar?
- [ ] Eine Fahrt, die vor Mitternacht beginnt und danach endet: Erscheinen die
      Erfolge trotzdem?
- [ ] Tour-Detail: „Erfolge dieser Tour“ und „Diese Runde“ bei einer Strecke,
      die mehrfach gefahren wurde. Werden die Wiederholungen erkannt, auch in
      Gegenrichtung? Wie lange dauert das erste Öffnen bei großem Tourbestand?
- [ ] Hin- und Rückweg derselben Strecke (z. B. Arbeitsweg) werden nicht
      miteinander verglichen.

## Training → Fortschritt

- [ ] Verlauf der letzten 12 Wochen und der Vergleich darüber passen zu den
      eigenen Touren.
- [ ] Fitness-Trend: Passt die Aussage („fitter“, „hält“, „etwas schwächer“)
      zum eigenen Gefühl der letzten Wochen? Mit und ohne Pulsmessung ansehen.
- [ ] Monats-Challenges: weder geschenkt noch unerreichbar. Nach einem
      einzelnen sehr langen Event nicht drei Monate lang überzogen.
- [ ] Bestwerte: Ein Tipp öffnet die richtige Tour.

## Vorgeschlagene Runden

- [ ] Mehrere Runden in bekanntem Gelände erzeugen (flach, hügelig mit
      Serpentinen, Stadt). Keine Stichstrecken mehr, kein Sprung über eine
      Serpentine oder zwischen parallelen Wegen, Abbiegehinweise an den
      bereinigten Stellen stimmig.
- [ ] Gravel-Profil bevorzugt bei ähnlicher Länge die Runde mit mehr
      unbefestigten Wegen, Asphalt-Profil die mit weniger.
- [ ] Ein schwieriger Startpunkt (Seeufer, Fluss mit einer Brücke): Sucht die
      App einmal in anderer Richtung nach? Wie lange dauert das? Nennt sie eine
      schwache Runde nicht „Beste“?
- [ ] „Andere Vorschläge“ liefert wirklich andere Runden.

## Neue Wege

- [ ] *Runde ab hier* → „Neu entdecken: Wege“ wählen, Runde von der
      Haustür. Führen die Vorschläge über Wege, die du wirklich noch nie
      gefahren bist? Stimmt „davon N km neu“ ungefähr mit deinem Gefühl und
      mit der Karte „Alle Touren“ überein?
- [ ] Die Hausstrecke, in beide Richtungen gefahren, zählt als bekannt — auch
      wo die Aufzeichnung auf der anderen Straßenseite lag.
- [ ] Eine Parallelstraße oder ein Feldweg 50–100 m neben einer oft
      gefahrenen Straße zählt als neu.
- [ ] Großer Bestand (mehrere Tausend km, viele Jahre): Wie lange dauert die
      erste Suche mit „Wege“ (der Index wird einmal aufgebaut)? Ist die zweite
      Suche und „Andere Vorschläge“ spürbar schneller? Kein Ruckeln, kein
      Absturz wegen Speicher.
- [ ] Nach einer neuen Fahrt: Die gerade gefahrenen Wege zählen bei der
      nächsten Suche als bekannt. Nach dem Löschen einer Tour wieder als neu.
- [ ] Ohne gefahrene Tour (frische Installation): Die Suche klappt, jede Runde
      zeigt ihre ganze Länge als neu.
- [ ] Die Wahl „Aus / Wege / Gegenden“ bleibt beim Tab-Wechsel und Drehen
      erhalten; „Gegenden“ verhält sich wie der frühere Schalter „Neue Gegenden
      bevorzugen“ (Pille „+N neu“), bei „Wege“ verschwindet die Pille.
- [ ] Flugmodus: Die Suche mit „Wege“ geht kein Netz an, außer dem Routing
      selbst (Offline-Kacheln) und, falls an, dem Wind.
- [ ] TalkBack: Überschrift, die drei Segmente samt Auswahl und der Hinweis
      darunter werden vorgelesen; „davon N km neu“ gehört zur Vorschlagszeile.
- [ ] Englisch: „Discover something new: Off / New roads / New areas“ und
      „23 km of it new“ passen auf ein schmales Gerät.

## Startbildschirm-Widget

- [ ] In beiden Größen hinzufügen: Darstellung hell und dunkel, Text nicht
      abgeschnitten.
- [ ] Zahlen stimmen mit der Startseite überein (Serie, Wochenziel).
- [ ] Aktualisiert sich nach einer gespeicherten Fahrt und am nächsten Morgen,
      ohne dass die App geöffnet wird.
- [ ] Folgt der in der App gewählten Sprache.
- [ ] Ein Tipp öffnet die App.

## Erinnerungen

- [ ] Anstupser einschalten: Läuft eine Serie und fehlt die Woche noch, kommt
      ab Freitag die Serien-Meldung (und davor in dieser Woche kein allgemeiner
      Anstupser).
- [ ] Wochenrückschau am Sonntagabend, auch ohne Trainingsplan.

## Backup

- [ ] Backup exportieren, App-Daten löschen oder auf einem zweiten Gerät
      importieren: Das Jahresziel ist wieder da, samt Stand zum Soll.
- [ ] Ein altes Backup (vor dieser Änderung) lässt sich weiter importieren.

## Galaxy Watch

- [ ] Aufzeichnung mit der Uhr starten und beenden funktioniert unverändert.
- [ ] Aufzeichnung **an der Uhr** beenden, wenn die Tour etwas gebracht hat
      (Bestwert, Wochenziel, Serie): Stehen auf dem Abschlussbild der Uhr bis
      zu zwei Zeilen dazu? Passen sie auf das runde Display? Kommen sie an,
      solange das Abschlussbild noch offen ist?
- [ ] Sprache der Zeilen: Sie folgen der Sprache der Handy-App, nicht der der
      Uhr.
- [ ] Die Uhr-App geschlossen, Tour beendet: Kommen die Zeilen trotzdem an?
- [ ] Tour mit der Uhr starten und **am Handy** beenden (App-Knopf, ein zweites
      Mal über die Benachrichtigung): Beendet die Uhr ihre Aufzeichnung von
      selbst und zeigt ihr Abschlussbild, mit den Zeilen, wenn die Tour etwas
      gebracht hat? Bleibt am Handy genau eine Tour gespeichert?
- [ ] Am Handy **pausieren**: Die Uhr beendet nicht, ihre Aufzeichnung läuft
      weiter. Danach am Handy fortsetzen und beenden.
- [ ] Am Handy beenden, sofort an der Uhr eine neue Aufzeichnung starten:
      Die neue läuft weiter.
- [ ] **Kachel hinzufügen**: Auf der Uhr in der Kachelauswahl „Serie & Wochenziel" anlegen. Vorschau
      sichtbar? Ohne vorherige Daten: „Noch keine Daten – Handy-App öffnen"; nach dem Öffnen der
      Handy-App erscheinen Serie und Kilometer.
- [ ] Kachel: Serie groß, „x von y km" und der Bogen passen aufs runde Display, nichts wird
      abgeschnitten; ein Tipp öffnet die Uhr-App. Deutsch und Englisch (Uhrsprache umstellen).
- [ ] **Komplikation aufs Zifferblatt**: Als Kurztext („5 W"), als Bereichswert (Bogen mit km) und
      als Langtext einrichten. Zeigt jedes Feld etwas Lesbares, öffnet ein Tipp die App?
- [ ] **Zahlen stimmen** mit der Heute-Seite am Handy überein (Serie in Wochen, gefahrene km,
      Wochenziel) — auch mit aktivem Trainingsplan.
- [ ] **Aktualisiert nach der Fahrt**: Tour am Handy speichern. Ändern sich Kachel und Komplikation
      binnen Sekunden bis Minuten, auch wenn die Uhr-App zu ist?
- [ ] Handy aus oder außer Reichweite: Kachel und Komplikation zeigen den letzten Stand weiter;
      nach Wiederverbindung zieht er nach.
- [ ] **Montag**: Über Mitternacht von Sonntag auf Montag (Uhr nicht mit dem Handy verbunden
      lassen): Zeigt die Uhr nicht mehr „Diese Woche geschafft", 0 km, die Serie als „in Gefahr"
      (war die Vorwoche geschafft) bzw. 0 Wochen (war sie es nicht)? Nach dem nächsten Öffnen der
      Handy-App stehen neues Ziel und Kilometer da.

## Bekannte offene Punkte

- Das Jahresziel ist nicht Teil des Selfhost-Syncs.
- `PRIVACY.md` hat einen neuen Absatz zur lokalen Berechnung erhalten. Die
  Angabe „Stand“ wurde nicht geändert. Die Entscheidung darüber liegt beim
  Eigentümer des Projekts.
