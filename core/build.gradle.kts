// Reines JVM-Modul: enthaelt das Rechen-/Domaenenmodell ohne Android-Bezug,
// damit es schnell und ohne Emulator testbar bleibt.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Die BRouter-Routing-Engine fuer das Rechnen auf dem Geraet (siehe
    // OfflineRouting.kt). Bewusst `implementation` und nicht `api`: Kein
    // anderes Modul soll `btools.*` sehen — die Engine ist ausschliesslich
    // hinter OfflineRouting.kt erreichbar. Ueber den Laufzeit-Klassenpfad
    // landet sie trotzdem transitiv in `:app` und damit im APK, ohne dass
    // dort etwas eingetragen werden muss.
    //
    // Die fuenf eingebundenen BRouter-Module sind reines Java ohne einen
    // `android.*`-Import, `:core` bleibt dadurch android-frei.
    implementation(project(":brouter"))

    // Nur das JsonElement/JsonObject-Baukastenmodell (kein @Serializable):
    // Dart schreibt manche Felder nur bei Nicht-Null (z. B. TrackPoint.hr),
    // andere immer inkl. expliziter `null` (z. B. RideStats.durationS) — das
    // laesst sich mit automatisch generierten Serializer-Defaults nicht sauber
    // abbilden, mit manuellem JsonObject-Aufbau pro Feld schon.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
