export const meta = {
  name: 'trailscape-ideenliste',
  description: 'Trailscape: Ideenliste (Englisch, Strava-Upload, Bluetooth-Sensoren, Play-Update-Pruefung) und Kleinigkeiten (Tourbild) entwerfen, umsetzen, reviewen und zusammenfuehren',
  whenToUse: 'In einer neuen Session mit „ultracode“: setzt die offenen Punkte aus docs/TODO.md und der Wachstums-Ideenliste als getestete Commits auf dem Session-Branch um. Optional args: { only: ["i18n","strava",...], skip: [...] }',
  phases: [
    { title: 'Setup', detail: 'Android-SDK, BRouter-Submodul, Gradle-Cache (mit 429-Retries), Ausgangsstand gruen' },
    { title: 'Design', detail: 'ein Architekt je Feature liest den Code und schreibt eine konkrete Spezifikation' },
    { title: 'i18n-Fundament', detail: 'Sprachumschaltung, Ressourcen-Aufteilung, Umgang mit Texten aus :core' },
    { title: 'Implement', detail: 'je Feature bzw. i18n-Bereich ein Worktree, Commit auf wf/<key>' },
    { title: 'Review', detail: 'zwei gegnerische Reviewer je Zweig (Korrektheit, UX/Texte)' },
    { title: 'Fix', detail: 'bestaetigte Befunde auf wf/<key>-final beheben' },
    { title: 'Integrate', detail: 'alle Zweige in den Session-Branch mergen, kompletter CI-Befehl' },
    { title: 'Final review', detail: 'Gesamt-Review ueber den kombinierten Diff, Screenshots DE+EN, Fixes' },
  ],
}

// ---------------------------------------------------------------------------
// Aufruf in einer neuen Session (Claude Code im Web, Repo robinrehbein/trailscape):
//   „ultracode: Führe den gespeicherten Workflow trailscape-ideenliste aus,
//    danach pushen und Draft-PR anlegen.“
// Optional: args = { only: ["share-card-polish"] } oder { skip: ["strava"] }.
//
// Der Workflow pusht NICHT und legt keinen PR an — das macht die Session
// selbst, nachdem sie das Ergebnis gelesen hat.
// ---------------------------------------------------------------------------

const REPO = '/home/user/trailscape'
const A = args || {}

const TRAILERS = `Co-Authored-By: Claude <noreply@anthropic.com>`

const CONTEXT = `
Projekt: Trailscape (Repo ${REPO}), native Android-Fahrrad-App (Kotlin, Jetpack Compose, Material 3 im „One UI“-Look) plus Wear-OS-Begleiter und optionaler Selfhost-Sync-Server (server/, Node, ohne Abhaengigkeiten).
Ein Gruender ist der einzige Entwickler. Alle UI-Texte sind DEUTSCH (du-Form, ruhig, praezise, „…“-Anfuehrungszeichen, echte Umlaute). Code-Kommentare und KDoc ebenfalls deutsch (bestehende Kommentare schreiben oft ae/oe/ue — der umgebenden Datei folgen).
Architektur: :core = reines Kotlin/JVM (keine Android-Imports, alle Logik + Unit-Tests), :app = Android-UI/Dienste (Robolectric NUR fuer Screenshot-Tests; plattformfreie Logik mit Unit-Tests), :wear = Uhr.
Positionierung: local-first, kein Konto-Zwang, keine Telemetrie. Jede neue Netzwerkanfrage braucht eine konkrete Nutzeraktion oder ein ausdrueckliches Opt-in und MUSS in PRIVACY.md („Was das Gerät nach außen sendet“) stehen; README-Featureliste aktuell halten.
Wichtige Dateien: README.md, docs/TODO.md, PRIVACY.md, app/.../ui/AppViewModel.kt (geteiltes ViewModel, sehr gross), ui/map/MapScreen.kt (sehr gross), ui/map/MapPanels.kt (RideCard), ui/rides/RideDetailScreen.kt, ui/rides/ShareCard*.kt (Tourbild), ui/ShareFiles.kt, record/RecordingService.kt, record/RecordingRepository.kt, wear/WearBridge.kt, update/UpdateChecker.kt + UpdateLogic.kt, ui/more/*.kt (Einstellungen), core/.../HttpClient.kt, core/.../JsonSupport.kt.
Build: JDK 21 + Android-SDK unter /root/android-sdk (sdk.dir steht in local.properties, das git-ignoriert ist — in jeden Worktree ${REPO}/local.properties kopieren). Jeder Worktree braucht ausserdem: git submodule update --init third_party/brouter.
Befehle: ./gradlew :core:test · ./gradlew :app:testDebugUnitTest · ./gradlew :app:assembleDebug · Screenshots: ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*Screenshot*' (PNGs unter app/build/outputs/roborazzi/, mit dem Read-Werkzeug ansehen).
Die CI fuehrt aus: ./gradlew :core:test :app:testDebugUnitTest -Pscreenshots :app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease — Screenshot-Tests laufen dort also MIT; ein Test, der auf einen Text klickt, bricht, wenn der Text sich aendert.
Maven Central antwortet manchmal mit HTTP 429 — dann ~45 s warten und bis zu 8-mal wiederholen. Die Maschine hat wenige Kerne: nie mehr als einen Gradle-Lauf gleichzeitig selbst starten.
Stil: KDoc erklaert das „Warum“, kleine fokussierte Dateien, reine Logik in :core mit Tests, UI-Bausteine aus ui/components (OneUiDialog, NeutralButton, ActionTile, NoticeBox, Eyebrow, TagPill, HoldToEndButton …), Theme-Tokens aus ui/theme.
Verboten: Analytics, Konto-Pflicht, neue Dritt-SDKs ohne ausdrueckliche Erlaubnis in der Spezifikation, Tests ueberspringen/deaktivieren, Force-Push, Secrets im Repo.
`

