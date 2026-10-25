FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0
WORKDIR /app
COPY target/huimeng-api-1.0.0-SNAPSHOT.jar /app/huimeng-api.jar
USER 10001:10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/huimeng-api.jar"]
