// Querschnittsbausteine; keine Abhängigkeit auf Fachmodule.
dependencies {
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")
    api("io.micrometer:micrometer-core")                    // Metriken der Betreiberwarnungen (WP-M1-47)
    implementation("org.slf4j:slf4j-api")                   // strukturierte Logzeile
    implementation("tools.jackson.core:jackson-databind")   // Ereignishülle, JSON-Verarbeitung (WP-M1-14, -46)
    implementation("com.rabbitmq:amqp-client")              // Relay-Adapter RabbitMQ (WP-M1-14), nicht ausgeführt ohne Broker
    implementation("io.lettuce:lettuce-core")
    testImplementation(libs.postgresql)
    testImplementation("com.zaxxer:HikariCP")
    testImplementation("ch.qos.logback:logback-classic")    // Log-Scan der Feldschutz- und Kartendatentests (WP-M1-31, -37)
}

tasks.withType<Test>().configureEach {
    // Datenbankabhängige Tests dürfen nie aus Cache oder „up-to-date“ als bestanden gelten (Umgebung ist kein Task-Input).
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Test hängt von einer laufenden Datenbank ab") { true }
    listOf("TAKTOVIA_DB_HOST", "TAKTOVIA_DB_PORT", "TAKTOVIA_DB_NAME", "TAKTOVIA_DB_RUNTIME_PASSWORD",
        "TAKTOVIA_DB_ADMIN_USER", "TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_DB_FIELDS_PASSWORD", "TAKTOVIA_REQUIRE_DB")
        .forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
