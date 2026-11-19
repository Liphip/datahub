# Configuration Reference

Every environment variable the backend reads, taken from `backend/src/main/resources/application.yaml`
and the code (`CONNECTOR_CONFIG_KEY`). Environment variables override the defaults. Development
defaults are convenient, not production secrets. Update this page in the same change as
`application.yaml`.

## Databases and messaging

| Variable | Default | Key | Meaning |
|---|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `5432`, `dataturntable` | `quarkus.datasource.jdbc.url` | Application (metadata) database |
| `DB_USER`, `DB_PASSWORD` | `dataturntable` (none in `%prod`) | `quarkus.datasource.*` | Metadata database credentials; required in production |
| `DATATURNTABLE_PIPELINE_DATA_JDBC_URL`, `..._USERNAME`, `..._PASSWORD` | empty | `dataturntable.pipeline-data.jdbc.*` | Separate database for tenant pipeline data (DB_LOAD/DB_SAVE); falls back to the metadata datasource. **On PostgreSQL, this user must hold `CREATEROLE`** so `TenantDatabaseRoleService` can provision a role per tenant (NFR-S8); without it, database-level tenant isolation of the hot tier silently falls back to the shared connection's own privileges (logged once per tenant) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka.bootstrap.servers`, messaging channels | Kafka bootstrap address |
| `PIPELINE_CONSUMER_CONCURRENCY` | `4` | `mp.messaging.incoming.pipeline-input.concurrency` | Parallel consumers per executor instance |

## Security

| Variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_JWT_SIGNING_KEY_LOCATION` | none | PKCS#8 private key for issuing tokens. Required; the backend does not start without it (NFR-S7, NFR-S11). Development: `scripts/dev/generate-dev-keys.sh` |
| `DATATURNTABLE_JWT_PUBLIC_KEY_LOCATION` | none | Matching SPKI public key for verifying tokens. Required |
| `CONNECTOR_CONFIG_KEY` | none | Base64 of exactly 32 bytes; AES key for connector configurations and secrets at rest. Required with the `prod` profile (startup fails without it); without it outside `prod`, values are stored unencrypted |
| `DATATURNTABLE_CONNECTORS_ALLOWED_NETWORKS` | empty | Comma-separated CIDR list of internal networks connectors may reach, e.g. `10.20.0.0/16,192.168.5.0/24`. Empty: loopback, private, link-local and multicast destinations are refused; cloud metadata endpoints are refused always (NFR-S9) |

## Runtime roles and connectors

| Variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_RUNTIME_ROLE` | `all` | `all`, `control-api`, `public-api`, `ingest-edge` or `pipeline-executor` (functional specification §2.2) |
| `DATATURNTABLE_RUNTIME_INSTANCE_ID` | empty | Stable instance identity for connector leases |
| `DATATURNTABLE_CONNECTOR_LEASE_TTL_SECONDS` | `30` | Lifetime of a connector ownership lease in PostgreSQL (NFR-R10) |
| `DATATURNTABLE_CONNECTOR_LEASE_RECONCILE_INTERVAL_SECONDS` | `10` | How often ownership is reconciled |
| `DATATURNTABLE_FILE_BASE_DIR` | `/data/files` | Root directory that FILE (directory inbox) connectors may read |

## Ingestion

