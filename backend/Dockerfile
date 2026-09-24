FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /app

COPY backend/pom.xml .
COPY backend/src src

RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

FROM eclipse-temurin:25.0.4_7-jre-alpine-3.24

RUN apk add --no-cache docker-cli

WORKDIR /app

COPY --from=build /app/target/quarkus-app/lib/ ./lib/
COPY --from=build /app/target/quarkus-app/*.jar ./
COPY --from=build /app/target/quarkus-app/app/ ./app/
COPY --from=build /app/target/quarkus-app/quarkus/ ./quarkus/

EXPOSE 8080

# Heap sized from the container memory limit instead of a fixed -Xmx
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

CMD ["sh", "-c", "exec java $JAVA_OPTS -jar quarkus-run.jar"]
