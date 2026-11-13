package com.bazaar.identity.api;

import java.util.List;

public record StaffPage<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
