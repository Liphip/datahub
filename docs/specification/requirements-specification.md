# DataTurntable — Software Requirements Specification (SRS)

**Version:** 1.1
**Date:** 2026-03-16
**Status:** Draft

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Overall Description](#2-overall-description)
3. [Stakeholders & Users](#3-stakeholders--users)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [System Constraints](#6-system-constraints)
7. [Multi-Tenancy & Access Control Requirements](#7-multi-tenancy--access-control-requirements)
8. [Data Requirements](#8-data-requirements)
9. [Integration Requirements](#9-integration-requirements)
10. [Deployment Requirements](#10-deployment-requirements)
11. [MVP Scope vs. Future Scope](#11-mvp-scope-vs-future-scope)

---

## 1. Introduction

### 1.1 Purpose

This document specifies the software requirements for **DataTurntable**, a high-performance, modular data hub for IoT/sensor data processing. It defines what the system must do (functional requirements) and how it must perform (non-functional requirements), providing the basis for design and development decisions.

### 1.2 Project Background

DataTurntable originates from the need for a flexible, self-hostable data integration platform encountered in professional work. It is developed as a private/open-source project intended to provide a genuinely useful tool for administrators and developers who need to ingest, transform, store, and route data from heterogeneous sources — without being locked into a vendor-specific ecosystem or AI-centric platform.

### 1.3 Scope

DataTurntable covers:

- Exposing protocol endpoints (REST, MQTT, WebSocket, UDP) for external systems to push data into pipelines, and actively pulling data from external systems
- A non-linear (DAG) processing pipeline layer with transformation, filtering, ML capabilities, and dedicated alert evaluation
- Storage connectors (TimescaleDB, PostgreSQL, MongoDB, MinIO/S3)
- Routing/exporting data by pushing to external endpoints (REST, MQTT, WebSocket, UDP) or exposing pull endpoints for external systems
- A visual admin UI for configuration, monitoring, and pipeline management
- A multi-tenant hierarchical access model
- A public REST API for all management and data operations

Out of scope: DataTurntable is **not** an AI/LLM platform, a BI tool, or a replacement for purpose-built message brokers (it uses them as infrastructure).

### 1.4 Definitions & Abbreviations

| Term | Definition |
| ---- | ---------- |
| Tenant | Organisational unit in the strict tree hierarchy; one parent except the root |
| Auth User | Platform-wide login identity (`auth_users` table); identified by email |
| Tenant Membership | A record binding one auth user to one tenant with a role/abilities |
| Superadmin | Auth user holding a TenantAdmin membership in the root tenant |
| Module | A user-written or official Python component: IMPORT, PROCESSING, ALERT, or EXPORT |
| Pipeline | A directed acyclic graph (DAG) of module nodes from data source(s) to data sink(s) |
| Connector | A named, tenant-scoped protocol interface definition (MQTT, REST, WebSocket, UDP, File, Custom); referenced by IMPORT/EXPORT pipeline nodes |
| Fan-out | One module's output routed to multiple downstream modules |
| Fan-in | Multiple upstream module outputs combined by a single downstream (combination) module |
| SRS | Software Requirements Specification |
| MVP | Minimum Viable Product |
| RBAC | Role-Based Access Control |
| DAG | Directed Acyclic Graph |

---

## 2. Overall Description

### 2.1 Product Perspective

DataTurntable acts as a **data router and processor** sitting between heterogeneous data sources and data sinks. It is architecturally similar to platforms such as Apache NiFi or Node-RED but is designed to be self-hostable, Python-friendly for data logic, and manageable through a lightweight web UI.

The platform occupies two positions relative to external systems:

**As a server (passive / push-to-us):** DataTurntable exposes protocol endpoints. External systems push data to these endpoints. The platform then routes data through pipelines.

**As a client (active / pull-from-them or push-to-them):** DataTurntable actively connects to external systems to pull data (IMPORT modules) or to push processed results (EXPORT modules). It can also expose read-only endpoints that external systems pull from (e.g. Grafana querying the platform).

### 2.2 Product Functions (Summary)

1. Expose REST (`/api/v1/`), MQTT, WebSocket, and UDP endpoints for external systems to push data into pipelines
2. Actively pull data from external sources via IMPORT modules
3. Route data through configurable non-linear (DAG) processing pipelines
4. Apply Python-based transformation and filtering via PROCESSING modules in isolated containers
5. Evaluate alert conditions via dedicated ALERT modules and dispatch to configured channels
6. Persist data to one or more storage backends
7. Push processed data to external systems (REST, MQTT, WebSocket, UDP) or expose pull endpoints via EXPORT modules
8. Expose a full management and data REST API (`/api/internal/v1/`)
9. Provide a lightweight admin UI for configuration, monitoring, and code editing
10. Enforce multi-tenant isolation and fine-grained permissions
11. Allow all modules and connectors to be installed, updated, and toggled at runtime

### 2.3 Assumptions and Dependencies

- Kafka is used as the internal message bus and pipeline backbone; it is a required deployment dependency.
- Docker is required for running user Python module containers.
- The platform does not provide end-user dashboards beyond basic built-in charts; Grafana and ThingsBoard integration is provided via EXPORT modules.
- At-least-once delivery with deduplication of system-generated duplicates is the target; exactly-once is not guaranteed at v1.

---

## 3. Stakeholders & Users

| Role | Description |
| ---- | ----------- |
| Super Admin (Superadmin) | Global platform administrator; manages tenants, modules, system config |
| Tenant Admin (TenantAdmin) | Manages their tenant hierarchy, users, and resources |
| Editor | Creates and edits modules, pipelines, connectors; runs pipelines |
| Viewer | Read-only access to pipelines, data, and alerts |
| API Client / Service Account | Machine identity for programmatic access (API key) |

---

## 4. Functional Requirements

Requirements are tagged: `MVP` = required for initial release; `V2` = planned post-MVP.

### 4.1 Inbound Data (Import / Ingestion)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| IMP-01 | The system shall expose a REST endpoint (`POST /api/v1/ingest/{pipelineId}`) that accepts arbitrary JSON payloads from external systems and routes them into the named pipeline. | MVP |
| IMP-02 | The system shall expose an MQTT subscription interface: the platform subscribes to configurable topics on a bundled or external MQTT broker; incoming messages are routed into the named pipeline. | MVP |
| IMP-03 | The system shall expose a WebSocket server endpoint; external clients connect and stream frames into a pipeline. | MVP |
| IMP-04 | The system shall expose a configurable UDP listener; incoming datagrams are routed into a pipeline. | V2 |
| IMP-05 | Users shall be able to write custom IMPORT modules (Python) that actively pull data from external sources (REST poll, database query, file watch, etc.). | MVP |
| IMP-06 | Inbound endpoints shall impose no default data structure; all payload formats are forwarded as-is to the pipeline's first module. | MVP |
| IMP-07 | Inbound endpoints shall support both live (streaming) and batch data modes. | MVP |
| IMP-08 | All inbound protocol endpoints shall be hot-pluggable; enabling or disabling one shall not affect other pipelines. | MVP |

### 4.2 Processing Pipeline

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| PIP-01 | Users shall be able to define pipelines as directed acyclic graphs (DAGs) of module nodes connected by edges. | MVP |
| PIP-02 | Pipelines shall support fan-out: one module's output can be routed to multiple downstream modules simultaneously. | MVP |
| PIP-03 | Pipelines shall support fan-in: a PROCESSING (combination) module can receive data from multiple upstream modules and merge the streams. | MVP |
| PIP-04 | Each pipeline node shall reference a specific module version (IMPORT, PROCESSING, ALERT, or EXPORT). | MVP |
| PIP-05 | Pipelines shall have a visual representation in the UI (ordered list/linear graph for MVP; full graph editor in V2). | MVP |
| PIP-06 | Pipelines shall support drag-and-drop node graph editing in the UI. | V2 |
| PIP-07 | Pipelines shall be version-controlled; previous versions shall be stored and restorable. | V2 |
| PIP-08 | Pipelines shall have draft and published states. | V2 |
| PIP-09 | The system shall deduplicate system-generated duplicates (from Kafka at-least-once retries) but shall not deduplicate user data unless a deduplication PROCESSING module is explicitly added. | MVP |
| PIP-10 | Pipelines shall support at-least-once delivery semantics. | MVP |
| PIP-11 | Pipeline execution metrics (throughput, error rate, latency per node) shall be collected and exposed. | MVP |

### 4.3 Python Module System

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| MOD-01 | Users with permission shall be able to write Python modules in the UI code editor. | MVP |
| MOD-02 | The system shall support four module types: `IMPORT`, `PROCESSING`, `ALERT`, `EXPORT`, each with a defined Python interface contract. | MVP |
| MOD-03 | Python modules shall run in isolated Docker containers (one container per running module instance). | MVP |
| MOD-04 | Container instances shall have configurable resource limits (CPU, memory). | MVP |
| MOD-05 | Module containers shall have no network access by default; network access is explicitly granted per module by a TenantAdmin or higher. | MVP |
| MOD-06 | The module sandbox shall pre-install: pandas, numpy, scikit-learn; optionally PyTorch, xgboost. | MVP |
| MOD-07 | The platform shall inject system helper functions into module execution context: `alert_fn` for alert dispatch and `query_fn` for data queries. | MVP |
| MOD-08 | Data shall be passed to PROCESSING and ALERT modules as a dict or pandas DataFrame; modules shall return the same types. | MVP |
| MOD-09 | Module source code and versions shall be stored in PostgreSQL by default. | MVP |
| MOD-10 | Module code versions shall be tracked; previous versions shall be auditable. | MVP |
| MOD-11 | Modules shall be scoped per tenant; a module in one tenant shall not be visible to another. | MVP |

### 4.4 Alert Module

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| ALT-01 | The system shall support a dedicated `ALERT` module type separate from `PROCESSING`. Alert modules evaluate conditions and dispatch alerts but do not modify or forward the data stream. | MVP |
| ALT-02 | Built-in alert rule types shall include: threshold, rate-of-change, time window, and absence of data. | MVP |
| ALT-03 | Alert modules shall support custom Python logic via the `AlertModule` class interface. | MVP |
| ALT-04 | Alert channels shall include: MQTT, HTTP webhook, REST endpoint, email (SMTP), and Slack. | MVP |
| ALT-05 | Alerts shall have configurable severity levels: info, warning, critical. | MVP |
| ALT-06 | Alert history shall be stored and queryable via the management API. | MVP |
| ALT-07 | Alert deduplication (cooldown window) shall prevent repeated firing for the same condition within a configurable period. | MVP |
| ALT-08 | Alerts shall be acknowledged by operators in the UI. | V2 |
| ALT-09 | Alert escalation chains shall be supported. | V2 |

### 4.5 Outbound Data (Export)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| EXP-01 | EXPORT modules shall be able to push data to external REST endpoints (HTTP POST/PUT). | MVP |
| EXP-02 | EXPORT modules shall be able to publish data to external MQTT brokers. | MVP |
| EXP-03 | EXPORT modules shall be able to push data over WebSocket connections. | MVP |
| EXP-04 | EXPORT modules shall be able to send data via UDP datagrams. | V2 |
| EXP-05 | EXPORT modules shall be able to write data to storage backends (TimescaleDB, PostgreSQL, MongoDB, MinIO). | MVP |
| EXP-06 | EXPORT modules shall be able to expose a pull endpoint (e.g. a Grafana-compatible SimpleJSON endpoint) that external systems query. | MVP |
| EXP-07 | Users shall be able to write custom EXPORT modules in Python. | MVP |
| EXP-08 | Export connectors shall be hot-pluggable. | MVP |

### 4.6 Connectors

Connectors are named, tenant-scoped protocol interface definitions. They describe how to reach a protocol endpoint. IMPORT and EXPORT pipeline nodes reference a connector by ID; the connector's config is injected into the module at runtime.

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| CON-01 | The system shall allow tenants to define named connector entities for each supported protocol: MQTT, REST, WebSocket, UDP, File, Custom. | MVP |
| CON-02 | Each connector shall have a `direction` property: INPUT, OUTPUT, or BOTH. | MVP |
| CON-03 | IMPORT pipeline nodes shall reference a connector with `direction` INPUT or BOTH. | MVP |
| CON-04 | EXPORT pipeline nodes shall reference a connector with `direction` OUTPUT or BOTH. | MVP |
| CON-05 | Connector configuration (host, port, credentials, topic, etc.) shall be stored in a typed JSON config block and injected into the module container at runtime. | MVP |
| CON-06 | Connectors shall be manageable (create, update, delete, enable/disable) independently of pipelines. | MVP |
| CON-07 | Connectors shall be hot-pluggable; updating a connector's config shall take effect on the next pipeline execution without restarting the core platform. | MVP |
| CON-08 | Connector definitions shall be tenant-scoped and not visible to other tenants. | MVP |

### 4.7 Storage

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| STR-01 | The system shall ship with TimescaleDB as the default time-series storage backend. | MVP |
| STR-02 | The system shall ship with PostgreSQL as the default relational/metadata storage. | MVP |
| STR-03 | The system shall support MongoDB as an optional document store. | MVP |
| STR-04 | The system shall support MinIO/S3-compatible object storage for blobs and batch files. | MVP |
| STR-05 | Storage backends shall be selectable per pipeline node or per data type. | MVP |
| STR-06 | The platform shall expose a data query API for stored time-series and relational data. | MVP |
| STR-07 | Data retention policies shall be configurable per tenant/workspace. | V2 |

### 4.7 Admin UI

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| UI-01 | The admin UI shall be a single-page application built with React 18+ and TypeScript, using Vite as the build tool and Tailwind CSS for styling. | MVP |
| UI-02 | The UI shall provide dashboard views showing pipeline status, throughput, and recent alerts. | MVP |
| UI-03 | The UI shall provide a pipeline builder (ordered step list for MVP; full graph editor in V2). | MVP |
| UI-04 | The UI shall provide an in-browser Python code editor (Monaco Editor) for all four module types. | MVP |
| UI-05 | The UI shall provide connector and module configuration forms. | MVP |
| UI-06 | The UI shall provide user and tenant management screens. | MVP |
| UI-07 | The UI shall provide a basic built-in data visualisation view (line/bar charts for quick sanity checks). | MVP |
| UI-08 | The UI shall provide alert rule configuration, channel management, and alert history views. | MVP |
| UI-09 | The UI shall be usable from both desktop and tablet form factors. | MVP |
| UI-10 | The UI shall support both light and dark themes. | V2 |
| UI-11 | The UI shall provide drag-and-drop node graph pipeline editing. | V2 |

### 4.8 REST API

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| API-01 | All platform management operations shall be available via a REST API (`/api/internal/v1/`) from day one. | MVP |
| API-02 | All data query operations shall be available via the management API. | MVP |
| API-03 | The management API shall use JWT Bearer tokens for user authentication. | MVP |
| API-04 | The management API shall support API keys for service accounts and machine clients. | MVP |
| API-05 | API keys shall have configurable, fine-grained permission scopes. | MVP |
| API-06 | The management API shall be documented (OpenAPI/Swagger). | MVP |
| API-07 | A public data ingestion API (`/api/v1/`) shall be available for external systems to push data into pipelines without using the management API. | MVP |
| API-08 | The API shall support OAuth2/OIDC for user authentication. | V2 |
| API-09 | The API shall enforce rate limiting (default: 100 req/min per client). | V2 |

### 4.9 Multi-Tenancy

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| TEN-01 | The system shall support a hierarchical tenant model with arbitrary depth (tenant → child tenant → … → workspace). | MVP |
| TEN-02 | Tenants shall form a **strict tree**: each tenant has exactly one parent, except the single root tenant which has no parent. Multiple parents are forbidden. | MVP |
| TEN-03 | Resources (pipelines, modules, connectors, data) shall be isolated between tenants. | MVP |
| TEN-04 | Permissions shall be scoped to the level of the hierarchy at which they are granted and inherited by children. | MVP |
| TEN-05 | A **Superadmin** shall be defined as an auth user holding a TenantAdmin membership in the root tenant. There is no global `is_superadmin` flag. | MVP |
| TEN-06 | Tenant-level admins shall have no visibility into sibling tenants. | MVP |
| TEN-07 | Login identity (`auth_users`) shall be **separate** from per-tenant membership (`tenant_memberships`). One auth user may hold memberships in multiple tenants, with a maximum of one membership per (auth user, tenant) pair. | MVP |
| TEN-08 | Root-tenant admins (superadmins) shall **not** simultaneously hold memberships in sub-tenants. | MVP |
| TEN-09 | Protocol connector definitions shall be scoped to a tenant and shall not be visible to other tenants. | MVP |

---

## 5. Non-Functional Requirements

### 5.1 Performance

| ID | Requirement |
| -- | ----------- |
| NFR-P1 | The platform shall handle a sustained throughput of at least **1 billion messages per day** (~11,574 msg/sec) across the full pipeline (ingest → process → store). |
| NFR-P2 | Pipeline latency from ingestion to storage shall be below **500 ms** at the 99th percentile under nominal load. |
| NFR-P3 | The management REST API shall respond within **200 ms** at the 95th percentile for metadata queries under nominal load. |
| NFR-P4 | The platform shall support horizontal scaling of the processing layer without downtime. |
| NFR-P5 | Individual Python module containers shall start within **10 seconds** of being triggered. |

### 5.2 Reliability & Availability

| ID | Requirement |
| -- | ----------- |
| NFR-R1 | The platform shall target **99.9% uptime** for the core ingestion and processing path. |
| NFR-R2 | The system shall recover from a single-node failure without data loss (at-least-once guarantee). |
| NFR-R3 | Hot-plugging a module or connector shall not interrupt processing of unrelated pipelines. |
| NFR-R4 | Failed module container instances shall be detected and restarted automatically within 30 seconds. |

### 5.3 Security

| ID | Requirement |
| -- | ----------- |
| NFR-S1 | All inter-service communication shall use TLS in production deployments. |
| NFR-S2 | User passwords shall be hashed client-side with SHA-256 before transmission, then stored server-side using BCrypt. |
| NFR-S3 | Python module containers shall run as non-root users with a read-only filesystem and no network access by default. |
| NFR-S4 | API keys shall be stored hashed; the plaintext key shall only be shown once at creation. |
| NFR-S5 | All user actions that modify system state shall be audit-logged. |
| NFR-S6 | Input data from inbound endpoints shall be sanitised before being passed to modules to prevent injection. |

### 5.4 Maintainability & Operability

| ID | Requirement |
| -- | ----------- |
| NFR-M1 | All services shall expose `/q/health` and `/q/metrics` (Prometheus-compatible) endpoints. |
| NFR-M2 | Structured JSON logging shall be used by all platform services. |
| NFR-M3 | The platform shall be deployable from a single `docker-compose up` command in development. |
| NFR-M4 | Module and connector updates shall be possible via the UI without modifying or restarting the core platform. |
| NFR-M5 | Database schema migrations shall be automated (Flyway). |

### 5.5 Usability

| ID | Requirement |
| -- | ----------- |
| NFR-U1 | A new admin with Docker knowledge shall be able to deploy and create a working pipeline within 30 minutes using the documentation. |
| NFR-U2 | The UI shall not require a separate build step to run in development (Vite dev server sufficient). |
| NFR-U3 | The Python module interface contract for all four module types shall be clearly documented with worked examples. |

---

## 6. System Constraints

| ID | Constraint |
| -- | ---------- |
| CON-C1 | Core platform backend shall be implemented in **Kotlin on Java 21+ / Quarkus 3.x** (the JVM language may be Kotlin or Java; Kotlin is the current choice). |
| CON-C2 | Official plugins shall be implemented in **Kotlin/Java** or **Python**. |
| CON-C3 | User-written data modules shall be implemented in **Python 3.11+**. |
| CON-C4 | The admin UI shall be implemented in **React 18+ with TypeScript** (current implementation; a future migration to a lighter framework is possible but not planned). |
| CON-C5 | The internal message bus shall be **Apache Kafka**. |
| CON-C6 | User Python modules shall run in **Docker containers** (no shared-process execution). |
| CON-C7 | The platform shall not introduce mandatory external SaaS dependencies (no required cloud services). |
| CON-C8 | All third-party libraries shall be open-source with a licence compatible with MIT or Apache 2.0. |

---

## 7. Multi-Tenancy & Access Control Requirements

### 7.1 Hierarchy

```text
Platform (Superadmin scope)
└── Tenant (e.g. Organisation A)
    └── Tenant (child, e.g. Division)
        └── Tenant (e.g. Department)
            └── Workspace / Project
                └── Resources (pipelines, modules, data)
```
Tenants form an arbitrary tree. The terms "mandate" and "workspace" describe semantic levels in the hierarchy but are not enforced by the system — any tenant node can contain any resource type.

### 7.2 Roles & Permissions

The platform ships with four built-in system roles per tenant. Admins may define additional custom roles.

| Role | Scope | Key Abilities |
| ---- | ----- | ------------- |
| Superadmin | Platform | All operations, cross-tenant visibility — defined as TenantAdmin of root tenant |
| TenantAdmin | Tenant subtree | Manage child tenants, memberships, resources within their tree |
| Editor | Workspace | Create/edit/run modules, pipelines, connectors; configure alerts |
| Viewer | Workspace | Read-only access to pipelines, data, alerts |
| API Key / Service Account | Configurable scope | Programmatic access with explicit ability set |

Permissions are additive; an auth user may hold memberships in multiple tenants, each with its own role. A user's effective abilities at any point in the tree are determined by the most permissive membership in that tenant's ancestor chain.

---

## 8. Data Requirements

| ID | Requirement |
| -- | ----------- |
| DAT-01 | The system shall accept data in any format (JSON, binary, CSV, Avro, Protobuf) at inbound endpoints; format parsing is the responsibility of the IMPORT module. |
| DAT-02 | Internally, data shall be represented as JSON or as a pandas DataFrame when passed to PROCESSING and ALERT modules. |
| DAT-03 | The system shall store metadata (tenant info, pipeline config, module source, alert rules) in PostgreSQL. |
| DAT-04 | Time-series sensor/IoT data shall be stored in TimescaleDB. |
| DAT-05 | Document/unstructured data shall be optionally stored in MongoDB. |
| DAT-06 | Binary/file data shall be stored in MinIO/S3-compatible object storage. |
| DAT-07 | User-imported duplicate data shall be preserved; only system-generated duplicates (from Kafka at-least-once retries) shall be suppressed. |
| DAT-08 | Data retention and archiving policies shall be configurable at workspace level. |

---

## 9. Integration Requirements

| ID | Requirement |
| -- | ----------- |
| INT-01 | The platform shall provide a Grafana-compatible query endpoint (SimpleJSON or Flux) via an EXPORT module. |
| INT-02 | The platform shall provide a ThingsBoard MQTT export module. |
| INT-03 | The platform shall support MQTT v3.1.1 and v5.0 for both inbound and outbound connectors. |
| INT-04 | HTTP connectors shall support REST (JSON) over HTTP/1.1 and HTTP/2. |
| INT-05 | The platform shall expose a Prometheus `/q/metrics` endpoint for monitoring integration. |
| INT-06 | The platform shall support LDAP/AD and OAuth2/OIDC authentication in a future version. |

---

## 10. Deployment Requirements

| ID | Requirement |
| -- | ----------- |
| DEP-01 | The platform shall be deployable on-premises using Docker Compose (development and small production). |
| DEP-02 | The platform shall be deployable on Kubernetes using Kustomize (production). |
| DEP-03 | A Helm chart shall be provided for Kubernetes deployment (V2). |
| DEP-04 | All module containers shall be individually updatable without restarting the core platform. |
| DEP-05 | The platform shall support rolling updates for the core backend with zero downtime. |
| DEP-06 | All default credentials shall be changeable via environment variables before first start. |
| DEP-07 | The platform shall be operable as a SaaS offering (multi-instance) with shared infrastructure. |

---

## 11. MVP Scope vs. Future Scope

### MVP (v1) — Must Have

- Inbound endpoints: REST (`/api/v1/ingest/`), MQTT, WebSocket
- Custom IMPORT and EXPORT Python modules
- Four module types: IMPORT, PROCESSING, ALERT, EXPORT
- Processing pipelines with fan-out and fan-in support
- TimescaleDB + PostgreSQL built-in storage
- ALERT module with threshold, rate-of-change, time-window, absence rule types
- Alert channels: MQTT, webhook, REST, email, Slack
- Management REST API (`/api/internal/v1/`) — full CRUD + data query
- Public ingestion API (`/api/v1/`)
- JWT + API key authentication
- Multi-tenancy (arbitrary-depth tenant hierarchy)
- React 18 + TypeScript admin UI (pipeline builder, code editor, monitoring, alert config)
- Basic built-in data visualisation (line/bar charts)
- Hot-pluggable modules
- Deduplication of system-generated duplicates
- Prometheus metrics, structured logging

### V2 — Planned

- UDP inbound listener
- Pipeline versioning (draft/published/rollback)
- Drag-and-drop node graph pipeline editor
- MongoDB + MinIO/S3 storage backends
- Grafana data source endpoint + ThingsBoard MQTT export module
- OAuth2/OIDC / LDAP authentication
- Alert acknowledgement and escalation chains
- Rate limiting on management API
- Helm chart for Kubernetes
- Module code version diff view
- SaaS management plane (multi-instance orchestration)

---

*End of Requirements Specification*
