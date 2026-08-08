// Root-Build des nativen Android-Projekts (Kotlin + Jetpack Compose).
//
// Liegt bewusst parallel zum bestehenden Flutter-Projekt: Das Verzeichnis
// `android/` bringt ein eigenes settings.gradle.kts mit und wird hier NICHT
// eingebunden. Gradle sucht nur dann aufwaerts nach einer Settings-Datei,
// wenn im Startverzeichnis keine liegt — der Flutter-Build bleibt also
// unberuehrt.
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

include(":core")
include(":app")
