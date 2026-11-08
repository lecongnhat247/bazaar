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

    DownstreamClient(
            ObjectProvider<RestClient.Builder> builders,
            @Value("${bazaar.order.catalog-url}") String catalogUrl,
            @Value("${bazaar.order.inventory-url}") String inventoryUrl,
            @Value("${bazaar.order.payment-url}") String paymentUrl) {
        this.catalog = client(builders, catalogUrl);
        this.inventory = client(builders, inventoryUrl);
        this.payment = client(builders, paymentUrl);
    }

    CatalogProduct product(UUID id) {
        return call(() -> catalog.get()
                .uri("/api/v1/products/{id}", id)
                .header("Bazaar-Read-Source", "database")
                .headers(this::forwardContext)
                .retrieve()
                .body(CatalogProduct.class));
    }

    StockHold reserve(String key, UUID productId, int quantity) {
        return call(() -> inventory.post()
                .uri("/api/v1/inventory/reservations")
                .header("Idempotency-Key", key)
                .headers(this::forwardContext)
                .body(new ReserveBody(productId, quantity))
                .retrieve()
                .body(StockHold.class));
    }

    void commit(UUID reservationId) {
        call(() -> inventory.post()
                .uri("/api/v1/inventory/reservations/{id}/commit", reservationId)
                .headers(this::forwardContext)
                .retrieve()
                .toBodilessEntity());
    }

    void release(UUID reservationId) {
        call(() -> inventory.post()
                .uri("/api/v1/inventory/reservations/{id}/release", reservationId)
                .headers(this::forwardContext)
                .retrieve()
                .toBodilessEntity());
    }

    PaymentState createPayment(String key, UUID orderId, BigDecimal amount, String currency) {
        return call(() -> payment.post()
                .uri("/api/v1/payments")
                .header("Idempotency-Key", key)
                .headers(this::forwardContext)
                .body(new PaymentBody(orderId, amount, currency))
                .retrieve()
                .body(PaymentState.class));
    }

    PaymentState simulate(UUID paymentId, String outcome) {
        String action = "SUCCESS".equals(outcome) ? "simulate-success" : "simulate-failure";
        return call(() -> payment.post()
                .uri("/api/v1/payments/{id}/{action}", paymentId, action)
                .headers(this::forwardContext)
                .retrieve()
                .body(PaymentState.class));
    }

    void cancelPayment(UUID paymentId) {
        call(() -> payment.post()
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

    private static RestClient client(ObjectProvider<RestClient.Builder> builders, String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builders.getObject().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static <T> T call(Remote<T> remote) {
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