// ---------------------------------------------------------------- Features

const FEATURES = [
  {
    key: 'share-card-polish',
    title: 'Tourbild: auch vom Tourblatt der Karte teilen, Start und Ziel ausblenden',
    brief: `Zwei Kleinigkeiten aus docs/TODO.md rund um das Tourbild (ui/rides/ShareCard*.kt, Dialog in RideDetailScreen.kt):
1) Das Tourblatt auf der Karte (RideCard in ui/map/MapPanels.kt) teilt bisher nur GPX. Denselben Teilen-Dialog (Bild Story/Quadrat oder GPX) dort anbieten — denselben Composable wiederverwenden, keinen zweiten bauen.
2) Privatsphaere: Im Tourbild Start und Ziel ausblenden (Spur an beiden Enden um einen Radius kuerzen, Standard 300 m, Schalter „Start und Ziel ausblenden“ im Teilen-Dialog, ab Werk AN, Wahl in den Prefs merken). Die Kuerzung ist reine, getestete Logik (plattformfrei, z. B. in :core oder neben ShareCardLayout mit Tests); Rundtouren, sehr kurze Touren (Spur kuerzer als 2× Radius → nur Kennzahlen, keine Spur) und Touren ohne Spur abdecken. Die Start-/Zielpunkte im Bild entfallen dann bzw. sitzen an den gekuerzten Enden.
docs/TODO.md: beide Punkte austragen. PRIVACY.md-Zeile zu geteilten Bildern um den Hinweis ergaenzen.`,
  },
  {
    key: 'play-update-check',
    title: 'Play-Installation: GitHub-Update-Pruefung abschalten',
    brief: `Aus docs/TODO.md (Release-Blocker): Wurde die App ueber Google Play installiert, darf sie nicht auf eine APK ausserhalb von Play verweisen. Erkennen ueber den Installer: API 30+ PackageManager.getInstallSourceInfo(packageName).installingPackageName == "com.android.vending", darunter getInstallerPackageName. Die Entscheidung („Update-Pruefung erlaubt?“) als reine, getestete Funktion in update/UpdateLogic.kt (oder :core), die Android-Abfrage duenn daneben. Bei Play-Installation: keine stille Pruefung, kein Snackbar-Hinweis, keine Update-Karte, und in Mehr → Über statt „Nach Updates suchen“ der Hinweis „Updates kommen über Google Play“. Sideload/GitHub-APK bleibt unveraendert. PRIVACY.md (Absatz Update-Pruefung) und docs/TODO.md anpassen.`,
  },
  {
    key: 'ble-sensors',
    title: 'Bluetooth-Sensoren: Pulsgurt, Leistungsmesser, Trittfrequenz',
    brief: `Direkte BLE-Anbindung ohne Uhr: Herzfrequenz (GATT 0x180D, Characteristic 0x2A37), Cycling Power (0x1818, 0x2A63) und Cycling Speed and Cadence (0x1816, 0x2A5B).
- :core: reine, gruendlich getestete Parser fuer die drei Messformate (Flags, 8/16-Bit-Puls, RR-Intervalle, Leistung, kumulative Kurbel-/Radumdrehungen mit Ueberlauf der 16-Bit-Ereigniszeit → Trittfrequenz/Tempo), plus Glaettung/Timeout („Sensor liefert seit X s nichts“).
- :app: Scannen und Koppeln in Mehr → „Sensoren“ (Liste gefundener Geraete je Typ, Koppeln, Vergessen, gemerkte Geraete in den Prefs), Verbindung waehrend der Aufzeichnung im RecordingService (automatisch zu gemerkten Sensoren verbinden, bei Abbruch neu versuchen, sauber trennen). Berechtigungen: BLUETOOTH_SCAN (neverForLocation) + BLUETOOTH_CONNECT ab API 31, darunter ACCESS_FINE_LOCATION (ist ohnehin fuer GPS da). Keine neue Bibliothek — Android-BLE-API direkt.
- Aufzeichnung: Puls/Leistung/Trittfrequenz je Punkt speichern, wo das Modell es erlaubt (Models.kt pruefen; Dateiformat muss abwaertskompatibel bleiben — fehlender Schluessel ≠ null, siehe JsonSupport.kt), Anzeige im Fahrmodus (feste Kachelpositionen wie beim Uhr-Puls, siehe RideModeScreen-KDoc) und in der Kompaktleiste; der Uhr-Puls hat Nachrang, wenn ein Gurt verbunden ist.
- Auswertung: vorhandene Leistungs-/Pulslogik (TrainingLoad, RideAnalysis) nutzt gemessene Leistung, wenn vorhanden, statt Schaetzung.
- README und PRIVACY.md (Bluetooth: nur lokal, keine Daten nach aussen) ergaenzen.
Nicht auf echter Hardware testbar — deshalb ist Testabdeckung der Parser und der Zustandslogik Pflicht, und die Grenzen gehoeren in den Bericht.`,
  },
  {
    key: 'strava',
    title: 'Strava-Upload nach der Fahrt (optional, eigenes Konto)',
    brief: `Optionaler Upload fertiger Touren zu Strava. Local-first bleibt: Verbinden ist freiwillig, ohne Verbindung aendert sich nichts.
- OAuth 2.0 mit Strava (Authorization Code, Scope activity:write), Redirect ueber einen App-Link/Custom-Scheme (z. B. trailscape://strava-callback), Browser per Custom Tab. Tokens (access/refresh, Ablauf) verschluesselt ablegen (EncryptedSharedPreferences o. ae. — nur wenn die Bibliothek schon im Projekt ist, sonst Android Keystore direkt), Refresh automatisch.
- CLIENT-SECRET-PROBLEM: Strava verlangt client_secret beim Token-Tausch. Es darf NICHT ins Repo. Loesung im Build: client_id/client_secret aus Gradle-Properties bzw. Umgebungsvariablen (STRAVA_CLIENT_ID/STRAVA_CLIENT_SECRET) in BuildConfig; fehlen sie, ist die Funktion unsichtbar (Build und CI bleiben gruen). Im Bericht klar festhalten, dass der Gruender eine Strava-API-App anlegen und die Werte als CI-Secret hinterlegen muss, und dass ein eingebettetes Secret in der APK auslesbar ist (Alternative: Token-Tausch ueber den eigenen Sync-Server — nur als Vorschlag beschreiben, nicht bauen).
- Upload: FIT oder GPX der Tour (vorhandener Export in :core wiederverwenden) per POST /api/v3/uploads (multipart), danach Status per GET /uploads/{id} abfragen bis activity_id oder Fehler; Duplikate (Strava meldet „duplicate of“) freundlich behandeln. HTTP ueber das vorhandene :core-HttpClient-Interface (Multipart dort ergaenzen, falls noetig) — Upload-/Statuslogik in :core mit Fake-HttpClient getestet.
- UI: Mehr → „Strava“ (Verbinden/Trennen, Schalter „Neue Touren automatisch hochladen“ ab Werk aus), in der Tour-Detailansicht Aktion „Zu Strava hochladen“ mit Status (hochgeladen → „Auf Strava ansehen“), nach Ende einer Aufzeichnung bei aktivem Auto-Upload im Hintergrund (WorkManager, bei Netz). Pro Tour merken, ob/wohin hochgeladen.
- PRIVACY.md (neuer Empfaenger Strava, nur nach Verbinden und Aktion bzw. Auto-Upload), README ergaenzen.`,
  },
]

