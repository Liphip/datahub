# DataTurntable API Documentation

## Overview

The DataTurntable API has two distinct paths:

- **`/api/internal/v1/`** — Management API. Used by the Admin UI and operators to configure tenants, modules, pipelines, alerts, and query stored data. Requires JWT or API key authentication.
- **`/api/v1/`** — Public data ingestion API. Used by external systems to push data into pipelines. Authentication is per-endpoint (API key or open, configured per pipeline input).

## Base URLs

```text
Development: http://localhost:8080
Production:  https://api.dataturntable.io
```
Management API: `http://localhost:8080/api/internal/v1/`
Ingestion API:  `http://localhost:8080/api/v1/`

---

## Authentication

All management API endpoints (except `/auth/login` and `/auth/setup`) require a JWT Bearer token or an API key.

### Login

```http
POST /api/internal/v1/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "<sha256-of-password>",
  "tenantId": "optional-tenant-id"
}
```
Response:

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "abilities": ["module:read", "pipeline:execute"],
    "isSuperadmin": false
  }
}
```
> **Note:** The password must be SHA-256 hashed client-side before sending. The server applies BCrypt on top.

### First-Run Setup

```http
POST /api/internal/v1/auth/setup
Content-Type: application/json

{
  "tenantName": "My Organisation",
  "email": "admin@example.com",
  "password": "<sha256-of-password>"
}
```
Creates the root tenant and the initial superadmin user. Only works once; subsequent calls return `409 Conflict`.

### Request Headers

```text
Authorization: Bearer <jwt-token>
```
or

```text
X-API-Key: <api-key>
```
---

## Management API Endpoints

### Tenants

#### List Tenants (root level)

```http
GET /api/internal/v1/tenants
```
#### Get Tenant

```http
GET /api/internal/v1/tenants/{id}
```
#### Get Child Tenants

```http
GET /api/internal/v1/tenants/{id}/children
```
#### Create Tenant

```http
POST /api/internal/v1/tenants
Content-Type: application/json

{
  "name": "New Tenant",
  "parentId": "optional-parent-uuid",
  "quota": {
    "max_users": 100,
    "max_pipelines": 50
  }
}
```
#### Update Tenant

```http
PATCH /api/internal/v1/tenants/{id}
Content-Type: application/json

{
  "name": "Updated Name"
}
```
#### Delete Tenant

```http
DELETE /api/internal/v1/tenants/{id}
```
---

### Auth Users

Login identities are platform-wide (not tenant-scoped). Superadmin = auth user who holds a `TenantAdmin` membership in the root tenant.

#### List Auth Users

```http
GET /api/internal/v1/auth-users
```

#### Get Auth User

```http
GET /api/internal/v1/auth-users/{id}
```

#### Create Auth User

```http
POST /api/internal/v1/auth-users
Content-Type: application/json

{
  "email": "user@example.com",
  "passwordHash": "<sha256-of-password>"
}
```

`password` is accepted as a backward-compatible alias for `passwordHash`.

#### Update Auth User

```http
PATCH /api/internal/v1/auth-users/{id}
Content-Type: application/json

{
  "email": "newemail@example.com"
}
```

#### Delete Auth User

```http
DELETE /api/internal/v1/auth-users/{id}
```

---

### Tenant Memberships

Each auth user may hold at most one membership per tenant. Root-tenant admins cannot hold sub-tenant memberships.

#### List Memberships for Tenant

```http
GET /api/internal/v1/tenants/{tenantId}/memberships
```

#### Add Membership

```http
POST /api/internal/v1/tenants/{tenantId}/memberships
Content-Type: application/json

{
  "authUserId": "uuid",
  "roleId": "optional-role-uuid",
  "abilities": ["module:read"]
}
```

#### Update Membership

```http
PATCH /api/internal/v1/tenants/{tenantId}/memberships/{id}
Content-Type: application/json

{
  "roleId": "uuid",
  "abilities": ["module:read", "module:write"]
}
```

#### Remove Membership

```http
DELETE /api/internal/v1/tenants/{tenantId}/memberships/{id}
```

---

### Connectors

Protocol connector/interface definitions are tenant-scoped named entities. Import and Export pipeline nodes reference a connector by `connectorId`.

Supported `connectorType` values: `MQTT`, `REST`, `WEBSOCKET`, `UDP`, `FILE`, `CUSTOM`.
Supported `direction` values: `INPUT`, `OUTPUT`, `BOTH`.

#### List Connectors

```http
GET /api/internal/v1/connectors?tenantId={tenant-uuid}&direction=INPUT
```

#### Get Connector

```http
GET /api/internal/v1/connectors/{id}
```

#### Create Connector

```http
POST /api/internal/v1/connectors?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "Sensor MQTT Broker",
  "connectorType": "MQTT",
  "direction": "INPUT",
  "config": {
    "broker": "mqtt://iot-broker:1883",
    "topic": "sensors/#",
    "clientId": "dataturntable-import"
  }
}
```

#### Update Connector

```http
PATCH /api/internal/v1/connectors/{id}
Content-Type: application/json

