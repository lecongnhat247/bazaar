using System.Globalization;
using System.Text.Json;

namespace Bazaar.Analytics;

/// <summary>
/// Folds Kafka facts into a reporting model. A duplicate event id is ignored.
/// An older fact does not rewind a newer order, payment, or product snapshot.
/// Money is taken only from fields the producer sent. A missing commission is
/// unavailable, not zero. This is at-least-once and eventually consistent.
/// </summary>
public sealed class Fold
{
    public Dictionary<string, OrderFact> Orders { get; } = new();
    public Dictionary<string, LineFact> Lines { get; } = new();
    public Dictionary<string, PaymentFact> Payments { get; } = new();
    public Dictionary<string, SellerFact> Sellers { get; } = new();
    public Dictionary<string, ProductFact> Products { get; } = new();
    public HashSet<string> Seen { get; } = new();
    public DateTimeOffset? LastSuccess { get; set; }
    public int Applied { get; set; }

    public static Fold Replay(IEnumerable<string> bodies)
    {
        var fold = new Fold();
        var parsed = new List<(DateTimeOffset At, string Id, JsonElement Event, JsonDocument Doc)>();
        foreach (var body in bodies)
        {
            var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            var at = ReadTime(root, "occurredAt") ?? DateTimeOffset.MinValue;
            var id = ReadString(root, "eventId") ?? "";
            parsed.Add((at, id, root.Clone(), doc));
        }
        foreach (var item in parsed.OrderBy(item => item.At).ThenBy(item => item.Id, StringComparer.Ordinal))
        {
            fold.Apply(item.Event);
            item.Doc.Dispose();
        }
        return fold;
    }

    public string Apply(JsonElement root)
    {
        var id = ReadString(root, "eventId");
        if (!IsUuid(id))
        {
            return "invalid";
        }
        if (!Seen.Add(id!))
        {
            return "duplicate";
        }
        var version = root.TryGetProperty("eventVersion", out var versionNode) && versionNode.TryGetInt32(out var parsed) ? parsed : 0;
        var type = ReadString(root, "eventType");
        var at = ReadTime(root, "occurredAt");
        if (version < 1 || type is null || at is null)
        {
            Seen.Remove(id!);
            return "invalid";
        }
        var payload = root.TryGetProperty("payload", out var payloadNode) && payloadNode.ValueKind == JsonValueKind.Object
            ? payloadNode
            : default;
        var applied = type switch
        {
            "order.pending-inventory" or "order.confirmed" or "order.cancelled" or "order.payment-failed" or "order.failed"
                => ApplyOrder(root, payload, type, at.Value),
            "payment.succeeded" or "payment.failed" or "payment.cancelled" or "payment.refunded"
                => ApplyPayment(root, payload, type, at.Value),
            "catalog.product-published" or "catalog.product-updated" or "catalog.product-unpublished"
                => ApplyProduct(root, payload, at.Value),
            _ => false,
        };
        if (!applied && type is "order.pending-inventory" or "order.confirmed" or "order.cancelled" or "order.payment-failed" or "order.failed"
            or "payment.succeeded" or "payment.failed" or "payment.cancelled" or "payment.refunded"
            or "catalog.product-published" or "catalog.product-updated" or "catalog.product-unpublished")
        {
            Seen.Remove(id!);
            return "invalid";
        }
        if (!applied)
        {
            Seen.Remove(id!);
            return "ignored";
        }
        Applied += 1;
        LastSuccess = DateTimeOffset.UtcNow;
        return "applied";
    }

    private bool ApplyOrder(JsonElement root, JsonElement payload, string type, DateTimeOffset at)
    {
        var orderId = ReadString(root, "aggregateId");
        var status = ReadString(payload, "status");
        var currency = ReadString(payload, "currency");
        var customer = ReadString(payload, "customerId");
        if (!IsUuid(orderId) || status is null || !IsMoney(ReadString(payload, "total"), out var total) || currency is not { Length: 3 } || customer is not { Length: > 0 and <= 64 })
        {
            return false;
        }
        if (!Orders.TryGetValue(orderId!, out var current) || at >= current.OccurredAt)
        {
            Orders[orderId!] = new OrderFact(orderId!, status, currency.ToUpperInvariant(), total, customer, ReadUuid(payload, "campaignId"), at);
        }
        if (type != "order.confirmed" || (current is not null && at < current.OccurredAt))
        {
            return true;
        }
        ReplaceLines(orderId!, payload);
        ReplaceSellers(orderId!, payload);
        return true;
    }