// Die englische Uebersetzung ist zu gross fuer einen Zweig: erst ein Fundament
// (Sprachumschaltung, Ressourcen-Aufteilung, Texte aus :core), dann je Bereich
// ein eigener Zweig auf diesem Fundament — mit EIGENER strings_<bereich>.xml je
// Bereich, damit sich die Zweige beim Mergen nicht in einer Datei treffen.
const I18N_AREAS = [
  { key: 'i18n-today', paths: 'app/src/main/kotlin/de/trailscape/app/ui/today/**, ui/TodayRouteTarget.kt, ui/ReadyToRideDialog.kt', res: 'strings_today.xml' },
  { key: 'i18n-map', paths: 'app/src/main/kotlin/de/trailscape/app/ui/map/** (ausser Dateien, die zu rides gehoeren)', res: 'strings_map.xml' },
  { key: 'i18n-rides', paths: 'app/src/main/kotlin/de/trailscape/app/ui/rides/**, ui/ShareFiles.kt, ui/RideImport.kt, ui/ActivityFileImport.kt, ui/ActivityImportAction.kt', res: 'strings_rides.xml' },
  { key: 'i18n-training', paths: 'app/src/main/kotlin/de/trailscape/app/ui/training/**, ui/TrainingInsights.kt', res: 'strings_training.xml' },
  { key: 'i18n-more', paths: 'app/src/main/kotlin/de/trailscape/app/ui/more/**, ui/health/**, update/**, feedback/**', res: 'strings_more.xml' },
  { key: 'i18n-onboarding-shell', paths: 'ui/onboarding/**, ui/TrailscapeApp.kt, ui/components/**, ui/ErrorText.kt, ui/UiFormat.kt, record/** (Benachrichtigungen, Ansagen), reminder/**, voice/**, wear/** (Uhr-App-Texte im :wear-Modul inklusive)', res: 'strings_shell.xml' },
]

