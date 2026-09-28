# Pipelines

## Pipeline lifecycle

1. Design stages and dependencies.
2. Configure source and destination modules.
3. Trigger test run.
4. Inspect run details and metrics.
5. Roll out with monitoring.

## Built-in steps

Not every node needs Python. In the node's settings (the selector is labelled **Step**, **Processing** for IMPORT and **Evaluation** for ALERT nodes):

| Node | Step | What it does |
|---|---|---|
| IMPORT | Pass through | Forwards records as received; *Deduplicate by* (e.g. `devEUI, time`) makes repeats harmless |
| PROCESSING | Enrich with metadata | Attaches the metadata object a field refers to (e.g. the meter of a device), as valid at the record's time, and optionally related objects |
| ALERT | Built-in rule | Threshold, rate of change, time window or absence of data, with cooldown |
| EXPORT | Send as is | Delivers the record unchanged through the node's output connector |
| EXPORT | Save to metadata | Creates or updates a metadata object from the record (e.g. a device registry feed) |

A complete example with all of these — readings enriched with meter and location data and
delivered to two systems — is in the developer section (*Example: §14a EnWG*).

## Reliability tips

- monitor 1h/24h/7d success trends
- investigate latency drift early
- keep rollback target ready

## When failures happen

- identify first failing run timestamp
- compare module/connector changes
- correlate logs and metrics
- mitigate quickly to reduce blast radius
