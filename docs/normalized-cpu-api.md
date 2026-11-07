# Normalized process CPU telemetry

`GET /api/telemetry` includes these additive fields. Existing fields keep their types and one-CPU percentage semantics. No new endpoint or authentication policy is introduced.

| Field | JSON type | Meaning |
| --- | --- | --- |
| `app_cpu_current_normalized_percent` | number or null | Current one-CPU percentage divided by the sampled logical CPU count. |
| `app_cpu_recent_average_normalized_percent` | number or null | Recent raw average divided by the same count. |
| `app_cpu_logical_count` | integer or null | Last sampled `Runtime.availableProcessors()` count; invalid counts are null. |
| `app_cpu_normalized_stale` | boolean | Whether the underlying sample is stale at query time. |
| `app_cpu_normalized_state` | string | `collecting`, `unavailable`, `available`, or `stale`. A partial reading may be available while one value is null; check each value. |

Percentages are numeric and have no percent sign or stale suffix. For example, raw usage of 200% with eight available logical CPUs yields 25%. This is CPU-time normalization, not frequency-weighted hardware capacity. Values are not clamped. Both current and recent average use the last sampled CPU count; the average is not historically capacity-weighted if CPU availability changes.

Query the existing endpoint using the configured server address and port, without opening the viewer or MJPEG stream:

```sh
curl 'http://SERVER:PORT/api/telemetry'
```

These changes are on the PR branch, not released or merged. Browser rendering and Android integration tests remain pending.
