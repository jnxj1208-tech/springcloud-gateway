# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# OpenTelemetry Java Agent：零代码侵入的链路追踪（CI 有外网，构建阶段下载）
ARG OTEL_AGENT_VERSION=v2.11.0
RUN wget -q -O /app/otel-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
 && test -s /app/otel-javaagent.jar

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/target/springcloud-gateway.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080 8719
ENV JAVA_OPTS="-javaagent:/app/otel-javaagent.jar -XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
