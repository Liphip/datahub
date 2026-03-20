# FAQ

## Is this docs portal separate from the app UI?

Yes. It runs as a dedicated service.

## Can we access docs with subdomain and route?

Yes. Use `docs.localhost` (or your docs subdomain) and `/docs` fallback route.

## Who should read which pages?

- beginners: Beginner Guide
- admins/operators: Administrator Guide + Operations Runbook

## How to add new documentation?

Add markdown pages under `docs-site/docs/` and update `docs-site/mkdocs.yml` navigation.
