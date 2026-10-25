FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0
WORKDIR /app
COPY --chmod=0644 target/huimeng-api-1.0.0-SNAPSHOT.jar /app/huimeng-api.jar
COPY --chmod=0644 target/native/libjnidispatch.so /opt/huimeng/native/libjnidispatch.so
COPY --chmod=0644 target/native/JNA-AL2.0.txt target/native/LIBFFI-LICENSE.txt /opt/huimeng/native/
RUN chmod 0755 /app /opt/huimeng /opt/huimeng/native
USER 10001:10001
ENTRYPOINT ["java","-Djna.boot.library.path=/opt/huimeng/native","-Djna.nounpack=true","-Djna.nosys=true","-XX:MaxRAMPercentage=70","-jar","/app/huimeng-api.jar"]
