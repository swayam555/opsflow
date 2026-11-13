# syntax=docker/dockerfile:1
# One build stage compiles every module once; each service is a small runtime target:
#   docker build --target ticket-service -t opsflow/ticket-service .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY common common
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd --system --uid 10001 opsflow
WORKDIR /app
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=150m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -Xss512k"
# Spring's health endpoint on $PORT, checked with bash /dev/tcp so the image needs no curl.
HEALTHCHECK --interval=10s --timeout=4s --start-period=60s --retries=6 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS ticket-service
ENV PORT=8080
COPY --from=build /src/services/ticket-service/target/ticket-service.jar /app/app.jar
EXPOSE 8080

FROM runtime AS triage-service
ENV PORT=8081
COPY --from=build /src/services/triage-service/target/triage-service.jar /app/app.jar
EXPOSE 8081

FROM runtime AS orchestrator-service
ENV PORT=8082
COPY --from=build /src/services/orchestrator-service/target/orchestrator-service.jar /app/app.jar
EXPOSE 8082

FROM runtime AS notification-service
ENV PORT=8083
COPY --from=build /src/services/notification-service/target/notification-service.jar /app/app.jar
EXPOSE 8083

FROM runtime AS deployment-service
ENV PORT=8084
COPY --from=build /src/services/deployment-service/target/deployment-service.jar /app/app.jar
EXPOSE 8084

FROM runtime AS tracker-service
ENV PORT=8085
COPY --from=build /src/services/tracker-service/target/tracker-service.jar /app/app.jar
EXPOSE 8085
