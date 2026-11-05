# ADR-002: Java and Spring Boot selection

- Status: Accepted
- Date: 2026-11-04

## Context

The host has Oracle JDK 25.0.5.0.3 installed at `C:\Program Files\Java\jdk-25.0.5.0.3`. `JAVA_HOME` is unset. Maven is not installed on Windows or in WSL. Node.js 24.22.1 and npm 11.19.1 are installed and are used only by the storefront.

Spring Boot 4.1.2 supports Java 17 through 27. Spring Cloud 2025.1.4 supports Spring Boot 4.1.2 and includes Spring Cloud Gateway 5.0.4. Spring Boot 3.5 and Spring Cloud 2025.0 are out of open-source support.

The system Maven install was left absent on purpose. The project uses the Maven Wrapper instead.

## Decision

- Compile and run the backend on Java 25.
- Set `JAVA_HOME` to the installed JDK for build commands. Do not change the machine-wide environment unless the developer asks.
- Use Spring Boot 4.1.2 for every Java service.
- Use Spring Cloud 2025.1.4 on the gateway only. Other services do not take a Cloud dependency in Phase 1.
- Use the gateway starter `spring-cloud-starter-gateway-server-webflux` and the property prefix `spring.cloud.gateway.server.webflux`.
- Pin Maven 3.10.0 through the Wrapper. The M2 recheck found 3.10.0 as the current stable release. Boot requires Maven 3.6.3 or newer, and the backend build completed on JDK 25 with Maven 3.10.0.
- Build container JREs from Eclipse Temurin 25. Do not copy the host Oracle JDK into images.
- The M2 recheck kept Spring Boot 4.1.2 and Spring Cloud 2025.1.4. Those versions are pinned in the parent POM, and each service README records them.

## Consequences

- Local builds need JDK 25 on `JAVA_HOME`. Java 21 is not the project baseline.
- Spring Boot 4 modular starters differ from Boot 3 examples. Implementation follows the Boot 4.1 and Gateway 5 names.
- The first Wrapper invocation downloads Maven into the user cache. That requires network access and is not a system-wide install.
- Image builds need a Temurin 25 base image available to Docker.

## Alternatives considered

Spring Boot 3.5 would also run on Java 25, and its open-source support has ended. Forcing Java 21 would ignore the installed JDK without a compatibility need.
