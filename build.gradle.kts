// Wurzel-Build von Trailscape. Die Anwendung selbst steckt in den Modulen
// :core (reines Kotlin/JVM) und :app (Android/Compose); hier stehen nur die
// Plugin-Versionen, die beide Module gemeinsam benutzen.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
}
