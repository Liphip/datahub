# DataTurntable API Guide

This guide explains the API's structure, authentication, ingestion, errors and pagination. The
**authoritative, complete endpoint contract is the OpenAPI document** generated from the code:
`GET /openapi` on a running backend, or `backend/target/generated-openapi/` after `mvn package`
(linted in CI, API-06). Requirement IDs refer to the
[requirements specification](../specification/requirements-specification.md).

## Base paths

| Path | Purpose | Authentication |
|---|---|---|
| `/api/internal/v1/` | Management API used by the UI: auth, tenants, users, roles, modules, pipelines, connectors, secrets, API keys, metadata, DLQ | JWT Bearer token |
| `/api/v1/` | Data, alert and monitoring API | JWT Bearer token |
| `/api/ingest/v1/` | Ingestion API for external systems | Ingest API key (`X-API-Key`) |

Through the Compose stack everything is reached via nginx at `http://localhost`; `mvn quarkus:dev`
serves on `http://localhost:8080`.

## Authentication

### First-run setup

```http
POST /api/internal/v1/auth/setup
Content-Type: application/json

{ "tenantName": "My Organisation", "email": "admin@example.com", "password": "a-strong-password" }
```

Creates the root tenant and the first platform administrator. It works once; later calls return
`409`.

### Login

```http
POST /api/internal/v1/auth/login
Content-Type: application/json

{ "email": "admin@example.com", "password": "a-strong-password", "tenantId": "optional-tenant-uuid" }
```

The password is sent as is, over TLS; the server stores a BCrypt hash (NFR-S2). The response
contains a short-lived JWT; send it as `Authorization: Bearer <token>`. Deactivating the user,
changing the password or changing role assignments invalidates existing tokens (IAM-14).
`GET /api/internal/v1/auth/me` returns the current user, `GET /auth/tenants` and
`POST /auth/switch-tenant` handle the tenant context.

### Ingest API keys

Created on the API keys page or with `POST /api/internal/v1/api-keys?tenantId=…` (permission
`secret:manage`):

```json
{ "name": "Gateway Nord", "pipelineIds": ["…"], "connectorIds": ["…"], "expiresAt": "2027-01-01T00:00:00Z" }
```

The response contains `key` (`dt_…`) **once**; only its SHA-256 is stored (NFR-S4). A key is
valid only for the listed pipelines and INPUT connectors of its tenant. `DELETE
/api/internal/v1/api-keys/{id}` revokes it. Keys are not accepted by the management API.

## Ingestion (IMP-01, IMP-10, PIP-14)

```http
POST /api/ingest/v1/ingest/{pipelineId}
Content-Type: application/json
X-API-Key: dt_…
Idempotency-Key: 70B3D57ED0000001|2026-07-01T12:00:00Z

{ "devEUI": "70B3D57ED0000001", "time": "2026-07-01T12:00:00Z", "energyKwh": 1301.25 }
```

- `202 Accepted` means the record is durably accepted (Kafka or the local spool). Senders do not
  need to wait for, poll or retry anything (fire-and-forget).
- With an `Idempotency-Key` (or `deduplicateBy` fields on the IMPORT node) the record is processed
  exactly once, also when the sender repeats it.
- `POST /api/ingest/v1/ingest/{pipelineId}/batch` takes a JSON array of records and queues one run
  per record (at most `dataturntable.ingest.batch-max-records`, default 1000, and 1 MB per request).
  `202` means every record is stored; `503` means at least one is not — send the whole batch again.
  A record's identity is its `deduplicateBy` key, or `<Idempotency-Key>:<position>` when the header
  is sent, so records the first attempt already queued are not processed twice. Records without
  either can repeat on a resend.
- `POST /api/ingest/v1/ingest/connector/{connectorId}` feeds every active pipeline that uses the
  INPUT connector.
- WebSocket: `ws(s)://host/api/ingest/v1/ws/{pipelineId}?apiKey=dt_…`, one JSON record per frame;
  `_idempotencyKey` in the frame identifies the record.
- MQTT, FILE, FTP/FTPS, SFTP and IMAP inputs are INPUT connectors that the platform subscribes to or
  polls; they need no ingestion call.
- Errors: `400` invalid id or inactive pipeline, `401` missing, wrong or expired key, `404`
  unknown pipeline, `413` payload too large, `429` rate limit (IMP-14), `503` neither Kafka nor the
  spool can accept.

## Endpoint overview

Paths relative to the base path. The OpenAPI document lists parameters and bodies.

