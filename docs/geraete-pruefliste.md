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

## Bekannte offene Punkte

- Die Uhr zeigt Serie und Wochenziel noch nicht auf einen Blick. Der Vorschlag
  dafür steht in `docs/wear-motivation.md`.
- Das Jahresziel ist nicht Teil des Selfhost-Syncs.
- `PRIVACY.md` hat einen neuen Absatz zur lokalen Berechnung erhalten. Die
  Angabe „Stand“ wurde nicht geändert. Die Entscheidung darüber liegt beim
  Eigentümer des Projekts.
