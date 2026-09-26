FROM gradle:9-jdk25 AS build
WORKDIR /app

COPY settings.gradle.kts build.gradle.kts ./

COPY gRPC/build.gradle.kts                 gRPC/
COPY NotificationService/build.gradle.kts  NotificationService/
COPY UserService/build.gradle.kts          UserService/
COPY APIGateway/build.gradle.kts           APIGateway/
COPY Eureka/build.gradle.kts               Eureka/
COPY FeedService/build.gradle.kts          FeedService/
COPY MediaService/build.gradle.kts         MediaService/
COPY PostService/build.gradle.kts          PostService/

RUN gradle :NotificationService:dependencies --no-daemon --quiet 2>/dev/null || true

COPY gRPC/src                gRPC/src
COPY NotificationService/src NotificationService/src

RUN gradle :NotificationService:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/NotificationService/build/libs/*.jar app.jar

EXPOSE 9006 9095
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
