# Prüfliste: Raum für Gruppenfahrten (zwei Geräte, ein Server)

Stand 25.11.2026. Gehört zu `docs/raum-protokoll.md` (Abschnitt 14) und `docs/ux/umbau.md`
(„Raum für Gruppenfahrten“). Alles hier ist nur mit echten Geräten prüfbar; die Zustandslogik
dahinter decken `IndoorRoomControllerTest` und `IndoorRoomFlowTest` ohne Netz ab.

## Was man braucht

- **Server**: ein Trailscape-Server in dem Stand, der Räume kann (`server/rooms.mjs`,
  `server/websocket.mjs`), mit eingeschalteten Räumen (`ROOMS=1`, siehe `server/README.md`), per
  **HTTPS** erreichbar. Der Reverse-Proxy muss WebSocket durchreichen (`docs/raum-protokoll.md`,
  Abschnitt 2; bei nginx die drei Zeilen dort). Schnelltest ohne App: `GET /api/rooms/ws` ohne
  Upgrade antwortet mit `426`, `GET /api/rooms` mit Token mit einer Liste.
- **Gerät A (Gastgeber)**: Adresse und Token des Servers unter Einstellungen → Sync mit eigenem
  Server eingetragen.
- **Gerät B (Gast)**: **kein** Server eingetragen. So zeigt sich, dass Beitreten ohne geht.
- Auf beiden dieselbe bekannte Strecke in derselben Version heruntergeladen – oder für den ersten
  Durchgang die Teststrecke. Auf beiden ein Trainer gekoppelt oder „Ohne Trainer ausprobieren“.
- Ein Weg, einen Link von A nach B zu schicken (Messenger).

## A Einstieg und Platz

- [ ] Indoor zeigt drei Segmente **Strecken · Plan · Gemeinsam**, einzeilig und ungekürzt – auch
      auf dem schmalsten Gerät (320 dp), mit großer Schrift und auf Englisch (Routes · Plan ·
      Together).
- [ ] „Gemeinsam“ zeigt oben den Abschnitt „Raum“, darunter „Herausforderungen“ mit allem, was
      vorher im Segment „Herausforderungen“ stand.
- [ ] Gerät B (ohne Server): „Raum anlegen“ ist nicht bedienbar; darunter steht, wohin man muss
      („Einstellungen → Sync mit eigenem Server“), „Einstellungen öffnen“ öffnet die
      Einstellungen. „Raum beitreten“ ist bedienbar.
- [ ] Gerät A: nach dem Eintragen des Servers und der Rückkehr ist „Raum anlegen“ bedienbar, die
      Zeile ist weg.
- [ ] „Trailscape öffnen“ nach dem Import einer Herausforderung landet auf „Gemeinsam“.

## B Anlegen (Gerät A)

- [ ] Unter „Strecken“ ist eine eigene Tour gewählt: Der Dialog „Raum anlegen“ erklärt, dass das
      nicht geht, und „Zu den Strecken“ führt hin. „Raum anlegen“ im Dialog ist nicht bedienbar.
- [ ] Bekannte Strecke gewählt: Der Dialog nennt Strecke mit Länge, den Server (nur den
      Rechnernamen, kein Token), das Namensfeld, „Leistung zeigen“ (aus) und den Satz dazu, was
      andere sehen.
- [ ] Ohne Namen bleibt „Raum anlegen“ gesperrt.
- [ ] Nach dem Bestätigen: kurz „Raum wird angelegt …“, dann „Raum auf <Server>“ mit der Marke
      „Gastgeber“, der Strecke und einem Teilnehmer („<Name> (du)“).
- [ ] Falsches Token eingetragen: Hinweis „Der Server lehnt dein Token ab …“. Server ohne Räume:
      „Dein Server bietet keine Räume an …“. Flugmodus: „Dein Server ist nicht erreichbar …“.
      Jeder Hinweis verschwindet mit „OK“.

## C Einladen und Beitreten (A → B)

- [ ] „Einladung teilen“ öffnet das Teilen-Blatt; der Text beginnt mit `trailscape://room?` und
      enthält weder das Token noch den Namen.
- [ ] B tippt den Link im Messenger an: Trailscape öffnet sich auf Indoor → Gemeinsam mit dem
      Dialog „Raum beitreten“, der Link steht im Feld, darunter „Server: <Rechnername>“. **Noch
      ist nichts verbunden** (auf dem Server: `GET /api/rooms` zeigt weiter `connected: 1`).
