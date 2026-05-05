FROM gradle:9-jdk25 AS build
WORKDIR /app

COPY settings.gradle.kts build.gradle.kts ./

COPY gRPC/build.gradle.kts         gRPC/
COPY APIGateway/build.gradle.kts   APIGateway/
COPY UserService/build.gradle.kts  UserService/
COPY PostService/build.gradle.kts  PostService/
COPY Eureka/build.gradle.kts       Eureka/
COPY FeedService/build.gradle.kts  FeedService/
COPY MediaService/build.gradle.kts MediaService/
COPY NotificationService/build.gradle.kts NotificationService/

RUN gradle :APIGateway:dependencies --no-daemon --quiet 2>/dev/null || true

COPY gRPC/src        gRPC/src
COPY APIGateway/src  APIGateway/src

RUN gradle :APIGateway:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/APIGateway/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
