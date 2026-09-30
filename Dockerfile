# syntax=docker/dockerfile:1

# Build with the Gradle wrapper committed to this repository so that production
# uses the same Gradle and Java versions as CI.
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon bootJar

# Keep the Codex CLI inside the image.  Pinning it means that a host update
# cannot silently change the executable used by a running API.
FROM node:22-bookworm-slim AS codex
ARG CODEX_VERSION=0.159.2
RUN npm install --global --omit=dev "@openai/codex@${CODEX_VERSION}"

# Node supplies the Codex runtime; copy a pinned Java 21 runtime into it.
FROM node:22-bookworm-slim
COPY --from=eclipse-temurin:21-jre-jammy /opt/java/openjdk /opt/java/openjdk
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates bubblewrap \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/var/lib/ai-service \
    CODEX_HOME=/var/lib/ai-service/.codex \
    CODEX_EXECUTABLE=/usr/local/bin/codex \
    CODEX_WORKSPACE_ROOT=/var/lib/ai-service/workspaces \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=18080

COPY --from=codex /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/@openai/codex/bin/codex.js /usr/local/bin/codex \
    && groupadd --gid 998 ai-service \
    && useradd --uid 998 --gid 998 --home-dir /var/lib/ai-service --no-create-home --shell /usr/sbin/nologin ai-service \
    && mkdir -p /var/lib/ai-service/.codex /var/lib/ai-service/workspaces \
    && chown -R ai-service:ai-service /var/lib/ai-service

COPY --from=builder /workspace/build/libs/ai-service-*.jar /app/ai-service.jar
USER 998:998
EXPOSE 18080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD node -e "require('http').get('http://127.0.0.1:18080/actuator/health', response => process.exit(response.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/ai-service.jar"]
