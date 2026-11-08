package com.bazaar.catalog.cache;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.bazaar.catalog.api.CategoryResponse;
import com.bazaar.catalog.api.PageResponse;
import com.bazaar.catalog.api.ProductResponse;
import com.bazaar.catalog.api.ProductSearch;
import com.bazaar.catalog.product.ProductStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

@Component
public class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);
    private static final String PRODUCT = "bazaar:catalog:v1:product:";
    private static final String LISTS = "bazaar:catalog:v1:lists";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final JavaType productPage;
    private final JavaType categoryPage;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;
    private final Timer reads;

    public CatalogCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            MeterRegistry meters,
            @Value("${bazaar.catalog.cache-ttl:60s}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.hits = meters.counter("bazaar.catalog.cache", "result", "hit");
        this.misses = meters.counter("bazaar.catalog.cache", "result", "miss");
        this.errors = meters.counter("bazaar.catalog.cache", "result", "error");
        this.reads = meters.timer("bazaar.catalog.cache.latency");
        this.productPage = objectMapper.getTypeFactory()
                .constructParametricType(PageResponse.class, ProductResponse.class);
        this.categoryPage = objectMapper.getTypeFactory()
                .constructParametricType(PageResponse.class, CategoryResponse.class);
    }

    public ProductResponse product(UUID id) {
        return read(PRODUCT + id, ProductResponse.class);
    }

    public void putProduct(ProductResponse product) {
        if (product.status() != ProductStatus.ACTIVE) {
            return;
        }
        write(PRODUCT + product.id(), product);
    }

    public void evictProduct(String aggregateId) {
        try {
            redis.delete(PRODUCT + UUID.fromString(aggregateId));
        } catch (IllegalArgumentException exception) {
            log.warn("Catalog cache ignored aggregateId={}", aggregateId);
        } catch (RuntimeException exception) {
            log.warn("Catalog cache product evict skipped: {}", exception.toString());
        }
    }

    public String publicListKey(ProductSearch search) {
        if (search.page() != 0 || search.size() != 20) {
            return null;
        }
        if (!"createdAt".equals(search.sort()) || !"desc".equals(search.direction())) {
            return null;
        }
        if (search.mine() || search.status() != null || search.minPrice() != null || search.maxPrice() != null) {
            return null;
        }
        if (search.keyword() != null && !search.keyword().isBlank()) {
            return null;
        }
        String category = search.categoryId() == null ? "all" : search.categoryId().toString();
        return "bazaar:catalog:v1:list:" + category;
    }

    public PageResponse<ProductResponse> list(String key) {
        return read(key, productPage);
    }

    public void putList(String key, PageResponse<ProductResponse> page) {
        write(key, page);
        try {
            redis.opsForSet().add(LISTS, key);
        } catch (RuntimeException exception) {
            log.warn("Catalog cache list index skipped: {}", exception.toString());
        }
    }

    public void evictLists() {
        try {
            Set<String> keys = redis.opsForSet().members(LISTS);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            redis.delete(LISTS);
        } catch (RuntimeException exception) {
            log.warn("Catalog cache list evict skipped: {}", exception.toString());
        }
    }

    public String categoryKey(int page, int size) {
        if (page != 0 || size != 20) {
            return null;
        }
        return "bazaar:catalog:v1:categories:0:20";
    }

    public PageResponse<CategoryResponse> categories(String key) {
        return read(key, categoryPage);
    }

    public void putCategories(String key, PageResponse<CategoryResponse> page) {
        write(key, page);
    }

    private <T> T read(String key, Class<T> type) {
        String json = readJson(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException exception) {
            errors.increment();
            log.warn("Catalog cache read skipped: {}", exception.toString());
            return null;
        }
    }

    private <T> T read(String key, JavaType type) {
        String json = readJson(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException exception) {
            errors.increment();
            log.warn("Catalog cache read skipped: {}", exception.toString());
            return null;
        }
    }

    private String readJson(String key) {
        long started = System.nanoTime();
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                misses.increment();
            } else {
                hits.increment();
            }
            return json;
        } catch (RuntimeException exception) {
            errors.increment();
            log.warn("Catalog cache read skipped: {}", exception.toString());
            return null;
        } finally {
            reads.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void write(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException exception) {
            log.warn("Catalog cache write skipped: {}", exception.toString());
        }
    }
}
