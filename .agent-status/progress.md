2026-08-18 — Phase 3 DLQ integration test

- Summary: Added a Testcontainers-backed Kafka integration test to verify the consumer poison-message/DLQ path and a Quarkus test resource to start Kafka during tests.
- Changed files:
  - backend/pom.xml (added Testcontainers test dependencies)
  - backend/src/test/kotlin/com/dataturntable/test/KafkaTestResource.kt (Quarkus test resource)
  - backend/src/test/kotlin/com/dataturntable/engine/PipelineConsumerDlqIT.kt (integration test)
  - docs/production-ready-v1-status.md (recorded verification)
- Tests run: `mvn -DskipTests=false test` — backend suite: 265 tests, 0 failures/errors, 11 skipped. New integration test executed successfully.
- Notes: The integration test starts an ephemeral Kafka broker and produces a malformed envelope to `dataturntable.pipelines.input`, verifying a `ConsumerDeadLetter` record is created (REQUIRES_NEW transaction) and exercising the connector dead-letter path.
- Next actions: (1) Add a replay/explicit requeue e2e test that exercises `/api/internal/v1/events/dlq/{id}/retry` end-to-end with broker handoff; (2) add CI job to run Kafka-backed integration tests optionally via a matrix or separate job; (3) continue Phase 3 reconciliation and operator requeue UX.

2026-08-18 — Starting full implementation run: "Implement everything"

- Summary: Beginning an implementation run to advance the remaining items in `docs/production-ready-spec.json`. This run will (a) prepare a CI workflow to run Kafka-backed integration tests, (b) continue Phase 3 broker-backed verification and outbox replay/e2e tests, and (c) iterate on high-priority Phase 1 security verifications if any regressions are found.
- Changed files (planned):
  - .github/workflows/integration-tests-kafka.yml (CI workflow, created but not pushed)
  - backend/src/test/kotlin/... (additional focused integration tests planned, created iteratively)
- Tests to run during development: targeted integration tests only (e.g. `EventOutboxReplayIT`, `PipelineConsumerDlqIT`). Full `mvn test`/suite runs will be performed only at the final verification step per project policy.
- Notes: This run will not push commits or create PRs unless you explicitly request that step. Local file changes are written in the workspace for review.
- Next actions: Create the CI workflow file, then add an end-to-end DLQ replay test that verifies broker handoff and consumer delivery under Testcontainers Kafka; update progress when the test passes locally.

2026-08-18 — Ran focused integration test: EventOutboxReplayIT

- Summary: Executed `mvn -Dtest=EventOutboxReplayIT test` locally to validate the workspace after adding the CI workflow. The Testcontainers Kafka broker started, the application connected, and the test passed.
- Tests run: `EventOutboxReplayIT` — 1 test passed, 0 failures/errors.
- Changed files: `.github/workflows/integration-tests-kafka.yml` (created).
- Next actions: (1) Implement an end-to-end DLQ replay e2e test that verifies broker handoff and consumer delivery; (2) optionally extend CI workflow to run additional ITs or a matrix; (3) proceed to other Phase 3 items or move to Phase 2 per your direction.
