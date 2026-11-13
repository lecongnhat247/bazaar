package com.bazaar.catalog.category;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
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
import com.bazaar.catalog.security.CatalogAccess;

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

    @Transactional
    public CategoryResponse create(Jwt jwt, String name) {
        if (!CatalogAccess.from(jwt).admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can change categories.");
        }
        String trimmed = name.trim();
        Category category = new Category(UUID.randomUUID(), trimmed, slug(trimmed));
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A category with that name already exists.");
        }
        cache.evictCategories();
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug());
    }

    @Transactional
    public CategoryResponse update(Jwt jwt, UUID id, String name) {
        if (!CatalogAccess.from(jwt).admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can change categories.");
        }
        Category category = categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category was not found."));
        String trimmed = name.trim();
        category.rename(trimmed, slug(trimmed));
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A category with that name already exists.");
        }
        cache.evictCategories();
        return new CategoryResponse(category.getId(), category.getName(), category.getSlug());
    }

    private static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is invalid");
        }
        return slug.length() <= 80 ? slug : slug.substring(0, 80);
    }
}
