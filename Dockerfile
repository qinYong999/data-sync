FROM node:22-alpine AS frontend
WORKDIR /app
COPY data-sync-web/package.json data-sync-web/package-lock.json ./
RUN npm install
COPY data-sync-web/ .
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY pom.xml settings.xml* ./
COPY data-sync-core/pom.xml data-sync-core/
COPY data-sync-server/pom.xml data-sync-server/
COPY --from=frontend /app/dist/ data-sync-server/src/main/resources/static/
COPY . .
RUN mvn package -DskipTests -pl data-sync-server -am -o

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/data-sync-server/target/data-sync-server-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
