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

include(":core")
include(":app")
// Erkundungs-Prototyp fuer Wear OS. Bewusst ein eigenstaendiges APK und kein
// Bestandteil der Telefon-App: Der Spike soll Hardwarefragen beantworten
// (welche DataTypes kann die Uhr, wie gut ist das GPS, was kostet es Akku)
// und danach entweder in ein echtes Modul wachsen oder ersatzlos verschwinden.
include(":wear")
