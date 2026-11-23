# Indoor: Funk (Slice indoor-ble)

Notizen zum Slice nach `docs/indoor.md`, Abschnitt 2.6. Vertrag: Abschnitte 3.2 und 5.1.

## Überblick

Quellen unter `app/src/main/kotlin/de/trailscape/app/sensors/indoor/`:

| Datei | Inhalt |
|---|---|
| `GattLinkKern.kt` | Plattformfreier Automat des Rohrs: Warteschlange, Fristen, Zustände, Neuversuche. |
| `GattBleLink.kt` | Android-Hülle (`BluetoothGatt`, Callbacks) um den Automaten; implementiert `BleLink`. |
| `IndoorScanner.kt` | Suche nach Trainern und Schalttastern (drei Filter, 20 s). |
| `IndoorGeraeteLogik.kt` | Speicherformat der gemerkten Geräte und Rollen aus der Werbung, ohne Android. |
| `IndoorDevices.kt` | Gemerkte Geräte (`IndoorDeviceRef`), Koppeln, Vergessen, `openLink`. |
| `IndoorDevicesUi.kt` | Abschnitt „Indoor-Training“ (zustandslos: `IndoorDevicesContent`). |

Tests: `app/src/test/kotlin/de/trailscape/app/sensors/indoor/`, dazu der
Screenshot-Test `IndoorDevicesScreenshotTest` (nur mit `-Pscreenshots`).
Texte: `strings_indoor_ble.xml` (Präfix `indoor_ble_`).

## Entscheidungen

**Abschnitt in der Oberfläche**
- Der Abschnitt verbindet nicht. Ein Trainer nimmt oft nur eine Verbindung an,
  und die gehört der Fahrt.
- Unter Mehr → Sensoren werden die Systemhinweise (Berechtigung, Bluetooth aus,
  kein BLE) nicht doppelt gezeigt (`systemHinweise = false`).
- Speicherung unter `trailscape.indoor.devices` als JSON-Array
  `{rolle, adresse, name}`, je Rolle ein Eintrag.

**Suche**
- Drei Filter: FTMS-Dienst, Zwift-Dienst, Zwift-Firmenkennung mit leerem
  Datenmuster. Dauer 20 s.
- Die Rollen kommen aus der Werbung. Ein Gerät, das beides bewirbt, kann in
  beiden Listen stehen.

**Verbindung (Rohr)**
- Thread ist der vorhandene „trailscape-ble“ (`BleSensors.bleThreadHandler()`).
- Blockaden (Bluetooth aus, keine Berechtigung) werden alle 2 s neu geprüft statt
  per `BroadcastReceiver`: kein Receiver, nichts abzumelden. Ebenso prüft der Kern
  in den aktiven Phasen (Aufbau, Diensterkennung, READY) alle 2 s, ob Bluetooth
  noch an ist — nicht jedes Gerät meldet beim Ausschalten einen
  Verbindungsabbruch, und ohne diese Wache bliebe das Rohr für immer „READY“.
- Zustandsmeldung `CONNECTING` beim ersten Aufbau und nach dem Ende einer
  Blockade, `RECONNECTING` nach einem Abbruch und während der Neuversuche. Kein
  Wechsel bei jedem Versuch.
- Der Fehlversuchszähler fällt erst auf null, wenn eine Verbindung 15 s lang
  READY gehalten hat — nicht schon bei einer erfolgreichen Operation oder einem
  Wert: Ein Schalttaster, der nach dem Handschlag jedesmal trennt, würde sonst im
  2-s-Takt neu verbunden (Gefahr von Status 133). Takt `neuerVersuchNachMs`:
  2/5/10/30 s.
- Frist 20 s für Verbindungsaufbau und Diensterkennung.
- Operationsfrist 5 s. Schon **ein** Fristablauf heißt: Der Stack hängt. Android
  hält intern „beschäftigt“, bis der Callback doch noch kommt; jede weitere
  Operation scheiterte sofort, und ein später Callback könnte der nächsten
  gleichartigen Operation (bei FTMS immer der Control Point) zugeordnet werden.
  Das Rohr meldet die Operation nach 5 s als gescheitert, trennt und verbindet
  neu; die neue GATT-Instanz verwirft alles Späte sicher. Ebenso drei synchrone
  Ablehnungen in Folge (Stack beschäftigt, `ERROR_GATT_WRITE_REQUEST_BUSY`); eine
  Antwort per Callback setzt diese Zählung zurück.
- Bei einem Abbruch werden alle offenen Operationen mit `ok = false` gemeldet,
  danach der Zustand. Nach `close()` kommt kein Ergebnis mehr, nur `CLOSED`.
- Status 133 gilt als Abbruch: `disconnect` und `close`, Neuversuch nach dem Takt.
- Antworten werden über Art und Characteristic dem laufenden Auftrag zugeordnet,
  sonst verworfen. Callbacks einer ersetzten GATT-Instanz werden verworfen.
- Ein `IllegalArgumentException` der Plattform beim Start (etwa ein Write über
  512 Byte ab API 33) wird zur Ablehnung, nicht zum Absturz.
