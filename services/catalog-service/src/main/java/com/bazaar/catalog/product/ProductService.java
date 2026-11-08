package com.bazaar.catalog.product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.catalog.api.ImageRequest;
import com.bazaar.catalog.cache.CatalogCache;
import com.bazaar.catalog.api.PageResponse;
import com.bazaar.catalog.api.ProductRequest;
import com.bazaar.catalog.api.ProductResponse;
import com.bazaar.catalog.api.ProductSearch;
import com.bazaar.catalog.category.Category;
import com.bazaar.catalog.category.CategoryRepository;
import com.bazaar.catalog.outbox.OutboxStore;
import com.bazaar.catalog.security.CatalogAccess;

import jakarta.persistence.criteria.Predicate;

@Service
public class ProductService {

    private static final Map<String, String> SORTS = Map.of(
            "createdAt", "createdAt",
            "name", "name",
            "price", "price");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final OutboxStore outbox;
    private final CatalogCache cache;

    public ProductService(
            ProductRepository products,
            CategoryRepository categories,
            OutboxStore outbox,
            CatalogCache cache) {
        this.products = products;
        this.categories = categories;
        this.outbox = outbox;
        this.cache = cache;
    }

    @Transactional
    public ProductResponse create(Jwt jwt, ProductRequest request) {
        CatalogAccess access = CatalogAccess.from(jwt);
        if (!access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can create a product.");
        }
        Product product = new Product(
                UUID.randomUUID(),
                access.subject(),
                category(request.categoryId()),
                request.name().trim(),
                blankToNull(request.description()),
                request.sku().trim(),
                money(request.price()),
                request.currency(),
                Instant.now());
        product.setAttributes(attributes(request.attributes()));
        product.replaceImages(images(request.images()));
        return ProductResponse.from(products.saveAndFlush(product));
    }

    @Transactional
    public ProductResponse update(Jwt jwt, UUID id, ProductRequest request) {
        Product product = editable(jwt, id);
        product.setCategory(category(request.categoryId()));
        product.setName(request.name().trim());
        product.setDescription(blankToNull(request.description()));
        product.setSku(request.sku().trim());
        product.setPrice(money(request.price()));
        product.setCurrency(request.currency());
        product.setAttributes(attributes(request.attributes()));
        product.replaceImages(images(request.images()));
        product.setUpdatedAt(Instant.now());
        Product saved = products.saveAndFlush(product);
        if (saved.getStatus() == ProductStatus.ACTIVE) {
            catalogEvent("catalog.product-updated", saved);
        }
        evictAfterCommit(saved.getId());
        return ProductResponse.from(saved);
    }

