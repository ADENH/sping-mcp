FROM eclipse-temurin:24-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B verify

FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/jagatlabs-mcp-server.jar /app/server.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
