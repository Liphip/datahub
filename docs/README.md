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

A standalone static documentation site is provided at:

- Source: `frontend/public/docs/`
- Runtime URL: `/docs` (served by the frontend web server)
- Framework: Docsify (static, no backend runtime required)

The web docs support language switching (EN/DE) and are intentionally separate from the React application pages.