const only = Array.isArray(A.only) ? A.only : null
const skip = Array.isArray(A.skip) ? A.skip : []
const wanted = (key) => (!only || only.includes(key) || (key.startsWith('i18n') && only.includes('i18n'))) && !skip.includes(key) && !(key.startsWith('i18n') && skip.includes('i18n'))

const features = FEATURES.filter((f) => wanted(f.key))
const doI18n = wanted('i18n-foundation') || (only && only.includes('i18n'))
const i18nAreas = doI18n ? I18N_AREAS.filter((a) => wanted(a.key)) : []
if (only || skip.length) log(`Auswahl: Features ${features.map((f) => f.key).join(', ') || '–'}; i18n ${doI18n ? i18nAreas.map((a) => a.key).join(', ') : 'aus'}`)

// ---------------------------------------------------------------- Schemas

const SETUP_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string', description: 'aktueller Branch in REPO (git branch --show-current)' },
    base: { type: 'string', description: 'HEAD-Commit-SHA in REPO' },
    green: { type: 'boolean' },
    notes: { type: 'string' },
  },
  required: ['branch', 'base', 'green'],
}

const SPEC_SCHEMA = {
  type: 'object',
  properties: {
    summary: { type: 'string' },
    files_to_change: { type: 'array', items: { type: 'object', properties: { path: { type: 'string' }, change: { type: 'string' } }, required: ['path', 'change'] } },
    new_files: { type: 'array', items: { type: 'object', properties: { path: { type: 'string' }, purpose: { type: 'string' } }, required: ['path', 'purpose'] } },
    ui_texts: { type: 'array', items: { type: 'string' } },
    tests: { type: 'array', items: { type: 'string' } },
    risks: { type: 'array', items: { type: 'string' } },
    needs_owner_decision: { type: 'array', items: { type: 'string' }, description: 'Punkte, die der Gruender entscheiden oder liefern muss (Secrets, Konten, Rechtliches)' },
  },
  required: ['summary', 'files_to_change', 'new_files', 'tests', 'risks'],
}

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    commit: { type: 'string' },
    summary: { type: 'string' },
    files_changed: { type: 'array', items: { type: 'string' } },
    tests: { type: 'string', description: 'Ergebnis von :core:test und :app:testDebugUnitTest' },
    build: { type: 'string', description: 'Ergebnis von :app:assembleDebug' },
    screenshots_checked: { type: 'string' },
    deviations_from_spec: { type: 'string' },
    open_issues: { type: 'array', items: { type: 'string' } },
  },
  required: ['branch', 'commit', 'summary', 'tests', 'build'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          line: { type: 'integer' },
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          problem: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['file', 'severity', 'problem', 'fix'],
      },
    },
  },
  required: ['findings'],
}

const FIX_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    commit: { type: 'string' },
    applied: { type: 'array', items: { type: 'string' } },
    rejected: { type: 'array', items: { type: 'string' } },
    tests: { type: 'string' },
    build: { type: 'string' },
  },
  required: ['branch', 'commit', 'applied', 'rejected', 'tests', 'build'],
}

const LENSES = [
  { key: 'korrektheit', text: 'KORREKTHEIT & ROBUSTHEIT: Bugs, Abstuerze (leere/fehlende Daten, keine Berechtigung, offline, Prozesstod, rememberSaveable), Threading (Netz/IO/BLE-Callbacks auf dem Main-Thread), Lecks (BLE-GATT nicht geschlossen, Bitmaps), falsche Rechnungen, fehlende oder schwache Tests, Regressionen, Abwaertskompatibilitaet des Dateiformats, Datenschutz (unangekuendigte Netzanfragen, Secrets im Repo), R8/Proguard fuer neue Klassen. Behauptungen am Code pruefen, wo guenstig Tests laufen lassen.' },
  { key: 'ux-texte', text: 'UX, TEXTE & KONSISTENZ: deutsche UI-Texte (du-Form, Ton der App, echte Umlaute, „…“, nichts Uebertriebenes), bei i18n auch die englischen Texte (natuerliches Englisch, gleiche Bedeutung, keine abgeschnittenen Beschriftungen, Pluralformen per plurals), Konsistenz mit vorhandenen Komponenten/Theme-Tokens, Barrierefreiheit (contentDescription, Touch-Flaechen), Auffindbarkeit der Einstiege, README/PRIVACY/TODO korrekt, KDoc erklaert das Warum, toter Code und doppelte Helfer.' },
]

// ---------------------------------------------------------------- Bausteine

const worktreeSetup = (branchCmd) => `Du arbeitest in deinem eigenen git-Worktree. Zuerst:
1. cp ${REPO}/local.properties ./local.properties
2. git submodule update --init third_party/brouter
3. ${branchCmd}`

