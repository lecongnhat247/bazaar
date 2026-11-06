package com.bazaar.catalog.product;

public enum ProductStatus {
    DRAFT,
    ACTIVE,
    INACTIVE,
    ARCHIVED;

    public boolean canTransitionTo(ProductStatus next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case DRAFT -> next == ACTIVE || next == INACTIVE || next == ARCHIVED;
            case ACTIVE -> next == INACTIVE || next == ARCHIVED;
            case INACTIVE -> next == ACTIVE || next == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
