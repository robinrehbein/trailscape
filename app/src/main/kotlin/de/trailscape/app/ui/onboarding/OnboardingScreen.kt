package de.trailscape.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.components.OneUiDropdownField
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.OneUiMotion
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.FirstRoundDuration
import de.trailscape.core.HealthSyncException
import de.trailscape.core.Sex
import de.trailscape.core.TrainingProfile
import de.trailscape.core.defaultFirstRoundDuration
import de.trailscape.core.firstRoundTarget
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * # Erststart-Einfuehrung
 *
 * Fuenf Seiten, die genau einmal laufen — beim allerersten Start, bevor die
 * Navigationsleiste ueberhaupt sichtbar wird. Danach merkt sich
 * [AppViewModel.completeOnboarding] das dauerhaft; erneut aufrufbar ist die
 * Einfuehrung ueber „Mehr → Über → Einführung erneut ansehen".
 *
 * ## Warum so kurz
 * Eine Erklaerseite und vier Schritte mit *je einer* Aufgabe. Kein
 * Assistent, der Einstellungen abfragt, die es auch spaeter noch gibt: Alles
 * hier ist ueberspringbar, und die App laeuft auch ohne jede Eingabe
 * vollstaendig. Die Texte sind knapp gehalten — wenige Saetze je Seite, jeder
 * davon wahr; was Details braucht, steht in README und PRIVACY.
 *
 *  1. **Was Trailscape ist** — Empfehlung plus passende Runde, und dass alles
 *     lokal bleibt (kein Konto, keine Telemetrie).
 *  2. **Daten mitbringen** — der wichtigste Handgriff fuer die Auswertung,
 *     weil sie sonst wochenlang leer bleibt.
 *  3. **Trainingsprofil** — Alter und Gewicht sind die einzigen zwei Werte,
 *     ohne die `:core` gar nicht rechnen kann (siehe [TrainingProfile]); alles
 *     Uebrige schaetzt es selbst. Genau diese beiden stehen hier, mehr nicht.
 *  4. **Health Connect** — optional, mit prominentem „Später".
 *  5. **Deine erste Runde** — wie viel Zeit heute ist (1 h / 1½ h / 2 h oder
 *     „Später").
 *
 * ## Warum die letzte Seite die Runde ist
 * Der Kernnutzen der App ist der Weg von „was fahre ich heute" zur passenden
 * Runde. Frueher endete die Einfuehrung bei Health Connect, und wer noch keine
 * Tour hatte, sah danach eine leere Auswertung. Jetzt fuehrt „Runde bauen"
 * direkt in die Karte, und die Suche startet sofort — in der ersten Minute,
 * ganz ohne Historie. Das Ziel kommt aus [firstRoundTarget]; gebaut wird es
 * vom bestehenden Rundkurs-Generator ([AppViewModel.requestRouteGeneration]
 * mit `autoStart`). Die Suche rechnet nur ab echtem Standort; ohne Freigabe
 * bleibt das Panel offen und erklaert den Weg von Hand. Die gewaehlte Dauer
 * wird nicht gespeichert — sie gilt fuer heute, nicht als Einstellung.
 *
 * ## Bedienung
 * Wischen oder „Weiter"; „Überspringen" oben rechts beendet die Einfuehrung
 * sofort (ohne Runde). Die Systemzurueckgeste blaettert eine Seite zurueck
 * (siehe `BackHandler` im Rumpf). Auf der Profilseite speichert „Weiter" die
 * Eingabe mit — leere Felder sind erlaubt und werden stillschweigend
 * uebergangen, fehlerhafte Eingaben melden sich unter dem Feld und halten die
 * Seite fest.
 *
 * ## One-UI-Anmutung
 * Jede Seite eroeffnet eine grosse, fette Headline (headlineLarge, fett direkt
 * aus dem Theme-Slot); die Absaetze darunter bleiben ruhig in bodyMedium auf
 * onSurfaceVariant. Die Fortschrittspunkte unten faerben sich aktiv in der
 * Primaerfarbe, inaktiv in outlineVariant — Knopfformen liefert das Theme als
 * Pillen mit.
 */
@Composable
fun OnboardingScreen(appViewModel: AppViewModel) {
    val pagerState = rememberPagerState(pageCount = { OnboardingPage.entries.size })
    val scope = rememberCoroutineScope()

    // Die Profileingabe lebt hier oben, nicht in der Seite: Der Pager haelt
    // Nachbarseiten nicht dauerhaft in der Komposition, ein `remember` in der
    // Seite selbst waere nach zwei Wischern weg.
    val profile by appViewModel.profile.collectAsStateWithLifecycle()
    val profileConfirmed by appViewModel.profileConfirmed.collectAsStateWithLifecycle()
    var ageText by rememberSaveable { mutableStateOf("") }
    var weightText by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf(Sex.UNBEKANNT) }
    var profileError by rememberSaveable { mutableStateOf<String?>(null) }

    // Die Dauer der ersten Runde; `null` steht fuer „Später". Vorauswahl aus
    // dem Zeitbudget beim ersten Komponieren — spaeter nicht nachgezogen, das
    // Profil aendert sich in der Einfuehrung nur ueber Alter und Gewicht.
    var firstRound by rememberSaveable {
        mutableStateOf<FirstRoundDuration?>(defaultFirstRoundDuration(profile))
    }
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    // „Mehr → Über → Einführung erneut ansehen" zeigt dieselben Seiten noch
    // einmal — bisher mit leeren Profilfeldern, als haette der Nutzer nie etwas
    // eingetragen. Wer bereits gespeichert hat, sieht jetzt seine Werte und
    // kann sie bestaetigen oder aendern. Beim allerersten Start bleiben die
    // Felder leer, denn dort steht nur `defaultTrainingProfile` dahinter —
    // fremde Zahlen, die nicht wie eine eigene Eingabe aussehen duerfen (siehe
    // AppViewModel.profileConfirmed).
    LaunchedEffect(profileConfirmed) {
        if (!profileConfirmed) return@LaunchedEffect
        if (ageText.isEmpty()) ageText = profile.ageYears.toString()
        if (weightText.isEmpty()) weightText = profile.weightKg.toInt().toString()
        if (sex == Sex.UNBEKANNT) sex = profile.sex
    }

    /**
     * Uebernimmt die Profileingabe. Liefert `false`, wenn ein *gefuellter*
     * Wert unbrauchbar ist — dann bleibt die Seite stehen. Leere Felder sind
     * kein Fehler; sie bedeuten schlicht „spaeter".
     */
    fun applyProfile(): Boolean {
        val ageRaw = ageText.trim()
        val weightRaw = weightText.trim().replace(',', '.')
        if (ageRaw.isEmpty() && weightRaw.isEmpty() && sex == Sex.UNBEKANNT) {
            profileError = null
            return true
        }
        val age = if (ageRaw.isEmpty()) profile.ageYears else ageRaw.toIntOrNull()
        if (age == null || age < 10 || age > 100) {
            profileError = "Bitte ein Alter zwischen 10 und 100 Jahren angeben."
            return false
        }
        val weight = if (weightRaw.isEmpty()) profile.weightKg else weightRaw.toDoubleOrNull()
        if (weight == null || weight < 30 || weight > 250) {
            profileError = "Bitte ein Gewicht zwischen 30 und 250 kg angeben."
            return false
        }
        profileError = null
        appViewModel.setProfile(profile.copy(ageYears = age, sex = sex, weightKg = weight))
        return true
    }

    /**
     * Beendet die Einfuehrung. Mit [buildRound] und gewaehlter Dauer liegt
     * danach das Ziel der ersten Runde samt Auto-Start bereit, und die
     * Navigationshuelle wechselt ueber die gehaltene Tab-Bitte in die Karte.
     * „Überspringen" und „Später" beenden ohne Routenanfrage.
     */
    fun finish(buildRound: Boolean) {
        // Auch beim Abschluss ueber die letzte Seite oder „Überspringen" soll
        // eine bereits getippte Profilangabe nicht verloren gehen.
        applyProfile()
        val duration = firstRound
        if (buildRound && duration != null) {
            appViewModel.requestRouteGeneration(
                firstRoundTarget(duration.hours, appViewModel.profile.value, appViewModel.rides.value),
                autoStart = true,
            )
        }
        appViewModel.completeOnboarding()
    }

    fun goBack() {
        if (pagerState.currentPage <= 0) return
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    // Die Systemzurueckgeste fuehrte bisher aus der Einfuehrung heraus in den
    // Hintergrund — die einzige Richtung war vorwaerts. Jetzt blaettert sie eine
    // Seite zurueck; auf der ersten Seite schluckt sie der Handler bewusst, denn
    // ein versehentliches Wischen soll nicht die App schliessen, bevor
    // irgendetwas eingerichtet ist. Beenden geht ueber „Überspringen".
    BackHandler { goBack() }

    fun goForward() {
        val page = OnboardingPage.entries[pagerState.currentPage]
        if (page == OnboardingPage.PROFILE && !applyProfile()) return
        if (pagerState.currentPage >= OnboardingPage.entries.lastIndex) {
            finish(buildRound = true)
            return
        }
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
    }

    // Ohne `contentWindowInsets`-Angabe: Die Einfuehrung laeuft ausserhalb der
    // Navigationshuelle, hier soll das Scaffold die System-Insets also mit
    // seiner Vorgabe selbst aufloesen (in den Tabs macht das die Huelle).
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = ContentMaxWidth),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { finish(buildRound = false) }) { Text("Überspringen") }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { index ->
                    val page = OnboardingPage.entries[index]
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ScreenPadding, vertical = 8.dp),
                    ) {
                        Text(
                            text = onboardingEyebrow(index, OnboardingPage.entries.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = page.title, style = MaterialTheme.typography.headlineLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        page.paragraphs.forEach { paragraph ->
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        when (page) {
                            OnboardingPage.WELCOME, OnboardingPage.DATA -> Unit

                            OnboardingPage.PROFILE -> ProfileFields(
                                ageText = ageText,
                                onAgeChange = {
                                    ageText = it
                                    profileError = null
                                },
                                weightText = weightText,
                                onWeightChange = {
                                    weightText = it
                                    profileError = null
                                },
                                sex = sex,
                                onSexChange = { sex = it },
                                error = profileError,
                            )

                            OnboardingPage.HEALTH -> HealthConnectStep(appViewModel)

                            OnboardingPage.FIRST_ROUND -> FirstRoundStep(
                                selected = firstRound,
                                onSelect = { firstRound = it },
                                previewKm = firstRound?.let {
                                    firstRoundTarget(it.hours, profile, rides).distanceKm.roundToInt()
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Vier Punkte fuer vier Schritte — nicht fuenf fuer fuenf
                    // Seiten. Die Willkommensseite ist kein Schritt (sie traegt
                    // auch keine Nummer), fuenf Punkte gegen „Schritt 1 von 4"
                    // waren aber genau der Widerspruch, den man beim ersten Blick
                    // sieht. Auf Seite 0 ist `current` damit -1: vier Punkte,
                    // keiner aktiv — es geht gleich los.
                    PageDots(
                        count = OnboardingPage.entries.size - 1,
                        current = pagerState.currentPage - 1,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (pagerState.currentPage > 0) {
                        TextButton(onClick = ::goBack) { Text("Zurück") }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(onClick = ::goForward) {
                        Text(
                            when {
                                pagerState.currentPage != OnboardingPage.entries.lastIndex -> "Weiter"
                                firstRound != null -> "Runde bauen"
                                else -> "Los geht's"
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Die Kopfzeile ueber dem Titel: „Willkommen" auf der ersten Seite, danach
 * „Schritt n von m". Die Willkommensseite ist kein Schritt, deshalb zaehlt
 * [pageCount] − 1. Abgeleitet statt je Seite fest geschrieben — eine neue
 * Seite hat die Zaehlung sonst an jeder Seite einzeln verstellt.
 */
internal fun onboardingEyebrow(index: Int, pageCount: Int): String =
    if (index <= 0) "Willkommen" else "Schritt $index von ${pageCount - 1}"

/**
 * Die fuenf Seiten samt Text. Als Aufzaehlung, damit Reihenfolge, Anzahl der
 * Punkte unten und die Fallunterscheidung im Rumpf nicht auseinanderlaufen
 * koennen.
 */
private enum class OnboardingPage(
    val title: String,
    val paragraphs: List<String>,
) {
    WELCOME(
        title = "Trailscape",
        paragraphs = listOf(
            // Bewusst die Schleife statt einer Merkmalsliste: Aufzeichnen,
            // Planen und Auswerten kann jede Konkurrenz einzeln auch. Was
            // sonst niemand verbindet, ist der Weg von der Tagesempfehlung
            // zur passenden Runde.
            "Trailscape sagt dir, was du heute fahren solltest – und baut dir die passende " +
                "Runde dazu, über Schotter und Nebenwege zurück nach Hause.",
            // Die Navigationsleiste ist waehrend der Einfuehrung ausgeblendet
            // — der Satz sagt deshalb, dass sie gleich kommt. Inhaltlich die
            // Fuehrung „Klartext" (siehe `ui/TrailscapeApp.kt`).
            "Gleich findest du unten Heute, Karte, Verlauf und Training, daneben den runden " +
                "Fahren-Knopf. Alles Weitere liegt hinter dem Zahnrad.",
            "Alles bleibt auf deinem Gerät. Kein Konto, keine Telemetrie.",
        ),
    ),
    DATA(
        title = "Bring deine Touren mit",
        paragraphs = listOf(
            "Mit deinen bisherigen Fahrten ist die Auswertung sofort aussagekräftig – ohne " +
                "dauert es rund zwei Wochen.",
            "GPX, FIT oder komplette Exporte aus Strava, Garmin und Wahoo als ZIP. Duplikate " +
                "erkennt Trailscape selbst.",
            "Import über das + oben rechts im Verlauf – oder teil eine Datei direkt an Trailscape.",
        ),
    ),
    PROFILE(
        title = "Ein paar Angaben für die Auswertung",
        paragraphs = listOf(
            "Aus Alter und Gewicht schätzt Trailscape Puls und Leistung – die Grundlage jeder " +
                "Trainingslast.",
            "Leer lassen geht auch. Genauere Werte trägst du später unter Einstellungen → " +
                "Profil ein.",
        ),
    ),
    HEALTH(
        title = "Erholungswerte aus deiner Uhr",
        paragraphs = listOf(
            "Schreibt deine Uhr nach Health Connect (Samsung Health, Garmin, Fitbit und " +
                "andere), fließen Ruhepuls, HRV und Schlaf in die Tagesempfehlung ein.",
            "Ohne Uhr funktioniert alles genauso. Verbinden geht auch später unter " +
                "Einstellungen → Uhr & Gesundheitsdaten.",
        ),
    ),
    FIRST_ROUND(
        title = "Deine erste Runde",
        paragraphs = listOf(
            "Wie viel Zeit hast du heute? Trailscape baut dir eine ruhige Runde ab deinem " +
                "Standort, die wieder zu Hause endet.",
        ),
    ),
}

/** Die Beschriftung der „Später"-Option neben den Dauern. */
private const val FIRST_ROUND_LATER = "Später"

/**
 * Auswahl der Dauer fuer die erste Runde — drei Dauern und „Später" als ein
 * Segmentknopf.
 *
 * Darunter steht bei gewaehlter Dauer, was daraus wird (≈ km, flach, ruhig),
 * und ausdruecklich, dass gleich die Standortabfrage kommt und was dabei das
 * Geraet verlaesst. Die Abfrage kaeme sonst unangekuendigt direkt nach der
 * Einfuehrung. Der Servername fehlt bewusst: Der Routing-Server ist unter
 * Einstellungen konfigurierbar.
 *
 * @param selected die gewaehlte Dauer, `null` fuer „Später".
 * @param previewKm die gerundete Distanz zur gewaehlten Dauer.
 */
@Composable
private fun FirstRoundStep(
    selected: FirstRoundDuration?,
    onSelect: (FirstRoundDuration?) -> Unit,
    previewKm: Int?,
) {
    val durations = FirstRoundDuration.entries
    PillSegments(
        options = durations.map { it.label } + FIRST_ROUND_LATER,
        selectedIndex = selected?.ordinal ?: durations.size,
        onSelect = { index -> onSelect(durations.getOrNull(index)) },
    )
    Spacer(modifier = Modifier.height(16.dp))
    if (selected != null && previewKm != null) {
        Text(
            text = "≈ $previewKm km · flach · ruhiges Tempo",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Gleich fragt Trailscape nach deinem Standort. Für die Berechnung gehen " +
                "nur die Wegpunkte der Runde an den Routing-Server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = "Kein Problem – auf „Heute“ wartet jederzeit eine passende Runde.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Die drei Pflichtangaben des Profils — mehr braucht `:core` nicht. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileFields(
    ageText: String,
    onAgeChange: (String) -> Unit,
    weightText: String,
    onWeightChange: (String) -> Unit,
    sex: Sex,
    onSexChange: (Sex) -> Unit,
    error: String?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        OneUiTextField(
            label = "Alter",
            value = ageText,
            onValueChange = onAgeChange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        OneUiTextField(
            label = "Gewicht (kg)",
            value = weightText,
            onValueChange = onWeightChange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(modifier = Modifier.height(12.dp))

    OneUiDropdownField(
        label = "Geschlecht (optional)",
        value = sex,
        options = onboardingSexOptions,
        onChange = onSexChange,
        modifier = Modifier.fillMaxWidth(),
    )

    if (error != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Der optionale Verbindungsschritt.
 *
 * Ruft denselben Weg wie die Health-Karte im Mehr-Tab
 * ([AppViewModel.requestHealthPermissions]) — inklusive derselben
 * Fehlerbehandlung: Der Berechtigungsweg wirft bei jedem Problem des Anbieters
 * eine [HealthSyncException], ungefangen waere das ein Absturz direkt im
 * Erststart.
 */
@Composable
private fun HealthConnectStep(appViewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Button(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                status = null
                try {
                    val granted = appViewModel.requestHealthPermissions()
                    status = if (granted) {
                        "Verbunden. Trailscape holt deine Werte ab jetzt automatisch."
                    } else {
                        "Keine Freigabe erteilt — du kannst das später unter " +
                            "Einstellungen → Uhr & Gesundheitsdaten nachholen."
                    }
                } catch (e: HealthSyncException) {
                    status = e.message
                } finally {
                    busy = false
                }
            }
        },
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Health Connect verbinden")
        }
    }

    status?.let { text ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Fortschrittspunkte statt einer Zahl — dieselbe Sprache wie jeder Pager.
 *
 * [current] darf ausserhalb von `0 until count` liegen; dann ist kein Punkt
 * aktiv. Genau das braucht die Willkommensseite, die kein Schritt ist (siehe
 * Aufrufstelle).
 */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = index == current
            // One UI markiert den aktuellen Schritt nicht durch einen
            // *groesseren* Punkt, sondern durch einen **in die Breite
            // gezogenen** — ein Strich zwischen Punkten. Hier stand ein 9-dp-
            // Kreis neben 7-dp-Kreisen; auf Armlaenge war der Unterschied
            // kaum zu sehen. Die Breite dagegen sieht man sofort, und zwar
            // auch dann, wenn die Farbe nicht hilft.
            val width by animateDpAsState(
                targetValue = if (active) 22.dp else 7.dp,
                animationSpec = OneUiMotion.short(),
                label = "pageDotWidth",
            )
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .width(width)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

private val onboardingSexOptions: List<Pair<Sex, String>> = listOf(
    Sex.MAENNLICH to "männlich",
    Sex.WEIBLICH to "weiblich",
    Sex.UNBEKANNT to "keine Angabe",
)
