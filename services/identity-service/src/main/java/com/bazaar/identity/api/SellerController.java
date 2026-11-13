package com.bazaar.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.identity.seller.SellerService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/sellers")
public class SellerController {

    private final SellerService sellers;

    public SellerController(SellerService sellers) {
        this.sellers = sellers;
    }

    @GetMapping
    public StaffPage<SellerResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return sellers.list(jwt, status, page, size);
    }

    @GetMapping("/{subject}")
    public SellerResponse one(@AuthenticationPrincipal Jwt jwt, @PathVariable String subject) {
        return sellers.getForStaff(jwt, subject(subject));
    }

    @PostMapping("/me")
    public ResponseEntity<SellerResponse> open(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StoreRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sellers.open(jwt, request));
    }

    @GetMapping("/me")
    public SellerResponse current(@AuthenticationPrincipal Jwt jwt) {
        return sellers.current(jwt);
    }

    @PatchMapping("/me/store")
    public SellerResponse updateStore(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StoreRequest request) {
        return sellers.updateStore(jwt, request);
    }

    @PostMapping("/{subject}/approve")
    public SellerResponse approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String subject,
            @Valid @RequestBody ReasonRequest request) {
        return sellers.approve(jwt, subject(subject), request.reason());
    }

    @PostMapping("/{subject}/reject")
    public SellerResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String subject,
            @Valid @RequestBody ReasonRequest request) {
        return sellers.reject(jwt, subject(subject), request.reason());
    }

    @PostMapping("/{subject}/suspend")
    public SellerResponse suspend(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String subject,
            @Valid @RequestBody ReasonRequest request) {
        return sellers.suspend(jwt, subject(subject), request.reason());
    }

    @PostMapping("/{subject}/reactivate")
    public SellerResponse reactivate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String subject,
            @Valid @RequestBody ReasonRequest request) {
        return sellers.reactivate(jwt, subject(subject), request.reason());
    }

    private static String subject(String subject) {
        if (subject == null || !subject.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject is invalid");
        }
        return subject;
    }
}
