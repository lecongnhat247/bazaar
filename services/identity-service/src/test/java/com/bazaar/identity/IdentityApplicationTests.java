package com.bazaar.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbc;

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

    @Test
    void seededSellerIsActive() throws Exception {
        mockMvc.perform(get("/api/v1/sellers/me").with(seller("11111111-1111-4111-8111-111111111111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.storeName").value("Local Seller"));
    }

    @Test
    void customerCannotOpenAStore() throws Exception {
        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(customer("customer-store"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Customer Shop")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only a seller can open a store."));
    }

    @Test
    void sellerOpensOnePendingStoreAndCannotApproveIt() throws Exception {
        mockMvc.perform(get("/api/v1/sellers/me").with(seller("seller-new")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(seller("seller-new"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("New Store")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.keycloakSubject").value("seller-new"))
                .andExpect(jsonPath("$.storeName").value("New Store"));

        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(seller("seller-new"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("New Store")))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/sellers/seller-new/approve")
                        .with(seller("seller-new"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"mine\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only an administrator can change seller status."));
    }

    @Test
    void operatorCannotApproveAndSellerCannotApproveAnotherSeller() throws Exception {
        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(seller("seller-other"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Other Store")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/sellers/seller-other/approve")
                        .with(role("operator-1", "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"ops\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/sellers/seller-other/approve")
                        .with(seller("seller-new"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotChangeTheirOwnSellerStatus() throws Exception {
        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(seller("admin-self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Admin Store")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/sellers/admin-self/approve")
                        .with(admin("admin-self"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"self\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("You cannot change your own seller status."));
    }

    @Test
    void adminApprovesSuspendsAndReactivates() throws Exception {
        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(seller("seller-life"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Life Store")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/sellers/seller-life/approve")
                        .with(admin("admin-life"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"looks real\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seller_audit WHERE target_subject = ? AND action = 'APPROVE' AND actor_subject = ?",
                Integer.class,
                "seller-life",
                "admin-life");
        assertThat(audits).isEqualTo(1);

        mockMvc.perform(post("/api/v1/sellers/seller-life/approve")
                        .with(admin("admin-life"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/sellers/seller-life/suspend")
                        .with(admin("admin-life"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"hold\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mockMvc.perform(patch("/api/v1/sellers/me/store")
                        .with(seller("seller-life"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Renamed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This store cannot be edited."));

        mockMvc.perform(post("/api/v1/sellers/seller-life/reactivate")
                        .with(admin("admin-life"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"clear\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/sellers/me/store")
                        .with(seller("seller-life"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Life Store Again")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Life Store Again"));
    }

    @Test
    void adminRejectsAPendingStore() throws Exception {
        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(seller("seller-reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Rejected Store")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/sellers/seller-reject/reject")
                        .with(admin("admin-reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"incomplete\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(patch("/api/v1/sellers/me/store")
                        .with(seller("seller-reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("Try again")))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidLogoIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/sellers/me")
                        .with(seller("seller-logo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"Logo\",\"logoUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("logoUrl is invalid"));
    }

    @Test
    void staffCanReadUsersButOnlyAnAdminCanChangeAnotherAccount() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me").with(customer("customer-staff")))
                .andExpect(status().isOk());
        MvcResult admin = mockMvc.perform(get("/api/v1/identity/me").with(role("admin-staff", "ADMIN")))
                .andExpect(status().isOk())
                .andReturn();
        String adminId = com.jayway.jsonpath.JsonPath.read(admin.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/identity/users").with(customer("customer-staff")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/identity/users").with(role("operator-staff", "OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        mockMvc.perform(get("/api/v1/identity/audit").with(role("operator-staff", "OPERATOR")))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/identity/users/" + adminId + "/status")
                        .with(role("operator-staff", "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"reason\":\"no\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/identity/users/" + adminId + "/status")
                        .with(role("admin-staff", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"reason\":\"self\"}"))
                .andExpect(status().isForbidden());

        String customerId = jdbc.queryForObject(
                "SELECT id FROM user_profile WHERE keycloak_subject = ?",
                String.class,
                "customer-staff");
        mockMvc.perform(patch("/api/v1/identity/users/" + customerId + "/status")
                        .with(role("admin-staff", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"reason\":\"review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(get("/api/v1/identity/audit").with(role("operator-staff", "OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("ACCOUNT_STATUS"))
                .andExpect(jsonPath("$.items[0].reason").value("review"));
    }

    private static String storeJson(String name) {
        return "{\"storeName\":\"" + name + "\"}";
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor seller(String subject) {
        return role(subject, "SELLER");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor role(String subject, String roleName) {
        return jwt().jwt(token -> token
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(roleName))));
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
