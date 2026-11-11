plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

// Nur Blattprojekte bauen; ":modules" und ":adapters" sind reine Gruppen.
val leaves = subprojects.filter { it.childProjects.isEmpty() }

configure(leaves) {
    group = "de.taktovia"
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories { mavenCentral() }

    // Dependency-Locking: jede aufgelöste Konfiguration ist über gradle.lockfile festgeschrieben.
    dependencyLocking { lockAllConfigurations() }

    dependencies {
        "implementation"(platform(rootProject.libs.spring.boot.dependencies))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        // Abhängigkeitsrichtung (Detailvertrag WP-M0-02): Modul/Adapter -> platform; app -> alle.
        if (path.startsWith(":modules:") || path.startsWith(":adapters:")) {
            "implementation"(project(":platform"))
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }

    // ./gradlew resolveAndLockAll --write-locks schreibt die Lockfiles aller Konfigurationen.
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("löst Konfigurationen zur Laufzeit auf")
        doFirst { require(gradle.startParameter.isWriteDependencyLocks) { "nur mit --write-locks aufrufen" } }
        doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
    }
}
