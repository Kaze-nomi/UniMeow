FROM gradle:9-jdk25 AS build
WORKDIR /app

COPY settings.gradle.kts build.gradle.kts ./

COPY gRPC/build.gradle.kts         gRPC/
COPY MediaService/build.gradle.kts MediaService/
COPY UserService/build.gradle.kts  UserService/
COPY PostService/build.gradle.kts  PostService/
COPY APIGateway/build.gradle.kts   APIGateway/
COPY FeedService/build.gradle.kts  FeedService/
COPY Eureka/build.gradle.kts       Eureka/
COPY NotificationService/build.gradle.kts NotificationService/

RUN gradle :MediaService:dependencies --no-daemon --quiet 2>/dev/null || true

COPY gRPC/src         gRPC/src
COPY MediaService/src MediaService/src

RUN gradle :MediaService:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/MediaService/build/libs/*.jar app.jar

EXPOSE 9003 9093
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
