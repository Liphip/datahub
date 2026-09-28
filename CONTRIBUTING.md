# Contributing to DataTurntable

Set up the environment and run the tests as described in
[docs/development/setup.md](docs/development/setup.md).

## Workflow

1. Create a feature branch from `dev`.
2. Make the change with its tests and documentation (see below).
3. Run the checks from the setup guide: backend suite, PostgreSQL migration test when the schema
   changed, frontend type check, lint, tests and build.
4. Open a pull request into `dev`. CI (`.github/workflows/ci.yml`) must pass.

The project has no production installation yet: do not add backward-compatibility code, legacy
fallbacks or data migrations, and keep dependencies current. Before the first production
installation, schema changes edit `V1__baseline.sql` directly.

## Code

- **Backend (Kotlin/Quarkus):** Kotlin conventions; KDoc on public classes and non-obvious
  functions. Packages are by domain (`api`, `engine`, `connectors`, `metadata`, `security`, …; see
  the [source reference](docs/development/source-reference.md)). Every REST behaviour change gets a
  test.
- **Frontend (React/TypeScript):** functional components with hooks, ESLint clean, every UI text in
  English and German (`src/i18n/locales`, checked by a test), component tests for new pages.
- **Security:** never commit secrets. `secrets/` is git-ignored because
  `scripts/dev/generate-dev-keys.sh` writes a private key there; stage files by path, not with
  `git add -A`.
- **Unknowns:** when behaviour depends on a decision nobody has made, add a precise
  `FIXME` next to the code (what is undecided and what evidence would close it) and an entry under
  "Open work" in `docs/development/handoff.md`. Do not leave vague TODOs.

## Documentation

Each fact lives in one place; link to it instead of repeating it.

| Change | Update |
|---|---|
| A requirement is implemented or its status changes | `docs/specification/requirements-specification.md`, Appendix A, in the same change |
| New or changed requirement, or a decision against an alternative | The SRS (requirements; section 12 for decisions) |
| Behaviour or design changes | `docs/specification/functional-specification.md` |
| REST request, response, status or permission changes | `docs/api/README.md` |
| New or changed environment variable or config key | `docs/development/configuration-reference.md` |
| A class gains or changes a responsibility | `docs/development/source-reference.md`; regenerate `source-function-reference.md` with `scripts/generate-source-function-reference.ps1` |
| User or operator workflow changes | The user guides (`docs-site/docs/en`, `docs-site/docs/de`) and `docs/operations/` |
| New dependency or image | `docs/development/licenses.md` |
| Work in progress, findings, next steps | `docs/development/handoff.md` (kept current so anyone can take over) |

Never present planned or untested behaviour as implemented (NFR-M9).

## Commits and pull requests

[Conventional Commits](https://conventionalcommits.org/): `<type>(<scope>): <description>`, with
types `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`. Pull request titles use the same
format. Describe what changed and why, link related issues, and list what was verified.
