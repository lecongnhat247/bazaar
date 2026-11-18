using Confluent.Kafka;

namespace Bazaar.Analytics;

/// <summary>
/// Reads order, payment, and catalog facts. A bad payload is skipped.
/// A store failure is retried, then the message is copied to the dead-letter topic.
/// Offsets commit after that. Delivery is at-least-once, not exactly-once.
/// </summary>
public sealed class AnalyticsConsumer : BackgroundService
{
    private readonly IAnalyticsStore _store;
    private readonly string _brokers;
    private readonly ILogger<AnalyticsConsumer> _log;

    public AnalyticsConsumer(IAnalyticsStore store, IConfiguration configuration, ILogger<AnalyticsConsumer> log)
    {
        _store = store;
        _brokers = configuration["KAFKA_BROKERS"] ?? "";
        _log = log;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (_brokers.Length == 0)
        {
            return;
        }
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await Run(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception error)
            {
                _log.LogWarning(error, "Analytics consumer will retry");
                await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken);
            }
        }
    }

    private Task Run(CancellationToken stoppingToken)
    {
        var consumerConfig = new ConsumerConfig
        {
            BootstrapServers = _brokers,
            GroupId = "analytics-service",
            AutoOffsetReset = AutoOffsetReset.Earliest,
            EnableAutoCommit = false,
        };
        using var consumer = new ConsumerBuilder<string, string>(consumerConfig).Build();
        consumer.Subscribe(["bazaar.order.events", "bazaar.payment.events", "bazaar.catalog.events"]);
        using var producer = new ProducerBuilder<string, string>(new ProducerConfig { BootstrapServers = _brokers }).Build();
        while (!stoppingToken.IsCancellationRequested)
        {
            var record = consumer.Consume(TimeSpan.FromSeconds(1));
            if (record is null)
            {
                continue;
            }
            for (var attempt = 0; attempt < 5; attempt++)
            {
                try
                {
                    _store.Apply(record.Message.Value);
                    break;
                }
                catch (Exception error) when (attempt == 4)
                {
                    producer.Produce("bazaar.dlq", new Message<string, string>
                    {
                        Key = record.Message.Key,
                        Value = System.Text.Json.JsonSerializer.Serialize(new
                        {
                            originalTopic = record.Topic,
                            error = error.Message,
                            value = record.Message.Value.Length > 8000 ? record.Message.Value[..8000] : record.Message.Value,
                        }),
                    });
                    producer.Flush(TimeSpan.FromSeconds(5));
                }
                catch (Exception)
                {
                    Thread.Sleep(TimeSpan.FromSeconds(Math.Min(8, Math.Pow(2, attempt))));
                }
            }
            consumer.Commit(record);
        }
        return Task.CompletedTask;
    }
}
