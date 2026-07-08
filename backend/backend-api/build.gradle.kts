plugins {
    kotlin("plugin.serialization")
    application
}

val ktor = "3.0.3"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-auth:$ktor")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")

    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:10.20.1")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("de.mkammerer:argon2-jvm:2.11")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}

application {
    mainClass.set("app.filmengine.backend.MainKt")
}

tasks.test {
    // Docker Engine 29+ rejects API versions < 1.44; the docker-java inside
    // Testcontainers 1.20.x defaults to 1.32 and gets HTTP 400 without this.
    systemProperty("api.version", "1.44")
}
