# Step-by-Step Tasks (Beginner Friendly)

## Task 1: Create your first module

1. Open **Modules**.
2. Click **Create Module**.
3. Enter module name and type.
4. Fill configuration/template.
5. Click **Save**.

Expected result: module appears in list with initial version.

## Task 2: Create and run your first pipeline

1. Open **Pipelines**.
2. Click **Create Pipeline**.
3. Choose import module.
4. Add processing/export modules.
5. Click **Save**.
6. Click **Run Pipeline**.

Expected result: run record appears and moves to success/failure state.

## Task 3: Verify output data

1. Open **Data**.
2. Select table.
3. Set optional start/end range.
4. Click **Search**.
5. Review summary cards and charts.
6. Click **Export CSV** if evidence is needed.

## Task 4: Add alerting

1. Open **Alerts**.
2. Create channel (webhook/email/slack/etc.).
3. Create rule and assign channel.
4. Enable rule.
5. Trigger test condition.
6. Check **History** for delivery state.

## Task 5: Diagnose a failing run

1. Open **Pipelines** and filter to failed runs.
2. Open run details.
3. Load lineage.
4. Open Grafana monitoring + logs.
5. Correlate timestamp and failing component.
6. Retry run or rollback if needed.

## Task 6: Invite a user (admin)

1. Open **Users**.
2. Click **Invite User**.
3. Enter email/name and temporary password.
4. Assign role.
5. Confirm user appears in list.

## Task 7: Create sub-tenant (admin)

1. Open **Tenants**.
2. Select parent tenant.
3. Click **Create Sub-Tenant**.
4. Enter tenant name and defaults.
5. Save and verify tree placement.

## Task 8: Investigate a performance regression

1. Find the start of the regression in the dashboard trends.
2. In Grafana, compare p95 latency before and after.
3. In Loki, look for error bursts or retry loops.
4. Check recent module and pipeline version changes.
5. Roll back to the previous version if the risk stays high.

## Task 9: Validate tenant access (admin)

1. Create a read-only test user.
2. Assign the Viewer role in a non-production tenant.
3. Verify the user can view but cannot change anything.
4. Record the evidence (screenshots and an audit note).

## Practical Tips

- Change one thing at a time.
- Keep timestamps in UTC when troubleshooting.
- Always confirm active tenant before edits.
- For production: create rollback plan before deployment.
