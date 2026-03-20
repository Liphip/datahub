# Connectors and Alerts

## Connectors

Connectors represent external systems and credentials for data movement.

Best practices:

- use dedicated credentials per environment
- set explicit timeout values
- validate connectivity after credential rotation

## Alerts

Alerting stack consists of:

- channels (where notifications are sent)
- rules (when notifications are triggered)
- delivery history (what happened)

## Alert Design Guidelines

- prefer actionable conditions over noisy low-signal rules
- use severity levels and throttling
- define escalation ownership and on-call schedule

## Delivery Troubleshooting

- `PENDING` too long: queue/processing delay
- `FAILED`: check channel endpoint/credentials
- frequent `RETRY`: investigate transient external failures

## Tips

- Test every channel after infrastructure changes.
- Keep message templates short and include pipeline/tenant identifiers.
