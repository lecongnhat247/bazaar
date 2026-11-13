package com.bazaar.inventory.seller;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.inventory.stock.InventoryAccess;

@Component
public class HttpSellerStatusCheck implements SellerStatusCheck {

    private final RestClient identity;

    public HttpSellerStatusCheck(@Value("${bazaar.inventory.identity-url}") String identityUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(1000));
        this.identity = RestClient.builder().baseUrl(identityUrl).requestFactory(factory).build();
    }

    @Override
    public void requireActive(Jwt jwt) {
        if (InventoryAccess.from(jwt).admin()) {
            return;
        }
        try {
            SellerView view = identity.get()
                    .uri("/api/v1/sellers/me")
                    .header(HttpHeaders.AUTHORIZATION, authorization())
                    .retrieve()
                    .body(SellerView.class);
            if (view == null || !"ACTIVE".equals(view.status())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seller is not active.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seller is not active.");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Seller status could not be verified.");
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Seller status could not be verified.");
        }
    }

    private static String authorization() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            String header = servlet.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                return header;
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Seller status could not be verified.");
    }

    private record SellerView(String status) {
    }
}
