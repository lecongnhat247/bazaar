package com.bazaar.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "bazaar.security.jwt.remote=false")
@AutoConfigureMockMvc
@Import({IdentityApplicationTests.DatabaseTestConfiguration.class, IdentityApplicationTests.TestJwtConfiguration.class})
class IdentityApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applicationContextStarts() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessAndReadinessAreUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void infoStaysPublic() throws Exception {
        mockMvc.perform(get("/api/v1/identity/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("identity-service"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void meRejectsMissingAndInvalidTokens() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/identity/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meCreatesAProfileFromTheTokenAndIsIdempotent() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/identity/me").with(customer("subject-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keycloakSubject").value("subject-1"))
                .andExpect(jsonPath("$.email").value("customer@localhost"))
                .andExpect(jsonPath("$.displayName").value("customer"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andReturn();

        String id = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/identity/me").with(customer("subject-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void customerCannotReadAnotherProfile() throws Exception {
        MvcResult created = mockMvc.perform(get("/api/v1/identity/me").with(customer("subject-owner")))
                .andExpect(status().isOk())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/identity/users/" + id).with(customer("subject-other")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadAnotherProfile() throws Exception {
        MvcResult created = mockMvc.perform(get("/api/v1/identity/me").with(customer("subject-member")))
                .andExpect(status().isOk())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/identity/users/" + id).with(admin("subject-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keycloakSubject").value("subject-member"));
    }

    @Test
    void userCanRenameTheirProfile() throws Exception {
        mockMvc.perform(patch("/api/v1/identity/me")
                        .with(customer("subject-rename"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ada\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada"));
    }

    @Test
    void blankDisplayNameIsRejected() throws Exception {
        mockMvc.perform(patch("/api/v1/identity/me")
                        .with(customer("subject-blank"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("displayName is invalid"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(String subject) {
        return jwt().jwt(token -> token
                .subject(subject)
                .claim("email", "customer@localhost")
                .claim("preferred_username", "customer")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER", "offline_access"))));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin(String subject) {
        return jwt().jwt(token -> token
                .subject(subject)
                .claim("preferred_username", "admin")
                .claim("realm_access", Map.of("roles", List.of("ADMIN"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseTestConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17.11-alpine");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfiguration {

        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
            return NimbusJwtDecoder.withPublicKey(publicKey).build();
        }
    }
}
