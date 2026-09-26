import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.internal.os.OperatingSystem

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.2"
    id("com.google.cloud.tools.jib") version "3.5.4"
}

group = "io.kellermann"
version = "0.1.0-SNAPSHOT"
description = "BigContainers equipment and inventory management application"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Frontend integration (contract with the frontend workspace)
// ---------------------------------------------------------------------------
// The pnpm workspace root is <repo>/frontend. The web package is @bigcontainers/web
// at frontend/apps/web and its production build output is frontend/apps/web/dist.
// `-PskipFrontend=true` lets backend-only local iteration (test, check) skip it.
// jib/jibBuildTar must never assemble an image without the matching frontend
// (ADR-0004): they fail fast below when the flag is set.
val skipFrontend: Boolean = (findProperty("skipFrontend") as String?)?.toBoolean() ?: false

val repoRoot: File = rootProject.projectDir.parentFile
val frontendDir: File = File(repoRoot, "frontend")
val frontendWebDir: File = File(frontendDir, "apps/web")
val frontendDistDir: File = File(frontendWebDir, "dist")
val pnpmExecutable: String = if (OperatingSystem.current().isWindows) "pnpm.cmd" else "pnpm"

val pnpmInstall = tasks.register<Exec>("pnpmInstall") {
    description = "Installs the frontend pnpm workspace dependencies with a frozen lockfile."
    group = "frontend"
    workingDir = frontendDir
    commandLine(pnpmExecutable, "install", "--frozen-lockfile")
    inputs.file(File(frontendDir, "pnpm-lock.yaml")).optional()
    inputs.file(File(frontendDir, "pnpm-workspace.yaml")).optional()
    inputs.file(File(frontendDir, "package.json")).optional()
    outputs.dir(File(frontendDir, "node_modules")).withPropertyName("nodeModules")
    outputs.cacheIf { true }
    onlyIf {
        if (skipFrontend) {
            logger.lifecycle("Skipping pnpmInstall because -PskipFrontend=true was set.")
        }
        !skipFrontend
    }
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    description = "Builds the @bigcontainers/web production bundle."
    group = "frontend"
    dependsOn(pnpmInstall)
    workingDir = frontendDir
    commandLine(pnpmExecutable, "--filter", "@bigcontainers/web", "build")
    inputs.dir(File(frontendWebDir, "src")).optional()
    inputs.file(File(frontendWebDir, "package.json")).optional()
    inputs.file(File(frontendWebDir, "vite.config.ts")).optional()
    inputs.file(File(frontendWebDir, "index.html")).optional()
    outputs.dir(frontendDistDir).withPropertyName("frontendDist")
    outputs.cacheIf { true }
    onlyIf {
        if (skipFrontend) {
            logger.lifecycle("Skipping frontendBuild because -PskipFrontend=true was set.")
        }
        !skipFrontend
    }
}

// Generated frontend files must never land in the tracked source tree: they are copied
// into the processed-resources output (build/resources/main/static), not into src/main.
tasks.named<ProcessResources>("processResources") {
    if (!skipFrontend) {
        dependsOn(frontendBuild)
        from(frontendDistDir) {
            into("static")
        }
    }
}

fun failFrontendRequired(taskName: String): Nothing = throw GradleException(
    "'$taskName' assembles the production application image and, per ADR-0004, can never run " +
        "with -PskipFrontend=true: a production image without the matching compiled frontend " +
        "would violate the single-application-image contract. Run './gradlew $taskName' " +
        "without -PskipFrontend, with the frontend workspace installed at $frontendDir."
)

tasks.matching { it.name == "jib" || it.name == "jibBuildTar" }.configureEach {
    doFirst {
        if (skipFrontend) {
            failFrontendRequired(name)
        }
    }
}