    private void ReplaceLines(string orderId, JsonElement payload)
    {
        foreach (var key in Lines.Keys.Where(key => key.StartsWith(orderId + "|", StringComparison.Ordinal)).ToList())
        {
            Lines.Remove(key);
        }
        if (!payload.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array)
        {
            return;
        }
        foreach (var item in items.EnumerateArray())
        {
            var productId = ReadString(item, "productId");
            var sellerId = ReadString(item, "sellerId");
            if (!IsUuid(productId) || sellerId is not { Length: > 0 and <= 64 } || !item.TryGetProperty("quantity", out var quantityNode) || !quantityNode.TryGetInt32(out var quantity) || quantity < 1 || quantity > 10_000)
            {
                continue;
            }
            IsMoney(ReadString(item, "unitPrice"), out var unit);
            var hasLine = IsMoney(ReadString(item, "lineTotal"), out var line);
            IsMoney(ReadString(item, "discountAmount"), out var discount);
            Lines[orderId + "|" + productId] = new LineFact(orderId, productId!, sellerId, ReadString(item, "productName"), quantity, ReadString(item, "unitPrice") is null ? null : unit, hasLine ? line : null, ReadString(item, "discountAmount") is null ? 0 : discount);
        }
    }

    private void ReplaceSellers(string orderId, JsonElement payload)
    {
        foreach (var key in Sellers.Keys.Where(key => key.StartsWith(orderId + "|", StringComparison.Ordinal)).ToList())
        {
            Sellers.Remove(key);
        }
        if (!payload.TryGetProperty("sellers", out var sellers) || sellers.ValueKind != JsonValueKind.Array)
        {
            return;
        }
        foreach (var seller in sellers.EnumerateArray())
        {
            var sellerId = ReadString(seller, "sellerId");
            if (sellerId is not { Length: > 0 and <= 64 } || !IsMoney(ReadString(seller, "gross"), out var gross) || !IsMoney(ReadString(seller, "discount"), out var discount) || !IsMoney(ReadString(seller, "commission"), out var commission))
            {
                continue;
            }
            int? rate = seller.TryGetProperty("rateBps", out var rateNode) && rateNode.TryGetInt32(out var parsed) ? parsed : null;
            Sellers[orderId + "|" + sellerId] = new SellerFact(orderId, sellerId, gross, discount, commission, rate);
        }
    }

    private bool ApplyPayment(JsonElement root, JsonElement payload, string type, DateTimeOffset at)
    {
        var paymentId = ReadString(root, "aggregateId");
        var orderId = ReadString(payload, "orderId");
        var customer = ReadString(payload, "customerId");
        var currency = ReadString(payload, "currency");
        var status = type == "payment.refunded" ? "REFUNDED" : ReadString(payload, "status");
        if (!IsUuid(paymentId) || !IsUuid(orderId) || customer is not { Length: > 0 and <= 64 } || currency is not { Length: 3 } || status is null || !IsMoney(ReadString(payload, "amount"), out var amount))
        {
            return false;
        }
        if (Payments.TryGetValue(paymentId!, out var current) && at < current.OccurredAt)
        {
            return true;
        }
        Payments[paymentId!] = new PaymentFact(paymentId!, orderId!, customer, amount, currency.ToUpperInvariant(), status, at);
        return true;
    }

    private bool ApplyProduct(JsonElement root, JsonElement payload, DateTimeOffset at)
    {
        var productId = ReadString(root, "aggregateId");
        var name = ReadString(payload, "name");
        var status = ReadString(payload, "status");
        if (!IsUuid(productId) || name is not { Length: > 0 and <= 160 } || status is not { Length: > 0 and <= 32 })
        {
            return false;
        }
        if (Products.TryGetValue(productId!, out var current) && at < current.UpdatedAt)
        {
            return true;
        }
        Products[productId!] = new ProductFact(productId!, name.Trim(), ReadUuid(payload, "categoryId"), ReadString(payload, "sellerId"), status, at);
        return true;
    }

    public object Overview(bool brokerConfigured)
    {
        var revenue = Orders.Values.Where(order => order.Status == "CONFIRMED")
            .GroupBy(order => order.Currency)
            .Select(group => new { currency = group.Key, amount = Money(group.Sum(order => order.Total)) })
            .OrderBy(row => row.currency)
            .ToArray();
        var byStatus = Orders.Values.GroupBy(order => order.Status)
            .Select(group => new { status = group.Key, count = group.Count() })
            .OrderBy(row => row.status)
            .ToArray();
        var payments = Payments.Values.GroupBy(payment => (payment.Status, payment.Currency))
            .Select(group => new { status = group.Key.Status, currency = group.Key.Currency, count = group.Count(), amount = Money(group.Sum(payment => payment.Amount)) })
            .OrderBy(row => row.status).ThenBy(row => row.currency)
            .ToArray();
        var missing = Orders.Values.Count(order => order.Status == "CONFIRMED" && !Lines.Values.Any(line => line.OrderId == order.OrderId));
        return new
        {
            asOf = LastSuccess,
            eventuallyConsistent = true,
            brokerConfigured,
            totalOrders = Orders.Count,
            revenue,
            ordersByStatus = byStatus,
            payments,
            confirmedWithoutLines = missing,
        };
    }

