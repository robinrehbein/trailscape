package de.trailscape.app.ui.indoor

/**
 * # Was vor „Fahren" noch fehlt (Startseite des Indoor-Ablaufs)
 *
 * Die Startseite hat genau einen Hauptknopf „Fahren". Er ist nur aktiv, wenn
 * Trainer und Strecke bereit sind; sonst steht darueber in einem Satz, was
 * fehlt, mit dem Knopf, der es behebt. Diese Datei rechnet das aus den
 * vorhandenen Zustaenden aus — rein, ohne Android, damit es sich pruefen
 * laesst (`IndoorReadinessTest`).
 */

/** Was am Trainer fehlt; `null` an der Stelle, an der es gebraucht wird = nichts. */
enum class IndoorTrainerIssue {
    /** Weder ein Trainer gemerkt noch der Demo-Trainer an. */
    NOT_PAIRED,

    /** Ein Trainer ist gemerkt, aber Bluetooth ist aus — er koennte sich nicht verbinden. */
    BLUETOOTH_OFF,

    /** Ein Trainer ist gemerkt, aber die Bluetooth-Berechtigung fehlt. */
    NO_PERMISSION,
}

/**
 * Was der Trainer-Status der Startseite zeigt.
 *
 * @param bluetoothOn Bluetooth ist eingeschaltet (fuer den Demo-Trainer egal).
 * @param permissionGranted die Bluetooth-Berechtigung ist erteilt.
 */
fun indoorTrainerIssue(pair: IndoorPairUi, bluetoothOn: Boolean, permissionGranted: Boolean): IndoorTrainerIssue? = when {
    pair.demoTrainer -> null
    pair.trainerName == null -> IndoorTrainerIssue.NOT_PAIRED
    !permissionGranted -> IndoorTrainerIssue.NO_PERMISSION
    !bluetoothOn -> IndoorTrainerIssue.BLUETOOTH_OFF
    else -> null
}

/** Zustand der gewaehlten Strecke aus Sicht von „Fahren". */
sealed interface IndoorRouteReadiness {
    /** Ob „Fahren" mit dieser Strecke geht. */
    val rideable: Boolean

    /** Noch keine Strecke gewaehlt. */
    data object NoRoute : IndoorRouteReadiness {
        override val rideable: Boolean = false
    }

    /**
     * Fahrbereit: Die 3D-Ansicht liegt auf dem Geraet (Testwelt, Katalog,
     * fertige eigene Welt). [update]: eine bekannte Strecke mit neuerer
     * Fassung im Katalog (die geladene bleibt fahrbar).
     */
    data class Ready(val name: String, val update: IndoorCatalogItemUi? = null) : IndoorRouteReadiness {
        override val rideable: Boolean = true
    }

    /** Eigene Strecke ohne 3D-Ansicht: „Fahren" fuehrt erst zu „Strecke vorbereiten". */
    data class NeedsPreparing(val name: String) : IndoorRouteReadiness {
        override val rideable: Boolean = true
    }

    /**
     * Bekannte Strecke, noch nicht (ganz) auf dem Geraet: herunterladen,
     * fortsetzen, laeuft, wird entpackt oder gescheitert — [item] traegt den
     * genauen Zustand (`IndoorCatalogItemUi.state`).
     */
    data class Catalog(val item: IndoorCatalogItemUi, val name: String) : IndoorRouteReadiness {
        override val rideable: Boolean
            // Waehrend ein Update laedt oder entpackt wird, nicht losfahren: Das
            // Verzeichnis der Welt wird gerade ersetzt.
            get() = item.ready &&
                item.state !is IndoorCatalogState.Downloading &&
                item.state !is IndoorCatalogState.Installing
    }
}

/**
 * Zustand der gewaehlten Strecke. Bekannte Strecken liest es aus [catalog]
 * (dort stehen Download und Fortschritt), alle anderen aus der Wahl selbst.
 *
 * @param nameOf Anzeigename einer Katalogkarte in der aktuellen Sprache.
 */
fun indoorRouteReadiness(
    route: IndoorRouteUi,
    catalog: IndoorCatalogUi,
    testWorldName: String,
    nameOf: (IndoorCatalogItemUi) -> String,
): IndoorRouteReadiness = when (val sel = route.selected) {
    null -> IndoorRouteReadiness.NoRoute
    IndoorRouteOption.TestWorld -> IndoorRouteReadiness.Ready(testWorldName)
    is IndoorRouteOption.Saved ->
        if (sel.hasWorld) IndoorRouteReadiness.Ready(sel.name) else IndoorRouteReadiness.NeedsPreparing(sel.name)
    is IndoorRouteOption.Generated -> IndoorRouteReadiness.NeedsPreparing(sel.name)
    is IndoorRouteOption.Catalog -> {
        val item = catalog.items.firstOrNull { it.id == sel.catalogId }
        when {
            item == null -> IndoorRouteReadiness.NoRoute
            item.state is IndoorCatalogState.Ready -> IndoorRouteReadiness.Ready(nameOf(item))
            item.state is IndoorCatalogState.UpdateAvailable -> IndoorRouteReadiness.Ready(nameOf(item), update = item)
            else -> IndoorRouteReadiness.Catalog(item, nameOf(item))
        }
    }
}

/**
 * „Fahren" ist aktiv: Trainer ohne Problem, Strecke fahrbereit und der Modus
 * passt (ERG nur mit Einheit, wie [IndoorRouteUi.canContinue]).
 */
fun indoorCanRide(trainer: IndoorTrainerIssue?, route: IndoorRouteUi, readiness: IndoorRouteReadiness): Boolean =
    trainer == null && readiness.rideable && route.canContinue
