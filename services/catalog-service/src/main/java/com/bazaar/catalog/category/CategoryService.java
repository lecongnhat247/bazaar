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
import com.bazaar.catalog.cache.CatalogCache;

@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final CatalogCache cache;

    public CategoryService(CategoryRepository categories, CatalogCache cache) {
        this.categories = categories;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> list(Jwt jwt, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is invalid");
        }
        String key = cache.categoryKey(page, size);
        if (key != null) {
            PageResponse<CategoryResponse> cached = cache.categories(key);
            if (cached != null) {
                return cached;
            }
        }
        var result = categories.findAll(PageRequest.of(page, size, Sort.by("name")));
        PageResponse<CategoryResponse> response = new PageResponse<>(
                result.getContent().stream()
                        .map(category -> new CategoryResponse(category.getId(), category.getName(), category.getSlug()))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
        if (key != null) {
            cache.putCategories(key, response);
        }
        return response;
    }
}
