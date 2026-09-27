# Requirements Review — 2026-09-27

Review of the [requirements specification](../specification/requirements-specification.md) (v2.1) against the implementation, with measurements where a requirement has a number. The status of every requirement is kept in the specification's Appendix A; this page explains the conclusions.

## Where the project stands

148 functional MVP requirements (v2.1): **72 done, 15 partial, 61 open**. The open ones are concentrated, not scattered:

| Area | Done | Partial | Open | Comment |
| ---- | ---: | ------: | ---: | ------- |
| Ingest, pipelines, modules, alerts, exports, connectors, tenants, UI | 72 | 10 | 15 | The data path works end to end, including HA and exactly-once delivery. |
| Lakehouse (LAK, STR-03/04/05/07, EXP-05/06) | 0 | 2 | 16 | Only the hot tier exists; nothing of the lakehouse yet. |
| Identity & audit (IAM, AUD, API-03 – 06, API-08) | 0 | 3 | 23 | Login is local JWT; no OIDC, auth proxy, audit trail or API keys. |
| Operations (OPS) | 0 | 0 | 7 | Nothing yet. |

In short: the **engine** is close to its MVP requirements; the **platform around it** (lakehouse, identity, audit, operations) is mostly still to be built.

## Targets checked with measurements

Measured on one 4-core machine running everything (3 Kafka brokers, PostgreSQL 16, two application instances), pipeline without modules. Absolute numbers are not representative of a cluster; the ratios are.

| Target | Result | Verdict |
| ------ | ------ | ------- |
| NFR-R2 no loss on failover (now: exactly once) | `kill -9` of a broker and of an instance under load: 6,000 acknowledged → 6,000 runs, 0 lost, 0 duplicates. PostgreSQL stopped for 15 s: 16,000 accepted with no client error, all processed once. | Met locally; to be repeated on Kubernetes. |
| NFR-R6 ingest without the database | Ingest kept accepting during the database outage (before: 109 × `503`). | Met. |
| NFR-P1 1B records/day (11,574/s) | Ingest 465 records/s, processing 306 runs/s on this machine. Each record costs 3–4 metadata transactions (run row, claim, completion, node log). | **Not reachable with the current design** — see below. |
| NFR-P2 p99 < 500 ms | p50 1.4 s, p99 14.8 s, because processing is slower than ingest and a backlog builds. | Follows from NFR-P1. |
| NFR-P3 API p95 < 200 ms | Lists p95 4–7 ms; run summary p95 215 ms with only 10,000 runs. | Met for metadata; run-history queries will not scale without OPS-07. |

## Findings

**1. One run per record cannot reach 1B/day (new PIP-15, NFR-P7, OPS-07).**
Every record becomes a row in `pipeline_runs` with three to four transactions, plus node log rows. At 11,574 records/s that is roughly 40,000–50,000 metadata transactions per second and one billion run rows per day on a single PostgreSQL primary — neither the write rate nor the table growth is workable. The requirement PIP-12 already asks for micro-batches; the implementation does not do it. This is the largest technical risk of the MVP and should be solved before the lakehouse, because the lakehouse writers need the same batching. Exactly-once semantics carry over: a batch is claimed by its first offset, and records with an identity are deduplicated inside the batch.

**2. Requirements contradicted the exactly-once decision.**
PIP-09, PIP-10, DAT-07 and NFR-R2 promised at-least-once and deduplication of "system duplicates" only. They now state exactly once for identified records (PIP-14), with the boundary stated honestly: records without an identity can repeat after a client resend, and external systems without idempotency support (e-mail, Slack, MQTT/UDP/file exports) can see a repeat in a crash window of milliseconds.

**3. Missing requirements (added in v2.1).**

| ID | Why it was missing |
| -- | ------------------ |
| ALT-13 platform health alerts | The functional spec (§4.6) had them; the SRS did not. Without them a failing pipeline is noticed only by its users. |
| TEN-10 tenant quotas | At 1B/day one tenant's burst delays every other tenant's data. |
| OPS-07 run-history retention | Operational tables grow by one or more rows per record. |
| DAT-09 tenant deletion / GDPR erasure | Required for EU customers; conflicts with immutable lakehouse snapshots unless planned for. |
| NFR-R5 recovery objectives | "No data loss" had no measurable RPO/RTO. |
| NFR-R6 ingest without the database | Makes the new behaviour a requirement. |
| UI-20 English and German | The UI already has both; now it is required and tested. |
| UI-21 accessibility | Not mentioned at all; set to V2. |

**4. Security items that block any pilot** (all already in the SRS, all open): unauthenticated ingest (IMP-09), a signing key committed to the repository (`backend/src/main/resources/privateKey.pem`, NFR-S7), one shared database role for all tenants (TEN-03, NFR-S8), default Grafana credentials, no CI (NFR-M6). They are small compared to the lakehouse and should come first (milestone M0).

**5. Scope.** The MVP list equals release 1.0. It is not reduced; the specification now orders it into milestones M0–M4 (§11) so that every milestone ends in a usable state: M0 pilot-ready core, M1 throughput, M2 lakehouse, M3 identity and audit, M4 operations.

## Recommended next steps

1. **M0** (small, unblocks pilots): ingest API keys, secrets out of the repository, per-tenant database roles, CI with PostgreSQL, platform health alerts, run-history retention.
2. **M1 micro-batch execution** with a benchmark harness on a Kubernetes cluster — the only way to know whether NFR-P1 holds before building on it.
3. Then the lakehouse (M2), identity and audit (M3), operations (M4).