| Variable | Default | Meaning |
|---|---|---|
| `dataturntable.execution.model-artifact-cache-seconds` | `60` | How long a preloaded model artifact is cached before re-checking the database (MOD-18) |
| `DATATURNTABLE_INGEST_SPOOL_DIR` | `${java.io.tmpdir}/…` | Local fsync'd spool for records Kafka refused (NFR-R7). Use a persistent volume in production (the Kubernetes ingest edge is a StatefulSet with a PVC) |
| `quarkus.http.limits.max-body-size` | `1M` | Largest accepted request body; larger ingest requests get `413` (IMP-14) |
| `dataturntable.ingest.spool-enabled` | `true` | Use the spool when Kafka refuses a record |
| `dataturntable.ingest.spool-after` | `5s` | How long a Kafka write may take before the record goes to the spool |
| `dataturntable.ingest.spool-segment-bytes`, `spool-max-bytes` | 16 MiB, 10 GiB | Spool segment size and total cap; beyond the cap ingest answers `503` |
| `dataturntable.ingest.spool-drain-timeout` | `25s` | Time budget per drain attempt |
| `dataturntable.ingest.batch-max-records` | `1000` | Most records one batch ingest request may carry (`413` beyond it). The 1 MB body limit applies as well. |
| `dataturntable.ingest.websocket.max-sessions-per-pipeline` | `100` | Concurrent ingest WebSocket sessions one pipeline accepts; further ones are closed with "try again later". |
| `dataturntable.ingest.tenant-rate-limit-per-minute` | `0` (off) | Default ingest quota per tenant in records per minute, all its credentials and protocols together (`429` + `Retry-After` beyond it). A tenant's own `ingestRecordsPerMinute` in its quota overrides it. Counted per ingest instance. |
| `dataturntable.ingest.rate-limit-per-minute` | `0` (off) | Per-key ingest limit (`429` beyond it). Off by default: fire-and-forget senders do not retry, so a limit trades their data for overload protection |
| `DATATURNTABLE_INGEST_WEBSOCKET_IDLE_TIMEOUT_MS` | `300000` | Idle WebSocket ingest sessions are closed after this time (IMP-14) |
| `DATATURNTABLE_INGEST_WEBSOCKET_IDLE_REAP_INTERVAL` | `10s` | How often idle sessions are checked |

## Alerts and mail

| Variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_ALERT_COOLDOWN_SECONDS` | `300` | Default alert cooldown (ALT-07) |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_START_TLS` | `localhost`, `25`, `dataturntable@localhost`, `OPTIONAL` | Mail server for e-mail alert channels |

## Retention

All categories default to `-1` (keep indefinitely) until an installation opts in (STR-07, OPS-07).

| Variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_RETENTION_CHECK_INTERVAL` | `24h` | How often the cleanup runs (on `control-api` only) |
| `DATATURNTABLE_RETENTION_PIPELINE_RUNS_DAYS` | `-1` | Runs and node logs |
| `DATATURNTABLE_RETENTION_ALERT_HISTORY_DAYS` | `-1` | Alert history and deliveries |
| `DATATURNTABLE_RETENTION_AUDIT_EVENTS_DAYS` | `-1` | Audit events |
| `DATATURNTABLE_RETENTION_READINGS_DAYS` | `-1` | Pipeline data readings |

## Observability

| Variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_OTEL_DISABLED` | `true` | Set to `false` to enable OpenTelemetry tracing (NFR-M7) |
| `DATATURNTABLE_OTEL_ENDPOINT` | `http://localhost:4317` | OTLP endpoint |

## Grafana integration

`DATATURNTABLE_GRAFANA_DASHBOARD_*` configures the dashboard Grafana (tenant dashboards) and
`DATATURNTABLE_GRAFANA_MONITORING_*` the monitoring Grafana (system monitoring).

| Variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_GRAFANA_{DASHBOARD,MONITORING}_ENABLED` | `false` | Enable reconciliation of users, organisations and data sources |
| `DATATURNTABLE_GRAFANA_DASHBOARD_BASE_URL` | `http://dashboard-grafana:3000` | Admin API base URL |
| `DATATURNTABLE_GRAFANA_MONITORING_BASE_URL` | `http://grafana:3000` | Admin API base URL |
| `DATATURNTABLE_GRAFANA_{DASHBOARD,MONITORING}_ADMIN_USER`, `..._ADMIN_PASSWORD` | none | Required when enabled; no default credentials |
| `DATATURNTABLE_GRAFANA_DASHBOARD_DATASOURCE_{HOST,PORT,DATABASE,USER,PASSWORD,SSL_MODE}` | port `5432`, SSL `disable` | Pipeline-data database provisioned as tenant data sources |

The Compose stack starts both Grafana instances with `admin/admin`. That is for local
development only; set explicit credentials in every other deployment.

## Module execution and MQTT defaults

