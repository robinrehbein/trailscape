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
