using System.Text.Json;
using Microsoft.Data.SqlClient;

namespace Bazaar.Analytics;

public interface IAnalyticsStore
{
    string Apply(string json);
    T Read<T>(Func<Fold, T> read);
    int Rebuild();
    bool Ready();
}

public sealed class MemoryStore : IAnalyticsStore
{
    private readonly Lock _lock = new();
    private readonly List<string> _events = [];
    private Fold _fold = new();

    public string Apply(string json)
    {
        lock (_lock)
        {
            using var doc = JsonDocument.Parse(json);
            var result = _fold.Apply(doc.RootElement);
            if (result == "applied")
            {
                _events.Add(json);
            }
            return result;
        }
    }

    public T Read<T>(Func<Fold, T> read)
    {
        lock (_lock)
        {
            return read(_fold);
        }
    }

    public int Rebuild()
    {
        lock (_lock)
        {
            _fold = Fold.Replay(_events);
            return _events.Count;
        }
    }

    public bool Ready() => true;
}

public sealed class SqlStore : IAnalyticsStore
{
    private readonly Lock _lock = new();
    private readonly string _connectionString;
    private readonly string _schema;
    private Fold _fold = new();

    public SqlStore(string connectionString, string schemaPath)
    {
        _connectionString = connectionString;
        _schema = File.ReadAllText(schemaPath);
        using var connection = Open();
        using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                IF DB_ID(N'analytics_db') IS NULL
                    CREATE DATABASE analytics_db;
                """;
            command.ExecuteNonQuery();
        }
        var builder = new SqlConnectionStringBuilder(_connectionString) { InitialCatalog = "analytics_db" };
        _connectionString = builder.ConnectionString;
        using var db = Open();
        using var setup = db.CreateCommand();
        setup.CommandText = _schema;
        setup.ExecuteNonQuery();
        _fold = Fold.Replay(Load(db));
        WriteProjection(db, null);
    }

    public string Apply(string json)
    {
        lock (_lock)
        {
            using var doc = JsonDocument.Parse(json);
            var result = _fold.Apply(doc.RootElement);
            if (result != "applied")
            {
                return result;
            }
            try
            {
                using var connection = Open();
                using var tx = connection.BeginTransaction();
                using var insert = connection.CreateCommand();
                insert.Transaction = tx;
                insert.CommandText = """
                    INSERT INTO dbo.analytics_event (event_id, occurred_at, body)
                    VALUES (@id, @at, @body);
                    """;
                var root = doc.RootElement;
                insert.Parameters.AddWithValue("@id", Guid.Parse(root.GetProperty("eventId").GetString()!));
                insert.Parameters.AddWithValue("@at", DateTimeOffset.Parse(root.GetProperty("occurredAt").GetString()!));
                insert.Parameters.AddWithValue("@body", json);
                insert.ExecuteNonQuery();
                WriteProjection(connection, tx);
                tx.Commit();
            }
            catch
            {
                using var connection = Open();
                _fold = Fold.Replay(Load(connection));
                throw;
            }
            return "applied";
        }
    }

    public T Read<T>(Func<Fold, T> read)
    {
        lock (_lock)
        {
            return read(_fold);
        }
    }

    public int Rebuild()
    {
        lock (_lock)
        {
            using var connection = Open();
            var bodies = Load(connection);
            _fold = Fold.Replay(bodies);
            WriteProjection(connection, null);
            return bodies.Count;
        }
    }

    public bool Ready()
    {
        try
        {
            using var connection = Open();
            using var command = connection.CreateCommand();
            command.CommandText = "SELECT 1";
            command.ExecuteScalar();
            return true;
        }
        catch
        {
            return false;
        }
    }

    private SqlConnection Open()
    {
        var connection = new SqlConnection(_connectionString);
        connection.Open();
        return connection;
    }

    private static List<string> Load(SqlConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT body FROM dbo.analytics_event ORDER BY occurred_at, event_id";
        using var reader = command.ExecuteReader();
        var bodies = new List<string>();
        while (reader.Read())
        {
            bodies.Add(reader.GetString(0));
        }
        return bodies;
    }

    private void WriteProjection(SqlConnection connection, SqlTransaction? tx)
    {
        Execute(connection, tx, "DELETE FROM dbo.fact_order_item; DELETE FROM dbo.fact_seller_amount; DELETE FROM dbo.fact_payment; DELETE FROM dbo.fact_order; DELETE FROM dbo.dim_product;");
        foreach (var order in _fold.Orders.Values)
        {
            Execute(connection, tx, """
                INSERT INTO dbo.fact_order (order_id, status, currency, total, customer_id, campaign_id, occurred_at)
                VALUES (@id, @status, @currency, @total, @customer, @campaign, @at)
                """, ("id", Guid.Parse(order.OrderId)), ("status", order.Status), ("currency", order.Currency), ("total", order.Total), ("customer", order.CustomerId), ("campaign", order.CampaignId is null ? DBNull.Value : (object)Guid.Parse(order.CampaignId)), ("at", order.OccurredAt));
        }
        foreach (var line in _fold.Lines.Values)
        {
            Execute(connection, tx, """
                INSERT INTO dbo.fact_order_item (order_id, product_id, seller_id, product_name, quantity, unit_price, line_total, discount_amount)
                VALUES (@orderId, @productId, @sellerId, @name, @quantity, @unit, @line, @discount)
                """, ("orderId", Guid.Parse(line.OrderId)), ("productId", Guid.Parse(line.ProductId)), ("sellerId", line.SellerId), ("name", (object?)line.ProductName ?? DBNull.Value), ("quantity", line.Quantity), ("unit", (object?)line.UnitPrice ?? DBNull.Value), ("line", (object?)line.LineTotal ?? DBNull.Value), ("discount", line.DiscountAmount));
        }
        foreach (var payment in _fold.Payments.Values)
        {
            Execute(connection, tx, """
                INSERT INTO dbo.fact_payment (payment_id, order_id, customer_id, amount, currency, status, occurred_at)
                VALUES (@id, @orderId, @customer, @amount, @currency, @status, @at)
                """, ("id", Guid.Parse(payment.PaymentId)), ("orderId", Guid.Parse(payment.OrderId)), ("customer", payment.CustomerId), ("amount", payment.Amount), ("currency", payment.Currency), ("status", payment.Status), ("at", payment.OccurredAt));
        }
        foreach (var seller in _fold.Sellers.Values)
        {
            Execute(connection, tx, """
                INSERT INTO dbo.fact_seller_amount (order_id, seller_id, gross, discount, commission, rate_bps)
                VALUES (@orderId, @sellerId, @gross, @discount, @commission, @rate)
                """, ("orderId", Guid.Parse(seller.OrderId)), ("sellerId", seller.SellerId), ("gross", seller.Gross), ("discount", seller.Discount), ("commission", seller.Commission), ("rate", (object?)seller.RateBps ?? DBNull.Value));
        }
        foreach (var product in _fold.Products.Values)
        {
            Execute(connection, tx, """
                INSERT INTO dbo.dim_product (product_id, name, category_id, seller_id, status, updated_at)
                VALUES (@id, @name, @category, @seller, @status, @at)
                """, ("id", Guid.Parse(product.ProductId)), ("name", product.Name), ("category", product.CategoryId is null ? DBNull.Value : (object)Guid.Parse(product.CategoryId)), ("seller", (object?)product.SellerId ?? DBNull.Value), ("status", product.Status), ("at", product.UpdatedAt));
        }
    }

    private static void Execute(SqlConnection connection, SqlTransaction? tx, string sql, params (string Name, object Value)[] parameters)
    {
        using var command = connection.CreateCommand();
        command.Transaction = tx;
        command.CommandText = sql;
        foreach (var (name, value) in parameters)
        {
            command.Parameters.AddWithValue("@" + name, value);
        }
        command.ExecuteNonQuery();
    }
}
