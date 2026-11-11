// Abhängigkeiten: platform (siehe Wurzel-Build) und das api-Paket des Moduls config (erste Konfigurationsrevision eines neuen Dienstes, WP-M1-09).
dependencies {
    implementation(project(":modules:config"))
    implementation("org.slf4j:slf4j-api")
    implementation("tools.jackson.core:jackson-databind")   // JSON-Werte der Dienstversionen und Gebiete (WP-M2-03, -04)
    testImplementation(libs.postgresql)
    testImplementation("com.zaxxer:HikariCP")
}

tasks.withType<Test>().configureEach {
    // Datenbankabhängige Tests dürfen nie aus Cache oder „up-to-date“ als bestanden gelten (Umgebung ist kein Task-Input).
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Test hängt von einer laufenden Datenbank ab") { true }
    listOf("TAKTOVIA_DB_HOST", "TAKTOVIA_DB_PORT", "TAKTOVIA_DB_NAME", "TAKTOVIA_DB_RUNTIME_PASSWORD",
        "TAKTOVIA_DB_ADMIN_USER", "TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_REQUIRE_DB")
        .forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
