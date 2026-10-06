# syntax=docker/dockerfile:1

# 1. Build the chart explorer.
FROM node:24-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# 2. Build the Spring Boot jar with the UI in static/, then extract it (one directory per layer, so dependency
#    layers stay cached when only application code changes). The AOT cache below needs the extracted form.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src src
COPY --from=web /web/dist src/main/resources/static
# Tests run in CI (./mvnw verify with Testcontainers), not inside the image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests \
    && java -Djarmode=tools -jar target/market-data.jar extract --layers --destination extracted

# 3. Runtime: JRE only, non-root.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
# JDK 25 AOT cache: a training run that starts the Spring context and exits records loaded and linked classes,
# which later starts reuse. It needs no database (Flyway is off for this run only). On a 0.1 CPU free instance
# this matters a lot; see docs/coldstart.md. Trained with the serial collector that deploy/render.yaml uses.
RUN java -XX:+UseSerialGC -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false -jar market-data.jar \
    && chown app:app app.aot
USER app
EXPOSE 8080
# Size the heap from the container's memory limit; deploy/render.yaml sets flags for a 512 MB / 0.1 CPU host.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "market-data.jar"]
