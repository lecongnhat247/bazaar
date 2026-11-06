package com.bazaar.catalog.api;

import java.util.UUID;

public record ImageResponse(UUID id, String url, String altText, int position) {
}