// ---------------------------------------------------------------------------
// Dependencies (Phase 0 scope)
// ---------------------------------------------------------------------------
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Generic OIDC login (ADR-0003). Spring Boot 4 splits OAuth2 client autoconfiguration out of
    // spring-boot-autoconfigure into its own spring-boot-oauth2-client module, exactly like
    // session-jdbc/flyway above: the bare spring-security-oauth2-client artifact would leave
    // ClientRegistrationRepository/OAuth2LoginAuthenticationFilter wiring inert. This starter
    // pulls the matching autoconfiguration module in; confirmed present in gradle.lockfile.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Spring Boot 4 split autoconfiguration out of spring-boot-autoconfigure into
    // per-technology modules that only activate when present on the classpath. The bare
    // org.springframework.session:spring-session-jdbc / org.flywaydb:flyway-core artifacts do
    // NOT pull those modules in, so spring.session.store-type=jdbc and spring.flyway.enabled
    // would silently be inert without the explicit starters below. See the Phase 0 report.
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    implementation("software.amazon.awssdk:s3:2.32.20")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("com.google.zxing:core:3.5.4")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 extracted TestRestTemplate out of spring-boot-test into its own module, no
    // longer pulled in transitively by spring-boot-starter-test. Its bean also needs
    // @AutoConfigureTestRestTemplate (see AbstractIntegrationTest) plus RestTemplateBuilder -
    // itself split into spring-boot-restclient - or the conditional bean-type deduction that
    // wires TestRestTemplate throws ClassNotFoundException. See the Phase 0 report.
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.google.zxing:javase:3.5.4")
    // Embedded (not containerized) mock OpenID Connect provider for the OIDC authorization-code
    // end-to-end tests (implementation plan section 3.3). Chosen over running the same project's
    // ghcr.io/navikt/mock-oauth2-server *container* (verified anonymously pullable) because the
    // embedded MockOAuth2Server gives per-test programmatic control of issued claims
    // (server.enqueueCallback(...)) - needed to prove issuer/subject permanence across an email
    // change and to drive the disabled-account/ambiguous-email scenarios - without juggling
    // static JSON_CONFIG token-callback matching or an HTML interactive-login page across
    // container restarts. It is still the exact same real HTTP authorization-code exchange,
    // discovery document, and JWKS-verified ID token Spring Security would see against a real
    // provider.
    testImplementation("no.nav.security:mock-oauth2-server:2.2.1")
    // Spring Boot 4.1.1's dependency management pulls in Testcontainers 2.0.5, which renamed
    // these module artifacts from "postgresql"/"junit-jupiter" (the 1.x coordinates) to
    // "testcontainers-postgresql"/"testcontainers-junit-jupiter". The bare 1.x coordinates do
    // not resolve against the 2.x BOM; see the Phase 0 report for details.
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------
// Compilation, build-info, and test execution
// ---------------------------------------------------------------------------
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation,unchecked"))
}

springBoot {
    buildInfo()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// ---------------------------------------------------------------------------
// Dependency locking
// ---------------------------------------------------------------------------
dependencyLocking {
    lockAllConfigurations()
}

tasks.register("resolveAndLockAll") {
    description = "Resolves every resolvable configuration so `--write-locks` can lock them all."
    group = "dependency management"
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

// ---------------------------------------------------------------------------
// Spotless (palantir-java-format produces 4-space Java as required by policy)
// ---------------------------------------------------------------------------
configure<SpotlessExtension> {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat("2.99.0")
        importOrder()
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(4)
    }
    format("sql") {
        target("src/*/resources/db/migration/**/*.sql")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("yaml") {
        target("src/*/resources/**/*.yml", "src/*/resources/**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(2)
    }
}

// ---------------------------------------------------------------------------
// Jib (ADR-0004): the only authoritative production image build.
// ---------------------------------------------------------------------------
val gitRevision: String = try {
    providers.exec {
        commandLine("git", "rev-parse", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifBlank { "unknown" }
} catch (_: Exception) {
    "unknown"
}

val ghcrOwner: String = (findProperty("ghcrOwner") as String? ?: "sh4tterh4nd").lowercase()
val ghcrImageName: String = (findProperty("imageName") as String?) ?: "ghcr.io/$ghcrOwner/bigcontainers"
val ghcrImageTag: String = (findProperty("imageTag") as String?) ?: "latest"

jib {
    from {
        // docker.io/library/eclipse-temurin:25-jre multi-arch manifest-list digest,
        // resolved from Docker Hub on 2026-09-25. Update deliberately; do not float this tag.
        image = "docker.io/library/eclipse-temurin@sha256:bb036ed6cfdc57e3da7c22634d15f1b840d2caf76183861c80e81ca4b5104abb"
    }
    to {
        image = ghcrImageName
        tags = setOf(ghcrImageTag)
    }
    container {
        // Non-root: eclipse-temurin images ship no dedicated app user, so a plain
        // numeric UID:GID (not required to exist in /etc/passwd) is used instead.
        user = "1000:1000"
        ports = listOf("8080")
        creationTime.set("USE_CURRENT_TIMESTAMP")
        labels.set(
            mapOf(
                "org.opencontainers.image.source" to "https://github.com/$ghcrOwner/BigContainers",
                "org.opencontainers.image.revision" to gitRevision,
                "org.opencontainers.image.version" to project.version.toString(),
                "org.opencontainers.image.description" to
                    "BigContainers equipment, container, and inventory management application"
            )
        )
    }
}
