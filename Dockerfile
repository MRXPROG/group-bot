FROM eclipse-temurin:17-jre-jammy

ENV TZ=Europe/Kyiv
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app

COPY target/TelegramBotGroupApplication-0.0.1-SNAPSHOT.jar .

ENTRYPOINT  ["java", "-jar", "TelegramBotGroupApplication-0.0.1-SNAPSHOT.jar"]