const implement = (item, spec, baseRef, phase) => agent(
  `${CONTEXT}\n\nDu bist der IMPLEMENTIERER fuer „${item.key}“: ${item.title}.\n\nAuftrag:\n${item.brief}\n\nSpezifikation des Architekten (JSON):\n${JSON.stringify(spec, null, 2)}\n\n${worktreeSetup(`git checkout -b wf/${item.key} ${baseRef}`)}\n4. Vollstaendig und sorgfaeltig umsetzen (Abweichungen nur mit gutem Grund, im Bericht nennen), Tests schreiben.\n5. ./gradlew :core:test, ./gradlew :app:testDebugUnitTest -Pscreenshots, ./gradlew :app:assembleDebug (bei 429 wiederholen). Alles gruen bekommen. Keine Tests ueberspringen.\n6. Betroffene Oberflaechen per Screenshot-Test rendern (vorhandene erweitern oder einen neuen *ScreenshotTest anlegen) und die PNGs mit dem Read-Werkzeug ansehen; Abgeschnittenes/Unpassendes beheben.\n7. Den eigenen Diff (git diff ${baseRef}) gegnerisch lesen und Probleme beheben.\n8. Auf wf/${item.key} committen, deutsche Betreffzeile im Stil der Repo-Historie, Nachricht endet mit:\n${TRAILERS}\nNICHT pushen. Branch, Commit-SHA und Test-/Build-Ergebnisse berichten.`,
  { label: `impl:${item.key}`, phase, schema: IMPL_SCHEMA, isolation: 'worktree' },
)

const reviewAndFix = async (item, impl, baseRef) => {
  if (!impl) return null
  const reviews = await parallel(LENSES.map((l) => () => agent(
    `${CONTEXT}\n\nDu bist GEGNERISCHER REVIEWER fuer „${item.key}“: ${item.title}.\nAuftrag:\n${item.brief}\n\nDie Umsetzung liegt auf Branch wf/${item.key} (Commit ${impl.commit}) in ${REPO}. Diff: git -C ${REPO} diff ${baseRef}..${impl.commit}; ganze Dateien: git -C ${REPO} show ${impl.commit}:<pfad>. Nur lesen — das Repo nicht veraendern, keine Branches auschecken.\nBericht des Implementierers: ${JSON.stringify(impl)}\n\nDeine Linse: ${l.text}\n\nNur echte, konkrete Probleme mit konkretem Fix. Leere Liste, wenn sauber.`,
    { label: `review:${item.key}:${l.key}`, phase: 'Review', schema: REVIEW_SCHEMA },
  )))
  const findings = reviews.filter(Boolean).flatMap((r) => r.findings)
  if (!findings.length) return { key: item.key, commit: impl.commit, impl, findings, fix: null }
  const fix = await agent(
    `${CONTEXT}\n\nDu bist der FIXER fuer „${item.key}“: ${item.title}.\nAuftrag:\n${item.brief}\n\n${worktreeSetup(`git checkout -B wf/${item.key}-final ${impl.commit}`)}\n4. Jeden Befund unten am Code pruefen. Echte beheben (blocker und major immer, minor wenn guenstig und richtig), falsche mit Begruendung ablehnen.\n5. ./gradlew :core:test :app:testDebugUnitTest -Pscreenshots und ./gradlew :app:assembleDebug bis gruen (bei 429 wiederholen).\n6. Auf wf/${item.key}-final committen (deutsche Betreffzeile, z. B. „${item.key}: Review-Befunde behoben“), Nachricht endet mit:\n${TRAILERS}\nWar nichts zu aendern: Branch trotzdem auf ${impl.commit} anlegen und diesen Commit melden. NICHT pushen.\n\nBefunde:\n${JSON.stringify(findings, null, 2)}`,
    { label: `fix:${item.key}`, phase: 'Fix', schema: FIX_SCHEMA, isolation: 'worktree' },
  )
  return { key: item.key, commit: fix ? fix.commit : impl.commit, impl, findings, fix }
}

// ---------------------------------------------------------------- Setup

phase('Setup')
const setup = await agent(
  `${CONTEXT}\n\nDu bereitest die Build-Umgebung in ${REPO} vor (direkt dort arbeiten, NICHT in einem Worktree). Schritte:
1. Falls /root/android-sdk/platforms/android-36 fehlt: Android-Commandline-Tools von https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip nach /root/android-sdk laden, nach cmdline-tools/latest entpacken und mit \`yes | cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/android-sdk "platforms;android-36" "build-tools;36.0.0" "platform-tools"\` installieren.
2. ${REPO}/local.properties mit sdk.dir=/root/android-sdk anlegen (git-ignoriert).
3. git submodule update --init third_party/brouter
4. \`.claude/worktrees/\` in .git/info/exclude eintragen, falls nicht vorhanden (Arbeitsverzeichnisse der Worktree-Agenten, gehoeren nicht ins Repo).
5. Den kompletten CI-Befehl ausfuehren, um den Abhaengigkeits-Cache zu fuellen und den Ausgangsstand zu pruefen: ./gradlew :core:test :app:testDebugUnitTest -Pscreenshots :app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease — bei HTTP 429 von Maven Central 45 s warten und wiederholen (bis zu 8-mal).
6. Ist der Ausgangsstand ROT, die Ursache ermitteln und berichten (nichts committen). Nicht reparieren, ausser es ist ein offensichtlicher, datums-/umgebungsabhaengiger Testfehler — dann minimal fixen und committen (deutsche Nachricht, endet mit:\n${TRAILERS}).
Melde Branch-Namen (git branch --show-current), HEAD-SHA und ob gruen.`,
  { label: 'setup', phase: 'Setup', schema: SETUP_SCHEMA },
)
if (!setup || !setup.green) {
  return { abgebrochen: true, grund: 'Ausgangsstand nicht gruen oder Setup fehlgeschlagen', setup }
}
const BASE = setup.base
const BRANCH = setup.branch
log(`Ausgangsstand gruen auf ${BRANCH} @ ${BASE}`)

