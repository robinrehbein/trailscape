package de.trailscape.app.ui.onboarding

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.HealthSyncException
import de.trailscape.core.Sex
import de.trailscape.core.TrainingProfile
import kotlinx.coroutines.launch

/**
 * # Erststart-Einfuehrung
 *
 * Vier Seiten, die genau einmal laufen — beim allerersten Start, bevor die
 * Navigationsleiste ueberhaupt sichtbar wird. Danach merkt sich
 * [AppViewModel.completeOnboarding] das dauerhaft; erneut aufrufbar ist die
 * Einfuehrung ueber „Mehr → Über → Einführung erneut ansehen".
 *
 * ## Warum so kurz
 * Vier Seiten, davon zwei reine Erklaerseiten und zwei mit *je einer* Aufgabe.
 * Kein Assistent, der Einstellungen abfragt, die es auch spaeter noch gibt:
 * Alles hier ist ueberspringbar, und die App laeuft auch ohne jede Eingabe
 * vollstaendig. Die Einfuehrung erklaert, was der Nutzer sonst nirgends
 * erfahren wuerde:
 *
 *  1. **Was Trailscape ist** — die drei Faehigkeiten und dass alles lokal
 *     bleibt (kein Konto, keine Anmeldung — das erwartet 2026 niemand mehr).
 *  2. **Daten mitbringen** — der wichtigste Handgriff ueberhaupt, weil die
 *     Trainingsauswertung sonst wochenlang leer bleibt.
 *  3. **Trainingsprofil** — Alter und Gewicht sind die einzigen zwei Werte,
 *     ohne die `:core` gar nicht rechnen kann (siehe [TrainingProfile]); alles
 *     Uebrige schaetzt es selbst. Genau diese beiden stehen hier, mehr nicht.
 *  4. **Health Connect** — optional, mit prominentem „Später".
 *
 * ## Bedienung
 * Wischen oder „Weiter"; „Überspringen" oben rechts beendet die Einfuehrung
 * sofort. Auf der Profilseite speichert „Weiter" die Eingabe mit — leere
 * Felder sind erlaubt und werden stillschweigend uebergangen, fehlerhafte
 * Eingaben melden sich unter dem Feld und halten die Seite fest.
 */
