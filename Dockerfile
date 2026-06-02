FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle settings.gradle
COPY build.gradle build.gradle
COPY core/build.gradle core/build.gradle
COPY adapters/build.gradle adapters/build.gradle
COPY api/build.gradle api/build.gradle
COPY worker/build.gradle worker/build.gradle

RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY core core
COPY adapters adapters
COPY api api

RUN ./gradlew :api:bootJar --no-daemon -x test

# Runtime image — JRE only, no build tools
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /workspace/api/build/libs/*.jar app.jar

EXPOSE 8080

# Render free tier = 512 MB total. JVM non-heap (metaspace, code cache,
# threads, native, GC, JIT) on a Spring Boot 3.x app with web + security
# + JPA + Flyway + Swagger is ~200 MB. 256 MB heap leaves ~50 MB OS
# headroom; tighter than ideal but works. Bump if/when on a larger plan.
ENV JAVA_OPTS="-Xmx256m -Xss512k -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
