FROM eclipse-temurin:25-jdk@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradlew ./
COPY gradle/ gradle/
COPY config/ config/
COPY gRPC/build.gradle.kts gRPC/
COPY outbox/build.gradle.kts outbox/
COPY APIGateway/build.gradle.kts APIGateway/
COPY UserService/build.gradle.kts UserService/
COPY PostService/build.gradle.kts PostService/
COPY FeedService/build.gradle.kts FeedService/
COPY MediaService/build.gradle.kts MediaService/
COPY NotificationService/build.gradle.kts NotificationService/
COPY Eureka/build.gradle.kts Eureka/
RUN chmod +x gradlew
COPY gRPC/src gRPC/src
COPY outbox/src outbox/src
COPY APIGateway/src APIGateway/src
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew :APIGateway:bootJar --no-daemon --max-workers=2

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app/APIGateway/build/libs/*-boot.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