    public object Sales(string grain)
    {
        var buckets = Orders.Values.Where(order => order.Status == "CONFIRMED")
            .GroupBy(order => (Period: Period(order.OccurredAt, grain), order.Currency))
            .Select(group => new
            {
                period = group.Key.Period,
                currency = group.Key.Currency,
                orders = group.Count(),
                revenue = Money(group.Sum(order => order.Total)),
            })
            .OrderBy(row => row.period).ThenBy(row => row.currency)
            .ToArray();
        return new { asOf = LastSuccess, grain, buckets };
    }

    public object SellerSales(string sellerId)
    {
        var orderIds = Lines.Values.Where(line => line.SellerId == sellerId).Select(line => line.OrderId)
            .Concat(Sellers.Values.Where(row => row.SellerId == sellerId).Select(row => row.OrderId))
            .ToHashSet();
        var confirmed = orderIds.Where(id => Orders.TryGetValue(id, out var order) && order.Status == "CONFIRMED").ToHashSet();
        var sales = confirmed.Select(id => Orders[id])
            .GroupBy(order => order.Currency)
            .Select(group =>
            {
                var ids = group.Select(order => order.OrderId).ToHashSet();
                var known = Sellers.Values.Where(row => row.SellerId == sellerId && ids.Contains(row.OrderId)).ToList();
                return new
                {
                    currency = group.Key,
                    orders = group.Count(),
                    gross = known.Count == 0 ? null : Money(known.Sum(row => row.Gross)),
                    discount = known.Count == 0 ? null : Money(known.Sum(row => row.Discount)),
                    net = known.Count == 0 ? null : Money(known.Sum(row => row.Gross - row.Discount)),
                    commission = known.Count == 0 ? null : Money(known.Sum(row => row.Commission)),
                    commissionComplete = known.Count == ids.Count,
                };
            })
            .OrderBy(row => row.currency)
            .ToArray();
        var products = Lines.Values.Where(line => line.SellerId == sellerId && confirmed.Contains(line.OrderId) && Orders.ContainsKey(line.OrderId))
            .GroupBy(line => (line.ProductId, Orders[line.OrderId].Currency))
            .Select(group =>
            {
                Products.TryGetValue(group.Key.ProductId, out var dim);
                var priced = group.Where(line => line.LineTotal is not null).ToList();
                return new
                {
                    productId = group.Key.ProductId,
                    name = dim?.Name ?? group.Select(line => line.ProductName).FirstOrDefault(name => name is not null),
                    currency = group.Key.Currency,
                    units = group.Sum(line => line.Quantity),
                    revenue = priced.Count == 0 ? null : Money(priced.Sum(line => line.LineTotal!.Value - line.DiscountAmount)),
                    revenueComplete = priced.Count == group.Count(),
                };
            })
            .OrderByDescending(row => row.units).ThenBy(row => row.productId).ThenBy(row => row.currency)
            .ToArray();
        return new
        {
            asOf = LastSuccess,
            sellerId,
            orderCount = confirmed.Count,
            sales,
            products,
            settlement = new { available = false, reason = "Settlement is not published on the event stream." },
        };
    }

    public object ProductPerformance()
    {
        var items = Lines.Values
            .Where(line => Orders.TryGetValue(line.OrderId, out var order) && order.Status == "CONFIRMED")
            .GroupBy(line => (line.ProductId, Orders[line.OrderId].Currency))
            .Select(group =>
            {
                Products.TryGetValue(group.Key.ProductId, out var dim);
                var priced = group.Where(line => line.LineTotal is not null).ToList();
                return new
                {
                    productId = group.Key.ProductId,
                    name = dim?.Name ?? group.Select(line => line.ProductName).FirstOrDefault(name => name is not null),
                    categoryId = dim?.CategoryId,
                    currency = group.Key.Currency,
                    units = group.Sum(line => line.Quantity),
                    revenue = priced.Count == 0 ? null : Money(priced.Sum(line => line.LineTotal!.Value - line.DiscountAmount)),
                    revenueComplete = priced.Count == group.Count(),
                };
            })
            .OrderByDescending(row => row.units).ThenBy(row => row.productId).ThenBy(row => row.currency)
            .ToArray();
        return new { asOf = LastSuccess, items };
    }

