plugins {
	java
	jacoco
	id("org.springframework.boot") version "3.4.2"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.4"
}

group = "uni"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

tasks.test { 
	finalizedBy(tasks.jacocoTestReport) 
}

tasks.jacocoTestReport { 
	dependsOn(tasks.test)
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

tasks.withType<JacocoReport> {
	classDirectories.setFrom(
		sourceSets.main.get().output.asFileTree.matching {
			exclude("uni/api/grpc/**")
		}
	)
}

dependencies {
    // Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
	implementation("org.springframework.retry:spring-retry")

    // OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    
    // gRPC
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("io.grpc:grpc-netty-shaded:1.62.2") 
    implementation("net.devh:grpc-spring-boot-starter:3.0.0.RELEASE")
	implementation("io.grpc:grpc-services:1.62.2")
    
    // Protobuf
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    
    // Annotations
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
    
    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

	// Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.grpc:grpc-testing:1.62.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.5.0")
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc:3.22.0"
	}
	plugins {
		create("grpc") {
			artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
		}
	}
	generateProtoTasks {
		all().forEach { task ->
			task.plugins {
				create("grpc")
			}
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.jacocoTestReport {
	classDirectories.setFrom(
		sourceSets.main.get().output.asFileTree.matching {
			exclude("uni/api/grpc/**")
		}
	)
}

sourceSets {
	main {
		java {
			srcDirs(
				"build/generated/source/proto/main/java",
				"build/generated/source/proto/main/grpc"
			)
		}
	}
}

tasks.named("compileJava").configure {
	dependsOn("generateProto")
}