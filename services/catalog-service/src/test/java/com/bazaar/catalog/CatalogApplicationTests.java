package com.bazaar.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "bazaar.security.jwt.remote=false")
@AutoConfigureMockMvc
@Import({CatalogApplicationTests.DatabaseTestConfiguration.class, CatalogApplicationTests.TestJwtConfiguration.class})
class CatalogApplicationTests {

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
        mockMvc.perform(get("/api/v1/catalog/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("catalog-service"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unknownApiRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/private"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void categoriesAreSeeded() throws Exception {
        mockMvc.perform(get("/api/v1/categories").with(customer("customer-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].name").value("Electronics"));
    }

    @Test
    void customerSeesActiveProductsAndNotDrafts() throws Exception {
        mockMvc.perform(get("/api/v1/products").with(customer("customer-2")).param("q", "Desk lamp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].price").value(25.00));

        mockMvc.perform(get("/api/v1/products").with(customer("customer-2")).param("q", "Pocket radio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        mockMvc.perform(get("/api/v1/products/8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1003").with(customer("customer-2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sellerOwnsTheProductEvenWhenTheBodyNamesSomeoneElse() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-b", "SKU-OWNER-1", "12.50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellerId").value("seller-a"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/v1/products/" + id)
                        .with(seller("seller-b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-b", "SKU-OWNER-1", "12.50")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/products/" + id)
                        .with(seller("seller-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-a", "SKU-OWNER-1B", "15.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-OWNER-1B"))
                .andExpect(jsonPath("$.sellerId").value("seller-a"));
    }

    @Test
    void customerCannotCreateOrPublish() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(customer("customer-3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("customer-3", "SKU-CUST-1", "10.00")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPriceIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-price"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-price", "SKU-PRICE-1", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("price is invalid"));
    }

    @Test
    void duplicateSkuIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-sku"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-sku", "SKU-DUP-1", "10.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-sku"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-sku", "SKU-DUP-1", "11.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void searchFiltersByKeywordCategoryAndPrice() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .with(customer("customer-4"))
                        .param("q", "shirt")
                        .param("categoryId", "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0003")
                        .param("minPrice", "10")
                        .param("maxPrice", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("FASHION-SHIRT-1"));

        mockMvc.perform(get("/api/v1/products")
                        .with(customer("customer-4"))
                        .param("q", "shirt")
                        .param("minPrice", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void archivedProductCannotReturnToActive() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/products")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("seller-status", "SKU-STATUS-1", "10.00")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/products/" + id + "/status")
                        .with(seller("seller-status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict());
    }

    private static String productJson(String sellerId, String sku, String price) {
        return """
                {"sellerId":"%s","categoryId":"8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002","name":"Sample %s","description":"A sample","sku":"%s","price":%s,"currency":"USD"}
                """.formatted(sellerId, sku, sku, price);
    }

    private static RequestPostProcessor customer(String subject) {
        return token(subject, "CUSTOMER");
    }

    private static RequestPostProcessor seller(String subject) {
        return token(subject, "SELLER");
    }

    private static RequestPostProcessor token(String subject, String role) {
        return jwt().jwt(builder -> builder
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(role))));
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