@Composable
fun OnboardingScreen(appViewModel: AppViewModel) {
    val pagerState = rememberPagerState(pageCount = { OnboardingPage.entries.size })
    val scope = rememberCoroutineScope()

    // Die Profileingabe lebt hier oben, nicht in der Seite: Der Pager haelt
    // Nachbarseiten nicht dauerhaft in der Komposition, ein `remember` in der
    // Seite selbst waere nach zwei Wischern weg.
    val profile by appViewModel.profile.collectAsStateWithLifecycle()
    var ageText by rememberSaveable { mutableStateOf("") }
    var weightText by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf(Sex.UNBEKANNT) }
    var profileError by rememberSaveable { mutableStateOf<String?>(null) }

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

    fun finish() {
        // Auch beim Abschluss ueber die letzte Seite oder „Überspringen" soll
        // eine bereits getippte Profilangabe nicht verloren gehen.
        applyProfile()
        appViewModel.completeOnboarding()
    }

    fun goForward() {
        val page = OnboardingPage.entries[pagerState.currentPage]
        if (page == OnboardingPage.PROFILE && !applyProfile()) return
        if (pagerState.currentPage >= OnboardingPage.entries.lastIndex) {
            finish()
            return
        }
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
    }

    // Ohne `contentWindowInsets`-Angabe: Die Einfuehrung laeuft ausserhalb der
    // Navigationshuelle, hier soll das Scaffold die System-Insets also mit
    // seiner Vorgabe selbst aufloesen (in den vier Tabs macht das die Huelle).
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
                    TextButton(onClick = ::finish) { Text("Überspringen") }
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
                            text = page.eyebrow,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = page.title, style = MaterialTheme.typography.headlineSmall)
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
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageDots(
                        count = OnboardingPage.entries.size,
                        current = pagerState.currentPage,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (pagerState.currentPage > 0) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                        ) { Text("Zurück") }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(onClick = ::goForward) {
                        Text(
                            if (pagerState.currentPage == OnboardingPage.entries.lastIndex) {
                                "Los geht's"
                            } else {
                                "Weiter"
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Die vier Seiten samt Text. Als Aufzaehlung, damit Reihenfolge, Anzahl der
 * Punkte unten und die Fallunterscheidung im Rumpf nicht auseinanderlaufen
 * koennen.
 */
private enum class OnboardingPage(
    val eyebrow: String,
    val title: String,
    val paragraphs: List<String>,
) {
    WELCOME(
        eyebrow = "Willkommen",
        title = "Trailscape",
        paragraphs = listOf(
            "Drei Dinge kann diese App: Touren aufzeichnen, Routen auf Schotter und " +
                "Nebenwegen planen und aus deinen Fahrten ein Trainingsbild rechnen — " +
                "Fitness, Ermüdung und eine Tagesempfehlung.",
            "Unten führen vier Tabs dorthin: Karte (aufzeichnen und planen), Touren " +
                "(alles Gefahrene), Training (die Auswertung) und Mehr (Profil, Import, " +
                "Einstellungen).",
            "Alles liegt auf deinem Gerät. Kein Konto, keine Anmeldung, keine Telemetrie. " +
                "Ein eigener Sync-Server ist möglich, aber freiwillig.",
        ),
    ),
    DATA(
        eyebrow = "Schritt 1 von 3",
        title = "Bring deine bisherigen Touren mit",
        paragraphs = listOf(
            "Die Trainingsauswertung braucht Historie. Wenn du schon woanders " +
                "aufgezeichnet hast, ist der Import der schnellste Weg zu einem " +
                "belastbaren Bild — sonst dauert es rund zwei Wochen.",
            "Trailscape liest einzelne GPX- und FIT-Dateien und komplette " +
                "Strava-, Garmin- oder Wahoo-Exporte als ZIP-Archiv auf einmal ein. " +
                "Duplikate erkennt es dabei selbst.",
            "Zu finden unter Mehr → Daten & Backup. Dort liegt auch der Export, mit " +
                "dem du alles auf ein neues Gerät mitnimmst.",
        ),
    ),
    PROFILE(
        eyebrow = "Schritt 2 von 3",
        title = "Zwei Zahlen für die Auswertung",
        paragraphs = listOf(
            "Aus Alter und Gewicht leitet Trailscape deine maximale Herzfrequenz, die " +
                "Schwelle und die gefahrene Leistung ab — die Grundlage jeder " +
                "Trainingslast.",
            "Du kannst das überspringen; wir rechnen dann mit Standardwerten weiter. " +
                "Ändern lässt sich alles jederzeit unter Mehr → Profil, dort stehen auch " +
                "die genaueren Felder (HFmax, Schwellenpuls, Zeitbudget).",
        ),
    ),
    HEALTH(
        eyebrow = "Schritt 3 von 3",
        title = "Erholungswerte aus deiner Uhr",
        paragraphs = listOf(
            "Wenn deine Uhr nach Health Connect schreibt (Samsung Health, Garmin, " +
                "Fitbit und andere), holt Trailscape von dort Ruhepuls, HRV und Schlaf — " +
                "und rechnet daraus die Tagesempfehlung im Trainings-Tab.",
            "Ohne diese Werte funktioniert die App vollständig; die Empfehlung stützt " +
                "sich dann allein auf deine Trainingslast.",
            "Verbinden geht auch später jederzeit unter Mehr → Samsung Health.",
        ),
    ),
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
        OutlinedTextField(
            value = ageText,
            onValueChange = onAgeChange,
            label = { Text("Alter") },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = weightText,
            onValueChange = onWeightChange,
            label = { Text("Gewicht (kg)") },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(modifier = Modifier.height(12.dp))

    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = onboardingSexLabels.getValue(sex),
            onValueChange = {},
            readOnly = true,
            label = { Text("Geschlecht (optional)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            onboardingSexLabels.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSexChange(value)
                        expanded = false
                    },
                )
            }
        }
    }

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
                            "Mehr → Samsung Health nachholen."
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

/** Fortschrittspunkte statt einer Zahl — dieselbe Sprache wie jeder Pager. */
@Composable
private fun PageDots(count: Int, current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(if (active) 9.dp else 7.dp)
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

private val onboardingSexLabels: Map<Sex, String> = linkedMapOf(
    Sex.MAENNLICH to "männlich",
    Sex.WEIBLICH to "weiblich",
    Sex.UNBEKANNT to "keine Angabe",
)
