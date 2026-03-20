# DataTurntable Documentation

This directory contains product, API, operations, and user documentation.

## Sections

- `api/` - internal API notes and examples
- `development/` - development plan and setup guides
- `docker/` - container, deployment, and runtime notes
- `specification/` - functional and requirements specs
- `user/` - end-user documentation (i18n)

## User Documentation (i18n)

- English: `docs/user/en/user-guide.md`
- German: `docs/user/de/benutzerhandbuch.md`
- User docs index: `docs/user/README.md`

## Web Docs (`/docs`)

A standalone MkDocs Material documentation service is provided at:

- Source: `docs-site/`
- Runtime URL (subdomain): `docs.localhost`
- Runtime URL (fallback): `/docs`
- Framework: MkDocs Material

The docs site is intentionally separate from the React application pages and runs as its own container/service.
