using System.IdentityModel.Tokens.Jwt;
using System.Text;
using System.Text.Json;
using Microsoft.IdentityModel.Tokens;

namespace Bazaar.Analytics;

public sealed record Caller(string Subject, IReadOnlySet<string> Roles)
{
    public bool Admin => Roles.Contains("ADMIN");
    public bool Operator => Roles.Contains("OPERATOR");
    public bool Seller => Roles.Contains("SELLER");
    public bool Staff => Admin || Operator;
}

public sealed class AuthError : Exception
{
    public AuthError(string message) : base(message)
    {
    }
}

public static class Tokens
{
    public static Caller? Read(string? authorization, string issuer, string hmacSecret, string jwksUri)
    {
        if (string.IsNullOrWhiteSpace(authorization))
        {
            return null;
        }
        if (!authorization.StartsWith("Bearer ", StringComparison.Ordinal))
        {
            throw new AuthError("A bearer token is required.");
        }
        var token = authorization["Bearer ".Length..].Trim();
        if (token.Length == 0)
        {
            throw new AuthError("A bearer token is required.");
        }
        var parameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = issuer,
            ValidateAudience = false,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromSeconds(30),
            NameClaimType = "sub",
        };
        if (hmacSecret.Length > 0)
        {
            parameters.IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(hmacSecret));
            parameters.ValidAlgorithms = ["HS256"];
        }
        else
        {
            parameters.IssuerSigningKeys = FetchKeys(jwksUri);
            parameters.ValidAlgorithms = ["RS256"];
        }
        var handler = new JwtSecurityTokenHandler { MapInboundClaims = false };
        try
        {
            var principal = handler.ValidateToken(token, parameters, out var validated);
            var subject = principal.FindFirst("sub")?.Value;
            if (string.IsNullOrWhiteSpace(subject) || subject.Length > 64)
            {
                throw new AuthError("The token has no subject.");
            }
            return new Caller(subject, Roles((JwtSecurityToken)validated));
        }
        catch (AuthError)
        {
            throw;
        }
        catch (Exception)
        {
            throw new AuthError("The token was rejected.");
        }
    }

    private static IReadOnlySet<string> Roles(JwtSecurityToken token)
    {
        if (!token.Payload.TryGetValue("realm_access", out var realm))
        {
            return new HashSet<string>();
        }
        var json = realm as string ?? JsonSerializer.Serialize(realm);
        using var doc = JsonDocument.Parse(json);
        if (!doc.RootElement.TryGetProperty("roles", out var roles) || roles.ValueKind != JsonValueKind.Array)
        {
            return new HashSet<string>();
        }
        return roles.EnumerateArray().Where(role => role.ValueKind == JsonValueKind.String).Select(role => role.GetString()!).ToHashSet();
    }

    private static IList<SecurityKey> FetchKeys(string jwksUri)
    {
        if (jwksUri.Length == 0)
        {
            throw new AuthError("The token was rejected.");
        }
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
        var json = client.GetStringAsync(jwksUri).GetAwaiter().GetResult();
        return new JsonWebKeySet(json).GetSigningKeys();
    }
}
