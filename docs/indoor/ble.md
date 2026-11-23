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
  per `BroadcastReceiver`: kein Receiver, nichts abzumelden.
- Zustandsmeldung `CONNECTING` beim ersten Aufbau und nach dem Ende einer
  Blockade, `RECONNECTING` nach einem Abbruch und während der Neuversuche. Kein
  Wechsel bei jedem Versuch.
- Der Fehlversuchszähler fällt auf null bei der ersten erfolgreichen Operation
  oder dem ersten Wert (wie bei den Sensoren bei Daten). Takt
  `neuerVersuchNachMs`: 2/5/10/30 s.
- Frist 20 s für Verbindungsaufbau und Diensterkennung.
- Operationsfrist 5 s. Zwei Fristabläufe in Folge bedeuten: Der Stack hängt,
  also trennen und neu verbinden.
- Bei einem Abbruch werden alle offenen Operationen mit `ok = false` gemeldet,
  danach der Zustand. Nach `close()` kommt kein Ergebnis mehr, nur `CLOSED`.
- Status 133 gilt als Abbruch: `disconnect` und `close`, Neuversuch nach dem Takt.
- Späte Antworten nach einem Fristablauf werden über Art und Characteristic dem
  laufenden Auftrag zugeordnet und sonst verworfen. Callbacks einer ersetzten
  GATT-Instanz werden verworfen.
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
- „Status 133 wie `BleSensorConnection`“ (Abschnitt 5.1) ist um zwei
  Fristabläufe in Folge ergänzt: Auch sie führen zum Trennen und Neuverbinden.

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
- [ ] **30 Minuten Dauerverbindung** ohne Abbruch (Trainer und Schalttaster).
- [ ] **MTU-Anfrage des Schalttasters:** Stört ein ungefragtes `onMtuChanged` die
      Warteschlange nicht?
