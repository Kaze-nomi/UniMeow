plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "uni.notification"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    mavenCentral()
}

extra["springCloudVersion"] = "2025.1.1"
extra["grpcVersion"] = "1.63.0"
extra["grpcSpringVersion"] = "3.1.0.RELEASE"

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
        mavenBom("io.grpc:grpc-bom:${property("grpcVersion")}")
    }
}

dependencies {
    implementation(project(":gRPC"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("net.devh:grpc-server-spring-boot-starter:${property("grpcSpringVersion")}")

    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.apache.kafka:kafka-clients")

    implementation("com.fasterxml.jackson.core:jackson-databind")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveClassifier.set("boot")
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "Required real infrastructure consumer replay, transaction and failure checks"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    outputs.upToDateWhen { false }
    doFirst {
        listOf("INTEGRATION_POSTGRES_URL", "INTEGRATION_POSTGRES_USER", "INTEGRATION_POSTGRES_PASSWORD").forEach { name ->
            require(!System.getenv(name).isNullOrBlank()) { "$name is required; run ops/test-integrations.sh with isolated infrastructure" }
        }
    }
}