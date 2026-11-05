FROM eclipse-temurin:25.0.5_8-jdk-noble AS build
# The wrapper falls back to a tar.gz when unzip is missing, then rejects that archive against the zip checksum.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/catalog-service/pom.xml services/catalog-service/pom.xml
COPY services/inventory-service/pom.xml services/inventory-service/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/api-gateway/src services/api-gateway/src
RUN --mount=type=cache,id=bazaar-maven,target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw -B -pl services/api-gateway -am -DskipTests package

FROM eclipse-temurin:25.0.5_8-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 bazaar \
    && useradd --system --uid 1001 --gid bazaar --home-dir /app --shell /usr/sbin/nologin bazaar
WORKDIR /app
COPY --from=build --chown=bazaar:bazaar /src/services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar app.jar
USER bazaar
EXPOSE 8080
ENV SERVER_PORT=8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "app.jar"]
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/readiness || exit 1
