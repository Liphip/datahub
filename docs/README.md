# DataTurntable Documentation

Each fact is kept in one place. Start with the document that matches your question.

| Question | Document |
|---|---|
| What must the product do, why, and what is built already? | [Requirements specification](specification/requirements-specification.md) — goals, requirements, decisions, **implementation status (Appendix A)** |
| How does it work? | [Functional specification](specification/functional-specification.md) |
| What is being worked on, what is open, what did we learn? | [Handoff / work log](development/handoff.md) |
| How do I test identity on a desktop without Kubernetes? | [Desktop identity checks](development/identity-desktop.md) |
| How do I run, build and test it? | [Development setup](development/setup.md) |
| Which settings exist? | [Configuration reference](development/configuration-reference.md) |
| How do I call the API? | [API guide](api/README.md) (complete contract: generated OpenAPI) |
| Where is a class and what does it do? | [Source reference](development/source-reference.md), [source function reference](development/source-function-reference.md) (generated) |
| Which licences are bundled? | [Licences](development/licenses.md) |
| How do I back up and restore? | [Backup and restore](operations/backup-restore.md) |
| How are the containers built and deployed? | [Docker and Kubernetes](docker/README.md) |
| A complete worked example | [§14a EnWG example](examples/energy-14a/README.md) |
| How do I use the UI? (end users, administrators) | The documentation site: `docs-site/docs/en`, `docs-site/docs/de`, served at `/docs` |
| How do I contribute, and which document do I update? | [CONTRIBUTING.md](../CONTRIBUTING.md) |

The documentation site (`docs-site/`, served by the `docs` container at `/docs` and
`docs.localhost`) contains the end-user and administrator guides in English and German. Its
developer section is copied from this directory when the image is built, so there is no second copy
to maintain.