{
  "config": {
    "topic": "sensors/temperature/#"
  }
}
```

#### Delete Connector

```http
DELETE /api/internal/v1/connectors/{id}
```

---

### Modules

Modules are Python classes that implement a defined interface. There are four types:

| Type | Purpose |
| ---- | ------- |
| `IMPORT` | Receive data from external systems (active pull or passive receive) |
| `PROCESSING` | Transform, filter, or enrich data |
| `ALERT` | Evaluate conditions and dispatch alerts |
| `EXPORT` | Send data to external systems or expose pull endpoints |

#### List Modules

```http
GET /api/internal/v1/modules?tenantId={tenant-uuid}&type=PROCESSING
```
#### Get Module

```http
GET /api/internal/v1/modules/{id}
```
#### Create Module

```http
POST /api/internal/v1/modules?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "My Module",
  "moduleType": "PROCESSING",
  "description": "Normalises sensor units"
}
```
#### Get Versions

```http
GET /api/internal/v1/modules/{id}/versions
```
#### Create Version

```http
POST /api/internal/v1/modules/{id}/versions
Content-Type: application/json

{
  "code": "class ProcessingModule:\n    def process(self, data, query_fn=None):\n        return data",
  "requirements": "pandas\nnumpy",
  "versionTag": "v1.0"
}
```
#### Delete Module

```http
DELETE /api/internal/v1/modules/{id}
```
---

### Pipelines

Pipelines are directed graphs (DAGs). A pipeline definition lists **nodes** (module instances) and **edges** (data connections). This allows fan-out (one node to many) and fan-in (many nodes combined by a combination `PROCESSING` module).

#### List Pipelines

```http
GET /api/internal/v1/pipelines?tenantId={tenant-uuid}
```
#### Get Pipeline

```http
GET /api/internal/v1/pipelines/{id}
```
#### Create Pipeline

IMPORT and EXPORT nodes must include a `connectorId` referencing a connector defined at the tenant level. The connector's protocol config is injected into the module at runtime.

```http
POST /api/internal/v1/pipelines?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "Temperature Sensor Pipeline",
  "graphDefinition": {
    "nodes": [
      { "id": "n1", "type": "IMPORT",      "moduleVersionId": "uuid", "connectorId": "connector-uuid" },
      { "id": "n2", "type": "PROCESSING",  "moduleVersionId": "uuid" },
      { "id": "n3", "type": "ALERT",       "moduleVersionId": "uuid" },
      { "id": "n4", "type": "EXPORT",      "moduleVersionId": "uuid", "connectorId": "connector-uuid2" }
    ],
    "edges": [
      { "from": "n1", "to": "n2" },
      { "from": "n2", "to": "n3" },
      { "from": "n2", "to": "n4" }
    ]
  }
}
```

> MVP compatibility: the flat `importModuleVersionId`, `processingModuleVersionIds`, `exportModuleVersionIds` arrays are also accepted and stored alongside `graphDefinition`.

#### Update Pipeline

```http
PATCH /api/internal/v1/pipelines/{id}
Content-Type: application/json

{
  "name": "Renamed Pipeline",
  "graphDefinition": { "nodes": [...], "edges": [...] }
}
```
#### Delete Pipeline

```http
DELETE /api/internal/v1/pipelines/{id}
```
#### Trigger Pipeline

```http
POST /api/internal/v1/pipelines/{id}/run?triggerType=MANUAL
```
#### Get Pipeline Runs

```http
GET /api/internal/v1/pipelines/{id}/runs
```
---

### Data

#### Query Stored Data

```http
GET /api/internal/v1/data/{tenantId}/query?table=measurements&start=2024-01-01&end=2024-01-02&limit=100
```
#### Insert Data Directly

```http
POST /api/internal/v1/data/{tenantId}/insert?table=measurements
Content-Type: application/json

