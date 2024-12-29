# Используем официальный образ OpenJDK как базовый образ
FROM openjdk:21-jdk-slim AS builder

# Указываем рабочую директорию внутри контейнера
WORKDIR /backend

# Копируем все файлы проекта в контейнер
COPY . .

# Убедитесь, что Gradle Wrapper имеет права на выполнение
RUN chmod +x ./gradlew

# Выполняем сборку приложения через Gradle Wrapper
RUN ./gradlew build --no-daemon

# Определяем команду, которая будет выполнена при запуске контейнера
CMD ["java", "-jar", "build/libs/model-store-0.0.1-SNAPSHOT.jar"]