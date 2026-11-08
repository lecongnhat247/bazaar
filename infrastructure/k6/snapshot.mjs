// Reads the local Prometheus instance and prints one JSON object.
// Used with the k6 baseline. It does not change the running stack.

const queries = {
  cpu: 'process_cpu_usage',
  heap: 'jvm_memory_used_bytes{area="heap"}',
  hikariActive: 'hikaricp_connections_active',
  hikariPending: 'hikaricp_connections_pending',
  hikariMax: 'hikaricp_connections_max',
  acquireP95: 'histogram_quantile(0.95, sum by (le, job) (rate(hikaricp_connections_acquire_seconds_bucket[8m])))',
  usageSum: 'hikaricp_connections_usage_seconds_sum',
  usageCount: 'hikaricp_connections_usage_seconds_count',
  acquireSum: 'hikaricp_connections_acquire_seconds_sum',
  acquireCount: 'hikaricp_connections_acquire_seconds_count',
  appLag: 'bazaar_kafka_consumer_lag',
  exporterLag: 'sum by (consumergroup) (kafka_consumergroup_lag)',
  cache: 'bazaar_catalog_cache_total',
  cacheLatencySum: 'bazaar_catalog_cache_latency_seconds_sum',
  cacheLatencyCount: 'bazaar_catalog_cache_latency_seconds_count',
  redisUp: 'bazaar_redis_up',
  redisHits: 'redis_keyspace_hits_total',
  redisMisses: 'redis_keyspace_misses_total',
  redisLatency: 'redis_latency_percentiles_usec',
  gatewayRate: 'sum by (routeId, httpStatusCode) (rate(spring_cloud_gateway_requests_seconds_count[8m]))',
  gatewayLatency: 'sum by (routeId) (rate(spring_cloud_gateway_requests_seconds_sum[8m])) / sum by (routeId) (rate(spring_cloud_gateway_requests_seconds_count[8m]))',
  checkout: 'bazaar_checkout_results_total',
  saga: 'bazaar_saga_completed_total',
  reservations: 'bazaar_inventory_reservations_total',
  outboxPending: 'bazaar_outbox_pending',
  deadlocks: 'pg_stat_database_deadlocks',
};

const base = process.env.PROMETHEUS_URL || 'http://localhost:9090';
const out = {};
for (const [name, query] of Object.entries(queries)) {
  const response = await fetch(`${base}/api/v1/query?query=${encodeURIComponent(query)}`);
  const body = await response.json();
  out[name] = (body.data?.result || []).map((row) => ({
    labels: row.metric,
    value: Number(row.value[1]),
  }));
}
console.log(JSON.stringify(out));