- Unter Mehr → Sensoren zeigt der gesperrte Suchknopf eine Zeile, dass die
  Hinweise oben stehen; unter Android 12 warnt der Abschnitt, wenn der
  Standortdienst aus ist (sonst bliebe die Suche wortlos leer).
- `subscribe` prüft die Property (NOTIFY/INDICATE) und den CCCD vor dem
  Schreiben.
- Eigene negative Statuscodes; `BluetoothStatusCodes`-Fehler ab API 33 als
  `1000 + Code`.
- Ein ungefragtes `onMtuChanged` bleibt ohne Folgen.

## Abweichungen vom Vertrag

- Keine Vertragstypen geändert.
- `BleSensors.kt` bekam `bleThreadHandler()`, damit das Rohr den vorhandenen
  Thread „trailscape-ble“ teilt (Vertrag: „dem vorhandenen Thread“). Die Datei
  gehört zum Slice (`sensors/**`).
- „Status 133 wie `BleSensorConnection`“ (Abschnitt 5.1) ist ergänzt: Auch ein
  Fristablauf einer Operation und drei synchrone Ablehnungen in Folge führen zum
  Trennen und Neuverbinden. „Nach 5 s als Fehler melden“ bleibt erfüllt; die
  Verbindung ist danach zusätzlich neu.

## Annahmen

- `onCharacteristicWrite` kommt auch bei Write Command
  (`WRITE_TYPE_NO_RESPONSE`); die Operation endet damit.
- Der Zwift Click bewirbt die Firmenkennung `0x094A`.
- Ein Trainer, der auch als Leistungsmesser in den Sensoren gekoppelt ist,
  verträgt zwei GATT-Clients der App (Sensoren und Rohr).

## Auf dem Gerät zu prüfen

- [ ] **KICKR CORE (FTMS):** Wird er gefunden und gekoppelt, öffnet `openLink` die
      Verbindung, erscheinen Dienste und Characteristics, laufen Subscribe und
      Write?
- [ ] **Zwift Click:** Wird er gefunden (auch wenn die Werbung den Dienst nicht
      nennt), kommen Tastendrücke an?
- [ ] **Suche findet den Click ohne Dienst in der Werbung** (nur über die
      Firmenkennung).
- [ ] **Android 11 gegen 12+:** Unter 12 verlangt die Suche Standortfreigabe und
      eingeschalteten Standort (Hinweistext stimmt), ab 12 „Geräte in der Nähe“.
- [ ] **Trainer ausschalten und wieder einschalten:** Verbindet das Rohr von selbst
      neu, im Takt 2/5/10/30 s, ohne Zustandsflackern?
- [ ] **Bluetooth aus und an während der Fahrt:** Blockade, Neuaufbau binnen etwa 2 s
      nach dem Einschalten.
- [ ] **Berechtigung während der Fahrt entziehen** und wieder erteilen.
- [ ] **Status 133 bei schnellem Neuverbinden** (Fahrt beenden und sofort neu
      starten): Fängt sich das Rohr?
- [ ] **Trainer gleichzeitig in der Hersteller-App** verbunden: Verhalten und
      Meldung am Rohr.
- [ ] **Doppelkopplung:** Trainer zugleich als Leistungsmesser unter Mehr → Sensoren
      gekoppelt, beide Verbindungen halten.
- [ ] **Gerät mit leerem Namen:** „Unbenanntes Gerät“ in Liste und Dialogen.
- [ ] **Drosselung:** Mehr als 5 Suchen in 30 s; die Fehlermeldung erscheint, die
      Suche geht danach wieder.
- [ ] **Neuverbinden ohne vorherige Suche** (Risiko aus dem Review): Koppeln, Handy
      neu starten oder Bluetooth aus/an, dann Indoor-Fahrt ohne neue Suche.
      `getRemoteDevice(adresse)` kennt den Adresstyp nicht; Geräte mit
      Random-Static-Adresse könnten dann bis zur nächsten Suche nicht verbinden
      (20-s-Frist bzw. 133 in Schleife). Falls ja: vor dem ersten Versuch eine
      kurze, auf die Adresse gefilterte Suche vorschalten (Bitte an indoor-ble
      in der nächsten Runde) oder ab API 33 den Adresstyp beim Koppeln merken.
- [ ] **Bluetooth am Handy aus, während der Trainer READY ist:** Meldet das Rohr
      binnen etwa 2 s `BLUETOOTH_OFF`, auch wenn kein Abbruch-Callback kommt?
- [ ] **Einzelner Fristablauf** (z. B. Trainer reagiert nicht auf ein Write):
      Neuverbinden statt Folge von Sofort-Fehlern; FTMS richtet sich danach neu ein.
- [ ] **30 Minuten Dauerverbindung** ohne Abbruch (Trainer und Schalttaster).
- [ ] **MTU-Anfrage des Schalttasters:** Stört ein ungefragtes `onMtuChanged` die
      Warteschlange nicht?
