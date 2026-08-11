# Wear-OS-Spike: Gerätetest

Dieses Dokument beschreibt, wie die Spike-APK des Moduls `:wear` auf eine
Samsung Galaxy Watch Ultra (Wear OS 5) kommt und wie eine echte Ausfahrt
damit aufgezeichnet wird.

## a) Was der Spike beantworten soll

1. Welche Datentypen liefert die Uhr tatsächlich — insbesondere: liefert sie
   **absolute Höhe** (nicht nur relative/barometrische Änderung)?
2. Wie gut sind Qualität und Rate der GPS-Punkte der Uhr im Vergleich zum
   Handy?
3. Wie hoch ist der Akkuverbrauch über eine echte, mehrstündige Ausfahrt?
4. Rechnet das plattformfreie `:core`-Modul unverändert auf der Uhr, oder
   gibt es dort Überraschungen (fehlende APIs, Performance, o. ä.)?

## b) APK auf die Uhr bringen

**Wichtig vorab:** Port `5555` funktioniert bei aktuellem Wear OS **nicht
mehr**. Seit Wear OS 4 ist der Pairing-Flow über Drahtlos-Debugging
verpflichtend, die Ports sind zufällig vergeben. Viele Anleitungen im Netz
sind an dieser Stelle veraltet und falsch. ADB über Bluetooth gibt es
außerdem seit Wear OS 3 nicht mehr — es geht nur über WLAN. Das ist kein
Samsung-Sonderweg, sondern Standardverhalten von aktuellem Wear OS.

Voraussetzung: PC und Uhr müssen im **selben WLAN** sein, und das Netz muss
Peer-to-Peer-Verkehr zwischen Geräten erlauben. Viele Gast- oder
Firmen-WLANs blockieren das — in dem Fall stattdessen einen Handy-Hotspot
nutzen und beide Geräte dort verbinden.

Schritte:

1. **Uhr:** Einstellungen → Info zur Uhr → Softwareinformationen →
   Softwareversion **5× antippen** → Entwickleroptionen erscheinen.
2. **Uhr:** Einstellungen → Entwickleroptionen → **ADB-Debugging**
   aktivieren.
3. **Uhr:** **Drahtlos-Debugging** aktivieren → „Immer in diesem Netzwerk
   zulassen“ bestätigen.
4. **Uhr:** Entwickleroptionen → Drahtlos-Debugging → **Neues Gerät
   koppeln** → zeigt IP-Adresse, Pairing-Port und einen 6-stelligen Code.
5. **PC:**
   ```
   adb pair <IP>:<Pairing-Port>
   ```
   Code eingeben, wenn danach gefragt wird.
6. **Uhr:** zurück auf die Übersicht von Drahtlos-Debugging (**nicht**
   erneut „Neues Gerät koppeln“) → dort steht der **Verbindungs-Port** — ein
   anderer als der Pairing-Port aus Schritt 4.
7. **PC:**
   ```
   adb connect <IP>:<Verbindungs-Port>
   ```
8. **PC:**
   ```
   adb install -r trailscape-wear.apk
   ```

**Der Verbindungs-Port ändert sich bei jedem Neustart der Uhr.** Nach einem
Neustart reicht es, Schritt 7 (`adb connect` mit dem neuen Port aus Schritt
6) zu wiederholen — das Pairing selbst (Schritte 4–5) bleibt bestehen und
muss nicht erneut durchgeführt werden.

## c) Wo die APK herkommt

Die Spike-APK kommt aus dem CI-Artefakt `trailscape-wear-apk` des
jeweiligen Build-Laufs: GitHub → Actions → den gewünschten Lauf öffnen →
Abschnitt „Artifacts“ → `trailscape-wear-apk` herunterladen.

Sie liegt **bewusst nicht** bei den Releases (`latest` / `v2.0.x`), weil sie
sich dort ohnehin nicht normal installieren ließe — eine Wear-APK auf der
Releases-Seite würde Nutzer nur verwirren.

## d) Ablauf des Tests

1. App auf der Uhr starten.
2. Angeforderte Berechtigungen erteilen.
3. Den angezeigten Fähigkeitsbericht **abfotografieren** — das ist bereits
   ein Ergebnis des Spikes, unabhängig vom Rest des Tests.
4. Aufzeichnung starten.
5. Eine möglichst realistische Ausfahrt fahren: mindestens 1–2 Stunden,
   Display zwischendurch bewusst ausgeschaltet lassen (nicht die ganze Zeit
   aktiv am Handgelenk anschauen).
6. Aufzeichnung am Ende beenden.

## e) Daten von der Uhr holen

```
adb connect <IP>:<Port>
adb shell ls /sdcard/Android/data/io.github.robinrehbein.trailscape/files/
adb pull /sdcard/Android/data/io.github.robinrehbein.trailscape/files/<datei>.jsonl
```

Die App schreibt ihr Aufzeichnungs-Journal nach `getExternalFilesDir`, daher
dieser Pfad. Die `applicationId` ist identisch mit der Handy-App
(`io.github.robinrehbein.trailscape`) — das ist Absicht und für die spätere
Kommunikation zwischen Uhr und Handy zwingend notwendig.

## f) Was danach kommt

Der Spike ist keine benutzbare App und wird nicht veröffentlicht. Auf Basis
der Messwerte (siehe Abschnitt a) wird entschieden, ob eine vollwertige
Wear-App gebaut wird. Zwei Hürden sind dabei schon jetzt bekannt und sollen
hier dokumentiert bleiben:

1. **Verteilung ohne Play Store ist praktisch nicht möglich.** Der frühere
   Weg, bei dem die Handy-App die passende Uhren-App automatisch mitliefert
   und installiert, wurde 2021 abgeschaltet und ist seit AGP 9 auch aus dem
   Build-System entfernt. Realistisch bleibt für eine echte Wear-App nur der
   Weg über die Play Console mit Internal Testing (bis zu 100 Tester, keine
   Review-Wartezeit).
2. **Signierung über den Play Store bricht die Kommunikation, wenn man
   nicht aufpasst.** Läuft das Wear-APK je über den Play Store, muss der
   eigene Signierschlüssel per PEPK hochgeladen werden. Sonst signiert
   Google die APK beim Ausliefern neu — und die Kommunikation zur
   sideloaded Handy-APK bricht, weil Google für die Kopplung identische
   Signatur **und** identischen Paketnamen verlangt.
