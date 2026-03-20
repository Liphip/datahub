# Pipelines

## Pipeline lifecycle

1. Design stages and dependencies.
2. Configure source and destination modules.
3. Trigger test run.
4. Inspect run details and metrics.
5. Roll out with monitoring.

## Reliability tips

- monitor 1h/24h/7d success trends
- investigate latency drift early
- keep rollback target ready

## When failures happen

- identify first failing run timestamp
- compare module/connector changes
- correlate logs and metrics
- mitigate quickly to reduce blast radius
