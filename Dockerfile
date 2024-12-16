# Используем официальный образ OpenJDK как базовый образ
FROM openjdk:21-jdk-slim AS builder

# Указываем рабочую директорию внутри контейнера
WORKDIR /backend

# Копируем все файлы проекта в контейнер
COPY . .

# Убедитесь, что Gradle Wrapper имеет права на выполнение
RUN chmod +x ./gradlew

# Выполняем тесты через Gradle Wrapper
RUN ./gradlew test --no-daemon

# Выполняем сборку приложения через Gradle Wrapper
RUN ./gradlew build --no-daemon

# Открываем порт для приложения (если ваше приложение работает на порту 8080)
EXPOSE 8080

# Определяем команду, которая будет выполнена при запуске контейнера
CMD ["java", "-jar", "build/libs/model-store-0.0.1-SNAPSHOT.jar"]