# Architecture and Runtime Guide

## Purpose

DataTurntable is a multi-tenant data-processing platform. The control plane stores
configuration and authorisation data; the data plane accepts events, runs pipeline
modules, persists pipeline data, and emits alerts. This document describes the
implemented system, rather than planned features in the specifications.

## Runtime topology

```text
React UI ──internal management API──> control-api ──> application PostgreSQL
                                           │
External producers ──public ingestion API──> ingest-edge ──> Kafka
                                                               │
Grafana / Data Explorer <── pipeline data DB <── pipeline-executor
                                           │
                                   Docker module containers
                                           │
                             alert channels / external services
```

`DATATURNTABLE_RUNTIME_ROLE` determines which responsibilities a backend process
owns. `all` is useful for a single-process local setup. The Docker development
stack separates the process into `control-api`, `public-api`, `ingest-edge`, and
`pipeline-executor` so that each boundary can be exercised independently.

| Role | Responsibility | Does not own |
|---|---|---|
| `control-api` | Management endpoints, configuration, Grafana provisioning | ingestion consumers and module execution |
| `public-api` | Public monitoring endpoints | background ingestion and execution |
| `ingest-edge` | Connector lifecycle and ingestion runtime | pipeline execution |
| `pipeline-executor` | Kafka pipeline consumption, module containers, run logs and metrics | management APIs |

## Request and event lifecycle

1. A user signs in through `AuthResource`; the frontend stores the JWT and attaches it to management calls.
2. A tenant-scoped management resource checks tenant access and the required ability before reading or changing persisted configuration.
3. An import endpoint or connector submits a payload to the ingestion path.
4. Kafka carries pipeline commands/events to the executor.
5. `PipelineConsumer` resolves the configured pipeline and `DockerService`/`WarmContainerPool` execute module code in Python containers.
6. `PipelineDataService` writes queryable output to the tenant schema. `PipelineLogWriter` and `PipelineMetrics` record operational evidence.
7. `AlertDispatcher` evaluates enabled rules and delivers matching alerts through the configured channel.

## Source-code documentation model

The API document is the public contract for REST handlers; the source reference is
the contract for implementation units. Private helpers are intentionally documented
with the class/function they support, rather than duplicated as fragile line-by-line
comments. When a behavior depends on credentials, a third-party protocol, or an
unimplemented product decision, it is recorded as a precise `FIXME` in source or in
the documentation-gap register.

Read these companion documents when changing implementation code:

- [Source-code reference](source-reference.md) for every production source file and its responsibility.
- [Configuration reference](configuration-reference.md) for runtime values and secret handling.
- [Documentation gaps](documentation-gaps.md) for known, actionable unknowns.
- [API reference](../api/README.md) for externally observable HTTP behavior.

## Ownership boundaries

| Area | Primary packages | Persistent state |
|---|---|---|
| Management and RBAC | `api`, `auth`, `tenant`, `service`, `dto` | application PostgreSQL |
| Data ingestion and connectors | `connectors`, `api` | connector records, Redis leases, Kafka |
| Pipeline execution | `engine`, `pipeline`, `module`, `service` | Kafka, pipeline PostgreSQL, Docker |
| Alerting | `alert` | application PostgreSQL, external channels |
| Observability/Grafana | `monitoring`, `grafana`, `logging`, `health` | Prometheus/Loki/Grafana and monitoring DB |
| Web application | `frontend/src` | browser storage and management API |

## Change checklist

1. Update the REST reference for changed request, response, status, or permission behavior.
2. Update the corresponding source-reference entry when a class gains a responsibility or changes its contract.
3. Add/update configuration entries for every environment variable or profile key.
4. Document a verifiable fallback or add a scoped `FIXME` if the required operational decision is unknown.
5. Add tests for externally observable behavior; update the user/operator documentation if their workflow changes.
