# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder

RUN apt-get update && apt-get install -y dos2unix && rm -rf /var/lib/apt/lists/*

WORKDIR /backend

# Copy only build files first — this layer is cached until build.gradle changes
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle/ gradle/
RUN dos2unix gradlew && chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy sources and build (skip tests)
COPY src/ src/
RUN ./gradlew build -x test --no-daemon

# Stage 2: Runtime — JRE only, no sources or Gradle tooling
FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /backend
COPY --from=builder /backend/build/libs/model-store-0.0.1-SNAPSHOT.jar app.jar
COPY --from=builder /backend/src/main/resources/keys /backend/keys

EXPOSE 8081
CMD ["java", "-jar", "/backend/app.jar"]
