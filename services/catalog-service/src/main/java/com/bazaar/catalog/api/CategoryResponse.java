package com.bazaar.catalog.api;

import java.util.UUID;

public record CategoryResponse(UUID id, String name, String slug) {
}