    public object Categories()
    {
        var items = Lines.Values
            .Where(line => Orders.TryGetValue(line.OrderId, out var order) && order.Status == "CONFIRMED")
            .GroupBy(line =>
            {
                Products.TryGetValue(line.ProductId, out var dim);
                return dim?.CategoryId ?? "unknown";
            })
            .Select(group => new
            {
                categoryId = group.Key,
                units = group.Sum(line => line.Quantity),
                products = group.Select(line => line.ProductId).Distinct().Count(),
            })
            .OrderByDescending(row => row.units).ThenBy(row => row.categoryId)
            .ToArray();
        return new { asOf = LastSuccess, items };
    }

    public object FlashSales()
    {
        var campaigns = Orders.Values.Where(order => order.Status == "CONFIRMED" && order.CampaignId is not null)
            .GroupBy(order => (order.CampaignId, order.Currency))
            .Select(group => new
            {
                campaignId = group.Key.CampaignId,
                currency = group.Key.Currency,
                orders = group.Count(),
                revenue = Money(group.Sum(order => order.Total)),
            })
            .OrderBy(row => row.campaignId)
            .ToArray();
        return new
        {
            asOf = LastSuccess,
            note = "No flash-sale-completed event is published. These figures are confirmed orders that carried a campaignId.",
            campaigns,
        };
    }

    public object Reconciliation()
    {
        var missing = 0;
        var uncheckedLines = 0;
        var mismatches = 0;
        foreach (var order in Orders.Values.Where(order => order.Status == "CONFIRMED"))
        {
            var lines = Lines.Values.Where(line => line.OrderId == order.OrderId).ToList();
            if (lines.Count == 0)
            {
                missing += 1;
                continue;
            }
            if (lines.Any(line => line.LineTotal is null))
            {
                uncheckedLines += 1;
                continue;
            }
            var net = lines.Sum(line => line.LineTotal!.Value - line.DiscountAmount);
            if (net != order.Total)
            {
                mismatches += 1;
            }
        }
        var paymentMismatches = Payments.Values.Count(payment =>
            payment.Status == "SUCCEEDED"
            && Orders.TryGetValue(payment.OrderId, out var order)
            && order.Currency == payment.Currency
            && order.Total != payment.Amount);
        return new
        {
            asOf = LastSuccess,
            confirmedWithoutLines = missing,
            confirmedWithUnpricedLines = uncheckedLines,
            lineTotalMismatches = mismatches,
            paymentMismatches,
        };
    }

    private static string Period(DateTimeOffset at, string grain)
    {
        var date = at.UtcDateTime.Date;
        if (grain == "month")
        {
            return date.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        }
        if (grain == "week")
        {
            var monday = date.AddDays(-(((int)date.DayOfWeek + 6) % 7));
            return monday.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        }
        return date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
    }

    private static string Money(decimal amount) => amount.ToString("0.00", CultureInfo.InvariantCulture);

    private static bool IsMoney(string? text, out decimal amount)
    {
        amount = 0;
        return text is not null && decimal.TryParse(text, NumberStyles.Number, CultureInfo.InvariantCulture, out amount) && amount >= 0;
    }

    public static bool IsUuid(string? value) => Guid.TryParse(value, out _);

    private static string? ReadString(JsonElement node, string name)
    {
        if (node.ValueKind != JsonValueKind.Object || !node.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
        {
            return null;
        }
        var text = value.GetString();
        return string.IsNullOrWhiteSpace(text) ? null : text.Trim();
    }

    private static string? ReadUuid(JsonElement node, string name)
    {
        var text = ReadString(node, name);
        return IsUuid(text) ? text : null;
    }

    private static DateTimeOffset? ReadTime(JsonElement node, string name)
    {
        var text = ReadString(node, name);
        return text is not null && DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var parsed) ? parsed : null;
    }
}

public sealed record OrderFact(string OrderId, string Status, string Currency, decimal Total, string CustomerId, string? CampaignId, DateTimeOffset OccurredAt);
public sealed record LineFact(string OrderId, string ProductId, string SellerId, string? ProductName, int Quantity, decimal? UnitPrice, decimal? LineTotal, decimal DiscountAmount);
public sealed record PaymentFact(string PaymentId, string OrderId, string CustomerId, decimal Amount, string Currency, string Status, DateTimeOffset OccurredAt);
public sealed record SellerFact(string OrderId, string SellerId, decimal Gross, decimal Discount, decimal Commission, int? RateBps);
public sealed record ProductFact(string ProductId, string Name, string? CategoryId, string? SellerId, string Status, DateTimeOffset UpdatedAt);
