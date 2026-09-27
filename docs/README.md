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

## Developer Documentation

- [Architecture and runtime guide](development/architecture.md)
- [Source-code reference](development/source-reference.md)
- [Source function reference](development/source-function-reference.md)
- [Configuration reference](development/configuration-reference.md)
- [Documentation gaps and FIXMEs](development/documentation-gaps.md)

## Operations Documentation

- [Backup and restore procedure](operations/backup-restore.md)
- [Feature and deployment support matrix](operations/support-matrix.md)

The source-code reference maps every production backend and frontend file to its
implemented responsibility. Public REST contracts remain in `api/README.md` so
they are documented once, next to request/response examples.

## Web Docs (`/docs`)

A standalone MkDocs Material documentation service is provided at:

- Source: `docs-site/`
- Runtime URL (subdomain): `docs.localhost`
- Runtime URL (fallback): `/docs`
- Framework: MkDocs Material

The docs site is intentionally separate from the React application pages and runs as its own container/service.