| Resource | Paths | Notes |
|---|---|---|
| Auth | `/auth/setup`, `/auth/login`, `/auth/register`, `/auth/me`, `/auth/tenants`, `/auth/switch-tenant` | |
| Tenants | `/tenants`, `/tenants/{id}`, `/tenants/{id}/children`, `/tenants/{tenantId}/memberships` | Tree with `?recursive=true` |
| Users | `/auth-users`, `/auth-users/{id}` | Login identities |
| Roles | `/roles`, `/roles/{id}` | Per tenant |
| Authorization | `/authorization/explain` | Why a user has or lacks a permission (IAM-15) |
| Modules | `/modules`, `/modules/{id}`, versions | Immutable versions (MOD-14) |
| Pipelines | `/pipelines`, `/pipelines/{id}`, `/{id}/run`, `/{id}/runs`, `/runs/summary`, run cancel/retry | Graph validated on save (PIP-17) |
| Connectors | `/connectors`, `/{id}/connect`, `/{id}/disconnect`, `/{id}/test`, `/{id}/clone`, `/{id}/revisions`, `/{id}/inbox`, `/{id}/inbox/{itemId}/retry`, `/{id}/lease`, `/mqtt/defaults`, `/mqtt/credentials` | Credentials only through `secretRefs` (CON-18); destinations checked (NFR-S9) |
| Secrets | `/secrets`, `/secrets/{id}/rotate` | Write-only values |
| API keys | `/api-keys`, `/api-keys/{id}` | See above |
| Metadata | `/metadata/types`, `/metadata/objects`, `/metadata/objects/upsert`, `/metadata/objects/import`, `/metadata/resolve`, `/metadata/templates/{key}/install` | As-of versioning (MET) |
| Dead letters | `/events/dlq`, `/events/dlq/{id}/retry` | PIP-13 |
| Executions | `/executions`, `/executions/{id}`, `/executions/{id}/cancel` | Submit, read and cancel a module execution |
| Grafana sync | `/grafana/sync-states`, `/grafana/reconcile`, `/grafana/apply-pending` | Platform administrators |
| Data (`/api/v1`) | `/data/{tenantId}/query`, tables | Tenant-scoped (`data:read`, `data:write`) |
| Alerts (`/api/v1`) | `/alerts/channels`, `/alerts/channels/{id}`, `/alerts/history` | Channels use secret references (ALT-12) |
| Monitoring (`/api/v1`) | `/monitoring/...` | Pipeline and module statistics, recent logs |

## Permissions

Permissions have the form `resource:action` and are matched exactly (IAM-01), for example
`pipeline:read`, `pipeline:write`, `pipeline:execute`, `module:write`, `connector:write`,
`data:read`, `alert:configure`, `secret:manage`, `tenant:manage`. The current vocabulary and the
built-in role templates are in the functional specification, §10.2. Platform administration is
the `is_platform_admin` flag on the user, separate from root-tenant administration (TEN-05).

## Errors (API-10)

```json
{ "code": "CONFLICT", "message": "An entry with this name or key already exists", "details": {}, "error": "An entry with this name or key already exists" }
```

`code` is stable and machine-readable; `message` is safe to show. `error` repeats `message` for
older clients. Server errors (`5xx`) never carry internal details. Unique-key violations return
`409`, missing or invalid values `400`.

## Pagination

Paginated lists return

```json
{ "items": [], "page": 0, "limit": 50, "hasMore": false }
```

with `?page=` and `?limit=` query parameters. The metadata object list returns `{ "total", "items" }`.

## Audit trail (AUD-07, AUD-08)

```http
GET /api/internal/v1/audit?tenantId={id}&from=2026-07-01T00:00:00Z&action=connector.*&q=sftp&limit=50&offset=0
Authorization: Bearer …
```

Filters: `from`/`to` (ISO-8601 instants), `actorId`, `action` (exact, or a prefix ending in `*`),
`resourceType`, `resourceId`, `outcome`, `q` (text in action, target and metadata). The answer is
`{ total, limit, offset, items }`, newest first. `GET /api/internal/v1/audit/export?format=csv|json`
returns the same selection as a download (at most 10,000 records) and is recorded as
`audit.exported`.

With `tenantId` the caller needs `audit:read` and access to that tenant. Without it the whole
installation is searched, including records that belong to no tenant (failed logins, retention
runs); that needs a platform administrator. The built-in Tenant Admin role of tenants created from
now on carries `audit:read`; add it to the role of existing tenants in the role editor.

`GET /api/internal/v1/audit/verify` (platform administrator) recomputes the hash chain that links
every audit record to the one before it and answers `{ intact, checked, unchained, brokenAtSeq,
problem }`. Notes: `POST /api/internal/v1/audit/{eventId}/notes` with `{ "note": "…" }` attaches a
note to a record, `POST /api/internal/v1/audit/notes` with `tenantId`, `resourceType`, `resourceId`
and `note` adds one to a component's timeline; both need `audit.note:write`. Notes cannot be
changed or deleted.

## Tenant ingest quota (TEN-10)

Set `ingestRecordsPerMinute` in a tenant's quota (`PATCH /api/internal/v1/tenants/{id}` with
`{"quota": "{\"ingestRecordsPerMinute\": 6000}"}`), or an installation default with
`dataturntable.ingest.tenant-rate-limit-per-minute`. Beyond it ingest answers `429` with
`Retry-After`; a batch is accepted or refused as a whole. A changed quota takes effect within the
pipeline directory refresh interval (10 s by default).
