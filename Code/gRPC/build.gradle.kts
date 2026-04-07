plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

group = "uni.grpc"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

dependencies {
    api("javax.annotation:javax.annotation-api:1.3.2")

    api("io.grpc:grpc-stub:1.62.2")
    api("io.grpc:grpc-protobuf:1.62.2")
    api("jakarta.annotation:jakarta.annotation-api:2.1.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.1" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.62.2" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { create("grpc") }
        }
    }
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