- [ ] Wird der Link vom Messenger nicht als Link erkannt: kopieren, „Raum beitreten“, einfügen –
      auch mit Text drumherum.
- [ ] Unsinn im Feld: „Das ist kein Einladungslink von Trailscape.“, „Beitreten“ gesperrt.
- [ ] Nach „Beitreten“: B sieht den Raum mit beiden Namen, A sieht B binnen einer Sekunde in der
      Liste. Auf B steht „Der Gastgeber startet die Fahrt.“, kein Startknopf, kein „Raum für alle
      beenden“.
- [ ] Derselbe Link ein zweites Mal auf B, während der Raum offen ist: Es passiert nichts
      Sichtbares (kein zweiter Raum).
- [ ] Läuft auf B gerade eine Fahrt, wenn der Link angetippt wird: Die Fahrt läuft weiter; der
      Dialog erscheint, sobald sie beendet ist.
- [ ] Link eines beendeten Raums: „Diesen Raum gibt es nicht mehr …“.
- [ ] Mehr Geräte als Plätze (Vorgabe des Servers): „Der Raum ist voll.“

## D Strecke

- [ ] B hat die Strecke nicht: statt „Zum Start“ steht „Strecke herunterladen“; der Knopf führt
      auf „Strecken“ zum Download. Nach dem Download zeigt der Raum „Zum Start“ (der Raum blieb
      beim Wechsel der Sicht offen).
- [ ] B hat eine neuere Version als A: erklärender Satz, nicht fahrbar.
- [ ] Ohne gekoppelten Trainer führt „Zum Start“ auf „Strecken“, wo die Trainer-Karte steht; die
      Strecke des Raums ist dort schon gewählt.

## E Bereit und gemeinsamer Start

- [ ] B drückt „Zum Start“: Die Strecke wird vorbereitet, die Fahransicht öffnet sich mit der
      Karte „Warten auf den Start“, „1 von 2 am Start“. Keine Startpunkt-Karte. Treten bewegt
      nichts.
- [ ] A sieht im Raum bei B „Bereit“ (binnen einer Sekunde).
- [ ] A drückt „Zum Start“: auf beiden „2 von 2 am Start“; auf A steht der Knopf „Start in
      10 Sekunden“, auf B „Der Gastgeber startet die Fahrt.“
- [ ] A startet: Auf beiden läuft derselbe Countdown (Unterschied höchstens eine halbe Sekunde,
      nebeneinander gehalten). Bei 0 verschwindet die Karte auf beiden; ab dem ersten Tritt wird
      gefahren.
- [ ] Die Karte liegt mittig, verdeckt keine Bedienelemente, die man vor dem Start braucht, und
      passt quer wie hoch auf den Bildschirm.
- [ ] Start aus dem Raum heraus (A noch nicht am Start): Countdown erscheint im Raum; kommt A
      danach an den Start, fährt A sofort los.
- [ ] Ein drittes Gerät tritt nach dem Start bei: Im Raum steht „Die Fahrt läuft. Du kannst
      jederzeit einsteigen.“; „Zum Start“ führt ohne Warten in die Fahrt.

## F Während der Fahrt

- [ ] Auf beiden steht unter den Kernwerten der andere mit Abstand („Anna · 12 m vor dir“ /
      „hinter dir“). Die Zahl folgt der Wirklichkeit: A fährt schneller → auf B wächst „vor dir“.
- [ ] Überholen: Die Zeile wechselt von „hinter dir“ auf „vor dir“ ohne Springen um mehr als ein
      paar Meter.
- [ ] „Leistung zeigen“ auf A ein (im Raum, vor der Fahrt): B sieht „· 240 W“ hinter dem Abstand.
      Aus: keine Watt mehr. B (aus) zeigt A nie Watt.
- [ ] Die Zeilen stören weder Bergwertung noch Hinweise; mit drei und mehr anderen bleiben es
      höchstens drei Zeilen (die nächsten).
- [ ] Im Raum (Gerät, das nicht fährt, oder nach der Fahrt) steht bei Fahrenden „Fährt · x km“.
- [ ] 60 Minuten am Stück ohne Abbruch; Akku und Wärme im Vergleich zu einer Fahrt allein.

## G Verbindungsabbruch und Wiederverbinden

- [ ] B während der Fahrt kurz in den Flugmodus (10 s): Auf B erscheint „Verbindung zum Raum
      unterbrochen“, die eigene Fahrt läuft weiter. Auf A wird B nach 3 s grau und bleibt stehen.
      Nach dem Ende des Flugmodus verschwindet die Zeile auf B von selbst, B ist auf A wieder
      farbig an der richtigen Stelle – derselbe Eintrag, kein zweiter „B“.
