package com.bazaar.order.checkout;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bazaar.order.market.Allocation;
import com.bazaar.order.market.Coupons;
import com.bazaar.order.market.FulfillmentView;
import com.bazaar.order.market.Fulfillments;
import com.bazaar.order.market.Ledger;
import com.bazaar.order.market.PricedLine;
import com.bazaar.order.outbox.OutboxStore;

@Service
class OrderTransactions {

    private final JdbcTemplate jdbc;
    private final OutboxStore outbox;
    private final CheckoutMetrics metrics;
    private final Coupons coupons;
    private final Fulfillments fulfillments;
    private final Ledger ledger;
    private final String orderTopic;
    private final String inventoryCommands;
    private final String paymentCommands;

    OrderTransactions(
            JdbcTemplate jdbc,
            OutboxStore outbox,
            CheckoutMetrics metrics,
            Coupons coupons,
            Fulfillments fulfillments,
            Ledger ledger,
            @Value("${bazaar.order.events-topic}") String orderTopic,
            @Value("${bazaar.order.inventory-commands-topic}") String inventoryCommands,
            @Value("${bazaar.order.payment-commands-topic}") String paymentCommands) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.metrics = metrics;
        this.coupons = coupons;
        this.fulfillments = fulfillments;
        this.ledger = ledger;
        this.orderTopic = orderTopic;
        this.inventoryCommands = inventoryCommands;
        this.paymentCommands = paymentCommands;
    }

    @Transactional(readOnly = true)
    public OrderRow findByKey(String key) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM customer_order WHERE idempotency_key = ?",
                (rs, row) -> rs.getObject("id", UUID.class),
                key);
        return ids.isEmpty() ? null : required(ids.getFirst());
    }

    @Transactional
    public OrderRow insert(
            OrderAccess access,
            String key,
            String hash,
            String outcome,
            String currency,
            BigDecimal total,
            List<Line> lines,
            Instant now,
            UUID campaignId,
            String couponCode) {
        UUID id = UUID.randomUUID();
        // The server prices the coupon; the browser only ever supplies a code.
        Allocation allocation = coupons.apply(couponCode, access.subject(), currency,
                lines.stream().map(line -> new PricedLine(line.sellerId(), line.lineTotal())).toList(),
                campaignId != null, now);
        BigDecimal payable = total.subtract(allocation.total());
        jdbc.update("""
                INSERT INTO customer_order (
                    id, customer_id, status, currency, total_amount, outcome,
                    idempotency_key, request_hash, payment_state, created_at, updated_at, campaign_id,
                    discount_total, coupon_code)
                VALUES (?, ?, 'PENDING_INVENTORY', ?, ?, ?, ?, ?, 'NONE', ?, ?, ?, ?, ?)
                """, id, access.subject(), currency, payable, outcome, key, hash,
                Timestamp.from(now), Timestamp.from(now), campaignId, allocation.total(), allocation.code());
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            jdbc.update("""
                    INSERT INTO order_item (
                        id, order_id, product_id, seller_id, sku, product_name,
                        unit_price, quantity, line_total, reservation_id, discount_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), id, line.productId(), line.sellerId(), line.sku(), line.name(),
                    line.unitPrice(), line.quantity(), line.lineTotal(), line.reservationId(),
                    allocation.perLine().get(index));
        }
        coupons.redeem(id, access.subject(), allocation, now);
        history(id, "PENDING_INVENTORY", 1, now);
        fact(id, "order.pending-inventory", "PENDING_INVENTORY", now);
        command(inventoryCommands, "inventory.reservation-requested", id.toString(), id,
                reservationCommand(id, access.subject(), campaignId, lines), now);
        return required(id);
    }

    @Transactional
    public OrderRow savePayment(UUID orderId, UUID paymentId, Instant now) {
        jdbc.update("""
                UPDATE customer_order
                SET payment_id = ?, updated_at = ?
                WHERE id = ? AND payment_id IS NULL
                """, paymentId, Timestamp.from(now), orderId);
        return required(orderId);
    }

    @Transactional
    public OrderRow mark(UUID orderId, String status, Instant now) {
        int changed = jdbc.update("""
                UPDATE customer_order
                SET status = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING_PAYMENT'
                """, status, Timestamp.from(now), orderId);
        if (changed == 1) {
            Integer seq = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) + 1 FROM order_status_history WHERE order_id = ?",
                    Integer.class,
                    orderId);
            history(orderId, status, seq == null ? 1 : seq, now);
            String type = eventType(status);
            if (type != null) {
                fact(orderId, type, status, now);
            }
            settle(orderId, status, now);
        }
        OrderRow latest = required(orderId);
        if (!status.equals(latest.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order can no longer change.");
        }
        return latest;
    }

    @Transactional(readOnly = true)
    public OrderRow get(OrderAccess access, UUID id) {
        OrderRow row = required(id);
        if (!row.customerId().equals(access.subject()) && !access.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot read this order.");
        }
        return row;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list(OrderAccess access) {
        String sql = access.admin()
                ? """
                SELECT id, customer_id, status, currency, total_amount, payment_id, created_at, updated_at
                FROM customer_order
                ORDER BY created_at DESC
                LIMIT 20
                """
                : """
                SELECT id, customer_id, status, currency, total_amount, payment_id, created_at, updated_at
                FROM customer_order
                WHERE customer_id = ?
                ORDER BY created_at DESC
                LIMIT 20
                """;
        List<ListedOrder> rows = access.admin()
                ? jdbc.query(sql, this::listed)
                : jdbc.query(sql, this::listed, access.subject());
        if (rows.isEmpty()) {
            return List.of();
        }
        String marks = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(ListedOrder::id).toArray();
        Map<UUID, List<ItemResponse>> items = new HashMap<>();
        jdbc.query("""
                SELECT order_id, product_id, seller_id, sku, product_name, unit_price, quantity, line_total,
                       reservation_id, discount_amount
                FROM order_item
                WHERE order_id IN (%s)
                ORDER BY order_id, product_id
                """.formatted(marks), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID orderId = rs.getObject("order_id", UUID.class);
                    items.computeIfAbsent(orderId, ignored -> new ArrayList<>()).add(item(rs));
                }, ids);
        Map<UUID, Discount> discounts = discounts(rows.stream().map(ListedOrder::id).toList());
        Map<UUID, List<FulfillmentView>> groups = fulfillments.forOrders(rows.stream().map(ListedOrder::id).toList(), null);
        Map<UUID, List<HistoryResponse>> history = new HashMap<>();
        jdbc.query("""
                SELECT order_id, status, created_at
                FROM order_status_history
                WHERE order_id IN (%s)
                ORDER BY order_id, seq
                """.formatted(marks), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID orderId = rs.getObject("order_id", UUID.class);
                    history.computeIfAbsent(orderId, ignored -> new ArrayList<>()).add(new HistoryResponse(
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant()));
                }, ids);
        List<OrderResponse> orders = new ArrayList<>();
        for (ListedOrder row : rows) {
            orders.add(new OrderResponse(
                    row.id(),
                    row.customerId(),
                    row.status(),
                    row.currency(),
                    row.total(),
                    row.paymentId(),
                    items.getOrDefault(row.id(), List.of()),
                    history.getOrDefault(row.id(), List.of()),
                    row.createdAt(),
                    row.updatedAt(),
                    discounts.getOrDefault(row.id(), Discount.NONE).total(),
                    discounts.getOrDefault(row.id(), Discount.NONE).code(),
                    Fulfillments.summary(groups.get(row.id())),
                    groups.getOrDefault(row.id(), List.of())));
        }
        return orders;
    }

    private static final Set<String> SALE_STATUSES = Set.of(
            "PENDING_INVENTORY",
            "PENDING_PAYMENT",
            "PENDING_INVENTORY_COMMIT",
            "COMPENSATING",
            "CONFIRMED",
            "PAYMENT_FAILED",
            "CANCELLED",
            "FAILED");

    static boolean saleStatus(String status) {
        return SALE_STATUSES.contains(status);
    }

    @Transactional(readOnly = true)
    public SellerSalesPage sales(OrderAccess access, int page, int size, String status, String query) {
        SaleFilter filter = saleFilter(access.subject(), status, query);
        Object[] filterArgs = filter.args().toArray();
        long totalOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_order " + filter.sql(),
                Long.class,
                filterArgs);
        long pendingOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_order " + filter.sql()
                        + " AND customer_order.status IN ('PENDING_INVENTORY', 'PENDING_PAYMENT', 'PENDING_INVENTORY_COMMIT', 'COMPENSATING')",
                Long.class,
                filterArgs);
        SaleTotal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(item.line_total), 0),
                       COUNT(DISTINCT BTRIM(customer_order.currency)),
                       MIN(BTRIM(customer_order.currency))
                FROM order_item item
                JOIN customer_order ON customer_order.id = item.order_id
                """ + filter.sql() + " AND item.seller_id = ?",
                (rs, row) -> new SaleTotal(rs.getBigDecimal(1), rs.getInt(2), rs.getString(3)),
                filter.with(access.subject()));
        List<Object> pageArgs = new ArrayList<>(filter.args());
        pageArgs.add(size);
        pageArgs.add(page * (long) size);
        List<ListedOrder> rows = jdbc.query("""
                SELECT id, customer_id, status, currency, total_amount, payment_id, created_at, updated_at
                FROM customer_order
                """ + filter.sql() + """
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, this::listed, pageArgs.toArray());
        Map<UUID, List<ItemResponse>> items = sellerItems(rows, access.subject());
        Map<UUID, List<HistoryResponse>> history = history(rows);
        Map<UUID, List<FulfillmentView>> groups = fulfillments.forOrders(
                rows.stream().map(ListedOrder::id).toList(), access.subject());
        List<SellerSale> orders = new ArrayList<>();
        for (ListedOrder row : rows) {
            List<ItemResponse> lines = items.getOrDefault(row.id(), List.of());
            BigDecimal sellerTotal = lines.stream().map(ItemResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sellerDiscount = lines.stream().map(ItemResponse::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<FulfillmentView> own = groups.getOrDefault(row.id(), List.of());
            orders.add(new SellerSale(
                    row.id(),
                    row.customerId(),
                    row.status(),
                    row.currency(),
                    sellerTotal,
                    lines,
                    history.getOrDefault(row.id(), List.of()),
                    row.createdAt(),
                    row.updatedAt(),
                    sellerDiscount,
                    own.isEmpty() ? null : own.getFirst()));
        }
        int totalPages = totalOrders == 0 ? 0 : (int) ((totalOrders + size - 1) / size);
        BigDecimal sellerTotal = total.currencies() == 1 ? total.amount() : (totalOrders == 0 ? BigDecimal.ZERO : null);
        String currency = total.currencies() == 1 ? total.currency() : null;
        return new SellerSalesPage(orders, page, size, totalOrders, totalPages, sellerTotal, currency, pendingOrders);
    }

    @Transactional(readOnly = true)
    public StaffOrderPage monitor(int page, int size, String query) {
        String where = "";
        List<Object> args = new ArrayList<>();
        if (query != null) {
            String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where = " WHERE id::text ILIKE ? ESCAPE '\\' OR status ILIKE ? ESCAPE '\\' OR customer_id ILIKE ? ESCAPE '\\'";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * (long) size);
        List<ListedOrder> rows = jdbc.query("""
                SELECT id, customer_id, status, currency, total_amount, payment_id, created_at, updated_at
                FROM customer_order
                """ + where + """
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, this::listed, pageArgs.toArray());
        List<OrderResponse> items = new ArrayList<>();
        for (ListedOrder row : rows) {
            items.add(response(new OrderRow(
                    row.id(),
                    row.customerId(),
                    row.status(),
                    row.currency(),
                    row.total(),
                    null,
                    null,
                    row.paymentId(),
                    List.of(),
                    null,
                    null,
                    0,
                    row.createdAt(),
                    row.updatedAt(),
                    null)));
        }
        int pages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new StaffOrderPage(items, page, size, total, pages);
    }

    private Map<UUID, List<ItemResponse>> sellerItems(List<ListedOrder> rows, String sellerId) {
        Map<UUID, List<ItemResponse>> items = new HashMap<>();
        if (rows.isEmpty()) {
            return items;
        }
        String marks = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        List<Object> args = new ArrayList<>();
        rows.forEach(row -> args.add(row.id()));
        args.add(sellerId);
        jdbc.query("""
                SELECT order_id, product_id, seller_id, sku, product_name, unit_price, quantity, line_total,
                       reservation_id, discount_amount
                FROM order_item
                WHERE order_id IN (%s) AND seller_id = ?
                ORDER BY order_id, product_id
                """.formatted(marks), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID orderId = rs.getObject("order_id", UUID.class);
                    items.computeIfAbsent(orderId, ignored -> new ArrayList<>()).add(item(rs));
                }, args.toArray());
        return items;
    }

    private Map<UUID, List<HistoryResponse>> history(List<ListedOrder> rows) {
        Map<UUID, List<HistoryResponse>> history = new HashMap<>();
        if (rows.isEmpty()) {
            return history;
        }
        String marks = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(ListedOrder::id).toArray();
        jdbc.query("""
                SELECT order_id, status, created_at
                FROM order_status_history
                WHERE order_id IN (%s)
                ORDER BY order_id, seq
                """.formatted(marks), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    UUID orderId = rs.getObject("order_id", UUID.class);
                    history.computeIfAbsent(orderId, ignored -> new ArrayList<>()).add(new HistoryResponse(
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant()));
                }, ids);
        return history;
    }

    private static ItemResponse item(ResultSet rs) throws SQLException {
        return new ItemResponse(
                rs.getObject("product_id", UUID.class),
                rs.getString("seller_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getBigDecimal("unit_price"),
                rs.getInt("quantity"),
                rs.getBigDecimal("line_total"),
                rs.getObject("reservation_id", UUID.class),
                rs.getBigDecimal("discount_amount"));
    }

    private Map<UUID, Discount> discounts(List<UUID> orderIds) {
        Map<UUID, Discount> result = new HashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        String marks = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        jdbc.query("SELECT id, discount_total, coupon_code FROM customer_order WHERE id IN (" + marks + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(
                        rs.getObject("id", UUID.class),
                        new Discount(rs.getBigDecimal("discount_total"), rs.getString("coupon_code"))),
                orderIds.toArray());
        return result;
    }

    private record Discount(BigDecimal total, String code) {
        static final Discount NONE = new Discount(BigDecimal.ZERO.setScale(2), null);
    }

    private static SaleFilter saleFilter(String sellerId, String status, String query) {
        StringBuilder sql = new StringBuilder("""
                WHERE EXISTS (
                    SELECT 1 FROM order_item mine
                    WHERE mine.order_id = customer_order.id AND mine.seller_id = ?
                )
                """);
        List<Object> args = new ArrayList<>();
        args.add(sellerId);
        if (status != null) {
            sql.append(" AND customer_order.status = ?");
            args.add(status);
        }
        if (query != null) {
            String pattern = like(query);
            sql.append("""
                     AND (
                         LOWER(CAST(customer_order.id AS text)) LIKE ? ESCAPE '\\'
                         OR EXISTS (
                             SELECT 1 FROM order_item named
                             WHERE named.order_id = customer_order.id
                               AND named.seller_id = ?
                               AND (LOWER(named.product_name) LIKE ? ESCAPE '\\'
                                 OR LOWER(named.sku) LIKE ? ESCAPE '\\')
                         )
                     )
                    """);
            args.add(pattern);
            args.add(sellerId);
            args.add(pattern);
            args.add(pattern);
        }
        return new SaleFilter(sql.toString(), args);
    }

    private static String like(String value) {
        return "%" + value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private record SaleFilter(String sql, List<Object> args) {
        Object[] with(Object extra) {
            List<Object> copy = new ArrayList<>(args);
            copy.add(extra);
            return copy.toArray();
        }
    }

    private record SaleTotal(BigDecimal amount, int currencies, String currency) {
    }

    private ListedOrder listed(ResultSet rs, int row) throws SQLException {
        return new ListedOrder(
                rs.getObject("id", UUID.class),
                rs.getString("customer_id"),
                rs.getString("status"),
                rs.getString("currency").trim(),
                rs.getBigDecimal("total_amount"),
                rs.getObject("payment_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    public OrderResponse response(OrderRow row) {
        List<ItemResponse> items = jdbc.query("""
                SELECT product_id, seller_id, sku, product_name, unit_price, quantity, line_total, reservation_id,
                       discount_amount
                FROM order_item
                WHERE order_id = ?
                ORDER BY product_id
                """, (rs, n) -> item(rs), row.id());
        Discount discount = discounts(List.of(row.id())).getOrDefault(row.id(), Discount.NONE);
        List<FulfillmentView> groups = fulfillments.forOrders(List.of(row.id()), null)
                .getOrDefault(row.id(), List.of());
        List<HistoryResponse> history = jdbc.query("""
                SELECT status, created_at
                FROM order_status_history
                WHERE order_id = ?
                ORDER BY seq
                """, (rs, n) -> new HistoryResponse(rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                row.id());
        return new OrderResponse(
                row.id(),
                row.customerId(),
                row.status(),
                row.currency(),
                row.total(),
                row.paymentId(),
                items,
                history,
                row.createdAt(),
                row.updatedAt(),
                discount.total(),
                discount.code(),
                Fulfillments.summary(groups),
                groups);
    }

    private OrderRow required(UUID id) {
        List<OrderRow> rows = jdbc.query("""
                SELECT id, customer_id, status, currency, total_amount, outcome, request_hash, payment_id,
                       compensate_to, payment_state, saga_attempt, created_at, updated_at, campaign_id
                FROM customer_order
                WHERE id = ?
                """, (rs, n) -> new OrderRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("customer_id"),
                        rs.getString("status"),
                        rs.getString("currency").trim(),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("outcome"),
                        rs.getString("request_hash").trim(),
                        rs.getObject("payment_id", UUID.class),
                        List.of(),
                        rs.getString("compensate_to"),
                        rs.getString("payment_state"),
                        rs.getInt("saga_attempt"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("campaign_id", UUID.class)),
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order was not found.");
        }
        OrderRow row = rows.getFirst();
        return new OrderRow(
                row.id(),
                row.customerId(),
                row.status(),
                row.currency(),
                row.total(),
                row.outcome(),
                row.requestHash(),
                row.paymentId(),
                itemsOf(row.id()),
                row.compensateTo(),
                row.paymentState(),
                row.sagaAttempt(),
                row.createdAt(),
                row.updatedAt(),
                row.campaignId());
    }

    private List<Line> itemsOf(UUID orderId) {
        return jdbc.query("""
                SELECT product_id, seller_id, sku, product_name, unit_price, quantity, line_total, reservation_id,
                       stock_state, discount_amount
                FROM order_item
                WHERE order_id = ?
                ORDER BY product_id
                """, (rs, n) -> new Line(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("seller_id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("unit_price"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("line_total"),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getString("stock_state"),
                        rs.getBigDecimal("discount_amount")),
                orderId);
    }

    @Transactional
    public OrderRow load(UUID id) {
        return required(id);
    }

    @Transactional
    public void onReserved(UUID orderId, UUID productId, UUID reservationId, Instant now) {
        OrderRow order = required(orderId);
        int attached = jdbc.update("""
                UPDATE order_item
                SET reservation_id = ?, stock_state = 'HELD'
                WHERE order_id = ? AND product_id = ? AND reservation_id IS NULL
                """, reservationId, orderId, productId);
        if (attached == 1 && "PENDING_INVENTORY".equals(order.status()) && allInState(orderId, "HELD")) {
            if (transitioned(orderId, "PENDING_INVENTORY", "PENDING_PAYMENT", now)) {
                requestPayment(required(orderId), now);
            }
            return;
        }
        if (attached == 0 && releasesLateReservation(order.status())) {
            releaseReservation(order, reservationId, now);
        }
    }

    @Transactional
    public void onReservationFailed(UUID orderId, Instant now) {
        OrderRow order = required(orderId);
        if (!"PENDING_INVENTORY".equals(order.status())) {
            return;
        }
        if (heldReservations(order).isEmpty()) {
            transitioned(orderId, "PENDING_INVENTORY", "FAILED", now);
            return;
        }
        compensate(order, "PENDING_INVENTORY", "FAILED", now);
    }

    @Transactional
    public void onPaymentSucceeded(UUID orderId, UUID paymentId, Instant now) {
        OrderRow order = required(orderId);
        if ("PENDING_PAYMENT".equals(order.status())) {
            notePayment(orderId, paymentId, "SUCCEEDED", now);
            if (transitioned(orderId, "PENDING_PAYMENT", "PENDING_INVENTORY_COMMIT", now)) {
                for (UUID reservationId : heldReservations(required(orderId))) {
                    commitReservation(required(orderId), reservationId, now);
                }
            }
            return;
        }
        if ("COMPENSATING".equals(order.status()) || "CANCELLED".equals(order.status())) {
            notePayment(orderId, paymentId, "SUCCEEDED", now);
            refundPayment(required(orderId), now);
        }
    }

    @Transactional
    public void onPaymentFailed(UUID orderId, UUID paymentId, Instant now) {
        OrderRow order = required(orderId);
        notePayment(orderId, paymentId, "FAILED", now);
        if ("PENDING_PAYMENT".equals(order.status())) {
            compensate(required(orderId), "PENDING_PAYMENT", "PAYMENT_FAILED", now);
            return;
        }
        finishCompensation(orderId, now);
    }

    @Transactional
    public void onPaymentRefunded(UUID orderId, Instant now) {
        notePayment(orderId, null, "REFUNDED", now);
        finishCompensation(orderId, now);
    }

    @Transactional
    public void onCommitted(UUID reservationId, Instant now) {
        UUID orderId = orderByReservation(reservationId);
        if (orderId == null) {
            return;
        }
        jdbc.update("UPDATE order_item SET stock_state = 'COMMITTED' WHERE reservation_id = ?", reservationId);
        if ("PENDING_INVENTORY_COMMIT".equals(required(orderId).status()) && allInState(orderId, "COMMITTED")) {
            transitioned(orderId, "PENDING_INVENTORY_COMMIT", "CONFIRMED", now);
        }
    }

    @Transactional
    public void onReleased(UUID reservationId, Instant now) {
        UUID orderId = orderByReservation(reservationId);
        if (orderId == null) {
            return;
        }
        jdbc.update("UPDATE order_item SET stock_state = 'RELEASED' WHERE reservation_id = ?", reservationId);
        finishCompensation(orderId, now);
    }

    @Transactional
    public void onCommitFailed(UUID orderId, UUID reservationId, Instant now) {
        OrderRow order = required(orderId);
        if (!"PENDING_INVENTORY_COMMIT".equals(order.status())) {
            return;
        }
        java.util.List<Integer> attempts = jdbc.query(
                "UPDATE customer_order SET saga_attempt = saga_attempt + 1, updated_at = ? WHERE id = ? RETURNING saga_attempt",
                (rs, row) -> rs.getInt(1),
                Timestamp.from(now),
                orderId);
        int attempt = attempts.isEmpty() ? 3 : attempts.getFirst();
        if (attempt < 3) {
            commitReservation(order, reservationId, now);
            return;
        }
        compensate(required(orderId), "PENDING_INVENTORY_COMMIT", "FAILED", now);
        refundPayment(required(orderId), now);
    }

    @Transactional
    public OrderRow cancel(OrderRow order, Instant now) {
        if ("CANCELLED".equals(order.status())) {
            return order;
        }
        if (!"PENDING_INVENTORY".equals(order.status()) && !"PENDING_PAYMENT".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order can no longer be cancelled.");
        }
        compensate(order, order.status(), "CANCELLED", now);
        return required(order.id());
    }

    @Transactional
    public void recover(Instant resendBefore, Instant giveUpBefore) {
        for (UUID id : olderThan(giveUpBefore)) {
            giveUp(required(id), giveUpBefore);
        }
        for (UUID id : olderThan(resendBefore)) {
            OrderRow order = required(id);
            if (order.updatedAt().isBefore(giveUpBefore)) {
                continue;
            }
            resend(order, resendBefore);
        }
    }

    private void giveUp(OrderRow order, Instant now) {
        switch (order.status()) {
            case "PENDING_INVENTORY" -> onReservationFailed(order.id(), now);
            case "PENDING_PAYMENT" -> compensate(order, "PENDING_PAYMENT", "PAYMENT_FAILED", now);
            case "PENDING_INVENTORY_COMMIT" -> {
                jdbc.update("UPDATE customer_order SET saga_attempt = 3, updated_at = ? WHERE id = ?", Timestamp.from(now), order.id());
                compensate(required(order.id()), "PENDING_INVENTORY_COMMIT", "FAILED", now);
                refundPayment(required(order.id()), now);
            }
            case "COMPENSATING" -> resend(order, now);
            default -> {
            }
        }
    }

    private void resend(OrderRow order, Instant now) {
        switch (order.status()) {
            case "PENDING_INVENTORY" -> {
                if (!inFlight(order.id().toString(), "inventory.reservation-requested")) {
                    command(inventoryCommands, "inventory.reservation-requested", order.id().toString(), order.id(),
                            reservationCommand(order.id(), order.customerId(), order.campaignId(), order.lines()), now);
                }
            }
            case "PENDING_PAYMENT" -> requestPayment(order, now);
            case "PENDING_INVENTORY_COMMIT" -> {
                for (UUID reservationId : heldReservations(order)) {
                    commitReservation(order, reservationId, now);
                }
            }
            case "COMPENSATING" -> {
                for (UUID reservationId : heldReservations(order)) {
                    releaseReservation(order, reservationId, now);
                }
                if ("SUCCEEDED".equals(order.paymentState())) {
                    refundPayment(order, now);
                }
            }
            default -> {
            }
        }
    }

    private void requestPayment(OrderRow order, Instant now) {
        if (inFlight(order.id().toString(), "payment.requested")) {
            return;
        }
        command(paymentCommands, "payment.requested", order.id().toString(), order.id(), java.util.Map.of(
                "orderId", order.id().toString(),
                "customerId", order.customerId(),
                "amount", order.total().toPlainString(),
                "currency", order.currency(),
                "outcome", order.outcome()), now);
    }

    private void commitReservation(OrderRow order, UUID reservationId, Instant now) {
        if (inFlight(reservationId.toString(), "inventory.commit-requested")) {
            return;
        }
        command(inventoryCommands, "inventory.commit-requested", reservationId.toString(), order.id(), java.util.Map.of(
                "orderId", order.id().toString(),
                "reservationId", reservationId.toString(),
                "customerId", order.customerId()), now);
    }

    private void releaseReservation(OrderRow order, UUID reservationId, Instant now) {
        if (inFlight(reservationId.toString(), "inventory.release-requested")) {
            return;
        }
        command(inventoryCommands, "inventory.release-requested", reservationId.toString(), order.id(), java.util.Map.of(
                "orderId", order.id().toString(),
                "reservationId", reservationId.toString(),
                "customerId", order.customerId()), now);
    }

    private void refundPayment(OrderRow order, Instant now) {
        if (order.paymentId() == null || inFlight(order.paymentId().toString(), "payment.refund-requested")) {
            return;
        }
        if ("REFUNDED".equals(order.paymentState()) || "REFUND_REQUESTED".equals(order.paymentState())) {
            return;
        }
        notePayment(order.id(), order.paymentId(), "REFUND_REQUESTED", now);
        command(paymentCommands, "payment.refund-requested", order.paymentId().toString(), order.id(), java.util.Map.of(
                "orderId", order.id().toString(),
                "paymentId", order.paymentId().toString(),
                "customerId", order.customerId()), now);
    }

    private void compensate(OrderRow order, String from, String target, Instant now) {
        int changed = jdbc.update("""
                UPDATE customer_order
                SET status = 'COMPENSATING', compensate_to = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """, target, Timestamp.from(now), order.id(), from);
        if (changed != 1) {
            finishCompensation(order.id(), now);
            return;
        }
        history(order.id(), "COMPENSATING", nextSeq(order.id()), now);
        OrderRow current = required(order.id());
        for (UUID reservationId : heldReservations(current)) {
            releaseReservation(current, reservationId, now);
        }
        if ("SUCCEEDED".equals(current.paymentState())) {
            refundPayment(current, now);
        }
        finishCompensation(order.id(), now);
    }

    private void finishCompensation(UUID orderId, Instant now) {
        OrderRow order = required(orderId);
        if (!"COMPENSATING".equals(order.status()) || order.compensateTo() == null) {
            return;
        }
        if (!released(orderId)) {
            return;
        }
        if ("FAILED".equals(order.compensateTo()) && !"REFUNDED".equals(order.paymentState()) && order.paymentId() != null
                && "SUCCEEDED".equals(order.paymentState())) {
            return;
        }
        if ("REFUND_REQUESTED".equals(order.paymentState())) {
            return;
        }
        transitioned(orderId, "COMPENSATING", order.compensateTo(), now);
    }

    private boolean transitioned(UUID orderId, String from, String to, Instant now) {
        int changed = jdbc.update("""
                UPDATE customer_order
                SET status = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """, to, Timestamp.from(now), orderId, from);
        if (changed != 1) {
            return false;
        }
        history(orderId, to, nextSeq(orderId), now);
        metrics.complete(to);
        String type = eventType(to);
        if (type != null) {
            fact(orderId, type, to, now);
        }
        settle(orderId, to, now);
        return true;
    }

    /** Marketplace side effects of a final order status, in the same transaction. */
    private void settle(UUID orderId, String status, Instant now) {
        switch (status) {
            case "CONFIRMED" -> {
                fulfillments.open(orderId, now);
                ledger.recordSale(orderId, now);
            }
            case "CANCELLED", "FAILED", "PAYMENT_FAILED" -> coupons.release(orderId, now);
            default -> {
            }
        }
    }

    private void notePayment(UUID orderId, UUID paymentId, String state, Instant now) {
        if (paymentId == null) {
            jdbc.update("""
                    UPDATE customer_order
                    SET payment_state = ?, updated_at = ?
                    WHERE id = ?
                    """, state, Timestamp.from(now), orderId);
            return;
        }
        jdbc.update("""
                UPDATE customer_order
                SET payment_id = COALESCE(payment_id, ?), payment_state = ?, updated_at = ?
                WHERE id = ?
                """, paymentId, state, Timestamp.from(now), orderId);
    }

    private boolean released(UUID orderId) {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM order_item
                WHERE order_id = ? AND reservation_id IS NOT NULL AND (stock_state IS NULL OR stock_state <> 'RELEASED')
                """, Integer.class, orderId);
        return open == null || open == 0;
    }

    private boolean allInState(UUID orderId, String state) {
        Integer waiting = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM order_item
                WHERE order_id = ? AND (stock_state IS NULL OR stock_state <> ?)
                """, Integer.class, orderId, state);
        return waiting == null || waiting == 0;
    }

    private java.util.List<UUID> heldReservations(OrderRow order) {
        return order.lines().stream()
                .filter(line -> line.reservationId() != null && "HELD".equals(line.stockState()))
                .map(Line::reservationId)
                .toList();
    }

    private UUID orderByReservation(UUID reservationId) {
        java.util.List<UUID> ids = jdbc.query(
                "SELECT order_id FROM order_item WHERE reservation_id = ?",
                (rs, row) -> rs.getObject("order_id", UUID.class),
                reservationId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private java.util.List<UUID> olderThan(Instant updatedBefore) {
        return jdbc.query("""
                SELECT id FROM customer_order
                WHERE status IN ('PENDING_INVENTORY', 'PENDING_PAYMENT', 'PENDING_INVENTORY_COMMIT', 'COMPENSATING')
                  AND updated_at < ?
                """, (rs, row) -> rs.getObject("id", UUID.class), Timestamp.from(updatedBefore));
    }

    private boolean inFlight(String aggregateId, String eventType) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox
                WHERE aggregate_id = ? AND event_type = ? AND status IN ('PENDING', 'SENDING')
                """, Integer.class, aggregateId, eventType);
        return count != null && count > 0;
    }

    private boolean releasesLateReservation(String status) {
        return "CANCELLED".equals(status) || "FAILED".equals(status) || "PAYMENT_FAILED".equals(status)
                || "COMPENSATING".equals(status) || "CONFIRMED".equals(status);
    }

    private int nextSeq(UUID orderId) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM order_status_history WHERE order_id = ?",
                Integer.class,
                orderId);
        return seq == null ? 1 : seq;
    }

    private void fact(UUID orderId, String eventType, String status, Instant now) {
        outbox.add(eventType, "order", orderId.toString(), orderId, java.util.Map.of(
                "status", status,
                "currency", latestCurrency(orderId),
                "total", latestTotal(orderId).toPlainString(),
                "customerId", latestCustomer(orderId)), now, orderTopic);
    }

    private static Map<String, Object> reservationCommand(UUID orderId, String customerId, UUID campaignId, List<Line> lines) {
        Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("orderId", orderId.toString());
        command.put("customerId", customerId);
        if (campaignId != null) {
            command.put("campaignId", campaignId.toString());
        }
        command.put("items", lines.stream().map(line -> Map.of(
                "productId", line.productId().toString(),
                "quantity", line.quantity())).toList());
        return command;
    }

    private void command(String topic, String eventType, String aggregateId, UUID correlationId, Object payload, Instant now) {
        outbox.add(eventType, "command", aggregateId, correlationId, payload, now, topic);
    }

    private static String eventType(String status) {
        return switch (status) {
            case "CONFIRMED" -> "order.confirmed";
            case "PAYMENT_FAILED" -> "order.payment-failed";
            case "CANCELLED" -> "order.cancelled";
            case "FAILED" -> "order.failed";
            default -> null;
        };
    }

    private String latestCustomer(UUID orderId) {
        return jdbc.queryForObject("SELECT customer_id FROM customer_order WHERE id = ?", String.class, orderId);
    }

    private String latestCurrency(UUID orderId) {
        return jdbc.queryForObject("SELECT currency FROM customer_order WHERE id = ?", String.class, orderId).trim();
    }

    private BigDecimal latestTotal(UUID orderId) {
        return jdbc.queryForObject("SELECT total_amount FROM customer_order WHERE id = ?", BigDecimal.class, orderId);
    }

    private void history(UUID orderId, String status, int seq, Instant now) {
        jdbc.update("""
                INSERT INTO order_status_history (id, order_id, status, seq, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, status, seq, Timestamp.from(now));
    }

    record Line(
            UUID productId,
            String sellerId,
            String sku,
            String name,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal,
            UUID reservationId,
            String stockState,
            BigDecimal discount) {
    }

    private record ListedOrder(
            UUID id,
            String customerId,
            String status,
            String currency,
            BigDecimal total,
            UUID paymentId,
            Instant createdAt,
            Instant updatedAt) {
    }

    record OrderRow(
            UUID id,
            String customerId,
            String status,
            String currency,
            BigDecimal total,
            String outcome,
            String requestHash,
            UUID paymentId,
            List<Line> lines,
            String compensateTo,
            String paymentState,
            int sagaAttempt,
            Instant createdAt,
            Instant updatedAt,
            UUID campaignId) {
    }
}
