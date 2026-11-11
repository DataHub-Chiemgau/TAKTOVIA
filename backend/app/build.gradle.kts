plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:config"))
    implementation(project(":modules:service-geo"))
    implementation(project(":modules:booking-dispatch"))
    implementation(project(":modules:journey-federation"))
    implementation(project(":modules:fare-payment"))
    implementation(project(":modules:fleet-safety"))
    implementation(project(":modules:staff-time"))
    implementation(project(":modules:documents-finance"))
    implementation(project(":modules:communication-support"))
    implementation(project(":modules:analytics"))
    implementation(project(":adapters:psp"))
    implementation(project(":adapters:partner-tomp"))
    implementation(project(":adapters:partner-osdm"))
    implementation(project(":adapters:timetable-gtfs"))
    implementation(project(":adapters:erp-export"))
    implementation(project(":adapters:ocr"))
    implementation(project(":adapters:notification"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly(libs.postgresql)

    testImplementation(libs.archunit.junit5)
    testImplementation(libs.testcontainers)
    testImplementation(libs.postgresql)
    testImplementation("io.lettuce:lettuce-core") // nur für den roten Selbsttest der Redis-Regel
}

tasks.withType<Test>().configureEach {
    // Datenbankabhängige Tests dürfen nie aus Cache oder „up-to-date“ als bestanden gelten: Umgebung ist kein Task-Input.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Test hängt von einer laufenden Datenbank ab") { true }
    systemProperty("taktovia.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    // Datenbank-Integrationstests: Verbindungsdaten aus der Umgebung (Runtime-Rolle, nie Migrator)
    listOf("TAKTOVIA_DB_HOST", "TAKTOVIA_DB_PORT", "TAKTOVIA_DB_NAME", "TAKTOVIA_DB_RUNTIME_PASSWORD", "TAKTOVIA_DB_MIGRATOR_PASSWORD",
        "TAKTOVIA_DB_ADMIN_USER", "TAKTOVIA_DB_ADMIN_PASSWORD", "TAKTOVIA_REQUIRE_DB")
        .forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
