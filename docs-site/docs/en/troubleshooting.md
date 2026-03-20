# Troubleshooting

## Fast triage sequence

1. validate tenant context
2. validate permissions
3. validate health endpoints
4. inspect run details
5. correlate metrics and logs

## Common issue matrix

| Symptom | Most likely cause | First action |
|---|---|---|
| Empty Data Explorer | wrong tenant/time range/permission | verify tenant and filter window |
| Pipeline immediate fail | connector auth/config error | inspect first failing stage log |
| Dashboard no data | datasource/query label mismatch | check datasource and metric labels |
| Slow but successful runs | resource saturation | inspect CPU/memory and queue backlog |

## Escalate when

- failures spread across multiple tenants
- data integrity risk is possible
- rollback does not reduce impact
