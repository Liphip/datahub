# DataTurntable

A self-hosted data hub for structured data: measurements, time series and the metadata that gives
them meaning. External systems push or are polled; records flow through graph pipelines with
Python modules, are enriched, stored, alerted on and delivered to other systems — exactly once for
identified records, without senders having to handle responses.

**Status: pre-release.** The data path (ingest, pipelines, metadata, alerts, exports, HA delivery)
works end to end. The lakehouse, the single login for all components (auth proxy, OIDC), the audit
UI and micro-batch execution for the 1 billion records/day target are not built yet. The status of
every requirement is in the [requirements specification, Appendix A](docs/specification/requirements-specification.md#appendix-a-traceability-and-implementation-status).

## Features

- **Inputs, fire-and-forget:** REST ingestion API with scoped API keys, MQTT, WebSocket, and
  platform-polled FILE, FTP/FTPS, SFTP, IMAP and REST sources with an item ledger and retry.
  A `202` means the record is durable; senders never need to retry.
- **Exactly once** for records with an identity (`Idempotency-Key` or `deduplicateBy`), across
  broker, executor and database failover; a local spool keeps ingest running while Kafka is down.
- **Graph pipelines:** fan-out, fan-in (MERGE), Python modules in isolated containers,
  DB_LOAD/DB_SAVE storage nodes, built-in alert rules (threshold, rate of change, time window,
  absence), "Send as is" exports.
- **Metadata:** typed objects (device, meter, location, …) with identifiers, relations and
  validity periods; pipelines enrich records with the metadata valid at the record's time.
- **Machine learning without an ML server:** a training pipeline fits a model (scikit-learn,
  PyTorch, pandas — all pre-installed) and saves it; an inference pipeline loads it and scores,
  classifies or forecasts each record — planned, not yet built (see the functional spec, §5.6).
- **Outputs:** REST, MQTT, WebSocket and UDP connectors; alert channels MQTT, webhook, REST,
  e-mail, Slack.
- **Multi-tenancy and security:** hierarchical tenants, fine-grained permissions, encrypted
  secrets referenced by connectors, SSRF protection for connector destinations, audit events.
- **Operations:** runtime roles that scale independently, Prometheus/Loki/Grafana monitoring,
  single-node Docker Compose and Kubernetes with an HA overlay; every bundled component is FOSS or
  freeware.

A complete example — LoRaWAN readings enriched with SAP meter data and internal locations,
delivered to a metering and a grid-calculation system (§14a EnWG) — is in
[docs/examples/energy-14a](docs/examples/energy-14a/README.md).

## Quick start

Requirements: Docker with Compose v2. For development also JDK 25, Maven 3.9+ and Node.js 22.12+.

```bash
./scripts/dev/generate-dev-keys.sh                    # throwaway JWT keys (git-ignored)
docker compose -f docker/docker-compose.dev.yml up -d
```

Open http://localhost, create the first administrator, and follow the in-app documentation at
http://localhost/docs. All service URLs, local development and tests:
[docs/development/setup.md](docs/development/setup.md).

## Architecture

```text
Push: REST, MQTT, WebSocket ──┐                              ┌──► REST, MQTT, WebSocket, UDP
Poll: FILE, FTP, SFTP, IMAP ──┤                              │    alert channels
                              ▼                              │
                   ingest-edge (API keys, spool)             │
                              │ Kafka                        │
                              ▼                              │
            pipeline-executor: DAG of IMPORT → PROCESSING / MERGE / ALERT
                               → EXPORT / DB_SAVE, metadata enrichment ─┘
                              │
                 PostgreSQL (metadata, tenant data)
control-api + public-api: management, data and alert APIs; React UI; Grafana
```

Details: [functional specification](docs/specification/functional-specification.md).

## API

| Path | Purpose |
|---|---|
| `/api/internal/v1/` | Management API (UI) |
| `/api/v1/` | Data, alerts, monitoring |
| `/api/ingest/v1/` | Ingestion for external systems |

Guide: [docs/api/README.md](docs/api/README.md); the complete contract is the generated OpenAPI
document (`/openapi`). Python module contracts: functional specification, §15.

## Documentation

Start at [docs/README.md](docs/README.md). The most important documents:

- [Requirements specification](docs/specification/requirements-specification.md): goals,
  requirements, decisions, implementation status
- [Functional specification](docs/specification/functional-specification.md): how it works
- [Handoff](docs/development/handoff.md): current work, open items, lessons learned
- [CONTRIBUTING.md](CONTRIBUTING.md): workflow and which document to update

## Licence

DataTurntable is distributed under the custom licence in [LICENSE](LICENSE) (not an OSI licence:
use, modification and distribution are permitted with attribution, selling copies is not, and the
licence is revocable). Bundled third-party components keep their own licences; see
[docs/development/licenses.md](docs/development/licenses.md).

## Contact

For questions or suggestions, contact: contact@dataturntable.io
