FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY backend/pom.xml .
COPY backend/src src

RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/quarkus-app/lib/ ./lib/
COPY --from=build /app/target/quarkus-app/*.jar ./
COPY --from=build /app/target/quarkus-app/app/ ./app/
COPY --from=build /app/target/quarkus-app/quarkus/ ./quarkus/

EXPOSE 8080

ENV JAVA_OPTS="-Xmx512m -Xms256m"

CMD ["java", "-jar", "quarkus-run.jar"]