// ---------------------------------------------------------------- Features-Kette

const featureChain = pipeline(
  features,
  (f) => agent(
    `${CONTEXT}\n\nDu bist der ARCHITEKT fuer „${f.key}“: ${f.title}.\n\nAuftrag:\n${f.brief}\n\nParallel entstehen auf eigenen Zweigen: ${FEATURES.filter((o) => o.key !== f.key).map((o) => o.key).join(', ')}${doI18n ? ' und die englische Uebersetzung (alle UI-Texte wandern in values/strings_<bereich>.xml — neue Texte deines Features bitte gleich als Ressource in einer eigenen Datei values/strings_' + f.key.replace(/-/g, '_') + '.xml anlegen, mit Uebersetzung in values-en/)' : ''}. Vermeide unnoetige Ueberschneidung in denselben Dateien; unvermeidliche nennen.\n\nLies den relevanten Code gruendlich (nur lesen, nichts aendern). Liefere eine konkrete Spezifikation: Dateien und Funktionen, neue Dateien, Datenfluss, exakte deutsche UI-Texte, Tests, Risiken, und was der Gruender entscheiden oder liefern muss. Vorhandene Mechanismen wiederverwenden, so klein wie moeglich.`,
    { label: `design:${f.key}`, phase: 'Design', schema: SPEC_SCHEMA },
  ),
  (spec, f) => implement(f, spec, BASE, 'Implement').then((impl) => ({ spec, impl })),
  (r, f) => reviewAndFix(f, r && r.impl, BASE).then((res) => res && { ...res, owner: (r.spec && r.spec.needs_owner_decision) || [] }),
)

// ---------------------------------------------------------------- i18n-Kette

