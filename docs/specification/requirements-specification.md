# DataTurntable — Software Requirements Specification (SRS)

**Version:** 2.3
**Date:** 2026-09-28
**Status:** Draft

Version 2.0 keeps every requirement of version 1.1 under its original ID. Requirements whose wording changed, and the reason, are listed in [Appendix A](#appendix-a-traceability-to-version-11); the same appendix records the implementation status. New requirement groups: Lakehouse (LAK), Identity & Access (IAM), Audit (AUD), System Administration (OPS), Licensing (NFR-L).

Version 2.1: delivery is exactly once for identified records (PIP-09, PIP-10, PIP-14, DAT-07, NFR-R2); requirements found missing in the review of 2026-09-27 were added (PIP-15, ALT-13, TEN-10, UI-20, UI-21, OPS-07, DAT-09, NFR-P7, NFR-R5, NFR-R6); section 11 orders the MVP into milestones.

Version 2.2: this document is the **single requirements and status document** of the project. It absorbs the production-readiness plan (`production-ready-spec.json`, FR-001 – FR-032, NFR-001 – NFR-020; mapping in [Appendix C](#appendix-c-production-readiness-plan-ids)), the product goals of the former target-architecture draft (1.5), the feature support matrix (status now in Appendix A only), and the requirements review with its measurements ([Appendix B](#appendix-b-measurements-and-review-findings)). New requirements: IMP-14, PIP-16 – PIP-18, MOD-14 – MOD-16, EXP-09, CON-17, CON-18, IAM-14, IAM-15, API-10, NFR-P8, NFR-R10, NFR-R11, NFR-S9 – NFR-S12, NFR-M7 – NFR-M9. Version 2.3 adds MOD-17 – MOD-19 (train-and-infer machine-learning models as a pipeline use case, owner request 2026-09-28). Decisions against alternatives are recorded in section 12. How the system is built is described in the [functional specification](functional-specification.md); the running work log is [`docs/development/handoff.md`](../development/handoff.md).

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
12. [Decisions and Rejected Alternatives](#12-decisions-and-rejected-alternatives)
- [Appendix A: Traceability and Implementation Status](#appendix-a-traceability-and-implementation-status)
- [Appendix B: Measurements and Review Findings](#appendix-b-measurements-and-review-findings)
- [Appendix C: Production-Readiness Plan IDs](#appendix-c-production-readiness-plan-ids)

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
- Training and applying machine-learning models as ordinary pipeline nodes — no dedicated model-serving infrastructure (see 4.3, MOD-17 – MOD-19)
- Storage: a hot tier (TimescaleDB / PostgreSQL) for recent data and a lakehouse (Apache Iceberg tables on bundled S3-compatible object storage) for complete history, replay and SQL analysis
- Routing/exporting data by pushing to external endpoints (REST, MQTT, WebSocket, UDP) or exposing pull endpoints for external systems
- A single web UI for configuration, monitoring, pipeline management, data exploration and dashboards (Grafana embedded behind the common login)
- A multi-tenant hierarchical access model with installation-defined, fine-grained, time-bounded permissions
- A complete, searchable audit trail
- A public REST API for all management and data operations

Everything runs inside the installation: all components are bundled, deployed and managed by the platform, and are free and open-source software (or at least freeware).

The data focus is measurements, time series and other structured data. A metadata layer enriches those records with a per-device, per-datapoint, per-endpoint context (see 4.15). Unstructured payloads — documents, images, archives, arbitrary binary files — are **not** stored: a pulled file, mail or FTP item is parsed into records, and only the resulting records are persisted.

Out of scope: DataTurntable is **not** an AI/LLM platform, a general BI suite, a document or blob store, or a replacement for purpose-built message brokers (it uses them as infrastructure).

§14a of BK6-22-300 (EnWG) is used throughout this specification as a worked **example** of the platform's capabilities — LoRaWAN readings, meter data from an ERP system, and internal location data delivered to a metering system and a grid-calculation system. It is an example, not a scope limit: nothing in the platform is specific to that use case.

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
| Connector | A tenant-usable protocol interface (MQTT, REST, WebSocket, UDP, FILE, FTP, SFTP, EMAIL, Custom) based on a connection, referenced by IMPORT/EXPORT pipeline nodes |
| Fire-and-forget | An input mode in which the sender transmits a record and requires no response, no result polling and no retry protocol; a 2xx acknowledgement means the record is durably accepted (IMP-10) |
| Ingest spool | A local, fsync'd disk area on an ingest node that holds records the message bus refused, and drains them into the bus once it recovers (NFR-R7) |
| Metadata object | A tenant-scoped, typed, versioned description of a device, datapoint, endpoint, location or meter that enriches the records about that subject (MET) |
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

### 1.5 Product Goals

These goals are not to be down-scoped; every requirement below serves at least one of them.

| Goal | Meaning | Success criterion |
| ---- | ------- | ----------------- |
| Self-contained | One installation (one Compose file or one Helm chart). All components are bundled, deployed and lifecycle-managed by the platform; users never operate a component directly. External prerequisites: the host or cluster (with a storage class) and a backup destination. | A new installation runs without any manually operated component (DEP-03, OPS). |
| Single UI | Everything is reached through the platform UI and its common login; Grafana is embedded behind it. | UI-15, IAM-09. |
| Structured data hub and lakehouse | Measurements, time series, metadata and other structured records; open table format, own catalog, embedded SQL, history and replay. Unstructured payloads are not stored. | STR-08, LAK. |
| Reliable delivery | Every input usable fire-and-forget; exactly once for identified records; no loss on failover. | IMP-10, PIP-14, NFR-R2, NFR-R7, NFR-R9. |
| Scale | 1 billion records per day on multi-node autoscaling Kubernetes (single node not held to it), proven by a reproducible benchmark. | NFR-P1, NFR-P7. |
| Secure multi-tenancy | Tenant isolation is an invariant enforced by the data layer; user code is hostile by default; secrets never leak. | TEN-03, NFR-S3, NFR-S8, NFR-S10. |
| Paper trail and reproducibility | Every action with impact is audited; every run references the immutable versions it executed. | AUD, PIP-16, MOD-14. |
| FOSS | Every bundled component is FOSS or at least freeware. | NFR-L1. |
| Honest status | Documentation never presents planned or untested behaviour as implemented. | NFR-M9, Appendix A. |

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
15. Maintain metadata objects per device, datapoint and endpoint, and enrich pipeline records with them (MET)

### 2.3 Assumptions and Dependencies

- Delivery is exactly once for records that carry an identity (PIP-14). Every input must be usable in a **fire-and-forget** mode in which the sender neither registers a response nor retries on a 2xx acknowledgement (IMP-10).
- Kafka is used as the internal message bus and pipeline backbone; it is a required, bundled component. In a Kubernetes HA deployment it is replicated (replication factor 3, minimum in-sync replicas 2) and paired with a durable on-disk ingest spool; the single-node Docker Compose reference stack runs a single broker and therefore cannot be redundant (NFR-R7).
- A container runtime is required for running user Python modules (Docker on a single node, Kubernetes pods in a cluster).
- Dashboards are built with the bundled Grafana, embedded in the UI behind the common login.
- Supported deployment targets are a single node (Docker Compose) and Kubernetes. The only external prerequisites are the host or cluster (with a storage class) and a backup destination. Only the Kubernetes target can satisfy the redundancy and failover requirements in 5.2; Docker Compose is a reference and development topology.
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
| IMP-01 | The system shall expose a REST endpoint (`POST /api/ingest/v1/ingest/{pipelineId}`) that accepts arbitrary JSON payloads from external systems and routes them into the named pipeline. A batch form accepting multiple records per request shall be available. Callers may identify a record with an `Idempotency-Key` header (PIP-14). | MVP |
| IMP-02 | The system shall expose an MQTT subscription interface: the platform subscribes to configurable topics on the bundled or an external MQTT broker; incoming messages are routed into the named pipeline. | MVP |
| IMP-03 | The system shall expose a WebSocket server endpoint; external clients connect and stream frames into a pipeline. | MVP |
| IMP-04 | The system shall expose a configurable UDP listener; incoming datagrams are routed into a pipeline. | V2 |
| IMP-05 | Users shall be able to write custom IMPORT modules (Python) that parse data the platform fetched via a connector (REST poll, file watch, broker subscription, etc.). | MVP |
| IMP-06 | Inbound endpoints shall impose no default data structure; all payload formats are forwarded as-is to the pipeline's first module. | MVP |
| IMP-07 | Inbound endpoints shall support both live (streaming) and batch data modes. | MVP |
| IMP-08 | All inbound protocol endpoints shall be hot-pluggable; enabling or disabling one shall not affect other pipelines. | MVP |
| IMP-09 | Inbound endpoints shall authenticate callers with API keys scoped to the pipeline or connector; unauthenticated ingestion shall be rejected. | MVP |
| IMP-10 | **Fire-and-forget.** Every input (REST, MQTT, WebSocket, UDP, FILE, FTP, SFTP, EMAIL) shall accept a record without the sender registering for a response and without the sender having to poll for a result. A 2xx acknowledgement means the record is durably accepted (bus write or spool) and will be processed exactly once if it carries an identity; it does not mean the pipeline has finished. Senders shall never be required to implement a response or retry protocol, because typical field devices and plant systems (SCADA, meters, PLCs, mail servers, FTP servers) cannot. | MVP |
| IMP-11 | Pull inputs shall be polled by the platform: a connector of type FILE, FTP, SFTP or EMAIL fetches new items on a configurable interval and feeds the parsed records into the pipeline. The platform, not the sender, initiates and owns the transfer. | MVP |
| IMP-12 | A pull input shall keep a per-connector ledger of the items it has seen, with their processing state, so that an operator can inspect what arrived and requeue a failed item individually. | MVP |
| IMP-14 | Inbound endpoints shall protect the platform: maximum payload size, per-credential message rate, maximum WebSocket connections and an idle timeout for WebSocket sessions, all configurable; violations are rejected with a specific status and counted in metrics. | MVP |
| IMP-13 | Unstructured payloads shall never be stored. A pulled file, mail attachment or FTP file is parsed into structured records at ingest time; only those records, and their metadata, are persisted (STR-08). | MVP |

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
| PIP-09 | The system shall suppress system-generated duplicates (redelivery after a failover, client resends of an identified record) but shall not deduplicate user data that carries no identity. | MVP |
| PIP-10 | Pipelines shall process every acknowledged record: at least once for records without an identity, exactly once for identified records (PIP-14). | MVP |
| PIP-11 | Pipeline execution metrics (throughput, error rate, latency per node) shall be collected and exposed. | MVP |
| PIP-12 | Pipeline processing shall scale horizontally: work is partitioned by tenant/pipeline and processed in micro-batches by long-running workers whose number follows the load. | MVP |
| PIP-13 | Messages that fail processing shall be routed to a dead-letter queue from which operators can inspect and requeue them. | MVP |
| PIP-14 | A record that carries an identity — `Idempotency-Key` (HTTP), `_idempotencyKey` (WebSocket) or the IMPORT node's `deduplicateBy` fields (any source) — shall be processed, stored and alerted on exactly once, including across broker, executor and database failover and client resends. Side effects leaving the platform shall carry an idempotency key where the protocol allows it (webhook, REST). | MVP |
| PIP-16 | Every run shall reference the immutable pipeline version (graph and node configuration) it executed; editing a pipeline creates a new version and never changes the record of past runs. | MVP |
| PIP-17 | The pipeline graph format shall carry a schema version and shall be validated on save (node types, connector directions, module-version compatibility, acyclicity, reachable sinks); unsupported versions are rejected. | MVP |
| PIP-18 | Runs and nodes shall follow one state machine (PENDING, QUEUED, RUNNING, CANCELLING, CANCELLED, SUCCESS, FAILED, TIMED_OUT) with defined transitions, so that crashes, duplicates, timeouts and cancellation races leave a deterministic state; runs can be cancelled and retried. | MVP |
| PIP-15 | Execution shall not require metadata database writes per record: records are executed in micro-batches (one run per batch per partition), so that the metadata database load is independent of the record rate (NFR-P7). Run history stays inspectable per record where an identity exists. | MVP |

### 4.3 Python Module System

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| MOD-01 | Users with permission shall be able to write Python modules in the UI code editor. | MVP |
| MOD-02 | The system shall support the module types `IMPORT`, `DB_LOAD`, `PROCESSING`, `MERGE`, `ALERT`, `EXPORT` and `DB_SAVE`, each with a defined Python interface contract. | MVP |
| MOD-03 | Python modules shall run in isolated containers (Docker on a single node, pods on Kubernetes), never in the platform's own process. | MVP |
| MOD-04 | Container instances shall have configurable resource limits: CPU, memory, process count, temporary disk, wall-clock timeout, log/output size, network, and concurrent executions per tenant. | MVP |
| MOD-05 | Module containers shall have no network access by default; network access is explicitly granted per module by a holder of the corresponding permission. Installing module requirements shall not require network access for the module at run time. | MVP |
| MOD-06 | The module sandbox shall pre-install: pandas, numpy, scikit-learn; optionally PyTorch, xgboost. | MVP |
| MOD-07 | The platform shall inject system helper functions into module execution context: `alert_fn` for alert dispatch and `query_fn` for tenant-scoped data queries. | MVP |
| MOD-08 | Data shall be passed to PROCESSING and ALERT modules as a dict or pandas DataFrame; modules shall return the same types. | MVP |
| MOD-09 | Module source code and versions shall be stored in PostgreSQL by default. | MVP |
| MOD-10 | Module code versions shall be tracked; previous versions shall be auditable. | MVP |
| MOD-11 | Modules shall be scoped per tenant; a module in one tenant shall not be visible to another. | MVP |
| MOD-12 | Each module version shall carry a configuration object that is passed to the module class constructor. | MVP |
| MOD-14 | The executable content of a module version (code, dependency manifest, configuration) shall be immutable once created and identified by a content digest; a change always creates a new version. | MVP |
| MOD-13 | Errors while loading or instantiating a module shall fail the node visibly (run log, metrics); they shall never be silently ignored. | MVP |
| MOD-15 | A PROCESSING module shall be able to persist an arbitrary fitted-model artifact (e.g. a serialized scikit-learn/PyTorch object) under a tenant- and module-scoped name, and load the current or a specific past version of it in a later, independent execution — of the same module or a different one, in the same or a different pipeline. | MVP |
| MOD-16 | Model artifacts shall be versioned like module and pipeline versions: saving under a name already used creates a new version rather than overwriting; every run that used one records which version. Artifacts are opaque bytes to the platform (no execution, no parsing) and are stored, encrypted at rest, in the metadata database — this is internal model state the platform itself writes and reads, not the unstructured user payload storage that STR-08 excludes. | MVP |
| MOD-17 | Users shall be able to build a **training pipeline**: an IMPORT or DB_LOAD node feeds historical records to a PROCESSING module that fits a model (using the pre-installed scikit-learn/PyTorch/pandas, MOD-06) and saves it as a model artifact (MOD-15). Training runs like any other pipeline: triggered manually, on a schedule (an IMPORT poll interval), or by an upstream event. | MVP |
| MOD-18 | Users shall be able to build an **inference pipeline**: a PROCESSING module loads a model artifact once per worker (not once per record) and applies it to each incoming record for classification, regression, anomaly scoring or forecasting; the result flows to EXPORT, DB_SAVE or ALERT nodes like any other PROCESSING output. | MVP |
| MOD-19 | A worker that has loaded a model artifact shall detect that a newer version was saved and reload it without a pipeline restart, within one polling interval (default 60 s). | MVP |

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
| ALT-13 | The platform shall raise platform alerts on pipeline health — repeated run failures, growing processing lag, dead-letter records, alerts that could not be delivered — to the tenant's default channel and to system administrators. | MVP |
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
| EXP-09 | An EXPORT node without a module shall deliver the record unchanged through its OUTPUT connector ("Send as is"), with an idempotency key per run and node. | MVP |

### 4.6 Connectors and Connections

Connections describe how to reach an external system (endpoint, credentials). They belong to the system plane: system administrators create them, possibly on request of a tenant administrator, and grant them to tenants. Connectors are the tenant-level protocol interfaces that pipeline nodes reference; each connector uses a granted connection.

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| CON-01 | The system shall allow tenants to define named connector entities for each supported protocol: MQTT, REST, WebSocket, UDP, FILE, FTP (incl. FTPS), SFTP, EMAIL (IMAP) and Custom. | MVP |
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
| CON-13 | Polling connectors (REST, FILE, FTP, SFTP, EMAIL) shall expose a configurable polling interval and an operator-visible last-run/next-run state. | MVP |
| CON-14 | The UI shall configure every pull input type with the fields the source actually needs (host, port, TLS mode, path, filename pattern, record format, mail folder, content selection, post-read action, minimum age) and shall validate them before the connector can be enabled. | MVP |
| CON-15 | A pull input shall support a `minAge` guard so that files or mails still being written by the remote system are not read half-finished. | MVP |
| CON-17 | Changes to a connector's configuration shall create a new connector revision; runs and deliveries reference the revision they used. | MVP |
| CON-18 | Credentials of connectors and alert channels shall be stored as encrypted, versioned secrets that the configuration references (`secretRefs`); inline credentials in a configuration are rejected, and secrets can be rotated without editing the connector. | MVP |
| CON-16 | After a successful read, the platform shall apply the connector's post-read action (keep, move, or delete the file; keep, move, or delete the mail) so that a re-run cannot duplicate or skip items. | MVP |

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
| STR-08 | The platform shall **not** store unstructured payloads. There is no blob/file store, no upload endpoint and no binary retention tier. Every input — a pushed JSON document, a CSV file, a mail with attachments, a binary protocol frame — is reduced to structured records (readings, locations, metadata) that are written to the hot tier or a lakehouse table. This removes a whole class of capacity, backup and retention concerns: there is exactly one copy of every value, and it is queryable. | MVP |
| STR-09 | Every stored record shall be attributable: the connector, endpoint and (for pull inputs) the source item it came from, plus ingest time, shall be recorded with it. | MVP |

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
| UI-20 | The UI shall be available in English and German; every text shall exist in both languages (checked automatically). | MVP |
| UI-21 | The UI shall meet WCAG 2.1 level AA. | V2 |

### 4.10 REST API

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| API-01 | All platform management operations shall be available via a REST API (`/api/internal/v1/`) from day one. | MVP |
| API-02 | All data query operations shall be available via the API (`/api/v1/`). | MVP |
| API-03 | The API shall authenticate browser users with the platform session (HttpOnly cookie with CSRF protection) and machine clients with JWT Bearer tokens or API keys. | MVP |
| API-04 | The management API shall support API keys for service accounts and machine clients. | MVP |
| API-05 | API keys shall have configurable, fine-grained permission scopes and an optional expiry. | MVP |
| API-06 | The management API shall be documented (OpenAPI/Swagger); the contract is generated from the code and linted in CI. | MVP |
| API-07 | A public data ingestion API (`/api/ingest/v1/`) shall be available for external systems to push data into pipelines without using the management API. | MVP |
| API-08 | The platform shall support OAuth2/OIDC for user authentication (see IAM-09). | MVP |
| API-09 | The API shall enforce rate limiting (default: 100 req/min per client). | V2 |
| API-10 | Every API error shall use one envelope (`code`, `message`, `details`) with a stable machine-readable code; constraint violations map to `400`/`409`, never `500`. | MVP |

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
| TEN-10 | Ingestion and processing shall be fair between tenants: per-tenant ingest quotas (records/s, payload size) with `429` + `Retry-After` beyond them, so that one tenant cannot delay another's data. | MVP |

### 4.12 Identity & Access (IAM)

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| IAM-01 | The product shall define the permission vocabulary: stable permission names of the form `resource:action` (e.g. `pipeline:write`, `data:read`, `system.config:read`), each corresponding to an enforcement point. Permissions are matched as exact tokens (or an explicit wildcard), never by substring. | MVP |
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
| IAM-14 | Access tokens shall be short-lived. Deactivating a user, changing a password or changing role assignments shall invalidate existing sessions and tokens of that user at once. | MVP |
| IAM-15 | Administrators shall be able to see why a user has or lacks a permission in a tenant: the memberships, roles, inherited grants and system-scope assignments that apply. | MVP |
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
| OPS-07 | Operational history (runs, node logs, alert deliveries, DLQ records) shall have configurable retention: details for a limited period (default 14 days), aggregated counts per pipeline and hour beyond it. | MVP |

### 4.15 Metadata (MET)

A metadata object describes something the data is *about*: a device, a datapoint, an endpoint, a market location, a meter. Metadata is what turns a bare measurement into something a pipeline, an alert or a downstream system can act on — "sensor 4711 in building B, reading 21.4 °C" instead of "21.4". It enriches records on the way through a pipeline and is itself stored, versioned and queryable.

| ID | Requirement | Priority |
| -- | ----------- | -------- |
| MET-01 | The system shall support metadata objects of user-defined types (for example Device, Datapoint, Endpoint, Location, Meter), each with a tenant-scoped set of typed attributes. | MVP |
| MET-02 | Metadata objects shall be tenant-scoped and follow the same isolation and permission model as other tenant data; a tenant cannot read or reference another tenant's objects. | MVP |
| MET-03 | Metadata objects shall be enriched into a pipeline through a dedicated PROCESSING step ("Enrich with metadata") that attaches the matching object to each record, so that a module does not have to implement the lookup itself. | MVP |
| MET-04 | A pipeline shall be able to write data back to metadata through a dedicated EXPORT step ("Save to metadata"), so that an incoming reading can update the state of the object it describes (for example a meter's current reading). | MVP |
| MET-05 | Metadata objects shall be versioned with an effective-dating (as-of) model: a change creates a new version rather than overwriting, and a record enriched at a known time receives the version effective at that time. This keeps enrichment reproducible on replay (LAK-03). | MVP |
| MET-06 | The UI shall provide a metadata page to list, search, inspect (with version history and an as-of view), create, edit and import metadata objects (including CSV import), and to manage types and templates. | MVP |
| MET-07 | The pipeline editor shall offer metadata enrichment and metadata export as node types, and shall show the configured metadata type and key of each such node in the graph. | MVP |
| MET-08 | The query API shall be able to filter and join records on metadata attributes without the caller having to denormalise them at write time. | MVP |
| MET-09 | Metadata attributes shall be typed and validated against their type definition, so a typo in a key is rejected at write time rather than silently producing empty enrichment. | MVP |
| MET-10 | Metadata shall be enrichable from a pull input as well as a push input: a record read from a file, mailbox or FTP server is matched to its metadata object in the same way as a pushed record. | MVP |

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
| NFR-P8 | Heavy UI dependencies (code editor, graph editor, charts) shall be loaded on demand and not inflate the initial page load. |
| NFR-P7 | Metadata database writes shall not grow with the record rate: at NFR-P1 load they stay below 1,000 transactions/s (see PIP-15). |

### 5.2 Reliability & Availability

| ID | Requirement |
| -- | ----------- |
| NFR-R1 | The platform shall target **99.9% uptime** for the core ingestion and processing path (Kubernetes HA deployment). |
| NFR-R2 | The Kubernetes HA deployment shall recover from a single-node failure without losing or duplicating acknowledged records (exactly once for identified records, PIP-14). |
| NFR-R3 | Hot-plugging a module or connector shall not interrupt processing of unrelated pipelines. |
| NFR-R4 | Failed module container instances shall be detected and restarted automatically within 30 seconds. |
| NFR-R5 | Recovery objectives of the Kubernetes HA deployment: RPO 0 for acknowledged records and committed metadata on single-node failure; automatic failover of every stateful component within 2 minutes; restore from backup after total loss with RPO ≤ 24 h. |
| NFR-R6 | Ingestion shall keep accepting data while the metadata database is unavailable; only the loss of the message bus write quorum may refuse data. |
| NFR-R7 | **Queue redundancy.** The message bus shall be replicated in the HA deployment: replication factor 3 with a minimum of 2 in-sync replicas, so that the loss of one broker node neither loses an acknowledged record nor reduces the write quorum. Durability of a record that the bus refuses shall be guaranteed by a local, fsync'd ingest spool on the ingest node, which is drained into the bus once it is available again. In Kubernetes the ingest edge runs as a StatefulSet with a persistent volume claim for that spool. |
| NFR-R8 | Redundancy is a property of the **Kubernetes** deployment. The single-node Docker Compose reference stack runs one broker and one database and therefore cannot satisfy NFR-R5 or NFR-R7; it is documented as a development and evaluation topology that accepts the risk of losing in-flight data with the node. The UI and the health endpoints shall state this rather than implying HA. |
| NFR-R10 | A persistent connector (broker subscription, poller, WebSocket client) shall run on exactly one instance at a time, owned through a database lease with heartbeat, expiry and a fencing token, and shall fail over to another instance without split brain. |
| NFR-R11 | Backup and restore shall be operationally proven: a documented drill restores a backup into an empty installation and verifies the data. |
| NFR-R9 | Fire-and-forget shall not weaken the durability guarantee: a 2xx acknowledgement (IMP-10) shall only be sent after the record is durably accepted by the bus or the spool, so a sender that never retries cannot lose data. |

### 5.3 Security

| ID | Requirement |
| -- | ----------- |
| NFR-S1 | All inter-service communication shall use TLS in production deployments. |
| NFR-S2 | User passwords shall only be transmitted over TLS and stored server-side using BCrypt; passwords shall have at least 8 characters. |
| NFR-S3 | User-defined Python is hostile by default: module containers shall run as non-root users with dropped capabilities, a seccomp profile, a read-only filesystem, no network access by default and no access to the container runtime socket or platform credentials. |
| NFR-S4 | API keys shall be stored hashed; the plaintext key shall only be shown once at creation. |
| NFR-S5 | All user actions that modify system state shall be audit-logged (see AUD). |
| NFR-S6 | Input data from inbound endpoints shall be sanitised before being passed to modules to prevent injection. |
| NFR-S7 | Signing keys, database credentials and connector secrets shall be supplied per installation (secret store / Kubernetes Secrets) and never be part of images or the repository. |
| NFR-S8 | Tenant isolation shall be enforced by the data layer itself (per-tenant database roles, catalog namespaces), not only by application checks. |
| NFR-S9 | Connectors shall not reach loopback, private, link-local, multicast or cloud-metadata addresses unless a system administrator allowed the network; cloud metadata endpoints stay blocked in every case. The check runs when a connector is saved and on every connect (server-side request forgery, DNS rebinding). |
| NFR-S10 | Secrets shall never appear in API responses, logs, metrics, audit records, error messages or execution metadata; logs are redacted centrally as a second line of defence. |
| NFR-S11 | A production start shall fail instead of falling back to insecure defaults: missing signing keys, encryption keys or default credentials abort startup. |
| NFR-S12 | The browser application shall minimise the impact of XSS: security response headers (CSP, HSTS, frame and content-type protection) and credentials kept out of script-readable storage (API-03). |

### 5.4 Maintainability & Operability

| ID | Requirement |
| -- | ----------- |
| NFR-M1 | All services shall expose `/q/health` and `/q/metrics` (Prometheus-compatible) endpoints. |
| NFR-M2 | Structured JSON logging shall be used by all platform services. User module output shall go to tenant-scoped run logs, not to the system log pipeline. |
| NFR-M3 | The platform shall be deployable from a single `docker compose up` command on a single node. |
| NFR-M4 | Module and connector updates shall be possible via the UI without modifying or restarting the core platform. |
| NFR-M5 | Database schema migrations shall be automated (Flyway). |
| NFR-M6 | Every change shall pass continuous integration (build, lint, tests against the real database engine). |
| NFR-M7 | One request, event or run shall be traceable across ingest, message bus, execution and delivery: events carry a versioned envelope with event, correlation and causation ids; logs and metrics carry the correlation id; distributed tracing (OpenTelemetry) can be enabled. |
| NFR-M8 | Release artifacts shall be immutable and traceable: images referenced by digest, a software bill of materials per image, and vulnerability scans of images and dependencies in CI. |
| NFR-M9 | Documentation shall not present planned or untested behaviour as implemented. The implementation status of every requirement is kept in Appendix A and updated in the same change as the implementation. |

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
| DAT-10 | Model artifacts (MOD-15) are the one platform-managed exception to "structured data only" (STR-08): they are serialized model state, never a user-supplied file, and are never exposed through a file/blob API — only through `save_model_fn`/`load_model_fn` inside module execution. |
| DAT-03 | The system shall store metadata (tenant info, pipeline config, module source, alert configuration, roles, audit trail) in PostgreSQL. |
| DAT-04 | Recent time-series sensor/IoT data shall be stored in TimescaleDB (hot tier); complete history in lakehouse tables. |
| DAT-05 | Variable-schema structured data shall be stored in lakehouse tables with schema evolution (see STR-03). |
| DAT-06 | Batch files shall be stored in the bundled object storage; structured files are imported into tables (LAK-12). Unstructured binary content is not a focus. |
| DAT-07 | User-imported duplicate data without an identity shall be preserved; system-generated duplicates and resends of identified records shall be suppressed (PIP-09, PIP-14). |
| DAT-08 | Data retention and archiving policies shall be configurable at workspace level. |
| DAT-09 | All data of a tenant (hot tier, lakehouse including old snapshots, run history, audit payloads) shall be deletable on request, e.g. when the tenant leaves or for a GDPR erasure; the deletion is audited and completes within 30 days including backups. |

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

- Inbound endpoints: REST (`/api/ingest/v1/`), MQTT, WebSocket; authenticated with API keys; every input usable fire-and-forget (IMP-10)
- Pull inputs: FILE, FTP/FTPS, SFTP, EMAIL (IMAP) with polling, a per-connector item ledger and requeue (IMP-11/12, CON-13 – CON-16)
- Structured data only: no blob or file store; every payload is parsed into records (STR-08)
- Metadata objects: user-defined types, as-of versioning, pipeline enrich/export steps, dedicated UI page (MET-01 – MET-10)
- Custom IMPORT and EXPORT Python modules; DB_LOAD/DB_SAVE storage nodes
- Train-and-infer machine learning: a PROCESSING module fits and saves a model, another loads and applies it — no separate ML server (MOD-15 – MOD-19)
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
- Immutable module and pipeline versions per run, connector revisions, secret references, single-owner connector leases (MOD-14, PIP-16, CON-17, CON-18, NFR-R10)
- Security baseline: SSRF destination policy, secret redaction, fail-closed production start, security headers (NFR-S9 – NFR-S12)
- Prometheus metrics, structured logging
- Single-node Docker Compose, Kubernetes (single-instance and HA), single Helm chart
- Reproducible 1B/day benchmark on Kubernetes

### MVP milestones

The MVP list above is the scope of release 1.0; nothing in it is optional. The milestones order it by dependency and risk, so that each one ends in a usable, releasable state. Status per requirement: Appendix A.

| Milestone | Content | Exit criterion |
| --------- | ------- | -------------- |
| M0 — Pilot-ready core | Ingest authentication (IMP-09), secrets out of the repository and images (NFR-S7), per-tenant database roles (TEN-03, NFR-S8), CI against PostgreSQL (NFR-M6), platform health alerts (ALT-13), run-history retention (OPS-07) | A department can run its measurements through the platform on a single node without a known security gap. |
| M1 — Throughput | Micro-batch execution (PIP-15, NFR-P7), long-running module workers on Kubernetes (MOD-03, NFR-P5), lag-driven autoscaling (PIP-12, NFR-P4), tenant quotas (TEN-10), DLQ inspection and requeue (PIP-13), benchmark harness | NFR-P1/P2 demonstrated by the reproducible benchmark on Kubernetes HA. |
| M2 — Lakehouse | LAK-01 – LAK-12, STR-03/04/05/07, EXP-05/06, INT-01, INT-07, DAT-04 – DAT-06, DAT-08, DAT-09 | Bronze retention, rollups, SQL workspace and catalog in the UI. |
| M3 — Identity & audit | IAM-01 – IAM-12, AUD-01 – AUD-09, API-03 – API-06, API-08, UI-15 – UI-17, CON-09 – CON-12 | Single login via the auth proxy and OIDC; every impactful action audited. |
| M4 — Operations | OPS-01 – OPS-06, UI-18, UI-19, DEP-03, DEP-05, NFR-S1, NFR-R5 verified on a cluster | Installation, upgrade, backup and restore run from the UI and the Helm chart. |
| M5 — Pull inputs, metadata and redundancy | IMP-10 – IMP-13, CON-13 – CON-16, MET-01 – MET-10, STR-08/09, NFR-R7 – NFR-R9 | A meter reading arrives from a mailbox or FTP server without the sender doing anything but sending, is enriched with its device metadata, and survives a broker node failure. |

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

## 12. Decisions and Rejected Alternatives

Alternatives that were considered and not adopted, with the reason, so that they are not re-proposed without new facts.

| Alternative | Decision and reason | Date / source |
| ----------- | ------------------- | ------------- |
| Split the backend into domain microservices behind an API gateway (former FR-029, NFR-011, NFR-012) | **Rejected.** One image with four runtime roles (`control-api`, `public-api`, `ingest-edge`, `pipeline-executor`) already scales each boundary independently (NFR-P4) and keeps single-node installations simple. A service split adds network hops, distributed transactions and operational load without a requirement that needs it. | Owner, 2026-09-28 |
| Big-bang rewrite; one service per entity/table; repository per service | Rejected: high risk, distributed monolith, no security or scaling value. | Production-readiness plan |
| Docker Swarm as a deployment target (former FR-030) | Removed (DEP-08). Supported: single-node Compose and Kubernetes. | Owner, 2026-09-25 |
| Blob/file store with S3/MinIO upload and download (former FR-022) | Dropped, not deferred (STR-08). Every payload is parsed into records at ingest. | Owner, 2026-09-27 |
| MinIO as bundled object storage | Excluded: no longer distributed as open source (see `docs/development/licenses.md`). Candidate for the lakehouse: SeaweedFS. | Licence review, 2026-09-25 |
| Redis for connector ownership, cooldowns or caches | Not used. Durable coordination (leases, cooldowns, run claims) lives in PostgreSQL; caches are in-process. One less bundled component. | Implementation |
| Argon2id password hashing (former FR-003) | Not adopted for now: BCrypt meets NFR-S2 and needs no extra dependency. Argon2id (via Bouncy Castle) remains a possible later change; it needs no requirement change. | Merge of `dev`, 2026-09-27 |
| Client-side SHA-256 pre-hashing of passwords | Removed: over TLS it adds no protection, and the hash becomes a password equivalent. | NFR-S2 |
| At-least-once delivery with deduplication only of system duplicates | Replaced by exactly once for identified records (PIP-14). | Owner, 2026-09-27 |
| Standalone alert-rule entities evaluated outside pipelines, with multi-channel rule actions (former FR-023 in `dev`) | Replaced by built-in ALERT node types in the pipeline graph (ALT-02) with one default channel per node (ALT-10). Multi-channel actions were not carried over. | Merge of `dev`, 2026-09-27 |
| Mounting the Docker socket into Kubernetes execution pods | Rejected: host-level privilege. Kubernetes executes modules as pods/Jobs (MOD-03). | NFR-S3 |
| Giving user code broad database, Kafka or platform credentials | Rejected: the platform performs all external I/O (CON-05); modules get scoped data only. | NFR-S3 |
| Storing credentials inline in connector JSON | Rejected: secrets need rotation, masking and encryption (CON-18). | Implementation |
| Normalising every JSON document and DAG node into relational tables | Rejected: graphs and nested configuration are document-shaped (JSONB with validation); relational only where semantics need it. | Production-readiness plan |
| A custom dashboard builder, message broker, object store or identity provider | Rejected: Grafana, Kafka, S3-compatible storage and external OIDC providers are used instead. | Production-readiness plan |
| Kubernetes mandatory for every installation | Rejected: Compose stays supported for single-node installations (DEP-01). | Production-readiness plan |
| Floating `latest` image tags | Rejected: images pinned by version/digest (NFR-M8). | Production-readiness plan |
| AI features for their own sake; many new connector types without a concrete need | Rejected: not a product goal (1.3). | Production-readiness plan |

---

## Appendix A: Traceability and Implementation Status

This appendix is the **only** place where implementation status is kept (NFR-M9). Update it in the same change as the implementation.

Change against version 1.1: **=** unchanged, **~** wording changed (idea kept), **+** new in 2.x. Status of the implementation on 2026-09-28: **Done** (built and covered by tests; for operational items also exercised on a real environment), **Partial**, **Open**. "Not on a cluster" means verified locally or with Compose but never on a real Kubernetes cluster.

| ID | Change | Reason for change | Status |
| -- | ------ | ----------------- | ------ |
| IMP-01 | ~ | Path follows the implemented API layout (`/api/ingest/v1/`); batch form added for throughput (NFR-P1). | Partial (no batch form) |
| IMP-02 | = | | Done |
| IMP-03 | = | | Done |
| IMP-04 | = | | Open (UDP output exists, no listener) |
| IMP-05 | ~ | The platform fetches via connectors and the module parses (security: no credentials in containers, CON-05). | Done |
| IMP-06 – IMP-08 | = | | Done |
| IMP-09 | + | Ingestion was unauthenticated (security review). | Done (API keys per pipeline/connector, SHA-256 stored, shown once, expiry, revocation, UI page `/api-keys`) |
| IMP-14 | + | Ingest protections (former FR-007). | Partial (1 MB body limit `413`, per-key rate limit `429` — off by default, WebSocket idle reaper; no connection-count limit) |
| IMP-10 – IMP-13 | + | Fire-and-forget for every input, platform-owned pull inputs, per-connector item ledger, and no unstructured storage (decision of 2026-09-27). | Partial (FILE/FTP/SFTP/EMAIL poller, ledger and retry API done; UI forms done; EMail/FTP/SFTP sources usable) |
| PIP-01, PIP-02 | = | | Done |
| PIP-03 | ~ | Fan-in is implemented by the dedicated MERGE node type. | Done |
| PIP-04 | ~ | Additional node types (DB_LOAD, MERGE, DB_SAVE). | Done |
| PIP-05, PIP-06 | ~ | The graph editor exists; the linear MVP builder is obsolete. PIP-06 moved to MVP. | Done |
| PIP-07, PIP-08 | = | | Open |
| PIP-09, PIP-10 | ~ | Exactly once for identified records (decision of 2026-09-27). | Done |
| PIP-11 | = | | Done |
| PIP-12 | + | Needed for NFR-P1. | Partial (keyed partitions, parallel consumers; no micro-batches, CPU-based autoscaling) |
| PIP-13 | + | DLQ was described in the functional spec only. | Partial (DLQ topic and records, retry API `/api/internal/v1/events/dlq/{id}/retry`; no inspection UI) |
| PIP-14 | + | Exactly-once delivery. | Done (verified with broker, executor and database failures) |
| PIP-15 | + | Per-record runs cannot reach NFR-P1 (review 2026-09-27). | Open |
| PIP-16 | + | Former FR-009. | Done (`PipelineVersion`; runs pinned to the version) |
| PIP-17 | + | Former FR-010. | Done (`schemaVersion` 1 validated; no migration framework needed yet) |
| PIP-18 | + | Former FR-011. | Done (state machine incl. cancel and retry) |
| MOD-01 | = | | Done |
| MOD-02 | ~ | Seven module types instead of four. | Done |
| MOD-03 | ~ | Pods on Kubernetes; long-running workers instead of one container per message (NFR-P5). | Partial (Docker only) |
| MOD-04 | ~ | Full quota list (former FR-014). | Done (Docker backend) |
| MOD-05 | ~ | Installing requirements currently opens network access. | Partial |
| MOD-06 | = | | Open (slim base image) |
| MOD-07 | = | | Partial (`query_fn` returns an empty result) |
| MOD-08 – MOD-11 | = | | Done |
| MOD-12, MOD-13 | + | Module config reaches the constructor; errors were previously swallowed. | Done |
| MOD-14 | + | Former FR-008. | Done |
| MOD-15, MOD-16 | + | Model artifact persistence (owner request 2026-09-28). | Open (no `save_model_fn`/`load_model_fn`, no artifact storage or versioning yet) |
| MOD-17 – MOD-19 | + | Train-and-infer use case built on MOD-15/16 (owner request 2026-09-28). | Open (design only, functional spec §5.6/§13.6; needs MOD-15/16 first) |
| ALT-01 | = | | Done |
| ALT-02 | ~ | Realised as built-in ALERT node types; the former rule table was never evaluated and was removed. | Done |
| ALT-03, ALT-04 | = | | Done |
| ALT-05 | ~ | `error` severity exists in the implementation. | Done |
| ALT-06, ALT-07 | = | | Done |
| ALT-08, ALT-09 | = | | Open |
| ALT-10, ALT-11 | + | Alert delivery rework; ALT-11 via the alert outbox (attempts, retries, errors). | Done |
| ALT-13 | + | Platform alerts on pipeline health (functional spec §4.6). | Open |
| ALT-12 | + | Channel secrets were stored unencrypted. | Done (secret references, resolved in memory only; MQTT via connector) |
| EXP-01 – EXP-03 | = | | Done |
| EXP-04 | = | | Partial (UDP output in connector runtime) |
| EXP-05 | ~ | MongoDB/MinIO replaced by hot tier + lakehouse (data focus, MinIO no longer distributed). | Partial (hot tier) |
| EXP-06 | ~ | SimpleJSON endpoint replaced by permission-checked query API, Grafana data source and Iceberg REST catalog. | Open |
| EXP-07, EXP-08 | = | | Done |
| EXP-09 | + | "Send as is" (2026-09-28). | Done (§14a example, `Energy14aExampleTest`) |
| CON-01 | ~ | File split into FILE, FTP/FTPS, SFTP and EMAIL (IMAP) pull inputs. | Done |
| CON-02 | ~ | "BOTH" realised through connections usable in both directions. | Partial |
| CON-03, CON-04 | ~ | Follows CON-02. | Done |
| CON-05 | ~ | Credentials are no longer injected into containers. | Done |
| CON-06 – CON-08 | = | | Done |
| CON-09 – CON-12 | + | Persona model: IT provisions connections, departments use them. | Open |
| CON-13 – CON-16 | + | Polling controls, per-type configuration forms, `minAge` guard and post-read action for pull inputs (decision of 2026-09-27). | Partial (sources, ledger, retry and UI forms done; no last-run/next-run display) |
| CON-17 | + | Former FR-017. | Done (`ConnectorRevision`) |
| CON-18 | + | Former FR-019. | Done (inline credentials rejected; UI stores them as secrets) |
| STR-01 | = | | Partial (the TimescaleDB image is shipped, but pipeline tables are plain PostgreSQL tables: no hypertables, compression or continuous aggregates) |
| STR-02 | = | | Done |
| STR-03 | ~ | MongoDB replaced by schema evolution in the lakehouse (focus on structured data). | Open |
| STR-04 | ~ | Object storage bundled and used for the lakehouse; MinIO excluded (no longer distributed). | Open |
| STR-05 | ~ | Targets are hot tier and lakehouse. | Partial |
| STR-06 | = | | Done |
| STR-07 | ~ | Moved to MVP: required to operate 1B/day. | Partial (per-category retention days, installation-wide, default unlimited; no per-tenant policy) |
| STR-08 | + | Unstructured blob storage dropped: data_blobs table, blob endpoints, Files tab and blob config removed (decision of 2026-09-27). | Done |
| STR-09 | + | Every record attributable to its connector/endpoint and source item. | Done |
| LAK-01 – LAK-12 | + | Lakehouse. | Open |
| UI-01 | ~ | React 19. | Done |
| UI-02 | = | | Done |
| UI-03 | ~ | Graph builder instead of ordered list. | Done |
| UI-04 – UI-07 | = | | Done |
| UI-08 | ~ | Alert rules are ALERT nodes. | Done |
| UI-09 | = | | Done |
| UI-10 | = | | Done (theme switch in the sidebar) |
| UI-11 | ~ | Moved to MVP (implemented). | Done |
| UI-12 – UI-14 | = | | Done |
| UI-15 – UI-19 | + | Single UI, Grafana for users, activity tabs, system area, in-app docs. | Open |
| UI-20 | + | UI texts exist in English and German. | Partial (locale files complete and checked by a test; some pipeline-editor labels such as the step choices are hard-coded in English) |
| UI-21 | + | Accessibility. | Open |
| API-01, API-02 | ~ | API-02 names the data API path. | Done |
| API-03 | ~ | Browser session via cookie for reverse-proxy authentication. | Open (JWT in localStorage) |
| API-04, API-05 | ~ | Expiry added. | Partial (keys with expiry, but grants are ingest-only; no management-API keys) |
| API-06 | ~ | Contract generated and linted in CI. | Done |
| API-10 | + | Former FR-028. | Done |
| API-07 | ~ | Path `/api/ingest/v1/`. | Done |
| API-08 | ~ | Moved to MVP. | Open |
| API-09 | = | | Open |
| TEN-01, TEN-02 | = | | Done |
| TEN-03 | ~ | Isolation at database and storage level (security review found shared DB credentials). | Partial |
| TEN-04 | = | | Done |
| TEN-05 | ~ | Superadmin replaced by system-scope assignments. | Partial (explicit `is_platform_admin` flag separate from root-tenant administration; no system-scope role assignments yet) |
| TEN-06, TEN-07 | = | | Done |
| TEN-08 | ~ | Replaced: system scope grants no implicit data access. | Open |
| TEN-09 | = | | Done |
| TEN-10 | + | Noisy-neighbour protection for NFR-P1. | Open |
| IAM-01, IAM-02 | + | Persona and permission model; exact-token matching (former FR-001). | Partial (one naming style, exact-token evaluation; roles editable per tenant) |
| IAM-05 | + | Role templates. | Partial (roles seeded; not immutable, no clone/disable) |
| IAM-03, IAM-04, IAM-06 – IAM-13 | + | Scopes, time-bounded assignments, OIDC, auth proxy, sessions. | Open |
| IAM-14 | + | Former FR-004. | Done (authorization version in the token; changes invalidate sessions) |
| IAM-15 | + | Former FR-002/FR-027. | Partial (explanation API `AuthorizationResource`; used in the Users page) |
| AUD-01 | + | Paper-trail requirement (extends NFR-S5). | Partial (create/update/delete of pipelines, connectors, tenants, users, memberships, roles, secrets, API keys, alert channels; run trigger/cancel/retry; login success/failure; no deployment/backup/retention events) |
| AUD-02 – AUD-10 | + | | Open (`audit_events` table without read API, UI, hash chain or notes) |
| OPS-01 – OPS-06 | + | System administrator persona. | Open |
| OPS-07 | + | Run history grows by one row per record (review 2026-09-27). | Partial (`RetentionCleanupService` deletes by age; no hourly aggregates) |
| MET-01 – MET-10 | + | New requirements group: a metadata component per device, datapoint, endpoint and location that enriches pipelines (decision of 2026-09-27). | Done (types, templates, as-of versioning, enrich/export node types, `/metadata` UI) |
| NFR-P1 | ~ | Scoped to multi-node autoscaling Kubernetes with a benchmark. | Open |
| NFR-P2 | ~ | Applies to the hot tier; lakehouse visibility bounded by the commit interval. | Open (measured p99 14.8 s under backlog on one node) |
| NFR-P3 | = | | Partial (p95 < 10 ms for lists; run summary 215 ms at 10,000 runs) |
| NFR-P4 | ~ | Automatic, load-driven. | Partial |
| NFR-P5 | ~ | Resolves the contradiction between per-message containers and NFR-P2. | Open |
| NFR-P6 | + | SQL workspace. | Open |
| NFR-P7 | + | Metadata load independent of record rate. | Open |
| NFR-P8 | + | Former NFR-015. | Done (lazy routes; CI checks the build manifest) |
| NFR-R1, NFR-R2 | ~ | Tied to the Kubernetes HA deployment; R2 exactly once. | Partial (verified with a local 3-broker cluster and two instances; not on Kubernetes) |
| NFR-R5 | + | Measurable recovery objectives. | Partial (synchronous replication configured; not measured on a cluster) |
| NFR-R6 | + | Ingest independent of the metadata database. | Done |
| NFR-R7 | + | Queue redundancy: Kafka RF3 / min ISR 2 plus the durable ingest spool (decision of 2026-09-27). | Partial (fsync'd ingest spool implemented and verified; replicated-broker values specified but not yet exercised on a cluster) |
| NFR-R8 | + | Docker Compose is a single-node reference stack and cannot be redundant; stated as such instead of implying HA. | Done |
| NFR-R9 | + | Fire-and-forget must not weaken durability: 2xx only after bus or spool acceptance. | Done |
| NFR-R3, NFR-R4 | = | | Partial |
| NFR-R10 | + | Former FR-018/NFR-009. | Done (two-replica fencing verified against real PostgreSQL; not on a cluster) |
| NFR-R11 | + | Former NFR-017. | Partial (drill done with the Compose topology; not on Kubernetes, no schedule) |
| NFR-S1 | = | | Open |
| NFR-S2 | ~ | Client-side SHA-256 pre-hashing dropped: over TLS it adds no protection (the hash becomes the password). BCrypt storage kept; minimum length added. | Done |
| NFR-S3 | ~ | Hostile-by-default (former NFR-002). | Partial (Docker backend hardened and tested with hostile modules; network opened while installing requirements; Kubernetes backend never run on a cluster) |
| NFR-S4 | = | | Done |
| NFR-S5 | ~ | Detailed in AUD. | Partial (see AUD-01) |
| NFR-S6 | = | | Open |
| NFR-S7 | + | Security review (committed signing key). | Done (key removed from the repository; keys mounted per installation, dev keys generated by `scripts/dev/generate-dev-keys.sh`) |
| NFR-S8 | + | Security review (shared DB credentials). | Open (one database role for all tenants) |
| NFR-S9 | + | Former NFR-004. | Done (`DestinationPolicy`, allow-list `dataturntable.connectors.allowed-networks`) |
| NFR-S10 | + | Former NFR-005. | Done (call-site redaction plus a global log filter) |
| NFR-S11 | + | Former NFR-003/FR-005. | Done |
| NFR-S12 | + | Former NFR-006. | Partial (security headers set; the token is still in `localStorage`, see API-03) |
| NFR-M1 | = | | Done |
| NFR-M2 | ~ | Module output kept out of system logs. | Partial |
| NFR-M3 | ~ | `docker compose` (v2 CLI). | Done |
| NFR-M4, NFR-M5 | = | | Done |
| NFR-M6 | + | No CI existed. | Partial (`.github/workflows/ci.yml`: backend, frontend, OpenAPI lint, manifest rendering, PostgreSQL migrations, image scan; not yet observed green on GitHub) |
| NFR-M7 | + | Former FR-015/FR-025/NFR-020. | Partial (event envelope and correlation ids; OpenTelemetry present, disabled by default) |
| NFR-M8 | + | Former NFR-018. | Partial (third-party images pinned; own-image digests, SBOM and scans wired in CI but not run) |
| NFR-M9 | + | Former NFR-019. | Done (this appendix) |
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
| DAT-07 | ~ | Follows PIP-14. | Done |
| DAT-10 | + | Model-artifact exception to STR-08 (owner request 2026-09-28). | Open (follows MOD-15) |
| DAT-08 | = | | Open |
| DAT-09 | + | Tenant offboarding and GDPR erasure. | Open |
| INT-01 | ~ | SimpleJSON endpoint replaced by a permission-checked Grafana data source. | Open |
| INT-02 | = | | Open |
| INT-03 | = | | Partial (MQTT v3.1.1 client only) |
| INT-04, INT-05 | = | | Done |
| INT-06 | ~ | OIDC in MVP; LDAP/AD via the identity provider. | Open |
| INT-07 | + | Lakehouse access for external tools. | Open |
| DEP-01 | = | | Done |
| DEP-02 | ~ | HA overlay with FOSS operators. | Partial (manifests render in CI; never deployed to a cluster) |
| DEP-03 | ~ | Moved to MVP; bundles all components. | Open |
| DEP-04 – DEP-06 | = | | Partial |
| DEP-07 | = | | Open |
| DEP-08 | + | Swarm removed. | Done |

---

## Appendix B: Measurements and Review Findings

Requirements review of 2026-09-27 against the implementation. Measured on one 4-core machine running everything (3 Kafka brokers, PostgreSQL 16, two application instances), pipeline without modules. Absolute numbers are not representative of a cluster; the ratios are.

| Target | Result | Verdict |
| ------ | ------ | ------- |
| NFR-R2 no loss on failover (exactly once) | `kill -9` of a broker and of an instance under load: 6,000 acknowledged → 6,000 runs, 0 lost, 0 duplicates. PostgreSQL stopped for 15 s: 16,000 accepted with no client error, all processed once. | Met locally; to be repeated on Kubernetes. |
| NFR-R6 ingest without the database | Ingest kept accepting during the database outage (before: 109 × `503`). | Met. |
| NFR-P1 1B records/day (11,574/s) | Ingest 465 records/s, processing 306 runs/s on this machine. Each record costs 3–4 metadata transactions (run row, claim, completion, node log). | Not reachable with per-record runs; needs PIP-15. |
| NFR-P2 p99 < 500 ms | p50 1.4 s, p99 14.8 s, because processing is slower than ingest and a backlog builds. | Follows from NFR-P1. |
| NFR-P3 API p95 < 200 ms | Lists p95 4–7 ms; run summary p95 215 ms with 10,000 runs. | Met for metadata; run-history queries need OPS-07. |

Findings that shaped version 2.1 and later:

1. **One run per record cannot reach 1B/day.** At 11,574 records/s that is 40,000–50,000 metadata transactions per second and one billion run rows per day on a single PostgreSQL primary. Micro-batch execution (PIP-15, NFR-P7) is the largest technical risk of the MVP and comes before the lakehouse, whose writers need the same batching. Exactly-once carries over: a batch is claimed by its first offset, and identified records are deduplicated inside the batch.
2. **Requirements contradicted the exactly-once decision** (PIP-09, PIP-10, DAT-07, NFR-R2); they now state exactly once for identified records (PIP-14), with the boundary stated honestly: records without an identity can repeat after a client resend, and external systems without idempotency support (e-mail, Slack, MQTT/UDP/file exports) can see a repeat in a crash window of milliseconds.
3. **Missing requirements** were added: ALT-13, TEN-10, OPS-07, DAT-09, NFR-R5, NFR-R6, UI-20, UI-21.
4. **Pilot blockers** (milestone M0): ingest authentication (IMP-09, done), signing key in the repository (NFR-S7, done), shared database role (TEN-03, NFR-S8, open), default Grafana credentials (NFR-S11), CI (NFR-M6, partial).

---

## Appendix C: Production-Readiness Plan IDs

The production-readiness plan (`production-ready-spec.json`, merged from `dev`, removed in version 2.2) used its own IDs, which still appear in code comments, tests and CI. They map to this specification as follows.

| Plan ID | SRS | Plan ID | SRS |
| ------- | --- | ------- | --- |
| FR-001 | IAM-01 | NFR-001 | TEN-03, NFR-S8 |
| FR-002 | TEN-05, IAM-15 | NFR-002 | NFR-S3 |
| FR-003 | NFR-S2 (BCrypt kept, section 12) | NFR-003 | NFR-S11 |
| FR-004 | IAM-14 | NFR-004 | NFR-S9 |
| FR-005 | NFR-S7, NFR-S11, DEP-06 | NFR-005 | NFR-S10 |
| FR-006 | IMP-09 | NFR-006 | NFR-S12, API-03 |
| FR-007 | IMP-14 | NFR-007 | PIP-14 (exactly once replaces at-least-once) |
| FR-008 | MOD-14 | NFR-008 | PIP-18 |
| FR-009 | PIP-16 | NFR-009 | NFR-R10 |
| FR-010 | PIP-17 | NFR-010 | NFR-P4 (runtime roles) |
| FR-011 | PIP-18 | NFR-011, NFR-012 | Rejected (section 12) |
| FR-012 – FR-014 | MOD-03, MOD-04, NFR-S3 | NFR-013 | Not applicable before production data |
| FR-015 | NFR-M7 | NFR-014 | NFR-M6 |
| FR-016 | PIP-13, PIP-14 | NFR-015 | NFR-P8 |
| FR-017 | CON-17 | NFR-016 | NFR-R1, NFR-R2 |
| FR-018 | NFR-R10 | NFR-017 | NFR-R11, OPS-03 |
| FR-019 | CON-18, ALT-12 | NFR-018 | NFR-M8 |
| FR-020 | Design decision (JSONB), section 12 | NFR-019 | NFR-M9 |
| FR-021 | STR-06, TEN-03 | NFR-020 | NFR-M7 |
| FR-022 | Dropped, STR-08 | | |
| FR-023 | ALT-02, ALT-10 (section 12) | | |
| FR-024 | AUD | | |
| FR-025 | NFR-M1, NFR-M2, NFR-M7 | | |
| FR-026 | UI-15, NFR-P8 | | |
| FR-027 | IAM-15 | | |
| FR-028 | API-06, API-10 | | |
| FR-029 | Rejected (section 12) | | |
| FR-030 | DEP-01, DEP-02, DEP-08, NFR-M8 | | |
| FR-031 | STR-07, OPS-07, AUD-09 | | |
| FR-032 | Repository clean-up; no requirement | | |

---

*End of Requirements Specification*