- [ ] Flugmodus länger als 30 s: A verliert B aus der Liste; nach dem Wiederverbinden erscheint B
      neu (neuer Platz). B fährt in beiden Fällen durch.
- [ ] B im Raum (nicht in der Fahrt) ohne Netz: Hinweis „Verbindung unterbrochen. Neuer Versuch
      in n s. …“ mit herunterzählender Zahl.
- [ ] WLAN → Mobilfunk während der Fahrt: wie ein kurzer Abbruch.
- [ ] B wartet am Start, verliert die Verbindung, A startet inzwischen: B fährt los, sobald die
      Verbindung wieder da ist. Mit „Raum verlassen und allein fahren“ kommt B auch ohne Netz weg.
- [ ] App auf B in den Hintergrund und nach 10 s zurück: Die Fahrt war pausiert (wie immer); der
      Raum ist noch da oder verbindet sich neu. Nach mehreren Minuten im Hintergrund: sinnvoller
      Zustand (wieder verbunden oder verständlicher Hinweis), kein Hänger.
- [ ] Server neu starten, während beide im Raum sind: beide bekommen „Der Server hat den Raum
      beendet. Legt einen neuen an.“; wer fährt, fährt allein weiter („Raum beendet · du fährst
      allein weiter“).

## H Verlassen und Beenden

- [ ] B „Raum verlassen“: B sieht wieder „Raum anlegen / Raum beitreten“, A verliert B sofort
      aus der Liste.
- [ ] B verlässt vor dem Start aus der Fahransicht („Raum verlassen und allein fahren“): Die
      Karte verschwindet, die Zeile „Raum beendet · du fährst allein weiter“ steht da, Treten
      startet die Fahrt.
- [ ] A „Raum für alle beenden“: Rückfrage; danach auf B „Der Gastgeber hat den Raum beendet.“,
      auf A kein Hinweis.
- [ ] A verlässt nur (ohne Beenden): Der Raum bleibt für B bestehen, aber niemand kann mehr
      starten – hält das jemand für einen Fehler? (Offene Frage an die Bedienung.)
- [ ] Fahrt beenden, speichern, „Tour ansehen“: Der Raum ist danach noch offen; die Tour ist eine
      gewöhnliche Indoor-Tour.
- [ ] Indoor-Tab verlassen (anderer Tab) und zurück: Raum noch offen. App beenden (aus der
      Übersicht wischen): Der Platz wird auf dem Server frei (spätestens nach 30 s).

## I Datenschutz – am Server geprüft

- [ ] Ohne offenen Raum baut die App keine Verbindung zum Server auf, auch nicht beim Öffnen von
      „Gemeinsam“ (Zugriffsprotokoll des Reverse-Proxys: kein `/api/rooms…`).
- [ ] Mit Raum stehen im Zugriffsprotokoll nur `POST /api/rooms` (A) und `GET /api/rooms/ws`
      (beide) – ohne Raumkennung und ohne Geheimnis in der Adresse.
- [ ] Das Server-Protokoll enthält weder Namen noch Positionen noch Geheimnisse.
- [ ] Mit einem Mitschnitt (Entwickler-Proxy auf einem Testgerät, ohne TLS gegen `localhost`):
      Die App sendet nur `join` (Name, `sharePower`), `state` (`distM`, `speedMps`, mit „Leistung
      zeigen“ `powerW`), `ping`, `settings`, `leave`, als Gastgeber `start` und `end` – kein
      Avatar, keine Trittfrequenz, kein Puls, kein Standort, keine Gerätekennung.
- [ ] Nach „Raum verlassen“ und nach dem Ende des Raums: keine weitere Verbindung.

## J Sonst

- [ ] Englisch: alle Texte übersetzt, nichts abgeschnitten (Dialoge, Karte „Waiting for the
      start“, Zeilen in der Fahrt).
- [ ] Dunkles Design und große Schrift: Abschnitt und Dialoge lesbar; die Dialoge scrollen, wenn
      die Tastatur offen ist.
- [ ] TalkBack: Segment „Gemeinsam“ wird als Tab angesagt, der Countdown wird vorgelesen,
      „Leistung zeigen“ ist ein Schalter mit Beschriftung.
- [ ] Drehen des Geräts im Raum und während des Wartens: Raum und Karte bleiben.
