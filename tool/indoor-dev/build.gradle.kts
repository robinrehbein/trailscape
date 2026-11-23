// Entwicklerwerkzeug des Indoor-Trainings (siehe docs/indoor.md, Abschnitt 9):
// Kommandozeile „Welt fuer eine Beispielstrecke bauen". Besitzer: Slice
// indoor-world. Laeuft nur auf dem Entwicklerrechner — hier (und in den Tests
// von `:core`) darf `javax.imageio` benutzt werden, in `:core`-Hauptquellen
// nicht (Android kennt es nicht).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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

application {
    mainClass.set("de.trailscape.tool.indoor.MainKt")
}

dependencies {
    implementation(project(":core"))
    // Wie in `:app`: `:core` reicht kotlinx-serialization-json nicht durch.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
