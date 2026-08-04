# Documentation Gaps and Required Follow-up

Every entry is deliberately actionable: it identifies the source, the missing
decision or verification, and the documentation that must be added once resolved.
It is not a substitute for implementation work.

| Priority | Source | FIXME | Required documentation after resolution |
|---|---|---|---|
| High | `backend/src/main/resources/application.yaml` | The configured `quarkus.kafka.bootstrap.servers` namespace is reported as unsupported by tests. Confirm the Quarkus 3.32.2 Kafka-client property and remove the obsolete key. | Supported Kafka configuration and validation procedure |
| High | `backend/src/main/resources/application.yaml` | Micrometer distribution keys are reported as unrecognised. Confirm the current Quarkus property names before promising histogram buckets to Grafana. | Metric bucket/percentile configuration and dashboard query contract |
| Critical | `backend/src/main/resources/privateKey.pem` and `application.yaml` | A signing key is bundled with the application and is unsuitable for environment isolation. Choose a secret mount or managed key service. | Key lifecycle, rotation, recovery, and deployment instructions |
| High | `application.yaml` Grafana defaults | Grafana provisioning can use `admin/admin` when enabled without overrides. Decide whether startup must fail for missing production credentials. | Required production secrets and bootstrap policy |
| Medium | `docker/docker-compose.dev.yml` | The pipeline Timescale image uses a floating `latest-pg16` tag. Pin a tested release. | Upgrade compatibility and rollback procedure |
| Medium | connector and alert integrations | Credential schemas and delivery guarantees are external-system-specific; the repository cannot claim universal values. | Per-connector/channel configuration examples, retry semantics, and provider prerequisites |
| Low | `scripts/generate-source-function-reference.ps1` | FIXME: The declaration scanner intentionally supports one-line Kotlin/TypeScript declarations only. Add a parser rule before introducing a multiline declaration style that would be omitted from the generated reference. | Updated generator coverage and regenerated source-function reference |

When adding a new `FIXME`, use this format near the code it concerns:

```text
# FIXME: concise unresolved behavior or decision
# Details: why it cannot be documented or implemented from the current source,
#          and the exact evidence/decision needed to close it.
```

Do not use a vague TODO for missing product requirements. Link the issue/change
request that supplies the missing authority when one exists.
