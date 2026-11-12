// Abhängigkeiten: nur platform (siehe Wurzel-Build). Andere Module ausschließlich über deren api-Paket.
dependencies {
    implementation(project(":modules:service-geo"))   // Zulässigkeitsprüfung über die Fachoperationen von service-geo (WP-M2-12), nur deren api-Paket
    implementation(project(":modules:config"))        // wirksame Dienstparameter (EffectiveConfig, WP-M2-11)
    implementation(project(":modules:fare-payment"))  // Preisstand der Angebote (FareQuotes, WP-M2-09/-21), nur deren api-Paket
    implementation("org.slf4j:slf4j-api")
    implementation("tools.jackson.core:jackson-databind")   // Routing-Client (WP-M2-10), Ressourcenvektor (WP-M2-14)
    testImplementation(libs.postgresql)
    testImplementation("com.zaxxer:HikariCP")
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.archunit.junit5)   // EtaArchitectureTest (WP-M4-61): Grundmodell ohne Fahrerbezug
}

tasks.withType<Test>().configureEach {
    // Datenbankabhängige Tests dürfen nie aus Cache oder „up-to-date“ als bestanden gelten (Umgebung ist kein Task-Input).
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Test hängt von einer laufenden Datenbank ab") { true }
    systemProperty("taktovia.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    listOf("TAKTOVIA_DB_HOST", "TAKTOVIA_DB_PORT", "TAKTOVIA_DB_NAME", "TAKTOVIA_DB_RUNTIME_PASSWORD",
        "TAKTOVIA_DB_ADMIN_USER", "TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD", "TAKTOVIA_REQUIRE_DB")
        .forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
