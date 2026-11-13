package com.bazaar.catalog.api;

import java.util.UUID;

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

import com.bazaar.catalog.api.CategoryRequest;
import com.bazaar.catalog.api.CategoryResponse;
import com.bazaar.catalog.api.PageResponse;
import com.bazaar.catalog.category.CategoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categories;

    public CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    public PageResponse<CategoryResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return categories.list(jwt, page, size);
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = categories.create(jwt, request.name());
        return ResponseEntity.created(java.net.URI.create("/api/v1/categories/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    public CategoryResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody CategoryRequest request) {
        return categories.update(jwt, id, request.name());
    }
}
