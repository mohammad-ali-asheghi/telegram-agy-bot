# Multi-stage Docker build for Telegram Antigravity Bot

# Stage 1: Build the shaded JAR
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build

COPY pom.xml .
# Cache dependencies
RUN mvn dependency:go-offline -B || true

COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal JRE Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install ca-certificates and openssh-client (useful for key permissions & debugging)
RUN apk add --no-cache ca-certificates openssh-client tzdata

# Create directory for SSH keys (if mounted)
RUN mkdir -p /app/keys && chmod 700 /app/keys

# Copy uber JAR from build stage
COPY --from=builder /build/target/telegram-agy-bot-1.0.0.jar /app/app.jar

# JVM options optimized for container & low RAM footprint
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
