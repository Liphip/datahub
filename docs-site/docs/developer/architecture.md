# Architecture and Runtime

DataTurntable separates management, public API, ingestion, and pipeline execution
responsibilities. The React UI calls the control API; ingestion and connector
runtimes publish work to Kafka; pipeline executors run module containers and write
tenant data; alerts and Grafana integrations provide operational feedback.

See the canonical, repository-versioned guide at
[`docs/development/architecture.md`](https://github.com/YOUR_ORGANIZATION/dataturntable/blob/main/docs/development/architecture.md).

> FIXME: Replace the placeholder repository URL with the published canonical
> repository URL when documentation is deployed outside the source tree.
