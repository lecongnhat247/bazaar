using System.Text.Json;
using Bazaar.Analytics;

namespace Analytics.Tests;

public class FoldTests
{
    private const string Lamp = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1001";
    private const string Shirt = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d1002";
    private const string Order = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9002";
    private const string Payment = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d9101";
    private const string Campaign = "6f1c2a10-6b3e-4a1d-9c20-0a0b0c0d2001";
    private const string Home = "8f1c2a10-6b3e-4a1d-9c20-0a0b0c0d0002";

    [Fact]
    public void EmptyReportsStayEmpty()
    {
        var fold = new Fold();
        var overview = Json(fold.Overview(false));
        Assert.Equal(0, overview.GetProperty("totalOrders").GetInt32());
        Assert.Equal(0, overview.GetProperty("revenue").GetArrayLength());
        Assert.False(overview.GetProperty("brokerConfigured").GetBoolean());
        var seller = Json(fold.SellerSales("seller-1"));
        Assert.Equal(0, seller.GetProperty("orderCount").GetInt32());
        Assert.False(seller.GetProperty("settlement").GetProperty("available").GetBoolean());
    }

    [Fact]
    public void ConfirmedOrderCountsRevenueUnitsAndCommissionOnce()
    {
        var store = new MemoryStore();
        var body = Confirmed("11111111-1111-4111-8111-111111111111", "2026-11-18T00:00:00Z");
        Assert.Equal("applied", store.Apply(body));
        Assert.Equal("duplicate", store.Apply(body));
        var overview = Json(store.Read(fold => fold.Overview(true)));
        Assert.Equal(1, overview.GetProperty("totalOrders").GetInt32());
        Assert.Equal("44.50", overview.GetProperty("revenue")[0].GetProperty("amount").GetString());
        var seller = Json(store.Read(fold => fold.SellerSales("seller-1")));
        Assert.Equal(1, seller.GetProperty("orderCount").GetInt32());
        Assert.Equal("44.50", seller.GetProperty("sales")[0].GetProperty("gross").GetString());
        Assert.Equal("4.45", seller.GetProperty("sales")[0].GetProperty("commission").GetString());
        Assert.True(seller.GetProperty("sales")[0].GetProperty("commissionComplete").GetBoolean());
        Assert.Equal(0, Json(store.Read(fold => fold.SellerSales("seller-2"))).GetProperty("orderCount").GetInt32());
        var products = Json(store.Read(fold => fold.ProductPerformance()));
        Assert.Equal(Lamp, products.GetProperty("items")[0].GetProperty("productId").GetString());
        Assert.Equal(1, products.GetProperty("items")[0].GetProperty("units").GetInt32());
        Assert.Equal("25.00", products.GetProperty("items")[0].GetProperty("revenue").GetString());
    }

    [Fact]
    public void AnOlderStatusDoesNotRewindAndARefundDoesNotChangeRevenue()
    {
        var fold = new Fold();
        Apply(fold, Confirmed("11111111-1111-4111-8111-111111111111", "2026-11-18T02:00:00Z"));
        Apply(fold, OrderEvent("22222222-2222-4222-8222-222222222222", "order.pending-inventory", "PENDING_INVENTORY", "10.00", "2026-11-18T01:00:00Z"));
        Apply(fold, PaymentEvent("33333333-3333-4333-8333-333333333333", "payment.succeeded", "SUCCEEDED", "2026-11-18T02:00:00Z"));
        Apply(fold, PaymentEvent("44444444-4444-4444-8444-444444444444", "payment.refunded", "REFUNDED", "2026-11-18T03:00:00Z"));
        var overview = Json(fold.Overview(false));
        Assert.Equal("CONFIRMED", overview.GetProperty("ordersByStatus")[0].GetProperty("status").GetString());
        Assert.Equal("44.50", overview.GetProperty("revenue")[0].GetProperty("amount").GetString());
        Assert.Equal("REFUNDED", overview.GetProperty("payments")[0].GetProperty("status").GetString());
    }

    [Fact]
    public void MissingLinesDoNotInventSellerRevenue()
    {
        var fold = new Fold();
        Apply(fold, OrderEvent("11111111-1111-4111-8111-111111111111", "order.confirmed", "CONFIRMED", "25.00", "2026-11-18T00:00:00Z"));
        var overview = Json(fold.Overview(false));
        Assert.Equal(1, overview.GetProperty("confirmedWithoutLines").GetInt32());
        Assert.Equal("25.00", overview.GetProperty("revenue")[0].GetProperty("amount").GetString());
        var seller = Json(fold.SellerSales("seller-1"));
        Assert.Equal(0, seller.GetProperty("orderCount").GetInt32());
        var reconciliation = Json(fold.Reconciliation());
        Assert.Equal(1, reconciliation.GetProperty("confirmedWithoutLines").GetInt32());
    }

