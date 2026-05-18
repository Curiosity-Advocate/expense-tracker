FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle settings.gradle
COPY build.gradle build.gradle
COPY core/build.gradle core/build.gradle
COPY api/build.gradle api/build.gradle
COPY worker/build.gradle worker/build.gradle

RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY core core
COPY api api

RUN ./gradlew :api:bootJar --no-daemon -x test

# Runtime image — JRE only, no build tools
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /workspace/api/build/libs/*.jar app.jar

EXPOSE 8080

# Allow JVM heap tuning via env var for Render's free tier (512 MB RAM)
ENV JAVA_OPTS="-Xmx400m -Xss512k -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
