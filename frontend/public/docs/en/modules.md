# Modules

## Module Types

- IMPORT: data ingestion from external systems
- PROCESSING: transformation and enrichment
- ALERT: threshold/condition evaluation and notifications
- EXPORT: delivery to downstream targets

## Versioning Rules

- Create a new version for every behavior change.
- Use semantic and environment tags (`v1.4.2`, `staging`, `prod`).
- Include intent in description: what changed and why.

## Authoring Workflow

1. Clone latest stable version.
2. Implement change in lower environment.
3. Run affected pipelines in test mode.
4. Review logs and metrics for regressions.
5. Promote to production with changelog note.

## Quality Checklist

- configuration defaults are explicit
- error handling is actionable
- timeouts and retries are bounded
- dependency updates are documented

## Tips

- Keep module descriptions operator-focused, not only developer-focused.
- Export critical modules regularly for disaster recovery.
