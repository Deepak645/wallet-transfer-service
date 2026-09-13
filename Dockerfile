# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine AS runtime

# curl is needed for the container HEALTHCHECK.
RUN apk add --no-cache curl \
    && addgroup -S app && adduser -S app -G app

WORKDIR /app
COPY --from=build /build/target/wallet-service.jar app.jar

USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:${PORT:-8080}/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
