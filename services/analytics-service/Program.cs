using System.Text.Json;
using Bazaar.Analytics;

var builder = WebApplication.CreateBuilder(args);
var issuer = builder.Configuration["KEYCLOAK_ISSUER"] ?? "http://localhost:8088/realms/bazaar";
var hmac = builder.Configuration["JWT_HMAC_SECRET"] ?? "";
var jwks = builder.Configuration["KEYCLOAK_JWKS_URI"] ?? "";
var sql = builder.Configuration["ANALYTICS_SQL"] ?? "";
var brokers = builder.Configuration["KAFKA_BROKERS"] ?? "";
var schema = Path.Combine(AppContext.BaseDirectory, "schema.sql");
if (!File.Exists(schema))
{
    schema = Path.Combine(builder.Environment.ContentRootPath, "schema.sql");
}
builder.Services.AddSingleton<IAnalyticsStore>(_ => sql.Length == 0 ? new MemoryStore() : new SqlStore(sql, schema));
builder.Services.AddHostedService<AnalyticsConsumer>();
builder.Services.AddSingleton(new AnalyticsOptions(issuer, hmac, jwks, brokers.Length > 0));

var app = builder.Build();

app.MapGet("/health/live", () => Results.Ok(new { status = "UP" }));
app.MapGet("/health/ready", (IAnalyticsStore store) => store.Ready()
    ? Results.Ok(new { status = "UP" })
    : Results.Json(new { status = "DOWN" }, statusCode: 503));

app.MapGet("/api/v1/analytics/platform/overview", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
    RequireStaff(request, options) ?? Results.Ok(store.Read(fold => fold.Overview(options.BrokerConfigured))));

app.MapGet("/api/v1/analytics/platform/sales", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
{
    if (RequireStaff(request, options) is { } denied)
    {
        return denied;
    }
    var grain = request.Query["grain"].ToString();
    if (grain is not ("day" or "week" or "month"))
    {
        return Results.BadRequest(new { detail = "grain is invalid" });
    }
    return Results.Ok(store.Read(fold => fold.Sales(grain)));
});

app.MapGet("/api/v1/analytics/platform/reconciliation", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
    RequireStaff(request, options) ?? Results.Ok(store.Read(fold => fold.Reconciliation())));

app.MapGet("/api/v1/analytics/products/performance", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
    RequireStaff(request, options) ?? Results.Ok(store.Read(fold => fold.ProductPerformance())));

app.MapGet("/api/v1/analytics/products/categories", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
    RequireStaff(request, options) ?? Results.Ok(store.Read(fold => fold.Categories())));

app.MapGet("/api/v1/analytics/flash-sales/performance", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
    RequireStaff(request, options) ?? Results.Ok(store.Read(fold => fold.FlashSales())));

app.MapGet("/api/v1/analytics/sellers/me/sales", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
{
    var caller = Optional(request, options);
    if (caller is null)
    {
        return Results.Json(new { detail = "A bearer token is required." }, statusCode: 401);
    }
    if (!caller.Seller && !caller.Staff)
    {
        return Results.Json(new { detail = "You can read only your own sales." }, statusCode: 403);
    }
    return Results.Ok(store.Read(fold => fold.SellerSales(caller.Subject)));
});

app.MapGet("/api/v1/analytics/sellers/{sellerId}/sales", (string sellerId, HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
{
    if (request.Query.ContainsKey("sellerId"))
    {
        return Results.BadRequest(new { detail = "sellerId is not accepted as a query." });
    }
    if (!IsSubject(sellerId))
    {
        return Results.BadRequest(new { detail = "sellerId is invalid" });
    }
    var caller = Optional(request, options);
    if (caller is null)
    {
        return Results.Unauthorized();
    }
    if (!caller.Staff && (!caller.Seller || caller.Subject != sellerId))
    {
        return Results.Json(new { detail = "You can read only your own sales." }, statusCode: 403);
    }
    return Results.Ok(store.Read(fold => fold.SellerSales(sellerId)));
});

app.MapPost("/api/v1/analytics/rebuild", (HttpRequest request, IAnalyticsStore store, AnalyticsOptions options) =>
{
    var caller = Optional(request, options);
    if (caller is null)
    {
        return Results.Unauthorized();
    }
    if (!caller.Admin)
    {
        return Results.Json(new { detail = "Only an administrator can rebuild analytics." }, statusCode: 403);
    }
    return Results.Ok(new { events = store.Rebuild() });
});

app.Run();

static bool IsSubject(string value) => value.Length is > 0 and <= 64 && value.All(ch => char.IsAsciiLetterOrDigit(ch) || ch is '.' or '_' or ':' or '-');

static Caller? Optional(HttpRequest request, AnalyticsOptions options)
{
    try
    {
        return Tokens.Read(request.Headers.Authorization.ToString(), options.Issuer, options.HmacSecret, options.JwksUri);
    }
    catch (AuthError)
    {
        return null;
    }
}

static IResult? RequireStaff(HttpRequest request, AnalyticsOptions options)
{
    var caller = Optional(request, options);
    if (caller is null)
    {
        return Results.Json(new { detail = "Staff reports require an administrator or an operator." }, statusCode: 401);
    }
    if (!caller.Staff)
    {
        return Results.Json(new { detail = "Staff reports require an administrator or an operator." }, statusCode: 403);
    }
    return null;
}

public sealed record AnalyticsOptions(string Issuer, string HmacSecret, string JwksUri, bool BrokerConfigured);

public partial class Program;