    @Transactional
    public ProductResponse changeStatus(Jwt jwt, UUID id, ProductStatus status) {
        Product product = editable(jwt, id);
        ProductStatus previous = product.getStatus();
        if (!previous.canTransitionTo(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That status change is not allowed.");
        }
        product.setStatus(status);
        product.setUpdatedAt(Instant.now());
        if (previous != status && status == ProductStatus.ACTIVE) {
            catalogEvent("catalog.product-published", product);
        } else if (previous == ProductStatus.ACTIVE && status != ProductStatus.ACTIVE) {
            catalogEvent("catalog.product-unpublished", product);
        }
        evictAfterCommit(product.getId());
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Jwt jwt, UUID id, boolean database) {
        if (!database) {
            ProductResponse cached = cache.product(id);
            if (cached != null && cached.status() == ProductStatus.ACTIVE) {
                return cached;
            }
        }
        CatalogAccess access = CatalogAccess.from(jwt);
        Product product = products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product was not found."));
        boolean owner = !access.subject().isEmpty() && product.getSellerId().equals(access.subject());
        if (product.getStatus() != ProductStatus.ACTIVE && !access.admin() && !owner) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product was not found.");
        }
        ProductResponse response = ProductResponse.from(product);
        if (!database) {
            cache.putProduct(response);
        }
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(Jwt jwt, ProductSearch search) {
        CatalogAccess access = CatalogAccess.from(jwt);
        validatePage(search);
        if (search.mine() && !access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can list their own products.");
        }
        if (search.minPrice() != null && search.maxPrice() != null && search.minPrice().compareTo(search.maxPrice()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minPrice is invalid");
        }
        ProductStatus status = visibleStatus(access, search);
        if (status == null && search.status() != null && search.status() != ProductStatus.ACTIVE && !canSeeNonActive(access, search)) {
            return new PageResponse<>(List.of(), search.page(), search.size(), 0, 0);
        }
        String property = SORTS.get(search.sort());
        if (property == null || !DIRECTIONS.contains(search.direction())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort is invalid");
        }
        String listKey = access.admin() ? null : cache.publicListKey(search);
        if (listKey != null) {
            PageResponse<ProductResponse> cached = cache.list(listKey);
            if (cached != null) {
                return cached;
            }
        }
        Sort.Direction direction = "asc".equals(search.direction()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Page<Product> page = products.findAll(specification(access, search, status), PageRequest.of(
                search.page(), search.size(), Sort.by(direction, property)));
        PageResponse<ProductResponse> response = new PageResponse<>(
                page.getContent().stream().map(ProductResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
        if (listKey != null) {
            cache.putList(listKey, response);
        }
        return response;
    }

    private Specification<Product> specification(CatalogAccess access, ProductSearch search, ProductStatus status) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search.mine()) {
                predicates.add(builder.equal(root.get("sellerId"), access.subject()));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (search.categoryId() != null) {
                predicates.add(builder.equal(root.get("category").get("id"), search.categoryId()));
            }
            if (search.minPrice() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("price"), search.minPrice()));
            }
            if (search.maxPrice() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("price"), search.maxPrice()));
            }
            if (search.keyword() != null && !search.keyword().isBlank()) {
                String pattern = "%" + like(search.keyword()) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("name")), pattern, '\\'),
                        builder.like(builder.lower(builder.coalesce(root.get("description"), "")), pattern, '\\')));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static ProductStatus visibleStatus(CatalogAccess access, ProductSearch search) {
        if (canSeeNonActive(access, search)) {
            return search.status();
        }
        if (search.status() != null && search.status() != ProductStatus.ACTIVE) {
            return null;
        }
        return ProductStatus.ACTIVE;
    }

    private static boolean canSeeNonActive(CatalogAccess access, ProductSearch search) {
        return access.admin() || (search.mine() && access.seller());
    }

    private void evictAfterCommit(UUID productId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evictProduct(productId.toString());
            cache.evictLists();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evictProduct(productId.toString());
                cache.evictLists();
            }
        });
    }

    private void catalogEvent(String eventType, Product product) {
        outbox.add(eventType, "catalog", product.getId().toString(), product.getId(), Map.of(
                "name", product.getName(),
                "sku", product.getSku(),
                "price", product.getPrice().toPlainString(),
                "currency", product.getCurrency(),
                "status", product.getStatus().name()), product.getUpdatedAt());
    }

    private Product editable(Jwt jwt, UUID id) {
        CatalogAccess access = CatalogAccess.from(jwt);
        if (!access.seller()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a seller can change a product.");
        }
        Product product = products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product was not found."));
        if (!access.admin() && !product.getSellerId().equals(access.subject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change this product.");
        }
        return product;
    }

    private Category category(UUID id) {
        return categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId is invalid"));
    }

    private static void validatePage(ProductSearch search) {
        if (search.page() < 0 || search.size() < 1 || search.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is invalid");
        }
    }

    private static BigDecimal money(BigDecimal price) {
        try {
            return price.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price is invalid");
        }
    }

    private static Map<String, String> attributes(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<>();
        }
        if (raw.size() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attributes is invalid");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (key.isEmpty() || key.length() > 40 || value.length() > 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attributes is invalid");
            }
            copy.put(key, value);
        }
        return copy;
    }

    private static List<ProductImage> images(List<ImageRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<ProductImage> images = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            ImageRequest request = requests.get(i);
            String url = request.url().trim();
            if (!(url.startsWith("https://") || url.startsWith("http://"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "images is invalid");
            }
            images.add(new ProductImage(UUID.randomUUID(), url, blankToNull(request.altText()), i));
        }
        return images;
    }

    private static String like(String keyword) {
        return keyword.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
