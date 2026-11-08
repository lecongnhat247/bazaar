package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.slf4j.MDC;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
class DownstreamClient {

    private final RestClient catalog;
    private final RestClient inventory;
    private final RestClient payment;
    private final CheckoutResilience resilience;

    DownstreamClient(
            ObjectProvider<RestClient.Builder> builders,
            CheckoutResilience resilience,
            @Value("${bazaar.order.catalog-url}") String catalogUrl,
            @Value("${bazaar.order.inventory-url}") String inventoryUrl,
            @Value("${bazaar.order.payment-url}") String paymentUrl,
            @Value("${bazaar.order.resilience.connect-timeout:300ms}") Duration connectTimeout,
            @Value("${bazaar.order.resilience.read-timeout:800ms}") Duration readTimeout) {
        this.resilience = resilience;
        this.catalog = client(builders, catalogUrl, connectTimeout, readTimeout);
        this.inventory = client(builders, inventoryUrl, connectTimeout, readTimeout);
        this.payment = client(builders, paymentUrl, connectTimeout, readTimeout);
    }

    CampaignQuote campaign(UUID id) {
        return read("inventory", () -> inventory.get()
                .uri("/api/v1/inventory/campaigns/{id}", id)
                .headers(this::forwardContext)
                .retrieve()
                .body(CampaignQuote.class));
    }

    CatalogProduct product(UUID id) {
        return read("catalog", () -> catalog.get()
                .uri("/api/v1/products/{id}", id)
                .header("Bazaar-Read-Source", "database")
                .headers(this::forwardContext)
                .retrieve()
                .body(CatalogProduct.class));
    }

    StockHold reserve(String key, UUID productId, int quantity) {
        return write("inventory", () -> inventory.post()
                .uri("/api/v1/inventory/reservations")
                .header("Idempotency-Key", key)
                .headers(this::forwardContext)
                .body(new ReserveBody(productId, quantity))
                .retrieve()
                .body(StockHold.class));
    }

    void commit(UUID reservationId) {
        write("inventory", () -> inventory.post()
                .uri("/api/v1/inventory/reservations/{id}/commit", reservationId)
                .headers(this::forwardContext)
                .retrieve()
                .toBodilessEntity());
    }

    void release(UUID reservationId) {
        write("inventory", () -> inventory.post()
                .uri("/api/v1/inventory/reservations/{id}/release", reservationId)
                .headers(this::forwardContext)
                .retrieve()
                .toBodilessEntity());
    }

    PaymentState createPayment(String key, UUID orderId, BigDecimal amount, String currency) {
        return write("payment", () -> payment.post()
                .uri("/api/v1/payments")
                .header("Idempotency-Key", key)
                .headers(this::forwardContext)
                .body(new PaymentBody(orderId, amount, currency))
                .retrieve()
                .body(PaymentState.class));
    }

    PaymentState simulate(UUID paymentId, String outcome) {
        String action = "SUCCESS".equals(outcome) ? "simulate-success" : "simulate-failure";
        return write("payment", () -> payment.post()
                .uri("/api/v1/payments/{id}/{action}", paymentId, action)
                .headers(this::forwardContext)
                .retrieve()
                .body(PaymentState.class));
    }

    void cancelPayment(UUID paymentId) {
        write("payment", () -> payment.post()
                .uri("/api/v1/payments/{id}/cancel", paymentId)
                .headers(this::forwardContext)
                .retrieve()
                .toBodilessEntity());
    }

    private void forwardContext(HttpHeaders headers) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorization);
            }
            String correlationId = attributes.getRequest().getHeader("X-Correlation-Id");
            if (correlationId != null && correlationId.matches("[A-Za-z0-9._:-]{8,80}")) {
                headers.set("X-Correlation-Id", correlationId);
            }
        }
        if (!headers.containsHeader("X-Correlation-Id")) {
            String correlationId = MDC.get("correlationId");
            if (correlationId != null && correlationId.matches("[A-Za-z0-9._:-]{8,80}")) {
                headers.set("X-Correlation-Id", correlationId);
            }
        }
    }

    private static RestClient client(
            ObjectProvider<RestClient.Builder> builders,
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return builders.getObject().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private <T> T read(String dependency, Remote<T> remote) {
        return resilience.protectRead(dependency, () -> invoke(remote));
    }

    private <T> T write(String dependency, Remote<T> remote) {
        return resilience.protectWrite(dependency, () -> invoke(remote));
    }

    private static <T> T invoke(Remote<T> remote) {
        try {
            return remote.get();
        } catch (RestClientResponseException exception) {
            throw new DownstreamFailure(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new DownstreamFailure(503);
        }
    }

    @FunctionalInterface
    private interface Remote<T> {
        T get();
    }

    record CampaignQuote(
            UUID id,
            UUID productId,
            BigDecimal price,
            String currency,
            String status,
            int remaining,
            int perCustomerLimit) {
    }

    record CatalogProduct(
            UUID id,
            String sellerId,
            String name,
            String sku,
            BigDecimal price,
            String currency,
            String status) {
    }

    record StockHold(UUID id, String status) {
    }

    record PaymentState(UUID id, String status) {
    }

    private record ReserveBody(UUID productId, int quantity) {
    }

    private record PaymentBody(UUID orderId, BigDecimal amount, String currency) {
    }
}