const i18nChain = (async () => {
  if (!doI18n) return []
  const foundationItem = {
    key: 'i18n-foundation',
    title: 'Englische Uebersetzung: Fundament',
    brief: `Die App ist heute rein deutsch; UI-Texte stehen fest im Kotlin-Code (kein stringResource). Lege das Fundament fuer Englisch, OHNE schon alle Texte umzuziehen:
- Sprachwahl: Systemsprache folgen (Deutsch fuer de-*, sonst Englisch) plus Auswahl in Mehr → „Sprache“ (System/Deutsch/English) ueber AppCompatDelegate.setApplicationLocales bzw. LocaleManager (API 33+) — per-App-Sprache inklusive android:localeConfig (res/xml/locales_config.xml).
- Ressourcen-Regeln festschreiben (in einer kurzen docs/i18n.md): je Bereich eine Datei values/strings_<bereich>.xml mit Gegenstueck values-en/strings_<bereich>.xml, Schluessel-Praefix je Bereich, plurals fuer Zahlen, Formatargumente statt Verkettung, Zahlen/Datum ueber Locale (Dezimalkomma DE, Punkt EN).
- Texte aus :core (z. B. Ansage-/Wortbausteine, formatDuration, turnAnsageText, Readiness-/Trainingstexte): Strategie festlegen und umsetzen — :core bleibt android-frei, liefert also Typen/Enums/Zahlen statt fertiger Saetze, oder bekommt eine kleine, getestete Uebersetzungs-Schnittstelle (TextProvider) mit DE/EN-Implementierung. Diese Umstellung fuer :core vollstaendig machen (inkl. Tests fuer beide Sprachen).
- Sprachansagen (TTS) in der gewaehlten Sprache.
- Eine Test-Hilfe fuer Screenshot-Tests in Englisch (Robolectric-qualifiers „en“) und einen Beispiel-Screenshot des Heute-Tabs auf Englisch.
- Vorhandene Screenshot-Tests, die auf deutsche Texte klicken, muessen weiter in Deutsch laufen (Locale im Test fest auf de setzen).`,
  }
  const spec = await agent(
    `${CONTEXT}\n\nDu bist der ARCHITEKT fuer „${foundationItem.key}“: ${foundationItem.title}.\n\nAuftrag:\n${foundationItem.brief}\n\nDanach ziehen ${I18N_AREAS.length} Folge-Zweige die Texte je Bereich um: ${I18N_AREAS.map((a) => `${a.key} (${a.paths} → ${a.res})`).join('; ')}. Deine Spezifikation muss diesen Zweigen eindeutige Regeln geben (Schluesselnamen, Dateien, Formatierung, Pluralformen, wie :core-Texte angebunden werden), damit sie ohne Absprache parallel arbeiten koennen. Nur lesen, nichts aendern.`,
    { label: 'design:i18n-foundation', phase: 'i18n-Fundament', schema: SPEC_SCHEMA },
  )
  const fImpl = await implement(foundationItem, spec, BASE, 'i18n-Fundament')
  const foundation = await reviewAndFix(foundationItem, fImpl, BASE)
  if (!foundation) {
    log('i18n-Fundament fehlgeschlagen — Bereichs-Zweige entfallen')
    return []
  }
  const areaResults = await pipeline(
    i18nAreas,
    (a) => {
      const item = {
        key: a.key,
        title: `Englische Uebersetzung: ${a.key.replace('i18n-', '')}`,
        brief: `Alle nutzersichtbaren Texte in diesem Bereich in Ressourcen ueberfuehren und ins Englische uebersetzen — nach den Regeln aus docs/i18n.md und dem Fundament-Commit ${foundation.commit} (Branch wf/i18n-foundation-final bzw. wf/i18n-foundation).
Bereich (Pfade): ${a.paths}
Ressourcen-Datei: values/${a.res} und values-en/${a.res} — NUR diese Dateien anlegen/aendern, keine anderen strings-Dateien (sonst Merge-Konflikte mit den Parallel-Zweigen).
Deutsch bleibt Wort fuer Wort wie heute (keine Umformulierungen). Englisch: natuerlich, knapp, gleiche Bedeutung; Fachbegriffe wie im englischen Radsport ueblich (Base/Endurance, Threshold, Training load, Readiness …). Keine abgeschnittenen Beschriftungen — die betroffenen Screens auf Englisch per Screenshot pruefen. Vorhandene Unit-Tests, die deutsche Texte pruefen, bleiben gueltig (deutscher Kontext) — neue Tests fuer englische Varianten, wo Logik Texte zusammensetzt.`,
      }
      return implement(item, { summary: 'Regeln siehe docs/i18n.md auf dem Fundament-Zweig', files_to_change: [{ path: a.paths, change: 'Texte → Ressourcen, EN-Uebersetzung' }], new_files: [{ path: `app/src/main/res/values/${a.res}`, purpose: 'DE' }, { path: `app/src/main/res/values-en/${a.res}`, purpose: 'EN' }], tests: [], risks: [] }, foundation.commit, 'Implement')
        .then((impl) => ({ item, impl }))
    },
    (r) => reviewAndFix(r.item, r.impl, foundation.commit),
  )
  return [foundation, ...areaResults.filter(Boolean)]
})()

const [featureResults, i18nResults] = await Promise.all([featureChain, i18nChain])
const done = [...featureResults.filter(Boolean), ...i18nResults]
const expected = [...features.map((f) => f.key), ...(doI18n ? ['i18n-foundation', ...i18nAreas.map((a) => a.key)] : [])]
const missing = expected.filter((k) => !done.find((d) => d.key === k))
if (missing.length) log(`Ohne Ergebnis (fehlen im Merge): ${missing.join(', ')}`)

// ---------------------------------------------------------------- Integrate

phase('Integrate')
// i18n-Bereiche bauen auf dem Fundament auf; sie kommen nach dem Fundament.
const order = [
  ...done.filter((d) => !d.key.startsWith('i18n')),
  ...done.filter((d) => d.key === 'i18n-foundation'),
  ...done.filter((d) => d.key.startsWith('i18n-') && d.key !== 'i18n-foundation'),
]
const integration = await agent(
  `${CONTEXT}\n\nDu bist der INTEGRATOR. Arbeite direkt in ${REPO} auf Branch ${BRANCH} (steht auf ${BASE}; local.properties ist git-ignoriert — liegen lassen).
Merge diese Commits nacheinander in dieser Reihenfolge per git merge --no-ff <commit> -m "<deutsche Nachricht>":
${order.map((d) => `- ${d.key}: ${d.commit}`).join('\n')}
Konflikte sorgfaeltig loesen, sodass das Verhalten BEIDER Seiten erhalten bleibt (beide Seiten lesen). Bei den i18n-Zweigen: Texte, die ein Feature-Zweig neu eingefuehrt hat, muessen am Ende ebenfalls als Ressource mit EN-Uebersetzung vorliegen — fehlende nachziehen.
Nach jedem Merge ./gradlew :app:compileDebugKotlin (bei 429 wiederholen). Am Ende den kompletten CI-Befehl: ./gradlew :core:test :app:testDebugUnitTest -Pscreenshots :app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease — alles gruen bekommen (Fixes als eigene Commits).
README.md (Features, Testzahlen), PRIVACY.md und docs/TODO.md auf den Gesamtstand bringen (erledigte Punkte austragen).
Alle Merge-/Fix-Commit-Nachrichten enden mit:\n${TRAILERS}\nNICHT pushen. Berichte HEAD-SHA, geloeste Konflikte und die Test-/Build-Ergebnisse.`,
  {
    label: 'integrate',
    phase: 'Integrate',
    schema: {
      type: 'object',
      properties: { head: { type: 'string' }, conflicts: { type: 'array', items: { type: 'string' } }, tests: { type: 'string' }, build: { type: 'string' }, notes: { type: 'string' } },
      required: ['head', 'tests', 'build'],
    },
  },
)

