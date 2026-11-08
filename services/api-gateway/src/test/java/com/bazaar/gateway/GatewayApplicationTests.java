package com.bazaar.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "bazaar.security.jwt.remote=false",
        "bazaar.gateway.rate-limit.read-limit=2",
        "bazaar.gateway.rate-limit.write-limit=2",
        "bazaar.gateway.rate-limit.window=60s"
})
@AutoConfigureWebTestClient
@Import(GatewayApplicationTests.TestJwtConfiguration.class)
class GatewayApplicationTests {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.3-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void applicationContextStarts() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void healthIsUp() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void prometheusIsPublic() {
        webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("jvm_memory_used_bytes"));
    }

    @Test
    void livenessAndReadinessAreUp() {
        webTestClient.get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        webTestClient.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void routesPointAtConfiguredServices() {
        var ids = routeLocator.getRoutes().map(route -> route.getId()).collectList().block();
        assertThat(ids).containsExactlyInAnyOrder(
                "identity", "catalog", "categories", "products", "inventory", "orders", "payments");
    }

    @Test
    void preflightAllowsTheStorefrontOrigin() {
        webTestClient.options()
                .uri("/api/v1/identity/info")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }

    @Test
    void catalogReadsAreLimitedPerRouteAndRedisLossDoesNotBlock() {
        for (int i = 0; i < 2; i++) {
            webTestClient.get().uri("/api/v1/products")
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }
        webTestClient.get().uri("/api/v1/products")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        webTestClient.post().uri("/api/v1/orders/checkout")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));

        try (GenericContainer<?> down = new GenericContainer<>("redis:8.10.3-alpine").withExposedPorts(6379)) {
            down.start();
            var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                    .commandTimeout(java.time.Duration.ofMillis(200))
                    .build();
            var standalone = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                    down.getHost(), down.getMappedPort(6379));
            var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(standalone, client);
            factory.afterPropertiesSet();
            var template = new org.springframework.data.redis.core.ReactiveStringRedisTemplate(factory);
            var isolated = new CustomerRateLimit(template, jwtDecoder, 1, 1, java.time.Duration.ofSeconds(60));
            down.stop();
            assertThat(isolated.permit("GET", "/api/v1/products", null, "10.0.0.8").block(java.time.Duration.ofSeconds(5)))
                    .isTrue();
            factory.destroy();
        }
    }

    @Test
    void customersDoNotShareARateLimitBucket() throws Exception {
        String first = TestJwtConfiguration.token("customer-a");
        String second = TestJwtConfiguration.token("customer-b");
        for (int i = 0; i < 2; i++) {
            webTestClient.get().uri("/api/v1/categories")
                    .header("Authorization", "Bearer " + first)
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }
        webTestClient.get().uri("/api/v1/categories")
                .header("Authorization", "Bearer " + first)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        webTestClient.get().uri("/api/v1/categories")
                .header("Authorization", "Bearer " + second)
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        String customer = RateLimitIdentity.tokenHash(jwtDecoder, "Bearer " + first, "127.0.0.1");
        String anonymous = RateLimitIdentity.tokenHash(jwtDecoder, "Bearer not-a-token", "127.0.0.1");
        assertThat(customer).doesNotContain("customer-a");
        assertThat(customer).isNotEqualTo(anonymous);
        assertThat(anonymous).isEqualTo(RateLimitIdentity.tokenHash(jwtDecoder, null, "127.0.0.1"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfiguration {

        private static final RSAPrivateKey PRIVATE;
        private static final RSAPublicKey PUBLIC;

        static {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var pair = generator.generateKeyPair();
                PRIVATE = (RSAPrivateKey) pair.getPrivate();
                PUBLIC = (RSAPublicKey) pair.getPublic();
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey(PUBLIC).build();
        }

        static String token(String subject) throws Exception {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
            jwt.sign(new RSASSASigner(PRIVATE));
            return jwt.serialize();
        }
    }
}
