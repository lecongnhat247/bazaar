using System.IdentityModel.Tokens.Jwt;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.IdentityModel.Tokens;

namespace Analytics.Tests;

public class ApiTests : IClassFixture<WebApplicationFactory<Program>>
{
    private const string Issuer = "http://localhost:8088/realms/bazaar";
    private const string Secret = "test-secret-which-is-long-enough";
    private readonly WebApplicationFactory<Program> _factory;

    public ApiTests(WebApplicationFactory<Program> factory)
    {
        _factory = factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("JWT_HMAC_SECRET", Secret);
            builder.UseSetting("KEYCLOAK_ISSUER", Issuer);
            builder.UseSetting("KAFKA_BROKERS", "");
            builder.UseSetting("ANALYTICS_SQL", "");
        });
    }

    [Fact]
    public async Task StaffCanReadAndASellerCannot()
    {
        var client = _factory.CreateClient();
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/health/ready")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/v1/analytics/platform/overview")).StatusCode);
        var seller = await client.SendAsync(Get("/api/v1/analytics/platform/overview", "seller-1", "SELLER"));
        Assert.Equal(HttpStatusCode.Forbidden, seller.StatusCode);
        var overview = await client.SendAsync(Get("/api/v1/analytics/platform/overview", "operator", "OPERATOR"));
        Assert.Equal(HttpStatusCode.OK, overview.StatusCode);
        var body = JsonDocument.Parse(await overview.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal(0, body.GetProperty("totalOrders").GetInt32());
        Assert.True(body.GetProperty("eventuallyConsistent").GetBoolean());
    }

    [Fact]
    public async Task ASellerReadsOnlyTheirOwnSubject()
    {
        var client = _factory.CreateClient();
        var own = await client.SendAsync(Get("/api/v1/analytics/sellers/seller-1/sales", "seller-1", "SELLER"));
        var other = await client.SendAsync(Get("/api/v1/analytics/sellers/seller-2/sales", "seller-1", "SELLER"));
        var query = await client.SendAsync(Get("/api/v1/analytics/sellers/seller-1/sales?sellerId=seller-2", "seller-1", "SELLER"));
        Assert.Equal(HttpStatusCode.OK, own.StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, other.StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, query.StatusCode);
        var mine = await client.SendAsync(Get("/api/v1/analytics/sellers/me/sales", "seller-1", "SELLER"));
        var mineBody = JsonDocument.Parse(await mine.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal("seller-1", mineBody.GetProperty("sellerId").GetString());
        Assert.False(mineBody.GetProperty("settlement").GetProperty("available").GetBoolean());
    }

    [Fact]
    public async Task OnlyAnAdministratorCanRebuild()
    {
        var client = _factory.CreateClient();
        Assert.Equal(HttpStatusCode.Forbidden, (await client.SendAsync(Post("/api/v1/analytics/rebuild", "operator", "OPERATOR"))).StatusCode);
        var rebuilt = await client.SendAsync(Post("/api/v1/analytics/rebuild", "admin", "ADMIN"));
        Assert.Equal(HttpStatusCode.OK, rebuilt.StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.SendAsync(Get("/api/v1/analytics/platform/sales?grain=hour", "admin", "ADMIN"))).StatusCode);
    }

    private static HttpRequestMessage Get(string url, string subject, string role)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token(subject, role));
        return request;
    }

    private static HttpRequestMessage Post(string url, string subject, string role)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token(subject, role));
        return request;
    }

    private static string Token(string subject, string role)
    {
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(Secret));
        var claims = new[]
        {
            new Claim("sub", subject),
            new Claim("realm_access", JsonSerializer.Serialize(new { roles = new[] { role } }), JsonClaimValueTypes.Json),
        };
        var jwt = new JwtSecurityToken(Issuer, claims: claims, expires: DateTime.UtcNow.AddMinutes(5), signingCredentials: new SigningCredentials(key, SecurityAlgorithms.HmacSha256));
        return new JwtSecurityTokenHandler().WriteToken(jwt);
    }
}
