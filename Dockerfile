FROM openjdk:16-jdk-alpine
COPY build/libs/KafkaRestProxyPerfTests.jar app.jar

EXPOSE 8080
EXPOSE 5006

ENTRYPOINT ["java", "-jar", "/app.jar"]
