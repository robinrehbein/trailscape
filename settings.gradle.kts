// Root-Build des Android-Projekts (Kotlin + Jetpack Compose).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "trailscape"

// Die BRouter-Routing-Engine (abrensch/brouter, MIT-Lizenz). Das Modul
// enthaelt keinen eigenen Quellcode, sondern zeigt mit seinen `sourceSets` in
// das auf v1.7.10 gepinnte Git-Submodul `third_party/brouter` — Begruendung
// siehe brouter/build.gradle.kts. Weil der Code aus dem Arbeitsbaum uebersetzt
// wird und nicht als Artefakt aufgeloest werden muss, braucht es hier trotz
// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` kein zusaetzliches Repository.
include(":brouter")
include(":core")
// `-Ptrailscape.toolOnly=true`: nur `:core` und `:indoor-dev` (Bau-Dienst im
// Container, server/worlds/Dockerfile) — ohne Android-SDK.
val toolOnly = providers.gradleProperty("trailscape.toolOnly").orNull == "true"
if (!toolOnly) include(":app")
// Erkundungs-Prototyp fuer Wear OS. Bewusst ein eigenstaendiges APK und kein
// Bestandteil der Telefon-App: Der Spike soll Hardwarefragen beantworten
// (welche DataTypes kann die Uhr, wie gut ist das GPS, was kostet es Akku)
// und danach entweder in ein echtes Modul wachsen oder ersatzlos verschwinden.
if (!toolOnly) include(":wear")
// Entwicklerwerkzeug des Indoor-Trainings (docs/indoor.md): baut auf der JVM
// fuer eine Beispielstrecke die 3D-Welt (GLB-Kacheln + Manifest) mit dem
// Weltbau aus `:core`, damit sie sich ohne Geraet in headless Chromium ansehen
// laesst. Reines JVM-Modul, kein Bestandteil des APK.
include(":indoor-dev")
project(":indoor-dev").projectDir = file("tool/indoor-dev")
