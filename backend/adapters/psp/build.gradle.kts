// Abhängigkeiten: platform; Modul-APIs nur bei Bedarf und nur deren api-Paket.
dependencies {
    implementation("tools.jackson.core:jackson-databind")   // PSP-Antworten lesen (WP-M2-29)
    implementation("org.slf4j:slf4j-api")
}

tasks.withType<Test>().configureEach {
    systemProperty("taktovia.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    // Der PSP-Simulator (tests/sandbox/psp) ist ein Python-Programm der Standardbibliothek; ohne python3 laufen die Simulatortests nicht (mit TAKTOVIA_REQUIRE_DB=true schlagen sie dann fehl).
    listOf("TAKTOVIA_PYTHON", "TAKTOVIA_REQUIRE_DB").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
