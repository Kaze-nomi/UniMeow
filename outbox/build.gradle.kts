plugins {
    `java-library`
    id("io.spring.dependency-management") version "1.1.7"
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5") }
}
dependencies {
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")
    api("org.apache.kafka:kafka-clients")
    implementation("org.slf4j:slf4j-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.postgresql:postgresql")
}
tasks.test { useJUnitPlatform { excludeTags("integration") } }
tasks.register<Test>("integrationTest") {
    description = "Real PostgreSQL/Kafka crash and ownership protocol checks on an isolated synthetic stack"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    outputs.upToDateWhen { false }
    doFirst {
        require(!System.getenv("OUTBOX_IT_JDBC_URL").isNullOrBlank()) { "OUTBOX_IT_JDBC_URL is required" }
        require(!System.getenv("OUTBOX_IT_KAFKA").isNullOrBlank()) { "OUTBOX_IT_KAFKA is required" }
    }
}
