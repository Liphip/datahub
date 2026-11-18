# Licence Inventory

Status as of 2026-09-25. Re-check on every dependency or image update.

**Policy:** every bundled component must be free and open-source software, or at least freeware. Nothing may require a paid licence or a licence key, including for clustering or high availability.

## Project licence

`LICENSE` is a custom, non-OSI licence. It grants use, copy, modify and distribute rights, but they are revocable at any time without notice (clause 5), and selling copies is prohibited (clause 2). This is the owner's decision. Two practical consequences:

- Enterprise customers and their legal departments usually reject licences that can be revoked without notice. A commercial EULA or a standard licence is typically expected for on-premises installations.
- Clause 3 ("modifications and derivatives are subject to the terms of this license") must not be read as applying to bundled third-party components. Those stay under their own licences (below) and are distributed as separate programs.

## Application dependencies

| Area | Licences found | Assessment |
|---|---|---|
| Backend (Maven, runtime scope; incl. quarkus-mailer for alert e-mail) | Apache-2.0 (majority), MIT, BSD-2/3, EPL-1.0/2.0, EDL-1.0, GPL-2.0 with Classpath Exception (dual-licensed with EPL-2.0), CC0 / public domain | Permissive or weak copyleft. Using unmodified libraries is fine; EPL-2.0 requires source availability only for modifications to the EPL code itself. |
| Frontend (npm, production) | MIT (67), ISC (8), BSD, Apache-2.0, MPL-2.0 OR Apache-2.0 | Permissive. |
| Frontend (npm, dev only) | adds CC-BY-4.0 (caniuse-lite data), Python-2.0 (argparse) | Build-time only, not shipped. |
| Docs build | Zensical (MIT) | Replaces Material for MkDocs (MIT), which reaches end of life on 2026-11-05. |

Regenerate with:

```bash
cd backend && mvn org.codehaus.mojo:license-maven-plugin:2.7.1:add-third-party -Dlicense.excludedScopes=test,provided
cd frontend && npx license-checker-rseidelsohn --production --summary
```

## Bundled components (container images)

| Component | Image | Licence | Assessment |
|---|---|---|---|
| Apache Kafka | `apache/kafka` | Apache-2.0 | OK. Replaces `confluentinc/cp-kafka`, which Confluent distributes under its Community License terms. |
| PostgreSQL | `postgres` | PostgreSQL License | OK. |
| TimescaleDB | `timescale/timescaledb` | Apache-2.0 core + Timescale License (TSL) for community features (compression, continuous aggregates) | Free of charge (freeware under the TSL), so it meets the policy. Allowed use: the TSL permits embedding in a "value added product or service" and forbids offering TimescaleDB itself as a database service. DataTurntable is a value-added product. Do not expose raw database access to customers as a product feature. |
| Grafana, Loki | `grafana/grafana`, `grafana/loki` | AGPL-3.0 | OK when shipped **unmodified** as separate programs that communicate over the network. Any modification to Grafana/Loki source must be published under AGPL-3.0. Grafana plugins built on the Apache-2.0 plugin SDK are not affected. |
| Alloy, Prometheus, exporters, cAdvisor, kube-state-metrics | various | Apache-2.0 / MIT | OK. |
| Mosquitto | `eclipse-mosquitto` | EPL-2.0 / EDL-1.0 | OK unmodified. |
| nginx | `nginx` | BSD-2-Clause | OK. |
| RabbitMQ + MQTT plugin (HA MQTT on Kubernetes) | `RabbitmqCluster` via RabbitMQ Cluster Operator | MPL-2.0 | OK. Replaces EMQX, whose releases from 5.9 on are BSL 1.1 and need a paid licence for clusters. |
| PostgreSQL HA / Kafka HA (Kubernetes) | CloudNativePG and Strimzi operators | Apache-2.0 | OK. Replace the Bitnami images and charts, which Bitnami no longer publishes for free. The Docker Swarm stacks were removed; supported targets are single-node Docker Compose and Kubernetes. |
| Object storage (planned) | — | — | MinIO is **not** an option: prebuilt images stopped in October 2025, the community repository was archived in February 2026 and its Docker Hub repositories were deleted in September 2026. Candidates: SeaweedFS (Apache-2.0), Garage (AGPL-3.0, same unmodified-use reasoning as Grafana). |
| Python module runtime | `python:3.14.x-slim` | PSF | OK. Packages that users install in their modules are the user's responsibility. |

The optional OIDC login uses `io.quarkus:quarkus-oidc` (Apache-2.0), managed by the existing Quarkus BOM. Its desktop flow test uses the JDK HTTP server and the existing SmallRye JWT library; no identity-provider container or new frontend dependency is bundled.

The optional desktop identity-provider overlay bundles `quay.io/keycloak/keycloak:26.6.2` (Keycloak, Apache-2.0). Desktop initialization uses the already bundled `python:3.14.7-slim-trixie` image and its OpenSSL executable; no host crypto tools are required.
