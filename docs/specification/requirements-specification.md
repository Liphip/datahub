# DataTurntable — Software Requirements Specification (SRS)

**Version:** 2.0
**Date:** 2026-09-25
**Status:** Draft

Version 2.0 keeps every requirement of version 1.1 under its original ID. Requirements whose wording changed, and the reason, are listed in [Appendix A](#appendix-a-traceability-to-version-11); the same appendix records the implementation status. New requirement groups: Lakehouse (LAK), Identity & Access (IAM), Audit (AUD), System Administration (OPS), Licensing (NFR-L).

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
- [Appendix A: Traceability to Version 1.1](#appendix-a-traceability-to-version-11)

---

## 1. Introduction

### 1.1 Purpose

This document specifies the software requirements for **DataTurntable**, a high-performance, modular, self-contained data platform for measurement, time-series and structured data. It combines a data hub (ingest, process, route, alert) with a data lakehouse (open-format storage, history, SQL). It defines what the system must do (functional requirements) and how it must perform (non-functional requirements), providing the basis for design and development decisions.

### 1.2 Project Background

DataTurntable originates from the need for a flexible, self-hostable data integration platform encountered in professional work. It is developed as a private/open-source project intended to provide a genuinely useful tool for organisations whose IT departments operate the platform for their business departments, and for the developers and domain experts who need to ingest, transform, store, and route data from heterogeneous sources — without being locked into a vendor-specific ecosystem or AI-centric platform.

### 1.3 Scope

DataTurntable covers:

- Exposing protocol endpoints (REST, MQTT, WebSocket, UDP) for external systems to push data into pipelines, and actively pulling data from external systems
- A non-linear (DAG) processing pipeline layer with transformation, filtering, ML capabilities, and dedicated alert evaluation
- Storage: a hot tier (TimescaleDB / PostgreSQL) for recent data and a lakehouse (Apache Iceberg tables on bundled S3-compatible object storage) for complete history, replay and SQL analysis
- Routing/exporting data by pushing to external endpoints (REST, MQTT, WebSocket, UDP) or exposing pull endpoints for external systems
- A single web UI for configuration, monitoring, pipeline management, data exploration and dashboards (Grafana embedded behind the common login)
- A multi-tenant hierarchical access model with installation-defined, fine-grained, time-bounded permissions
- A complete, searchable audit trail
- A public REST API for all management and data operations

Everything runs inside the installation: all components are bundled, deployed and managed by the platform, and are free and open-source software (or at least freeware).

The data focus is measurements, time series and structured data. Text, documents and binary files (PDF, images, etc.) are not a focus.

Out of scope: DataTurntable is **not** an AI/LLM platform, a general BI suite, a document store, or a replacement for purpose-built message brokers (it uses them as infrastructure).

### 1.4 Definitions & Abbreviations

| Term | Definition |
| ---- | ---------- |
| Installation | One deployment of DataTurntable (single node or Kubernetes cluster), operated by one organisation |
| Tenant | Organisational unit in the strict tree hierarchy; one parent except the root |
| Auth User | Installation-wide login identity (`users` table); identified by email or by an external identity provider subject |
| Membership / Role assignment | A record granting one role to one auth user at a scope (system, or a tenant subtree), optionally time-bounded |
| System administrator | A user holding system-scope roles (typically the IT department) |
| Tenant administrator | A user holding tenant administration permissions for a tenant subtree (typically a department's key user) |
| End user | A user building and running pipelines, modules and dashboards inside a tenant |
| Superadmin | Term of version 1.1; replaced by system-scope role assignments (see IAM) |
| Module | A user-written or official Python component; types: IMPORT, DB_LOAD, PROCESSING, MERGE, ALERT, EXPORT, DB_SAVE |
| Pipeline | A directed acyclic graph (DAG) of module nodes from data source(s) to data sink(s) |
| Connection | A system-managed definition of how to reach an external system (endpoint, credentials) |
| Connector | A tenant-usable protocol interface (MQTT, REST, WebSocket, UDP, File, Custom) based on a connection, referenced by IMPORT/EXPORT pipeline nodes |
| Hot tier | TimescaleDB/PostgreSQL storage for recent data with low-latency access |
| Lakehouse | Apache Iceberg tables on S3-compatible object storage, with the platform's own catalog and SQL engine |
| Bronze / Silver / Gold | Raw ingested data / cleaned pipeline output / aggregated or curated data |
| Fan-out | One module's output routed to multiple downstream modules |
| Fan-in | Multiple upstream module outputs combined by a single downstream (MERGE) module |
| FOSS | Free and open-source software |
| SRS | Software Requirements Specification |
| MVP | Minimum Viable Product |
| RBAC | Role-Based Access Control |
| DAG | Directed Acyclic Graph |

---

## 2. Overall Description

### 2.1 Product Perspective

DataTurntable acts as a **data router, processor and store** sitting between heterogeneous data sources and data sinks. It is architecturally similar to platforms such as Apache NiFi or Node-RED for the processing side, and to a lakehouse for the storage side, but is designed to be self-hostable, Python-friendly for data logic, and manageable through a single web UI.

The platform occupies two positions relative to external systems:

**As a server (passive / push-to-us):** DataTurntable exposes protocol endpoints. External systems push data to these endpoints. The platform then routes data through pipelines.

**As a client (active / pull-from-them or push-to-them):** DataTurntable actively connects to external systems to pull data (IMPORT modules) or to push processed results (EXPORT modules). It can also expose read-only endpoints that external systems pull from (e.g. Grafana or SQL clients querying the platform).

### 2.2 Product Functions (Summary)

1. Expose REST (`/api/ingest/v1/`), MQTT, WebSocket, and UDP endpoints for external systems to push data into pipelines
2. Actively pull data from external sources via IMPORT modules and connectors
3. Route data through configurable non-linear (DAG) processing pipelines
4. Apply Python-based transformation and filtering via PROCESSING and MERGE modules in isolated containers
5. Evaluate alert conditions via dedicated ALERT modules and dispatch to configured channels
6. Persist data to the hot tier and to lakehouse tables; keep raw data for replay
7. Query stored data with SQL, including history and time travel
8. Push processed data to external systems (REST, MQTT, WebSocket, UDP) or expose pull endpoints
9. Expose a full management and data REST API
10. Provide a single web UI for configuration, monitoring, code editing, data exploration and dashboards
11. Enforce multi-tenant isolation and installation-defined, fine-grained, time-bounded permissions
12. Record every action with impact in a searchable audit trail with notes
13. Allow all modules and connectors to be installed, updated, and toggled at runtime
14. Let system administrators operate the installation (health, upgrades, backups, scaling) from the same UI

### 2.3 Assumptions and Dependencies

- Kafka is used as the internal message bus and pipeline backbone; it is a required, bundled component.
- A container runtime is required for running user Python modules (Docker on a single node, Kubernetes pods in a cluster).
- Dashboards are built with the bundled Grafana, embedded in the UI behind the common login.
- At-least-once delivery with deduplication of system-generated duplicates is the target; exactly-once is not guaranteed.
- Supported deployment targets are a single node (Docker Compose) and Kubernetes. The only external prerequisites are the host or cluster (with a storage class) and a backup destination.
- All bundled components are FOSS or freeware; no component requires a paid licence or licence key (NFR-L1).

---

## 3. Stakeholders & Users

| Role | Description |
| ---- | ----------- |
| System administrator | IT department. Operates the installation (deployment, upgrades, backups, monitoring), creates tenants, provisions connections to external systems, manages role templates. Has no access to tenant data unless explicitly granted a data role. |
| Tenant administrator | Key user of a department. Manages the tenant subtree: members, role assignments, connection requests, tenant dashboards, pipeline oversight. |
| Editor | End user. Creates and edits modules, pipelines, connectors on granted connections; runs pipelines; builds dashboards. |
| Viewer | End user with read-only access to pipelines, data, alerts and dashboards. |
| Auditor | Reads audit trails in a given scope (system or tenant). |
| API Client / Service Account | Machine identity for programmatic access (API key). |

The roles above are delivered as built-in role templates. Each installation decides which permissions its roles contain and who holds them (IAM).

---

## 4. Functional Requirements

Requirements are tagged: `MVP` = required for initial release; `V2` = planned post-MVP.

### 4.1 Inbound Data (Import / Ingestion)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| IMP-01 | The system shall expose a REST endpoint (`POST /api/ingest/v1/ingest/{pipelineId}`) that accepts arbitrary JSON payloads from external systems and routes them into the named pipeline. A batch form accepting multiple records per request shall be available. | MVP |
| IMP-02 | The system shall expose an MQTT subscription interface: the platform subscribes to configurable topics on the bundled or an external MQTT broker; incoming messages are routed into the named pipeline. | MVP |
| IMP-03 | The system shall expose a WebSocket server endpoint; external clients connect and stream frames into a pipeline. | MVP |
| IMP-04 | The system shall expose a configurable UDP listener; incoming datagrams are routed into a pipeline. | V2 |
| IMP-05 | Users shall be able to write custom IMPORT modules (Python) that parse data the platform fetched via a connector (REST poll, file watch, broker subscription, etc.). | MVP |
| IMP-06 | Inbound endpoints shall impose no default data structure; all payload formats are forwarded as-is to the pipeline's first module. | MVP |
| IMP-07 | Inbound endpoints shall support both live (streaming) and batch data modes. | MVP |
| IMP-08 | All inbound protocol endpoints shall be hot-pluggable; enabling or disabling one shall not affect other pipelines. | MVP |
| IMP-09 | Inbound endpoints shall authenticate callers with API keys scoped to the pipeline or connector; unauthenticated ingestion shall be rejected. | MVP |

### 4.2 Processing Pipeline

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| PIP-01 | Users shall be able to define pipelines as directed acyclic graphs (DAGs) of module nodes connected by edges. | MVP |
| PIP-02 | Pipelines shall support fan-out: one module's output can be routed to multiple downstream modules simultaneously. | MVP |
| PIP-03 | Pipelines shall support fan-in: a MERGE node receives the outputs of multiple upstream nodes and combines them. | MVP |
| PIP-04 | Each pipeline node shall reference a specific module version of a type compatible with the node (IMPORT/DB_LOAD, PROCESSING, MERGE, ALERT, EXPORT/DB_SAVE). | MVP |
| PIP-05 | Pipelines shall have a visual graph representation in the UI. | MVP |
| PIP-06 | Pipelines shall support drag-and-drop node graph editing in the UI. | MVP |
| PIP-07 | Pipelines shall be version-controlled; previous versions shall be stored and restorable. | V2 |
| PIP-08 | Pipelines shall have draft and published states. | V2 |
| PIP-09 | The system shall deduplicate system-generated duplicates (from Kafka at-least-once retries) but shall not deduplicate user data unless a deduplication PROCESSING module is explicitly added. | MVP |
| PIP-10 | Pipelines shall support at-least-once delivery semantics. | MVP |
| PIP-11 | Pipeline execution metrics (throughput, error rate, latency per node) shall be collected and exposed. | MVP |
| PIP-12 | Pipeline processing shall scale horizontally: work is partitioned by tenant/pipeline and processed in micro-batches by long-running workers whose number follows the load. | MVP |
| PIP-13 | Messages that fail processing shall be routed to a dead-letter queue from which operators can inspect and requeue them. | MVP |

### 4.3 Python Module System

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| MOD-01 | Users with permission shall be able to write Python modules in the UI code editor. | MVP |
| MOD-02 | The system shall support the module types `IMPORT`, `DB_LOAD`, `PROCESSING`, `MERGE`, `ALERT`, `EXPORT` and `DB_SAVE`, each with a defined Python interface contract. | MVP |
| MOD-03 | Python modules shall run in isolated containers (Docker on a single node, pods on Kubernetes), never in the platform's own process. | MVP |
| MOD-04 | Container instances shall have configurable resource limits (CPU, memory). | MVP |
| MOD-05 | Module containers shall have no network access by default; network access is explicitly granted per module by a holder of the corresponding permission. Installing module requirements shall not require network access for the module at run time. | MVP |
| MOD-06 | The module sandbox shall pre-install: pandas, numpy, scikit-learn; optionally PyTorch, xgboost. | MVP |
| MOD-07 | The platform shall inject system helper functions into module execution context: `alert_fn` for alert dispatch and `query_fn` for tenant-scoped data queries. | MVP |
| MOD-08 | Data shall be passed to PROCESSING and ALERT modules as a dict or pandas DataFrame; modules shall return the same types. | MVP |
| MOD-09 | Module source code and versions shall be stored in PostgreSQL by default. | MVP |
| MOD-10 | Module code versions shall be tracked; previous versions shall be auditable. | MVP |
| MOD-11 | Modules shall be scoped per tenant; a module in one tenant shall not be visible to another. | MVP |
| MOD-12 | Each module version shall carry a configuration object that is passed to the module class constructor. | MVP |
| MOD-13 | Errors while loading or instantiating a module shall fail the node visibly (run log, metrics); they shall never be silently ignored. | MVP |

### 4.4 Alert Module

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| ALT-01 | The system shall support a dedicated `ALERT` module type separate from `PROCESSING`. Alert modules evaluate conditions and dispatch alerts but do not modify or forward the data stream. | MVP |
| ALT-02 | Built-in alert rule types shall include: threshold, rate-of-change, time window, and absence of data. They are available as built-in ALERT node types configured in the UI, without writing code. | MVP |
| ALT-03 | Alert modules shall support custom Python logic via the `AlertModule` class interface. | MVP |
| ALT-04 | Alert channels shall include: MQTT, HTTP webhook, REST endpoint, email (SMTP), and Slack. | MVP |
| ALT-05 | Alerts shall have configurable severity levels: info, warning, error, critical. | MVP |
| ALT-06 | Alert history shall be stored and queryable via the management API. | MVP |
| ALT-07 | Alert deduplication (cooldown window) shall prevent repeated firing for the same condition within a configurable period. | MVP |
| ALT-08 | Alerts shall be acknowledged by operators in the UI. | V2 |
| ALT-09 | Alert escalation chains shall be supported. | V2 |
| ALT-10 | Each ALERT node shall have a default alert channel; an individual alert may name another channel of the same tenant. | MVP |
| ALT-11 | Every delivery attempt shall be recorded with its outcome (sent, failed); failed deliveries shall be visible in the UI. | MVP |
| ALT-12 | Channel secrets (auth tokens, webhook URLs containing tokens) shall be stored encrypted and never returned in plaintext after creation. MQTT alert channels shall reuse an MQTT connector instead of storing broker credentials. | MVP |

### 4.5 Outbound Data (Export)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| EXP-01 | EXPORT modules shall be able to push data to external REST endpoints (HTTP POST/PUT). | MVP |
| EXP-02 | EXPORT modules shall be able to publish data to external MQTT brokers. | MVP |
| EXP-03 | EXPORT modules shall be able to push data over WebSocket connections. | MVP |
| EXP-04 | EXPORT modules shall be able to send data via UDP datagrams. | V2 |
| EXP-05 | Pipeline nodes shall be able to write data to the platform's storage: hot-tier tables (DB_SAVE) and lakehouse tables. | MVP |
| EXP-06 | The platform shall expose pull endpoints that external systems query: the data query API, a Grafana data source, and the Iceberg REST catalog for SQL/analytics tools (all permission-checked). | MVP |
| EXP-07 | Users shall be able to write custom EXPORT modules in Python. | MVP |
| EXP-08 | Export connectors shall be hot-pluggable. | MVP |

### 4.6 Connectors and Connections

Connections describe how to reach an external system (endpoint, credentials). They belong to the system plane: system administrators create them, possibly on request of a tenant administrator, and grant them to tenants. Connectors are the tenant-level protocol interfaces that pipeline nodes reference; each connector uses a granted connection.

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| CON-01 | The system shall allow tenants to define named connector entities for each supported protocol: MQTT, REST, WebSocket, UDP, File, Custom. | MVP |
| CON-02 | A connection shall be usable for input, output, or both; each connector declares its direction (INPUT or OUTPUT), so a bidirectional system is used through two connectors on the same connection. | MVP |
| CON-03 | IMPORT pipeline nodes shall reference an INPUT connector. | MVP |
| CON-04 | EXPORT pipeline nodes shall reference an OUTPUT connector. | MVP |
| CON-05 | Connector configuration (host, port, credentials, topic, etc.) shall be stored as a typed JSON config block, encrypted at rest. The platform performs all external I/O; module containers receive only non-sensitive connector metadata, never credentials. | MVP |
| CON-06 | Connectors shall be manageable (create, update, delete, enable/disable) independently of pipelines. | MVP |
| CON-07 | Connectors shall be hot-pluggable; updating a connector's config shall take effect on the next pipeline execution without restarting the core platform. | MVP |
| CON-08 | Connector definitions shall be tenant-scoped and not visible to other tenants. | MVP |
| CON-09 | Connections (endpoint and credentials) shall be created and maintained by system administrators; their secrets shall never be visible to tenants. | MVP |
| CON-10 | Connections shall be granted to tenants with a scope (e.g. topic prefix, path, rate limit) and an optional validity period. | MVP |
| CON-11 | Tenant administrators shall be able to request a new connection in the UI; the request, its processing by system administrators and the resulting grant shall be tracked in the UI and audited. | MVP |
| CON-12 | Broker credentials managed by the platform shall be provisioned on the bundled MQTT broker, so that anonymous access can be disabled. | MVP |

### 4.7 Storage

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| STR-01 | The system shall ship with TimescaleDB as the default hot-tier time-series storage backend. | MVP |
| STR-02 | The system shall ship with PostgreSQL as the default relational/metadata storage. | MVP |
| STR-03 | Variable-schema structured data shall be supported through schema evolution of lakehouse tables (columns can be added; nested values can be stored as structured types). | MVP |
| STR-04 | The system shall bundle S3-compatible object storage for lakehouse tables and batch files. | MVP |
| STR-05 | Storage targets (hot-tier table, lakehouse table) shall be selectable per pipeline node. | MVP |
| STR-06 | The platform shall expose a data query API for stored time-series and relational data. | MVP |
| STR-07 | Data retention policies shall be configurable per tenant/workspace and per storage tier. | MVP |

### 4.8 Lakehouse

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| LAK-01 | Lakehouse tables shall use the Apache Iceberg open table format on the bundled object storage. | MVP |
| LAK-02 | The platform shall provide the table catalog itself (no separately operated catalog service) and shall expose it through the Iceberg REST catalog API, with one namespace per tenant and access governed by the platform's permissions. | MVP |
| LAK-03 | Every ingested record shall be retained unmodified in a bronze table per tenant, so that pipelines can be replayed and reprocessed. | MVP |
| LAK-04 | Pipeline outputs shall be writable to silver/gold lakehouse tables (EXP-05). | MVP |
| LAK-05 | Measurement data shall use a standard time-series layout (tenant, series id, tags, timestamp, values, quality, ingest time), partitioned by time and series, sorted for efficient series/time-range reads; late-arriving data shall be handled. | MVP |
| LAK-06 | The platform shall maintain rollups (at least 1 minute, 1 hour, 1 day with min, max, avg, count, last) continuously. | MVP |
| LAK-07 | Users shall be able to query lakehouse and hot-tier data with SQL from the UI (SQL workspace) and the API: interactively for recent data and rollups, as asynchronous jobs for large scans. | MVP |
| LAK-08 | Users shall be able to read a table as of an earlier snapshot or point in time (time travel). | MVP |
| LAK-09 | The platform shall run table maintenance automatically: compaction, snapshot expiry, orphan-file cleanup. | MVP |
| LAK-10 | The UI shall provide a data catalog: tables, schemas, partitions, snapshots, lineage (which pipelines write/read a table). | MVP |
| LAK-11 | Tenant isolation shall be enforced by the catalog and the query layer; a query can never read another tenant's tables without an explicit permission. | MVP |
| LAK-12 | Structured files (CSV, Parquet, JSON) shall be importable into tables. | MVP |

### 4.9 Admin UI

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| UI-01 | The UI shall be a single-page application built with React 19+ and TypeScript, using Vite as the build tool and Tailwind CSS for styling. | MVP |
| UI-02 | The UI shall provide dashboard views showing pipeline status, throughput, and recent alerts. | MVP |
| UI-03 | The UI shall provide a graph-based pipeline builder. | MVP |
| UI-04 | The UI shall provide an in-browser Python code editor (Monaco Editor) for all module types. | MVP |
| UI-05 | The UI shall provide connector and module configuration forms. | MVP |
| UI-06 | The UI shall provide user and tenant management screens. | MVP |
| UI-07 | The UI shall provide a basic built-in data visualisation view (line/bar charts for quick sanity checks). | MVP |
| UI-08 | The UI shall provide alert configuration (built-in rule nodes, custom ALERT modules, default channels), channel management, and alert history views. | MVP |
| UI-09 | The UI shall be usable from both desktop and tablet form factors. | MVP |
| UI-10 | The UI shall support both light and dark themes. | V2 |
| UI-11 | The UI shall provide drag-and-drop node graph pipeline editing. | MVP |
| UI-12 | The UI shall be **intuitive and easy to understand for new users**, with embedded contextual examples (e.g. boilerplate Python code for each module type, example connector configs, sample pipeline structures). | MVP |
| UI-13 | All major configuration forms shall include inline help text and/or example values to guide first-time users. | MVP |
| UI-14 | The module code editor shall show a pre-filled boilerplate template appropriate to the selected module type when creating a new module. | MVP |
| UI-15 | The platform shall have a single UI: every component a user interacts with (dashboards, monitoring, documentation, data catalog, SQL workspace, audit) is reached through it and behind the common login. | MVP |
| UI-16 | Users of every level shall be able to build their own dashboards and visualisations with the bundled Grafana, embedded in the UI. | MVP |
| UI-17 | The configuration page of every component (pipeline, connector, connection, tenant, user, role, dashboard, alert channel, …) shall show an Activity tab with its audit trail and notes (AUD). | MVP |
| UI-18 | System administrators shall have a system area in the UI for installation operations (OPS) and system monitoring. | MVP |
| UI-19 | User and administrator documentation shall be available inside the UI. | MVP |

### 4.10 REST API

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| API-01 | All platform management operations shall be available via a REST API (`/api/internal/v1/`) from day one. | MVP |
| API-02 | All data query operations shall be available via the API (`/api/v1/`). | MVP |
| API-03 | The API shall authenticate browser users with the platform session (HttpOnly cookie with CSRF protection) and machine clients with JWT Bearer tokens or API keys. | MVP |
| API-04 | The management API shall support API keys for service accounts and machine clients. | MVP |
| API-05 | API keys shall have configurable, fine-grained permission scopes and an optional expiry. | MVP |
| API-06 | The management API shall be documented (OpenAPI/Swagger). | MVP |
| API-07 | A public data ingestion API (`/api/ingest/v1/`) shall be available for external systems to push data into pipelines without using the management API. | MVP |
| API-08 | The platform shall support OAuth2/OIDC for user authentication (see IAM-09). | MVP |
| API-09 | The API shall enforce rate limiting (default: 100 req/min per client). | V2 |

### 4.11 Multi-Tenancy

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| TEN-01 | The system shall support a hierarchical tenant model with arbitrary depth (tenant → child tenant → … → workspace). | MVP |
| TEN-02 | Tenants shall form a **strict tree**: each tenant has exactly one parent, except the single root tenant which has no parent. Multiple parents are forbidden. | MVP |
| TEN-03 | Resources (pipelines, modules, connectors, data, dashboards) shall be isolated between tenants, including at the database and storage level. | MVP |
| TEN-04 | Permissions shall be scoped to the level of the hierarchy at which they are granted and inherited by children. | MVP |
| TEN-05 | Installation-wide administration shall be granted through role assignments at system scope. There is no global `is_superadmin` flag. | MVP |
| TEN-06 | Tenant-level admins shall have no visibility into sibling tenants. | MVP |
| TEN-07 | Login identity (`users`) shall be **separate** from role assignments. One auth user may hold role assignments in multiple tenants, with a maximum of one membership per (auth user, tenant) pair. | MVP |
| TEN-08 | System-scope role assignments shall not implicitly grant access to tenant data; access to tenant data requires a data permission assigned at system scope or in the tenant. | MVP |
| TEN-09 | Protocol connector definitions shall be scoped to a tenant and shall not be visible to other tenants. | MVP |

### 4.12 Identity & Access (IAM)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| IAM-01 | The product shall define the permission vocabulary: stable permission names of the form `resource:action` (e.g. `pipeline:write`, `data:read`, `system.config:read`), each corresponding to an enforcement point. | MVP |
| IAM-02 | Each installation shall define its roles and role assignments; which permissions a role contains, and who holds it, is not fixed by the product. | MVP |
| IAM-03 | Role assignments shall have a scope: system (whole installation) or tenant (optionally including descendants); resource scope (single pipeline or connection) is planned. | MVP (resource scope V2) |
| IAM-04 | Role assignments shall support a validity period (start and end time), the granting user and a reason. Permissions shall be evaluated on every request, so that an assignment takes effect and expires exactly on time. | MVP |
| IAM-05 | The installation shall ship built-in role templates (e.g. System Operator, Config Auditor, Cross-Tenant Data Analyst, Tenant Admin, Editor, Viewer). Built-in templates are marked as such, cannot be modified, and can be inspected, cloned and disabled. | MVP |
| IAM-06 | System administrators shall manage role templates in the UI: create custom roles, clone, edit custom roles, disable (with an impact preview and a reason). | MVP |
| IAM-07 | On upgrade, changes to built-in templates shall be shown to system administrators and audited; custom roles shall never change automatically. | MVP |
| IAM-08 | Nobody shall be able to grant permissions they do not hold themselves; the last holder of system administration cannot be removed. | MVP |
| IAM-09 | Users shall log in once; every component (UI, API, embedded Grafana, monitoring, documentation) shall use the same session via reverse-proxy authentication. Components shall not be reachable around the proxy. | MVP |
| IAM-10 | The platform shall act as an OIDC client for external identity providers (e.g. Keycloak, Entra ID, AD FS), configured at run time in the UI: issuer, client, scopes, claim-to-role-assignment mapping, just-in-time user provisioning. | MVP |
| IAM-11 | Local password login shall be restrictable to designated break-glass accounts. | MVP |
| IAM-12 | Logout shall end the session everywhere (RP-initiated and back-channel logout); administrators shall be able to revoke sessions. | MVP |
| IAM-13 | Directory synchronisation (SCIM) for immediate revocation of IdP group changes. | V2 |

### 4.13 Audit (AUD)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| AUD-01 | Every action with impact shall leave an audit record: all create/update/delete operations, pipeline and connector start/stop/run/cancel/retry, logins and failed logins, permission denials, role and assignment changes including automatic activation and expiry, connection requests and grants, deployments, scaling, upgrades, backups, restores, retention deletions and lake maintenance. | MVP |
| AUD-02 | Access to tenant data at system scope shall always be audited; access and exports within a tenant shall be audited when the installation enables it. | MVP |
| AUD-03 | An audit record shall contain: time, actor (identity, name snapshot, type, authentication method, session, IP address, user agent), action, target (type, id, name snapshot, parent), tenant, scope, outcome, changes (before/after, secrets redacted), reason, source, correlation id. | MVP |
| AUD-04 | The audit record shall be written in the same transaction as the change; if it cannot be written, the change shall fail. Actions outside the database record an intent before and an outcome after. | MVP |
| AUD-05 | Audit records shall be append-only (no update or delete by the application) and tamper-evident (hash chain), verifiable from the UI. | MVP |
| AUD-06 | Users shall be able to attach notes to audit records and add standalone notes to a component's timeline. Notes are immutable and audited; corrections are follow-up notes. | MVP |
| AUD-07 | The audit trail shall be searchable and filterable (time range, actor, action, target, tenant, outcome, source, full text), with saved filters and CSV/JSON export (the export itself is audited). | MVP |
| AUD-08 | Audit visibility shall follow the `audit:read` permission scope. | MVP |
| AUD-09 | Audit retention shall be configurable per installation with a minimum; older records are archived to the lakehouse and stay queryable. | MVP |
| AUD-10 | Audit records may be forwarded to external systems (syslog, OTLP, webhook). | V2 |

### 4.14 System Administration (OPS)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| OPS-01 | System administrators shall see the health, version and scaling state of every bundled component in the UI. | MVP |
| OPS-02 | Upgrades of the installation shall be started and followed from the UI; database migrations run automatically. | MVP |
| OPS-03 | Backups (metadata databases, hot tier, lakehouse catalog) shall be scheduled and restorable from the UI to a configured backup destination. | MVP |
| OPS-04 | System monitoring (metrics and logs of all components) shall be available to holders of `system.monitoring:read`, embedded in the UI. It shall contain no tenant payload data. | MVP |
| OPS-05 | System administrators shall manage tenants and tenant quotas. | MVP |
| OPS-06 | Module containers, connectors and platform services shall be restarted automatically after failures (see NFR-R4). | MVP |

---

## 5. Non-Functional Requirements

### 5.1 Performance

| ID | Requirement |
| -- | ----------- |
| NFR-P1 | The platform shall handle a sustained throughput of at least **1 billion messages per day** (~11,574 msg/sec) across the full pipeline (ingest → process → store) on a multi-node, autoscaling Kubernetes deployment, with a design target of 50,000 msg/sec sustained peak. Single-node deployments are not held to this figure. The claim shall be backed by a reproducible, documented benchmark. |
| NFR-P2 | Pipeline latency from ingestion to hot-tier storage shall be below **500 ms** at the 99th percentile under nominal load. Data shall be visible in lakehouse tables within the configured commit interval (default ≤ 60 s). |
| NFR-P3 | The management REST API shall respond within **200 ms** at the 95th percentile for metadata queries under nominal load. |
| NFR-P4 | The platform shall support horizontal scaling of the ingestion and processing layers without downtime, driven automatically by load (e.g. consumer lag). |
| NFR-P5 | Module workers shall be ready within **10 seconds** of being started; processing an individual message shall not start a new container. |
| NFR-P6 | Interactive SQL queries over recent data and rollups shall return within 5 seconds at the 95th percentile. |

### 5.2 Reliability & Availability

| ID | Requirement |
| -- | ----------- |
| NFR-R1 | The platform shall target **99.9% uptime** for the core ingestion and processing path (Kubernetes HA deployment). |
| NFR-R2 | The Kubernetes HA deployment shall recover from a single-node failure without data loss (at-least-once guarantee). |
| NFR-R3 | Hot-plugging a module or connector shall not interrupt processing of unrelated pipelines. |
| NFR-R4 | Failed module container instances shall be detected and restarted automatically within 30 seconds. |

### 5.3 Security

| ID | Requirement |
| -- | ----------- |
| NFR-S1 | All inter-service communication shall use TLS in production deployments. |
| NFR-S2 | User passwords shall only be transmitted over TLS and stored server-side using BCrypt; passwords shall have at least 8 characters. |
| NFR-S3 | Python module containers shall run as non-root users with a read-only filesystem and no network access by default. |
| NFR-S4 | API keys shall be stored hashed; the plaintext key shall only be shown once at creation. |
| NFR-S5 | All user actions that modify system state shall be audit-logged (see AUD). |
| NFR-S6 | Input data from inbound endpoints shall be sanitised before being passed to modules to prevent injection. |
| NFR-S7 | Signing keys, database credentials and connector secrets shall be supplied per installation (secret store / Kubernetes Secrets) and never be part of images or the repository. |
| NFR-S8 | Tenant isolation shall be enforced by the data layer itself (per-tenant database roles, catalog namespaces), not only by application checks. |

### 5.4 Maintainability & Operability

| ID | Requirement |
| -- | ----------- |
| NFR-M1 | All services shall expose `/q/health` and `/q/metrics` (Prometheus-compatible) endpoints. |
| NFR-M2 | Structured JSON logging shall be used by all platform services. User module output shall go to tenant-scoped run logs, not to the system log pipeline. |
| NFR-M3 | The platform shall be deployable from a single `docker compose up` command on a single node. |
| NFR-M4 | Module and connector updates shall be possible via the UI without modifying or restarting the core platform. |
| NFR-M5 | Database schema migrations shall be automated (Flyway). |
| NFR-M6 | Every change shall pass continuous integration (build, lint, tests against the real database engine). |

### 5.5 Usability

| ID | Requirement |
| -- | ----------- |
| NFR-U1 | A new admin with Docker knowledge shall be able to deploy and create a working pipeline within 30 minutes using the documentation. |
| NFR-U2 | The UI shall not require a separate build step to run in development (Vite dev server sufficient). |
| NFR-U3 | The Python module interface contract for all module types shall be clearly documented with worked examples. |

### 5.6 Licensing

| ID | Requirement |
| -- | ----------- |
| NFR-L1 | Every bundled component shall be FOSS or at least freeware. No component may require a paid licence or a licence key, including for clustering or high availability. |
| NFR-L2 | The licence inventory of all dependencies and bundled components shall be maintained with every update (`docs/development/licenses.md`). |

---

## 6. System Constraints

| ID | Constraint |
| -- | ---------- |
| CON-C1 | Core platform backend shall be implemented in **Kotlin on Java 25 / Quarkus 3.x** (the JVM language may be Kotlin or Java; Kotlin is the current choice). |
| CON-C2 | Official plugins shall be implemented in **Kotlin/Java** or **Python**. |
| CON-C3 | User-written data modules shall be implemented in **Python 3.12+** (default runtime image: Python 3.14). |
| CON-C4 | The UI shall be implemented in **React 19+ with TypeScript**. |
| CON-C5 | The internal message bus shall be **Apache Kafka**. |
| CON-C6 | User Python modules shall run in **containers** (no shared-process execution). |
| CON-C7 | The platform shall not introduce mandatory external SaaS dependencies (no required cloud services). |
| CON-C8 | All third-party libraries linked into the platform shall be open-source with a licence compatible with MIT or Apache 2.0 (permissive or weak copyleft). Bundled services that run as separate programs may use other FOSS or freeware licences within NFR-L1 (e.g. AGPL-3.0 when unmodified). |
| CON-C9 | The lakehouse table format shall be **Apache Iceberg**; the embedded SQL engine shall run inside the platform (no separately operated query cluster unless benchmarks require it). |

---

## 7. Multi-Tenancy & Access Control Requirements

### 7.1 Hierarchy

```text
Installation (system scope — system administrators)
└── Tenant (root, e.g. Organisation)
    └── Tenant (child, e.g. Division)
        └── Tenant (e.g. Department — tenant administrators = key users)
            └── Workspace / Project
                └── Resources (pipelines, modules, connectors, data, dashboards)
```

Tenants form an arbitrary tree. The terms "mandate" and "workspace" describe semantic levels in the hierarchy but are not enforced by the system — any tenant node can contain any resource type.

### 7.2 Roles & Permissions

Permissions are product-defined; roles and assignments are installation-defined (IAM-01, IAM-02). The platform ships these built-in role templates:

| Template | Typical scope | Key Abilities |
| -------- | ------------- | ------------- |
| System Operator | System | Installation operations, tenants, connections, role templates; no tenant data |
| Config Auditor | System | Read configuration and audit trails of the whole installation; no tenant data |
| Cross-Tenant Data Analyst | System | Read data of all tenants (always audited) |
| Tenant Admin | Tenant subtree | Manage child tenants, memberships, connection requests, resources within their tree |
| Editor | Tenant / workspace | Create/edit/run modules, pipelines, connectors on granted connections; configure alerts; build dashboards |
| Viewer | Tenant / workspace | Read-only access to pipelines, data, alerts, dashboards |
| API Key / Service Account | Configurable scope | Programmatic access with explicit ability set |

Permissions are additive; an auth user may hold role assignments in multiple tenants and at system scope. A user's effective abilities at any point in the tree are the union of the currently valid assignments at that tenant, its ancestors (inherited) and system scope.

---

## 8. Data Requirements

| ID | Requirement |
| -- | ----------- |
| DAT-01 | The system shall accept data in any format (JSON, binary, CSV, Avro, Protobuf) at inbound endpoints; format parsing is the responsibility of the IMPORT module. |
| DAT-02 | Internally, data shall be represented as JSON or as a pandas DataFrame when passed to PROCESSING and ALERT modules. |
| DAT-03 | The system shall store metadata (tenant info, pipeline config, module source, alert configuration, roles, audit trail) in PostgreSQL. |
| DAT-04 | Recent time-series sensor/IoT data shall be stored in TimescaleDB (hot tier); complete history in lakehouse tables. |
| DAT-05 | Variable-schema structured data shall be stored in lakehouse tables with schema evolution (see STR-03). |
| DAT-06 | Batch files shall be stored in the bundled object storage; structured files are imported into tables (LAK-12). Unstructured binary content is not a focus. |
| DAT-07 | User-imported duplicate data shall be preserved; only system-generated duplicates (from Kafka at-least-once retries) shall be suppressed. |
| DAT-08 | Data retention and archiving policies shall be configurable at workspace level. |

---

## 9. Integration Requirements

| ID | Requirement |
| -- | ----------- |
| INT-01 | The platform shall provide a Grafana data source that queries the platform's query API with the logged-in user's identity, so that dashboards respect the same fine-grained, time-bounded permissions as the rest of the platform. |
| INT-02 | The platform shall provide a ThingsBoard MQTT export module. |
| INT-03 | The platform shall support MQTT v3.1.1 and v5.0 for both inbound and outbound connectors. |
| INT-04 | HTTP connectors shall support REST (JSON) over HTTP/1.1 and HTTP/2. |
| INT-05 | The platform shall expose a Prometheus `/q/metrics` endpoint for monitoring integration. |
| INT-06 | The platform shall support OAuth2/OIDC authentication (IAM-10); LDAP/AD directories are supported through an OIDC identity provider. |
| INT-07 | External SQL and analytics tools shall be able to read lakehouse tables through the Iceberg REST catalog (LAK-02) with platform credentials. |

---

## 10. Deployment Requirements

| ID | Requirement |
| -- | ----------- |
| DEP-01 | The platform shall be deployable on a single node using Docker Compose (development and small production). |
| DEP-02 | The platform shall be deployable on Kubernetes using Kustomize, with single-instance infrastructure or a high-availability overlay based on FOSS operators (CloudNativePG, Strimzi, RabbitMQ Cluster Operator). |
| DEP-03 | A single Helm chart shall install the complete platform including its bundled components and operators. |
| DEP-04 | All module containers shall be individually updatable without restarting the core platform. |
| DEP-05 | The platform shall support rolling updates for the core backend with zero downtime. |
| DEP-06 | All default credentials shall be changeable via environment variables or Kubernetes Secrets before first start. |
| DEP-07 | The platform shall be operable as a SaaS offering (multi-instance) with shared infrastructure. |
| DEP-08 | No other deployment targets are supported (Docker Swarm stacks were removed). |

---

## 11. MVP Scope vs. Future Scope

### MVP (v1) — Must Have

- Inbound endpoints: REST (`/api/ingest/v1/`), MQTT, WebSocket; authenticated with API keys
- Custom IMPORT and EXPORT Python modules; DB_LOAD/DB_SAVE storage nodes
- Module types: IMPORT, DB_LOAD, PROCESSING, MERGE, ALERT, EXPORT, DB_SAVE
- Graph pipeline editor with fan-out and fan-in; horizontally scaling processing; dead-letter queue
- TimescaleDB + PostgreSQL hot tier; Iceberg lakehouse on bundled object storage with bronze retention, rollups, SQL workspace, time travel and data catalog
- ALERT module with threshold, rate-of-change, time-window, absence rule types; cooldown; default channel per node
- Alert channels: MQTT, webhook, REST, email, Slack
- Management REST API (`/api/internal/v1/`) — full CRUD; data API (`/api/v1/`)
- Public ingestion API (`/api/ingest/v1/`)
- Session login with reverse-proxy authentication for all components, OIDC client, JWT + API key authentication for machines
- Installation-defined roles, built-in role templates, time-bounded role assignments, system/tenant scopes
- Complete audit trail with notes, activity tabs and search
- Multi-tenancy (arbitrary-depth tenant hierarchy) with data-layer isolation
- Connections managed by system administrators, grants and request workflow
- Single React 19 + TypeScript UI (pipeline builder, code editor, monitoring, alert config, catalog, SQL, embedded Grafana, docs, system area)
- Basic built-in data visualisation (line/bar charts)
- Hot-pluggable modules
- Deduplication of system-generated duplicates
- Prometheus metrics, structured logging
- Single-node Docker Compose, Kubernetes (single-instance and HA), single Helm chart
- Reproducible 1B/day benchmark on Kubernetes

### V2 — Planned

- UDP inbound listener and UDP export modules
- Pipeline versioning (draft/published/rollback)
- ThingsBoard MQTT export module
- Resource-scoped role assignments; SCIM directory synchronisation
- Alert acknowledgement and escalation chains
- Rate limiting on management API
- Audit forwarding to external systems
- Module code version diff view
- Light/dark theme switch
- SaaS management plane (multi-instance orchestration)

---

## Appendix A: Traceability to Version 1.1

Change: **=** unchanged, **~** wording changed (idea kept), **+** new in 2.0. Status of the implementation on 2026-09-25: **Done**, **Partial**, **Open**.

| ID | Change | Reason for change | Status |
| -- | ------ | ----------------- | ------ |
| IMP-01 | ~ | Path follows the implemented API layout (`/api/ingest/v1/`); batch form added for throughput (NFR-P1). | Partial (no batch form) |
| IMP-02 | = | | Done |
| IMP-03 | = | | Done |
| IMP-04 | = | | Open (UDP output exists, no listener) |
| IMP-05 | ~ | The platform fetches via connectors and the module parses (security: no credentials in containers, CON-05). | Done |
| IMP-06 – IMP-08 | = | | Done |
| IMP-09 | + | Ingestion is currently unauthenticated (security review). | Open |
| PIP-01, PIP-02 | = | | Done |
| PIP-03 | ~ | Fan-in is implemented by the dedicated MERGE node type. | Done |
| PIP-04 | ~ | Additional node types (DB_LOAD, MERGE, DB_SAVE). | Done |
| PIP-05, PIP-06 | ~ | The graph editor exists; the linear MVP builder is obsolete. PIP-06 moved to MVP. | Done |
| PIP-07, PIP-08 | = | | Open |
| PIP-09, PIP-10 | = | | Partial |
| PIP-11 | = | | Done |
| PIP-12, PIP-13 | + | Needed for NFR-P1; DLQ was described in the functional spec only. | Open |
| MOD-01 | = | | Done |
| MOD-02 | ~ | Seven module types instead of four. | Done |
| MOD-03 | ~ | Pods on Kubernetes; long-running workers instead of one container per message (NFR-P5). | Partial (Docker only) |
| MOD-04 | = | | Done |
| MOD-05 | ~ | Installing requirements currently opens network access. | Partial |
| MOD-06 | = | | Open (slim base image) |
| MOD-07 | = | | Partial (`query_fn` is a stub) |
| MOD-08 – MOD-11 | = | | Done |
| MOD-12, MOD-13 | + | Module config reaches the constructor; errors were previously swallowed. | Done |
| ALT-01 | = | | Done |
| ALT-02 | ~ | Realised as built-in ALERT node types; the former rule table was never evaluated and was removed. | Done |
| ALT-03, ALT-04 | = | | Done |
| ALT-05 | ~ | `error` severity exists in the implementation. | Done |
| ALT-06, ALT-07 | = | | Done |
| ALT-08, ALT-09 | = | | Open |
| ALT-10, ALT-11 | + | Alert delivery rework. | Done |
| ALT-12 | + | Channel secrets are currently stored unencrypted. | Partial (MQTT via connector done) |
| EXP-01 – EXP-03 | = | | Done |
| EXP-04 | = | | Partial (UDP output in connector runtime) |
| EXP-05 | ~ | MongoDB/MinIO replaced by hot tier + lakehouse (data focus, MinIO no longer distributed). | Partial (hot tier) |
| EXP-06 | ~ | SimpleJSON endpoint replaced by permission-checked query API, Grafana data source and Iceberg REST catalog. | Open |
| EXP-07, EXP-08 | = | | Done |
| CON-01 | = | | Done |
| CON-02 | ~ | "BOTH" realised through connections usable in both directions. | Partial |
| CON-03, CON-04 | ~ | Follows CON-02. | Done |
| CON-05 | ~ | Credentials are no longer injected into containers. | Done |
| CON-06 – CON-08 | = | | Done |
| CON-09 – CON-12 | + | Persona model: IT provisions connections, departments use them. | Open |
| STR-01, STR-02 | = | | Done |
| STR-03 | ~ | MongoDB replaced by schema evolution in the lakehouse (focus on structured data). | Open |
| STR-04 | ~ | Object storage bundled and used for the lakehouse; MinIO excluded (no longer distributed). | Open |
| STR-05 | ~ | Targets are hot tier and lakehouse. | Partial |
| STR-06 | = | | Done |
| STR-07 | ~ | Moved to MVP: required to operate 1B/day. | Open |
| LAK-01 – LAK-12 | + | Lakehouse. | Open |
| UI-01 | ~ | React 19. | Done |
| UI-02 | = | | Done |
| UI-03 | ~ | Graph builder instead of ordered list. | Done |
| UI-04 – UI-07 | = | | Done |
| UI-08 | ~ | Alert rules are ALERT nodes. | Partial |
| UI-09 | = | | Done |
| UI-10 | = | | Open |
| UI-11 | ~ | Moved to MVP (implemented). | Done |
| UI-12 – UI-14 | = | | Done |
| UI-15 – UI-19 | + | Single UI, Grafana for users, activity tabs, system area, in-app docs. | Open |
| API-01, API-02 | ~ | API-02 names the data API path. | Done |
| API-03 | ~ | Browser session via cookie for reverse-proxy authentication. | Open (JWT in localStorage) |
| API-04, API-05 | ~ | Expiry added. | Open |
| API-06 | = | | Open |
| API-07 | ~ | Path `/api/ingest/v1/`. | Done |
| API-08 | ~ | Moved to MVP. | Open |
| API-09 | = | | Open |
| TEN-01, TEN-02 | = | | Done |
| TEN-03 | ~ | Isolation at database and storage level (security review found shared DB credentials). | Partial |
| TEN-04 | = | | Done |
| TEN-05 | ~ | Superadmin replaced by system-scope assignments; still no `is_superadmin` flag. | Partial (flag removed; root Tenant Admin still acts as superadmin) |
| TEN-06, TEN-07 | = | | Done |
| TEN-08 | ~ | Replaced: system scope grants no implicit data access. | Open |
| TEN-09 | = | | Done |
| IAM-01 – IAM-13 | + | Persona and permission model. | Partial (IAM-01: one naming style enforced) |
| AUD-01 – AUD-10 | + | Paper-trail requirement (extends NFR-S5). | Open |
| OPS-01 – OPS-06 | + | System administrator persona. | Open |
| NFR-P1 | ~ | Scoped to multi-node autoscaling Kubernetes with a benchmark. | Open |
| NFR-P2 | ~ | Applies to the hot tier; lakehouse visibility bounded by the commit interval. | Open |
| NFR-P3 | = | | Open (not measured) |
| NFR-P4 | ~ | Automatic, load-driven. | Partial |
| NFR-P5 | ~ | Resolves the contradiction between per-message containers and NFR-P2. | Open |
| NFR-P6 | + | SQL workspace. | Open |
| NFR-R1, NFR-R2 | ~ | Tied to the Kubernetes HA deployment. | Partial (HA overlay exists) |
| NFR-R3, NFR-R4 | = | | Partial |
| NFR-S1 | = | | Open |
| NFR-S2 | ~ | Client-side SHA-256 pre-hashing dropped: over TLS it adds no protection (the hash becomes the password). BCrypt storage kept; minimum length added. | Done |
| NFR-S3 | = | | Partial (network with requirements) |
| NFR-S4 | = | | Open |
| NFR-S5 | ~ | Detailed in AUD. | Open |
| NFR-S6 | = | | Open |
| NFR-S7, NFR-S8 | + | Security review (committed signing key, shared DB credentials). | Open |
| NFR-M1 | = | | Done |
| NFR-M2 | ~ | Module output kept out of system logs. | Partial |
| NFR-M3 | ~ | `docker compose` (v2 CLI). | Done |
| NFR-M4, NFR-M5 | = | | Done |
| NFR-M6 | + | No CI existed. | Open |
| NFR-U1 – NFR-U3 | ~ | NFR-U3 covers all module types. | Partial |
| NFR-L1, NFR-L2 | + | Licensing policy. | Done |
| CON-C1 | ~ | Java 25. | Done |
| CON-C2 | = | | Done |
| CON-C3 | ~ | Python 3.12+, default 3.14. | Done |
| CON-C4 | ~ | React 19. | Done |
| CON-C5 – CON-C7 | ~ | CON-C6: containers on Docker or Kubernetes. | Done |
| CON-C8 | ~ | Distinguishes linked libraries from separately running bundled services. | Done |
| CON-C9 | + | Lakehouse technology. | Open |
| DAT-01, DAT-02 | = | | Done |
| DAT-03 | ~ | Alert configuration, roles, audit trail. | Done |
| DAT-04 | ~ | Hot tier plus lakehouse history. | Partial |
| DAT-05 | ~ | MongoDB replaced by lakehouse schema evolution. | Open |
| DAT-06 | ~ | Binary content not a focus; structured file import. | Open |
| DAT-07 | = | | Partial |
| DAT-08 | = | | Open |
| INT-01 | ~ | SimpleJSON endpoint replaced by a permission-checked Grafana data source. | Open |
| INT-02 | = | | Open |
| INT-03 | = | | Partial (MQTT v3.1.1 client only) |
| INT-04, INT-05 | = | | Done |
| INT-06 | ~ | OIDC in MVP; LDAP/AD via the identity provider. | Open |
| INT-07 | + | Lakehouse access for external tools. | Open |
| DEP-01 | = | | Done |
| DEP-02 | ~ | HA overlay with FOSS operators. | Done (not load-tested) |
| DEP-03 | ~ | Moved to MVP; bundles all components. | Open |
| DEP-04 – DEP-06 | = | | Partial |
| DEP-07 | = | | Open |
| DEP-08 | + | Swarm removed. | Done |

---

*End of Requirements Specification*
