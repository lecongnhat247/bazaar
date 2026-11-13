package com.bazaar.order.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FulfillmentRequest(
        @NotNull @Pattern(regexp = "PACKED|SHIPPED|DELIVERED|CANCELLED") String status,
        @Size(max = 40) String shipmentReference,
        @Size(max = 200) String note) {
}
