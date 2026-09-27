package de.trailscape.core.i18n

/**
 * # Die Uebersetzungsschnittstelle von `:core`
 *
 * `:core` bleibt android-frei — Android-Ressourcen (`strings.xml`) gibt es
 * hier nicht. Nutzersichtbare Saetze, die in `:core` entstehen (Empfehlungen,
 * Erklaerungen, Fehlermeldungen, Sprachansagen), laufen deshalb ueber diese
 * kleine Schnittstelle mit einer deutschen und einer englischen
 * Implementierung. Beide sind reine Kotlin-Objekte und in `:core` getestet.
 *
 * ## Regeln
 *  * **Pflichtparameter ohne Default.** Jede Funktion, die einen
 *    nutzersichtbaren Satz liefert oder wirft, nimmt `texts: CoreTexts` als
 *    **letzten** Parameter und ohne Vorgabewert — so findet der Compiler
 *    jede Aufrufstelle, und kein Satz faellt stillschweigend auf Deutsch
 *    zurueck. Uebergeben wird immer benannt (`texts = …`).
 *  * **Typen statt Saetze, wo es die Logik vereinfacht.** Wer nur eine Art
 *    braucht (z. B. [de.trailscape.core.TurnAnnouncer] → `TurnAnsage`,
 *    [de.trailscape.core.WeeklyLoadCap]), bekommt einen Typ; der Satz
 *    entsteht erst bei der Anzeige.
 *  * **Diagnose bleibt deutsch.** `DiagLog`, die Health-Diagnosezeilen,
 *    `require`/`check`-Meldungen und Fehlerberichte richten sich an den
 *    Entwickler und laufen bewusst nicht hierueber.
 *  * **Plantexte haben Schluessel** ([SessionTextKey]), damit ein gespeicherter
 *    Plan nach dem Sprachwechsel in der neuen Sprache erscheint.
 *
 * Die App waehlt die Implementierung ueber [coreTexts] aus der aktuellen
 * [AppLanguage].
 */
interface CoreTexts {
    val language: AppLanguage
    val format: FormatTexts
    val speech: SpeechTexts
    val readiness: ReadinessTexts
    val load: LoadTexts
    val goal: GoalTexts
    val training: TrainingTexts
    val today: TodayTexts
    val routing: RoutingTexts
    val health: HealthTexts
    val files: FileTexts
    val sync: SyncTexts
}

/** Deutsche Texte — die Ausgangssprache der App. */
object CoreTextsDe : CoreTexts {
    override val language = AppLanguage.DE
    override val format: FormatTexts = FormatTextsDe
    override val speech: SpeechTexts = SpeechTextsDe
    override val readiness: ReadinessTexts = ReadinessTextsDe
    override val load: LoadTexts = LoadTextsDe
    override val goal: GoalTexts = GoalTextsDe
    override val training: TrainingTexts = TrainingTextsDe
    override val today: TodayTexts = TodayTextsDe
    override val routing: RoutingTexts = RoutingTextsDe
    override val health: HealthTexts = HealthTextsDe
    override val files: FileTexts = FileTextsDe
    override val sync: SyncTexts = SyncTextsDe
}

/** Englische Texte (britische Schreibweise). */
object CoreTextsEn : CoreTexts {
    override val language = AppLanguage.EN
    override val format: FormatTexts = FormatTextsEn
    override val speech: SpeechTexts = SpeechTextsEn
    override val readiness: ReadinessTexts = ReadinessTextsEn
    override val load: LoadTexts = LoadTextsEn
    override val goal: GoalTexts = GoalTextsEn
    override val training: TrainingTexts = TrainingTextsEn
    override val today: TodayTexts = TodayTextsEn
    override val routing: RoutingTexts = RoutingTextsEn
    override val health: HealthTexts = HealthTextsEn
    override val files: FileTexts = FileTextsEn
    override val sync: SyncTexts = SyncTextsEn
}

/** Die Texte zu einer Sprache. */
fun coreTexts(language: AppLanguage): CoreTexts = when (language) {
    AppLanguage.DE -> CoreTextsDe
    AppLanguage.EN -> CoreTextsEn
}
