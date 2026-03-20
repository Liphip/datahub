# Connectors and Alerts

## Connectors

Connectors define integrations and credential boundaries.

Best practices:

- separate credentials per environment
- test after secret rotation
- keep timeout values explicit

## Alerts

Alert design should favor signal quality over volume.

Recommended pattern:

1. configure channel
2. define actionable rule
3. test trigger
4. validate delivery history

Delivery states to watch:

- `PENDING`
- `SENT`
- `FAILED`
- `RETRY`
