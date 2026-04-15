FROM gradle:9-jdk25 AS build
WORKDIR /app

COPY settings.gradle.kts build.gradle.kts ./

COPY gRPC/build.gradle.kts        gRPC/
COPY UserService/build.gradle.kts UserService/
COPY APIGateway/build.gradle.kts  APIGateway/
COPY Eureka/build.gradle.kts      Eureka/

RUN gradle :UserService:dependencies --no-daemon --quiet 2>/dev/null || true

COPY gRPC/src         gRPC/src
COPY UserService/src  UserService/src

RUN gradle :UserService:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/UserService/build/libs/*.jar app.jar

EXPOSE 9000 9090
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]