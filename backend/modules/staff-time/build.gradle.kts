// Abhängigkeiten: nur platform (siehe Wurzel-Build). Andere Module ausschließlich über deren api-Paket.
dependencies {
    implementation("org.slf4j:slf4j-api")
    implementation("tools.jackson.core:jackson-databind")   // Pausenplan (WP-M4-03)
    testImplementation(libs.postgresql)
    testImplementation("com.zaxxer:HikariCP")
    testImplementation(libs.json.schema.validator)
}

tasks.withType<Test>().configureEach {
    // Datenbankabhängige Tests dürfen nie aus Cache oder „up-to-date“ als bestanden gelten (Umgebung ist kein Task-Input).
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Test hängt von einer laufenden Datenbank ab") { true }
    systemProperty("taktovia.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    listOf("TAKTOVIA_DB_HOST", "TAKTOVIA_DB_PORT", "TAKTOVIA_DB_NAME", "TAKTOVIA_DB_RUNTIME_PASSWORD",
        "TAKTOVIA_DB_ADMIN_USER", "TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_REQUIRE_DB")
        .forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
