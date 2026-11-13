package com.bazaar.order.market;

import java.math.BigDecimal;
import java.util.List;

/** Result of a coupon evaluation: the discount per input line (same order) and its funder. */
public record Allocation(String code, String fundedBy, BigDecimal total, List<BigDecimal> perLine) {

    public static Allocation none(int lines) {
        return new Allocation(null, null, BigDecimal.ZERO.setScale(2),
                java.util.Collections.nCopies(lines, BigDecimal.ZERO.setScale(2)));
    }

    public boolean applied() {
        return code != null;
    }
}
