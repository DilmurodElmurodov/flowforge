# syntax=docker/dockerfile:1.7
# =====================================================================================================
# FlowForge backend - multi-stage build
#   stage 1 (build):   compile & package with the JDK, Gradle dependency cache mounted for fast rebuilds
#   stage 2 (runtime): slim JRE, non-root user, Spring Boot layered jar for optimal Docker layer caching
# =====================================================================================================

# ---------------------------------------------------------------- build stage -------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 1. Gradle wrapper + build scripts first: this layer only changes when the build definition changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

# 2. Resolve dependencies (cached by BuildKit across builds; no-op when build.gradle is unchanged).
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies --quiet > /dev/null 2>&1 || true

# 3. Sources last so that code changes do not invalidate the dependency layers.
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test -x integrationTest --quiet

# 4. Explode the fat jar into Spring Boot layers (dependencies / spring-boot-loader / snapshots / application)
RUN java -Djarmode=tools -jar build/libs/flowforge-*.jar extract --layers --destination /workspace/extracted --launcher

# ---------------------------------------------------------------- runtime stage -----------------------
FROM eclipse-temurin:21-jre AS runtime

# curl is used by the container health check against the actuator readiness probe.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 flowforge \
 && useradd --system --uid 1001 --gid flowforge --home /app --shell /usr/sbin/nologin flowforge

WORKDIR /app

# Copy layers least-volatile first so only the application layer is rebuilt on a code change.
COPY --from=build --chown=flowforge:flowforge /workspace/extracted/dependencies/ ./
COPY --from=build --chown=flowforge:flowforge /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=flowforge:flowforge /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=flowforge:flowforge /workspace/extracted/application/ ./

USER flowforge:flowforge

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080 \
    TZ=UTC

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