[
  {
    "time": "2024-01-01T00:00:00Z",
    "device_id": "sensor-001",
    "value": 23.5,
    "tags": {"location": "warehouse"}
  }
]
```
#### List Tenant Tables

```http
GET /api/internal/v1/data/{tenantId}/tables
```
#### Drop Table

```http
DELETE /api/internal/v1/data/{tenantId}/tables/{table}
```
---

### Alerts

#### List Alert Channels

```http
GET /api/internal/v1/alerts/channels?tenantId={tenant-uuid}
```
#### Create Alert Channel

```http
POST /api/internal/v1/alerts/channels?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "Ops Slack",
  "channelType": "SLACK",
  "config": {
    "webhookUrl": "https://hooks.slack.com/..."
  }
}
```

`tenantId` may be provided either as query param (`?tenantId=...`) or in the request body for compatibility.
Supported `channelType` values: `MQTT`, `WEBHOOK`, `REST`, `EMAIL`, `SLACK`.

#### List Alert Rules

```http
GET /api/internal/v1/alerts/rules?tenantId={tenant-uuid}
```
#### Create Alert Rule

```http
POST /api/internal/v1/alerts/rules?tenantId={tenant-uuid}
Content-Type: application/json

{
  "name": "High Temperature Alert",
  "channelId": "channel-uuid",
  "pipelineId": "pipeline-uuid",
  "condition": {
    "field": "temperature",
    "operator": ">",
    "value": 100
  }
}
```

`tenantId` may be provided either as query param (`?tenantId=...`) or in the request body for compatibility.
`condition` may be sent as a JSON object (recommended) or as a serialized string.
#### Get Alert History

```http
GET /api/internal/v1/alerts/history?tenantId={tenant-uuid}&limit=100
```
---

## Public Ingestion API

External systems push data to pipelines via the public ingestion API. The ingestion endpoint routes data into the matching pipeline based on configuration.

```http
POST /api/v1/ingest/{pipelineId}
Content-Type: application/json
X-API-Key: <ingestion-api-key>

{
  "sensor": "room-42",
  "value": 23.5,
  "timestamp": "2024-01-01T00:00:00Z"
}
```
No fixed schema is enforced — the payload is forwarded as-is to the pipeline's import module.

For streaming protocols, the platform bundles or connects to:

- **MQTT**: Subscribe to `dataturntable/ingest/{pipelineId}/#` on the bundled Mosquitto broker (port 1883)
- **WebSocket**: Connect to `ws://host:8080/api/v1/ws/{pipelineId}`
- **UDP**: Send datagrams to the configured UDP listener port (configurable per pipeline)

---

## Identity & Roles

### Auth Users vs Tenant Memberships

Login identity (`auth_users`) is separate from per-tenant membership (`tenant_memberships`).

- One `auth_user` record per unique email, platform-wide.
- An auth user gains access to a tenant by holding a `tenant_membership` for that tenant.
- Each auth user may hold **at most one membership per tenant**.
- **Superadmin** = auth user who holds a `TenantAdmin` role membership in the **root tenant** (the single tenant with no parent). There is no `is_superadmin` flag.
- Root-tenant admins (superadmins) cannot simultaneously hold memberships in sub-tenants.

### Built-in Roles

| Role | Scope | Description |
| ---- | ----- | ----------- |
| `TenantAdmin` | Root tenant | Full platform control (superadmin) |
| `TenantAdmin` | Any tenant | Full control within that tenant and its subtree |
| `Editor` | Any tenant | Create/edit modules, pipelines, connectors |
| `Viewer` | Any tenant | Read-only access |

### Abilities

| Ability | Description |
| ------- | ----------- |
| `module:read` | View code modules |
| `module:write` | Create/edit code modules |
| `module:delete` | Delete code modules |
| `pipeline:read` | View pipelines |
| `pipeline:write` | Create/edit pipelines |
| `pipeline:execute` | Run pipelines |
| `connector:read` | View connectors |
| `connector:write` | Create/edit connectors |
| `data:read` | Query time-series data |
| `data:write` | Insert data |
| `data:export` | Export data |
| `alert:read` | View alerts |
| `alert:configure` | Configure alert channels/rules |
| `tenant:users` | Manage tenant memberships |
| `tenant:manage` | Manage tenant settings |

---

## Error Responses

All errors follow this format:

```json
{
  "error": "Error message describing what went wrong"
}
```
Common status codes:

- `400` Bad Request
- `401` Unauthorized
- `403` Forbidden
- `404` Not Found
- `500` Internal Server Error

---

## Pagination

List endpoints support pagination:

```text
GET /api/internal/v1/modules?tenantId=...&page=0&size=20
```
Response:

```json
{
  "content": [],
  "totalElements": 100,
  "totalPages": 5,
  "number": 0,
  "size": 20
}
```