// ---------------------------------------------------------------- Final review

phase('Final review')
const FINAL_LENSES = [
  { key: 'zusammenspiel', text: 'ZUSAMMENSPIEL: Passen alle Zweige zusammen (Sensor-Puls vs. Uhr-Puls in Fahrmodus und Aufzeichnung, Strava-Upload nach Aufzeichnung, Tourbild vom Tourblatt, Update-Pruefung bei Play, Sprachwahl ueberall wirksam)? Doppelte Helfer, kollidierende Prefs-Schluessel, Sackgassen in der Navigation, Merge-Fehler, verlorenes Verhalten.' },
  { key: 'release-risiko', text: 'RELEASE-RISIKO: Absturz beim ersten Start ohne Daten, Prozesstod, verweigerte Berechtigungen (Bluetooth, Standort, Benachrichtigungen), offline, R8/Proguard fuer neue Klassen (Release-Build!), Main-Thread-IO, BLE-Ressourcen, fehlende BuildConfig-Werte (Strava ohne Secret → Funktion unsichtbar, Build gruen), PRIVACY.md vollstaendig und wahr fuer JEDE neue Anfrage, keine Secrets im Repo (git log -p pruefen).' },
  { key: 'englisch-sichtpruefung', text: 'SICHTPRUEFUNG ENGLISCH: Alle Screenshot-Tests einmal auf Deutsch und einmal auf Englisch rendern (./gradlew :app:testDebugUnitTest -Pscreenshots --tests \'*Screenshot*\', ggf. mit der Englisch-Test-Hilfe aus docs/i18n.md) und die PNGs mit dem Read-Werkzeug ansehen: uebrig gebliebene deutsche Texte in der englischen Ansicht, abgeschnittene Beschriftungen, falsche Zahlen-/Datumsformate. Diese Linse darf Gradle ausfuehren, aber nichts committen.' },
]
const finalReviews = await parallel(FINAL_LENSES.map((l) => () => agent(
  `${CONTEXT}\n\nGEGNERISCHES GESAMT-REVIEW des kombinierten Stands auf ${BRANCH} in ${REPO}: git -C ${REPO} diff ${BASE}..HEAD. Nichts committen, keine Branches wechseln.\nLinse: ${l.text}\nNur echte, konkrete Probleme mit konkretem Fix.`,
  { label: `final:${l.key}`, phase: 'Final review', schema: REVIEW_SCHEMA },
)))
const finalFindings = finalReviews.filter(Boolean).flatMap((r) => r.findings)
let finalFix = null
if (finalFindings.length) {
  finalFix = await agent(
    `${CONTEXT}\n\nDu bist der LETZTE FIXER, direkt in ${REPO} auf Branch ${BRANCH}. Jeden Befund am Code pruefen, echte beheben, falsche mit Begruendung ablehnen. Danach den kompletten CI-Befehl bis gruen: ./gradlew :core:test :app:testDebugUnitTest -Pscreenshots :app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease (bei 429 wiederholen). Committen (deutsche Nachricht, endet mit:\n${TRAILERS}). NICHT pushen.\n\nBefunde:\n${JSON.stringify(finalFindings, null, 2)}`,
    { label: 'final-fix', phase: 'Final review', schema: FIX_SCHEMA },
  )
}

return {
  branch: BRANCH,
  base: BASE,
  zweige: done.map((d) => ({
    key: d.key,
    commit: d.commit,
    zusammenfassung: d.impl && d.impl.summary,
    abweichungen: d.impl && d.impl.deviations_from_spec,
    offen: d.impl && d.impl.open_issues,
    gruender_entscheidet: d.owner || [],
    befunde: (d.findings || []).length,
    abgelehnt: d.fix ? d.fix.rejected : [],
  })),
  fehlend: missing,
  integration,
  gesamtBefunde: finalFindings.length,
  finalFix,
  naechsteSchritte: [
    'Ergebnis lesen, git log pruefen, dann pushen und Draft-PR anlegen',
    'Strava: API-App unter strava.com/settings/api anlegen, STRAVA_CLIENT_ID/STRAVA_CLIENT_SECRET als CI-Secret und lokal als Gradle-Property hinterlegen',
    'Auf dem Geraet testen: Bluetooth-Sensoren (Pulsgurt/Leistungsmesser), Strava-Verbindung, Sprache Englisch, Tourbild vom Tourblatt',
  ],
}