    [Fact]
    public void CategoryAndFlashSaleUseOnlyKnownFacts()
    {
        var fold = new Fold();
        Apply(fold, Confirmed("11111111-1111-4111-8111-111111111111", "2026-11-18T00:00:00Z", Campaign));
        Apply(fold, Product("55555555-5555-4555-8555-555555555555", "2026-11-17T00:00:00Z"));
        var categories = Json(fold.Categories());
        Assert.Equal(Home, categories.GetProperty("items")[0].GetProperty("categoryId").GetString());
        var flash = Json(fold.FlashSales());
        Assert.Equal(1, flash.GetProperty("campaigns").GetArrayLength());
        Assert.Contains("campaignId", flash.GetProperty("note").GetString());
    }

    [Fact]
    public void ReplayMatchesArrivalOrder()
    {
        var older = OrderEvent("22222222-2222-4222-8222-222222222222", "order.pending-inventory", "PENDING_INVENTORY", "44.50", "2026-11-18T01:00:00Z");
        var newer = Confirmed("11111111-1111-4111-8111-111111111111", "2026-11-18T02:00:00Z");
        var forward = Fold.Replay([older, newer]);
        var backward = Fold.Replay([newer, older]);
        Assert.Equal("CONFIRMED", Json(forward.Overview(false)).GetProperty("ordersByStatus")[0].GetProperty("status").GetString());
        Assert.Equal("CONFIRMED", Json(backward.Overview(false)).GetProperty("ordersByStatus")[0].GetProperty("status").GetString());
        var store = new MemoryStore();
        store.Apply(newer);
        store.Apply(older);
        Assert.Equal(2, store.Rebuild());
        Assert.Equal("44.50", Json(store.Read(fold => fold.Overview(false))).GetProperty("revenue")[0].GetProperty("amount").GetString());
    }

    [Fact]
    public void DayWeekAndMonthBucketConfirmedSales()
    {
        var fold = new Fold();
        Apply(fold, Confirmed("11111111-1111-4111-8111-111111111111", "2026-11-18T15:00:00Z"));
        Assert.Equal("2026-11-18", Json(fold.Sales("day")).GetProperty("buckets")[0].GetProperty("period").GetString());
        Assert.Equal("2026-11-16", Json(fold.Sales("week")).GetProperty("buckets")[0].GetProperty("period").GetString());
        Assert.Equal("2026-11", Json(fold.Sales("month")).GetProperty("buckets")[0].GetProperty("period").GetString());
    }

    private static void Apply(Fold fold, string json)
    {
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("applied", fold.Apply(doc.RootElement));
    }

    private static JsonElement Json(object value)
    {
        return JsonDocument.Parse(JsonSerializer.Serialize(value)).RootElement.Clone();
    }

    private static string Confirmed(string eventId, string at, string? campaign = null)
    {
        var campaignField = campaign is null ? "" : $",\"campaignId\":\"{campaign}\"";
        return $$"""
        {
          "eventId":"{{eventId}}","eventType":"order.confirmed","eventVersion":1,
          "aggregateId":"{{Order}}","aggregateType":"order","occurredAt":"{{at}}",
          "correlationId":"{{eventId}}","producer":"order-service",
          "payload":{
            "status":"CONFIRMED","currency":"USD","total":"44.50","customerId":"ada"{{campaignField}},
            "items":[
              {"productId":"{{Lamp}}","sellerId":"seller-1","productName":"Lamp","unitPrice":"25.00","quantity":1,"lineTotal":"25.00","discountAmount":"0.00"},
              {"productId":"{{Shirt}}","sellerId":"seller-1","productName":"Shirt","unitPrice":"19.50","quantity":1,"lineTotal":"19.50","discountAmount":"0.00"}
            ],
            "sellers":[{"sellerId":"seller-1","gross":"44.50","discount":"0.00","commission":"4.45","rateBps":1000}]
          }
        }
        """;
    }

    private static string OrderEvent(string eventId, string type, string status, string total, string at) => $$"""
    {
      "eventId":"{{eventId}}","eventType":"{{type}}","eventVersion":1,
      "aggregateId":"{{Order}}","aggregateType":"order","occurredAt":"{{at}}",
      "correlationId":"{{eventId}}","producer":"order-service",
      "payload":{"status":"{{status}}","currency":"USD","total":"{{total}}","customerId":"ada"}
    }
    """;

    private static string PaymentEvent(string eventId, string type, string status, string at, string? id = null) => $$"""
    {
      "eventId":"{{eventId}}","eventType":"{{type}}","eventVersion":1,
      "aggregateId":"{{id ?? Payment}}","aggregateType":"payment","occurredAt":"{{at}}",
      "correlationId":"{{eventId}}","producer":"payment-service",
      "payload":{"orderId":"{{Order}}","customerId":"ada","amount":"44.50","currency":"USD","status":"{{status}}"}
    }
    """;

    private static string Product(string eventId, string at) => $$"""
    {
      "eventId":"{{eventId}}","eventType":"catalog.product-published","eventVersion":1,
      "aggregateId":"{{Lamp}}","aggregateType":"product","occurredAt":"{{at}}",
      "correlationId":"{{eventId}}","producer":"catalog-service",
      "payload":{"name":"Lamp","status":"ACTIVE","categoryId":"{{Home}}","sellerId":"seller-1"}
    }
    """;
}
