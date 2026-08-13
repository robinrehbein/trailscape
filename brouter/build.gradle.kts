// Duennes Huellmodul um die BRouter-Routing-Engine (abrensch/brouter, MIT).
//
// Es enthaelt bewusst *keinen* eigenen Quellcode: die `sourceSets` unten
// zeigen direkt in das Git-Submodul `third_party/brouter`, das auf den Tag
// v1.7.10 gepinnt ist. Damit gibt es genau eine Wahrheit ueber den
// Engine-Code — den Upstream-Commit — und kein per Hand kopierter Abzug, der
// beim naechsten Update auseinanderlaeuft.
//
// ## Warum ein Submodul und kein Artefakt aus einem Repository?
//
// BRouter liegt nicht auf Maven Central. GitHub Packages verlangt selbst fuer
// oeffentliche Pakete einen Zugriffstoken (fuer fremde Bauumgebungen und CI
// von Forks unbrauchbar), und JitPacks Bau von 1.7.10 schlaegt fehl. Der
// Submodulweg ist der einzige, der ohne Anmeldedaten reproduzierbar baut —
// und er kommt ohne ein zusaetzliches Repository in
// `settings.gradle.kts` aus, das dort wegen
// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` ohnehin zentral eingetragen
// werden muesste. Vorbild ist die Fahrrad-App VeloSpot (drzeeb/VeloSpot),
// die BRouter genauso einbindet.
//
// ## Warum nur fuenf der Upstream-Module?
//
// Gebraucht wird ausschliesslich die Rechenkette
// util → codec → expressions → mapaccess → core. Alles andere im Upstream
// (`brouter-server` mit eingebautem HTTP-Server, `brouter-map-creator` mit
// dem Kachel-Erzeuger, `brouter-routing-app` mit Androids Activity-Oberflaeche)
// braucht die App nicht und wuerde nur Gewicht und Angriffsflaeche
// mitbringen. Die fuenf hier eingebundenen Verzeichnisse sind reines Java
// ohne einen einzigen `android.*`-Import — deshalb darf dieses Modul ein
// schlichtes `java-library` sein und von `:core` (Kotlin/JVM, android-frei)
// benutzt werden.
plugins {
    id("java-library")
}

// Upstream uebersetzt mit `--release 11`. Wir ziehen auf 17 hoch, damit das
// Modul zum Rest des Projekts passt (`:core` und `:app` stehen auf 17) und
// kein zweiter Bytecode-Stand im Klassenpfad entsteht. Geprueft: der Code
// benutzt weder `java.nio.file` noch `java.time`, Streams, Lambdas oder
// `var`, laeuft also unveraendert auch auf Androids minSdk 26 — mit
// `d8 --min-api 26` gedext ohne Warnung, ein Core Library Desugaring ist
// nicht noetig.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Die fuenf Upstream-Quellverzeichnisse in Abhaengigkeitsreihenfolge.
val brouterSourceDirs = listOf(
    "brouter-util",
    "brouter-codec",
    "brouter-expressions",
    "brouter-mapaccess",
    "brouter-core",
).map { rootProject.file("third_party/brouter/$it/src/main/java") }

// Ein Klon ohne `--recurse-submodules` haette hier ein leeres Verzeichnis.
// Ohne diese Pruefung uebersetzte das Modul klaglos null Klassen und der
// Fehler taeuchte erst weit spaeter in `:core` als „unresolved reference
// btools" auf — deshalb frueh und mit dem noetigen Befehl abbrechen.
require(brouterSourceDirs.all { it.isDirectory }) {
    "Das BRouter-Submodul fehlt oder ist leer. Einmalig nachholen mit:\n" +
        "    git submodule update --init third_party/brouter"
}

sourceSets {
    named("main") {
        java.setSrcDirs(brouterSourceDirs)
        // Upstream legt in diesen fuenf Modulen keine Ressourcen ab; ein
        // leeres Verzeichnis vorzugeben ist trotzdem sauberer als Gradles
        // Standard `src/main/resources`, den es hier gar nicht gibt.
        resources.setSrcDirs(emptyList<String>())
    }
    // Dieses Modul hat keine eigenen Tests: getestet wird die Engine ueber
    // den Wrapper in `:core` (OfflineRouting.kt). Upstreams eigene Tests
    // brauchen Kachel- und Testdaten, die nicht im Repository liegen.
    named("test") {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

// Der Upstream-Code ist alt und erzeugt eine Flut von Warnungen (rohe Typen,
// veraltete Aufrufe). Sie sind nicht unsere Warnungen — wir aendern an
// diesem Code nichts —, deshalb aus dem Bau-Protokoll heraushalten, damit
// dort nur Meldungen stehen, auf die man reagieren kann.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
    options.isWarnings = false
}

// Reproduzierbare Ausgabe: ohne diese beiden Schalter traegt jedes JAR die
// aktuelle Uhrzeit und die Dateireihenfolge des Dateisystems, sodass zwei
// Bauten desselben Standes unterschiedliche Bytes ergeben. Macht VeloSpot
// genauso.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
