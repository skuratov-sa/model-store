# Используем официальный образ OpenJDK как базовый образ
FROM openjdk:21-jdk-slim

# Указываем рабочую директорию
WORKDIR /app

# Копируем файл сборки JAR в контейнер
COPY build/libs/*.jar app.jar

# Сообщаем Docker, что контейнер прослушивает порт 8080
EXPOSE 8080

# Определяем команду, которая будет выполняться при запуске контейнера
ENTRYPOINT ["java", "-jar", "/app/app.jar"]