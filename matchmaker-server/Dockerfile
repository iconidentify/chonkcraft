FROM eclipse-temurin:25-jre

WORKDIR /opt/chonkcraft
COPY target/chonkcraft-matchmaker.jar /opt/chonkcraft/matchmaker.jar

USER 10001

EXPOSE 9092
ENV MATCHMAKER_PORT=9092
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/opt/chonkcraft/matchmaker.jar"]