| Key / variable | Default | Meaning |
|---|---|---|
| `dataturntable.docker.module.base-image` | `python:3.14.7-slim-trixie` | Base image for module containers |
| `dataturntable.docker.module.network` | `dataturntable` | Docker network for modules that are granted network access |
| `dataturntable.docker.dependency-install.network` | `bridge` | Network of the short-lived container that installs module requirements (MOD-05). It must reach the package index; the module's own container stays without one |
| `dataturntable.docker.dependency-install.index-url` | — | Alternative package index (pip `--index-url`), e.g. an internal mirror |
| `dataturntable.docker.dependency-install.proxy` | — | Outbound proxy for the install container only (`HTTPS_PROXY`/`HTTP_PROXY`) |
| `dataturntable.docker.dependency-install.allow-source-builds` | `false` | Accept sdists. Off by default: only wheels are installed, so no package build script runs while the install container has a network |
| `dataturntable.docker.dependency-install.timeout-seconds` | `600` | Limit for one install |
| `dataturntable.docker.resources.cpu-limit`, `.memory-limit` | `1.0`, `512m` | Container limits (MOD-04) |
| `MQTT_INTEGRATED_INTERNAL_BROKER_URL` | `mqtt://mosquitto:1883` | Bundled broker as seen from the stack (UI default) |
| `MQTT_INTEGRATED_EXTERNAL_BROKER_URL` | `mqtt://localhost:1883` | Bundled broker as seen from a developer machine (UI default) |
| `MQTT_INTEGRATED_WEBSOCKET_URL` | `ws://localhost:9001` | MQTT over WebSocket (UI default) |
| `MQTT_INTEGRATED_DEFAULT_TOPIC` | `dataturntable/events` | Topic suggested in the UI |

## Profiles

- `%test`: H2 in memory, Flyway off (schema from the entities), inbound pipeline consumption and
  background jobs off, `allowed-networks: 127.0.0.0/8` for local test receivers.
- `%dev` and `%prod` override datasource and Kafka values; `%prod` sets pool bounds and requires
  credentials.
- Role profiles disable inbound pipeline consumption everywhere except `pipeline-executor`.
- Flyway runs at startup. Back up the metadata database before an upgrade.

## Secrets

Never commit production values for database passwords, Grafana credentials, JWT keys, the
connector encryption key, connector credentials or alert tokens. Inject them with the deployment's
secret mechanism (Compose `env_file`/secrets, Kubernetes Secrets). Connector and alert-channel
credentials are stored as platform secrets and referenced with `secretRefs` (CON-18).


## Optional OIDC login

| Environment variable | Default | Meaning |
|---|---|---|
| `DATATURNTABLE_OIDC_ENABLED` | `false` | Enable the external browser login on the control API. |
| `DATATURNTABLE_OIDC_ISSUER` | `http://localhost:8180/realms/dataturntable` | Exact provider issuer/discovery base; HTTPS in production. |
| `DATATURNTABLE_OIDC_CLIENT_ID` | `dataturntable` | Confidential client id, also the required ID-token audience. |
| `DATATURNTABLE_OIDC_CLIENT_SECRET` | empty | Client secret from secret management; required by a confidential provider. |

Changes require restarting the control API. The provider is disabled unless explicitly enabled.
Compose passes these variables to `control-api` only and, by default, points them at its bundled Keycloak (see [setup](setup.md)). For host development, export them before
starting Quarkus. See [desktop identity checks](identity-desktop.md) for local protocol tests and
[setup](setup.md) for callback registration and multi-replica encryption-key requirements.

The OIDC browser flow verifies the ID token; provider access tokens are not used for platform
permissions or forwarded to services. `authentication.verify-access-token=false` and
`roles.source=idtoken` are explicit to prevent JWT injection elsewhere in the application from
automatically enabling unrelated access-token verification. Issuer, audience, signature, nonce,
PKCE and expiry validation for the ID token remain enabled.


## Local password / break-glass policy (IAM-11)

`DATATURNTABLE_LOCAL_LOGIN_MODE` defaults to `all`. Set it to `break-glass-only` to restrict password
login to the comma-separated account UUIDs in `DATATURNTABLE_BREAK_GLASS_USER_IDS`. All identifiers
must be complete UUIDs. Unknown modes, invalid identifiers and an empty restricted allowlist fail
startup instead of falling back to unrestricted access. OIDC login still requires an explicit
issuer/subject link to an active account and is unaffected by the password-only restriction.

Bootstrap the installation in `all` mode, designate existing account IDs, verify provider login,
and then restart the control API with the restriction. Preserve at least one tested emergency
account. Update the process settings and restart to change the policy; no account migration is
required and there is no UI editor yet. Restriction applies to new password logins, not to tokens
already issued. User deactivation/password/role changes retain their authorization-version checks.
Both desktop and multi-role development Compose forward these settings to the control API.
