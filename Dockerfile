# Baton API — Spring Boot on Java 21.
# Build:  docker build -t baton-api .
# Run:    docker run -p 8080:8080 -e DB_URL=... -e DB_USER=... -e DB_PASSWORD=... baton-api

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 baton
COPY --from=build /app/app.jar app.jar
USER baton
EXPOSE 8080
# Sized for small instances (e.g. Render free, 512 MB). Hosts like Render pass the port in $PORT.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
ENTRYPOINT ["sh", "-c", "exec java -Dserver.port=${PORT:-8080} -jar app.jar"]
