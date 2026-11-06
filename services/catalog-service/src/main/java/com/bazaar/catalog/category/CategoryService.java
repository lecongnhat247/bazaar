package com.bazaar.catalog.category;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.catalog.api.CategoryResponse;
import com.bazaar.catalog.api.PageResponse;

@Service
public class CategoryService {

    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> list(Jwt jwt, int page, int size) {
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A token is required.");
        }
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is invalid");
        }
        var result = categories.findAll(PageRequest.of(page, size, Sort.by("name")));
        return new PageResponse<>(
                result.getContent().stream()
                        .map(category -> new CategoryResponse(category.getId(), category.getName(), category.getSlug()))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }
}
