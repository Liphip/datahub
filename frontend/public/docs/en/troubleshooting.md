# Troubleshooting

## Fast Triage Flow

1. Confirm user context (tenant + role).
2. Confirm platform health endpoints.
3. Check recent deployments/config changes.
4. Correlate metrics and logs by time.
5. Apply rollback/mitigation if blast radius grows.

## Common Issues

### No Data in UI

- wrong tenant selected
- missing read permission
- table has no rows in selected time range

### Pipeline Starts but Fails Quickly

- connector authentication expired
- module dependency/runtime mismatch
- upstream service timeout increase

### Dashboard Panels Empty

- datasource UID mismatch
- query label dimensions not present in runtime metrics
- time window outside available data retention

### High Latency, Low Error Rate

- saturation before hard failures
- queue buildup or broker lag
- downstream throttling

## Evidence Collection for Escalation

- exact timestamp and timezone
- tenant ID, pipeline/module IDs
- relevant Grafana screenshot/query
- copied diagnostics payload
- exact error response or log snippet
