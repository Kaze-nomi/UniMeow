plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "uni.gateway"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

extra["springCloudVersion"] = "2025.1.1"

configurations.all {
    resolutionStrategy {
        force("io.grpc:grpc-stub:1.62.2")
        force("io.grpc:grpc-protobuf:1.62.2")
        force("io.grpc:grpc-core:1.62.2")
    }
}

dependencies {
    implementation(project(":gRPC"))

    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    
    implementation("org.springframework.boot:spring-boot-starter-graphql")

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")

    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    implementation("net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}