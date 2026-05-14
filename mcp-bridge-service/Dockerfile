# ─── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Cache dependency layer separately from source code
COPY pom.xml ./
RUN mvn dependency:go-offline --no-transfer-progress

COPY src ./src
RUN mvn package -DskipTests --no-transfer-progress

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Non-root user for least-privilege execution
RUN addgroup -S mcpbridge && adduser -S mcpbridge -G mcpbridge

WORKDIR /app
COPY --from=build /app/target/mcp-bridge-service-*.jar app.jar
RUN chown mcpbridge:mcpbridge app.jar

USER mcpbridge

EXPOSE 8081

# Liveness probe: wget is available in the alpine image
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget -q -O- http://localhost:8081/actuator/health || exit 1

ENTRYPOINT [ \
    "java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar" \
]
