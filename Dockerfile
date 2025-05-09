FROM openjdk:21-jdk-slim AS builder
RUN apt-get update && apt-get install -y dos2unix

WORKDIR /backend
COPY . .
RUN dos2unix gradlew
RUN chmod +x /backend/gradlew && chmod +x /backend/entrypoint.sh && /backend/gradlew build --no-daemon

ENTRYPOINT ["bash", "-c", "source /backend/entrypoint.sh && \"$@\"", "--"]
CMD ["java", "-jar", "build/libs/model-store-0.0.1-SNAPSHOT.jar"]