FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Сборка через Gradle Wrapper: он скачивает версию Gradle из gradle-wrapper.properties, ту же, что и при локальной сборке.
# Wrapper копируется раньше остального: слой со скачанным Gradle не пересобирается при каждой правке кода.
COPY gradlew ./
COPY gradle ./gradle
# В рабочей копии на Windows у gradlew нет права на запуск, поэтому выставляем его здесь.
RUN chmod +x gradlew && ./gradlew --version --no-daemon
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/backend /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
